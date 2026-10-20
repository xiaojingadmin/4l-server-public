import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest import mock
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
import zipfile

import patch_assets as assets
from patch_metadata import OLD as API_OLD
from test_assets_patch import fixture
import patch_ipa
from tools.client_patch_web import patcher
from tools.client_patch_web.server import App, Jobs, handler_factory, safe_filename


class PatcherTests(unittest.TestCase):
    @staticmethod
    def apk_bytes(metadata):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('AndroidManifest.xml', b'manifest')
            archive.writestr(patcher.APK_METADATA_ENTRY, metadata)
            archive.writestr('classes.dex', b'dex')
        return buffer.getvalue()

    @staticmethod
    def ipa_bytes(metadata):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('Payload/Test.app/Info.plist',
                             b'<?xml version="1.0"?><plist><dict></dict></plist>')
            archive.writestr('Payload/Test.app/Data/Managed/Metadata/global-metadata.dat',
                             metadata)
            archive.writestr('Payload/Test.app/Test', b'binary')
        return buffer.getvalue()

    def test_apk_is_patched_and_other_entries_are_preserved(self):
        metadata = fixture([API_OLD, assets.OLD + b'/raw/'],
                           [API_OLD, assets.OLD], parameter_constants=(1,))
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / 'client.apk'
            with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('AndroidManifest.xml', b'manifest')
                archive.writestr(patcher.APK_METADATA_ENTRY, metadata)
                archive.writestr('classes.dex', b'dex')
                archive.writestr('META-INF/CERT.SF', b'old signature')
                archive.writestr('META-INF/CERT.RSA', b'old signature')

            result = patcher.patch_client(
                source, Path(tmp) / 'out', api_origin='https://api.example.test',
                asset_origin='https://cdn.example.test', sign_apk=False)

            self.assertEqual('apk', result.report['kind'])
            self.assertFalse(result.report['signed'])
            with zipfile.ZipFile(result.output) as archive:
                patched = archive.read(patcher.APK_METADATA_ENTRY)
                self.assertEqual(b'manifest', archive.read('AndroidManifest.xml'))
                self.assertEqual(b'dex', archive.read('classes.dex'))
                self.assertNotIn('META-INF/CERT.SF', archive.namelist())
                self.assertNotIn('META-INF/CERT.RSA', archive.namelist())
            self.assertIn(b'https://api.example.test', patched)
            self.assertIn(b'https://cdn.example.test/raw/', patched)
            self.assertNotIn(API_OLD, patched)
            self.assertNotIn(assets.OLD, patched)

    def test_ipa_is_patched_and_http_target_adds_ats(self):
        original_index = assets.STRING_TYPE_INDEX
        assets.STRING_TYPE_INDEX = patch_ipa.API_STRING_TYPE_INDEX
        try:
            metadata = fixture([API_OLD, assets.OLD + b'/raw/'],
                               [API_OLD, assets.OLD], parameter_constants=(1,))
        finally:
            assets.STRING_TYPE_INDEX = original_index
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / 'client.ipa'
            with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('Payload/Test.app/Info.plist',
                                 b'<?xml version="1.0"?><plist><dict></dict></plist>')
                archive.writestr('Payload/Test.app/Data/Managed/Metadata/global-metadata.dat',
                                 metadata)
                archive.writestr('Payload/Test.app/Test', b'binary')

            result = patcher.patch_client(
                source, Path(tmp) / 'out', api_origin='https://api.example.test',
                asset_origin='http://127.0.0.1:8083')

            self.assertEqual('ipa', result.report['kind'])
            self.assertFalse(result.report['signed'])
            self.assertTrue(result.report['warning'])
            with zipfile.ZipFile(result.output) as archive:
                plist = archive.read('Payload/Test.app/Info.plist')
                patched = archive.read(
                    'Payload/Test.app/Data/Managed/Metadata/global-metadata.dat')
            self.assertIn(b'NSAllowsArbitraryLoads', plist)
            self.assertIn(b'https://api.example.test', patched)
            self.assertIn(b'http://127.0.0.1:8083/raw/', patched)

    def test_origin_validation_rejects_paths_and_credentials(self):
        for value in ('https://api.example/path', 'https://u:p@api.example',
                      'ftp://api.example', 'https://api.example?x=1',
                      'https://api.example:', 'https://api example'):
            with self.assertRaises(patcher.PatchError):
                patcher.normalize_origin(value, label='API')

    def test_safe_filename_removes_directories(self):
        self.assertEqual('game.apk', safe_filename('../../game.apk'))
        self.assertEqual('game.apk', safe_filename(r'..\..\game.apk'))
        self.assertEqual('客户端.ipa', safe_filename('客户端.ipa'))
        with self.assertRaises(patcher.PatchError):
            safe_filename('game.exe')

    def test_apk_signing_invokes_apksigner_and_removes_unsigned_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            unsigned = Path(tmp) / 'client-patched.apk'
            unsigned.write_bytes(b'unsigned')
            report = {}

            def fake_run(command, **kwargs):
                if command[0].endswith('zipalign'):
                    Path(command[-1]).write_bytes(b'aligned')
                elif command[0].endswith('apksigner'):
                    Path(command[command.index('--out') + 1]).write_bytes(b'signed')
                return None

            with mock.patch.object(patcher, '_find_tool',
                                   side_effect=lambda env, name: '/fake/' + name
                                   if name in ('apksigner', 'zipalign') else None), \
                    mock.patch.object(patcher, '_keystore',
                                      return_value=(Path('/fake/key.keystore'), 'pw', 'alias')), \
                    mock.patch.object(patcher, '_run', side_effect=fake_run):
                signed = patcher._sign_apk(unsigned, Path(tmp), report)

            self.assertEqual('client-signed.apk', signed.name)
            self.assertEqual(b'signed', signed.read_bytes())
            self.assertFalse(unsigned.exists())
            self.assertTrue(report['signed'])

    def test_rejects_duplicate_metadata_and_decompression_bomb(self):
        metadata = fixture([API_OLD, assets.OLD + b'/raw/'],
                           [API_OLD, assets.OLD], parameter_constants=(1,))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            duplicate = root / 'duplicate.ipa'
            with zipfile.ZipFile(duplicate, 'w', zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('Payload/Test.app/Data/Managed/Metadata/global-metadata.dat',
                                 metadata)
                archive.writestr('Payload/Other.app/Data/Managed/Metadata/global-metadata.dat',
                                 metadata)
            with self.assertRaisesRegex(patcher.PatchError, '唯一的 global-metadata.dat'):
                patcher.patch_client(duplicate, root / 'out', kind='ipa',
                                     api_origin='https://api.example.test',
                                     asset_origin='https://cdn.example.test')

            bomb = root / 'bomb.apk'
            with zipfile.ZipFile(bomb, 'w', zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('AndroidManifest.xml', b'manifest')
                archive.writestr(patcher.APK_METADATA_ENTRY, metadata)
                archive.writestr('assets/padding.bin', b'\0' * (2 * 1024 * 1024))
            with mock.patch.object(patcher, 'MAX_UNCOMPRESSED_BYTES', 1024 * 1024):
                with self.assertRaisesRegex(patcher.PatchError, '解压后体积超过限制'):
                    patcher.patch_client(
                        bomb, root / 'out', kind='apk',
                        api_origin='https://api.example.test',
                        asset_origin='https://cdn.example.test', sign_apk=False)


class ServerTests(unittest.TestCase):
    def start_server(self, data: Path, *, max_upload: int = 1024 * 1024,
                     api_origin: str = 'https://api.example.test',
                     asset_origin: str = 'https://cdn.example.test'):
        app = App(
            data_dir=data,
            jobs=Jobs(data / 'jobs', 60),
            api_origin=api_origin,
            asset_origin=asset_origin,
            max_upload=max_upload,
            semaphore=threading.BoundedSemaphore(1),
        )
        server = ThreadingHTTPServer(('127.0.0.1', 0), handler_factory(app))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        def stop():
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

        self.addCleanup(stop)
        return app, 'http://127.0.0.1:%d' % server.server_address[1]

    def test_health_and_invalid_upload(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp)
            _, base = self.start_server(data)
            with urllib.request.urlopen(base + '/', timeout=5) as response:
                page = response.read().decode('utf-8')
                self.assertEqual('nosniff', response.headers['X-Content-Type-Options'])
            self.assertIn('APK / IPA 地址自动补丁', page)
            with urllib.request.urlopen(base + '/health', timeout=5) as response:
                health = json.loads(response.read())
            self.assertEqual('ok', health['status'])

            request = urllib.request.Request(base + '/api/patch?name=bad.apk',
                                             data=b'not-a-zip', method='POST')
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(request, timeout=5)
            self.assertEqual(400, caught.exception.code)

    def test_upload_apk_is_patched_and_downloadable(self):
        metadata = fixture([API_OLD, assets.OLD + b'/raw/'],
                           [API_OLD, assets.OLD], parameter_constants=(1,))
        with tempfile.TemporaryDirectory() as tmp:
            app, base = self.start_server(Path(tmp))
            request = urllib.request.Request(
                base + '/api/patch?name=game.apk',
                data=PatcherTests.apk_bytes(metadata), method='POST')
            with urllib.request.urlopen(request, timeout=10) as response:
                result = json.loads(response.read())

            self.assertEqual('game-patched.apk', result['filename'])
            self.assertEqual('apk', result['kind'])
            self.assertFalse(result['signed'])
            self.assertTrue(result['warning'])
            with urllib.request.urlopen(base + result['download_url'], timeout=10) as response:
                downloaded = response.read()
            with zipfile.ZipFile(io.BytesIO(downloaded)) as archive:
                patched = archive.read(patcher.APK_METADATA_ENTRY)
            self.assertIn(b'https://api.example.test', patched)
            self.assertIn(b'https://cdn.example.test/raw/', patched)
            self.assertNotIn(API_OLD, patched)
            self.assertNotIn(assets.OLD, patched)

            with urllib.request.urlopen(base + result['report_url'], timeout=10) as response:
                report = json.loads(response.read())
            self.assertEqual(result['sha256'], report['output_sha256'])
            jobs = list((app.data_dir / 'jobs').iterdir())
            self.assertEqual(1, len(jobs))
            self.assertFalse((jobs[0] / 'game.apk').exists())

    def test_upload_ipa_adds_ats_and_is_downloadable(self):
        original_index = assets.STRING_TYPE_INDEX
        assets.STRING_TYPE_INDEX = patch_ipa.API_STRING_TYPE_INDEX
        try:
            metadata = fixture([API_OLD, assets.OLD + b'/raw/'],
                               [API_OLD, assets.OLD], parameter_constants=(1,))
        finally:
            assets.STRING_TYPE_INDEX = original_index
        with tempfile.TemporaryDirectory() as tmp:
            _, base = self.start_server(Path(tmp), asset_origin='http://127.0.0.1:8083')
            request = urllib.request.Request(
                base + '/api/patch?name=client.ipa',
                data=PatcherTests.ipa_bytes(metadata), method='POST')
            with urllib.request.urlopen(request, timeout=10) as response:
                result = json.loads(response.read())

            self.assertEqual('client-patched.ipa', result['filename'])
            self.assertEqual('ipa', result['kind'])
            with urllib.request.urlopen(base + result['download_url'], timeout=10) as response:
                downloaded = response.read()
            with zipfile.ZipFile(io.BytesIO(downloaded)) as archive:
                plist = archive.read('Payload/Test.app/Info.plist')
                patched = archive.read(
                    'Payload/Test.app/Data/Managed/Metadata/global-metadata.dat')
            self.assertIn(b'NSAllowsArbitraryLoads', plist)
            self.assertIn(b'https://api.example.test', patched)
            self.assertIn(b'http://127.0.0.1:8083/raw/', patched)

    def test_oversized_upload_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            _, base = self.start_server(Path(tmp), max_upload=128)
            request = urllib.request.Request(
                base + '/api/patch?name=game.apk', data=b'x' * 256, method='POST')
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(request, timeout=5)
            self.assertEqual(413, caught.exception.code)


if __name__ == '__main__':
    unittest.main()
