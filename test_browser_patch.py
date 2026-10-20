"""Regression tests for the browser-only APK/IPA patcher."""
import io
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import zipfile
from http.server import ThreadingHTTPServer

import patch_assets as assets
import patch_ipa
from patch_assets_cdn import patch as patch_assets_cdn
from patch_metadata import OLD as API_OLD, patch as patch_api
from test_assets_patch import fixture
from tools.client_patch_web.server import Handler

ROOT = Path(__file__).resolve().parent
TOOL = ROOT / 'tools' / 'client_patch_web'
NODE = shutil.which('node')
APK_ENTRY = 'assets/bin/Data/Managed/Metadata/global-metadata.dat'
IPA_ENTRY = 'Payload/Test.app/Data/Managed/Metadata/global-metadata.dat'


def node_supports_browser_modules() -> bool:
    if not NODE:
        return False
    try:
        result = subprocess.run(
            [NODE, '-e', 'console.log(typeof Blob, typeof DecompressionStream)'],
            capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return False
    return result.returncode == 0 and result.stdout.strip() == 'function function'


NODE_SUPPORTED = node_supports_browser_modules()


def local_data_offset(path: Path, name: str) -> int:
    with zipfile.ZipFile(path) as archive:
        info = archive.getinfo(name)
    with path.open('rb') as stream:
        stream.seek(info.header_offset)
        header = stream.read(30)
    name_length, extra_length = struct.unpack_from('<2H', header, 26)
    return info.header_offset + 30 + name_length + extra_length


class NonSeekable(io.RawIOBase):
    """Force zipfile to write data descriptors instead of seeking back."""

    def __init__(self):
        self.buffer = io.BytesIO()

    def writable(self):
        return True

    def write(self, data):
        return self.buffer.write(data)

    def tell(self):
        return self.buffer.tell()

    def seekable(self):
        return False

    def getvalue(self):
        return self.buffer.getvalue()


@unittest.skipUnless(NODE_SUPPORTED and (TOOL / 'test_browser.mjs').is_file(),
                    'Node.js with Blob/DecompressionStream unavailable')
class BrowserPatchTests(unittest.TestCase):
    def run_node(self, source: Path, output: Path, api: str, asset: str, kind: str) -> dict:
        result = subprocess.run(
            [NODE, str(TOOL / 'test_browser.mjs'), str(source), str(output),
             api, asset, kind],
            cwd=ROOT, capture_output=True, text=True, timeout=60)
        if result.returncode:
            self.fail('browser patcher failed:\n' + result.stdout + result.stderr)
        return json.loads(result.stdout)

    def test_apk_patch_is_byte_identical_to_python_and_drops_signatures(self):
        metadata = fixture([API_OLD, assets.OLD + b'/raw/', b'unchanged'],
                           [API_OLD, assets.OLD, b'unchanged'], parameter_constants=(1,))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'game.apk'
            stored = zipfile.ZipInfo('lib/arm64-v8a/libtest.so')
            stored.compress_type = zipfile.ZIP_STORED
            with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('AndroidManifest.xml', b'manifest')
                archive.writestr(APK_ENTRY, metadata)
                archive.writestr('classes.dex', b'dex')
                archive.writestr('assets/raw.bin', b'raw-data')
                archive.writestr(stored, b'native-library')
                archive.writestr('META-INF/CERT.SF', b'old signature')
                archive.writestr('META-INF/CERT.RSA', b'old signature')

            for label, api, asset in (
                    ('shrink', 'https://api.example.test', 'https://cdn.example.test'),
                    ('grow', 'https://linklike.lovelive-series.cn',
                     'https://assets-link-like.lovelive-series.cn')):
                with self.subTest(label=label):
                    expected_api, _ = patch_api(metadata, api.encode(), allow_any_origin=True)
                    expected, _ = patch_assets_cdn(expected_api, asset, allow_any_origin=True)
                    output = root / ('out-' + label + '.apk')
                    report = self.run_node(source, output, api, asset, 'apk')
                    with zipfile.ZipFile(output) as archive:
                        self.assertIsNone(archive.testzip())
                        self.assertEqual(expected, archive.read(APK_ENTRY))
                        self.assertEqual(b'manifest', archive.read('AndroidManifest.xml'))
                        self.assertEqual(b'dex', archive.read('classes.dex'))
                        self.assertEqual(b'raw-data', archive.read('assets/raw.bin'))
                        self.assertEqual(b'native-library', archive.read('lib/arm64-v8a/libtest.so'))
                        self.assertNotIn('META-INF/CERT.SF', archive.namelist())
                        self.assertNotIn('META-INF/CERT.RSA', archive.namelist())
                        self.assertEqual(zipfile.ZIP_STORED,
                                         archive.getinfo('lib/arm64-v8a/libtest.so').compress_type)
                        self.assertEqual(0, local_data_offset(output, 'lib/arm64-v8a/libtest.so') % 16384)
                    self.assertEqual('apk', report['kind'])
                    self.assertEqual(APK_ENTRY, report['metadata_entry'])
                    self.assertFalse(report['ats'])

    def test_ipa_patch_adds_ats_and_is_byte_identical_to_python(self):
        original_index = assets.STRING_TYPE_INDEX
        assets.STRING_TYPE_INDEX = patch_ipa.API_STRING_TYPE_INDEX
        try:
            metadata = fixture([API_OLD, assets.OLD + b'/raw/'],
                               [API_OLD, assets.OLD], parameter_constants=(1,))
        finally:
            assets.STRING_TYPE_INDEX = original_index
        api = 'https://api.example.test'
        asset = 'http://127.0.0.1:8083'
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'game.ipa'
            with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('Payload/Test.app/Info.plist',
                                 b'<?xml version="1.0"?><plist><dict></dict></plist>')
                archive.writestr(IPA_ENTRY, metadata)
                archive.writestr('Payload/Test.app/Test', b'binary')
            for label, api, asset, expect_ats in (
                    ('http', 'https://api.example.test', 'http://127.0.0.1:8083', True),
                    ('https', 'https://api.example.test', 'https://cdn.example.test', False),
                    ('grow', 'https://linklike.lovelive-series.cn',
                     'https://assets-link-like.lovelive-series.cn', False)):
                with self.subTest(label=label):
                    expected_api, _ = patch_api(metadata, api.encode(), allow_any_origin=True)
                    expected, _ = patch_assets_cdn(
                        expected_api, asset, string_type_index=patch_ipa.API_STRING_TYPE_INDEX,
                        allow_any_origin=True)
                    output = root / ('out-' + label + '.ipa')
                    report = self.run_node(source, output, api, asset, 'ipa')
                    with zipfile.ZipFile(output) as archive:
                        self.assertIsNone(archive.testzip())
                        self.assertEqual(expected, archive.read(IPA_ENTRY))
                        plist = archive.read('Payload/Test.app/Info.plist')
                        self.assertEqual(b'binary', archive.read('Payload/Test.app/Test'))
                    self.assertEqual(expect_ats, b'NSAllowsArbitraryLoads' in plist)
                    self.assertEqual(expect_ats, report['ats'])
                    self.assertEqual(IPA_ENTRY, report['metadata_entry'])

    def test_repacks_archives_with_data_descriptors(self):
        metadata = fixture([API_OLD, assets.OLD + b'/raw/'],
                           [API_OLD, assets.OLD], parameter_constants=(1,))
        api = 'https://api.example.test'
        asset = 'https://cdn.example.test'
        expected_api, _ = patch_api(metadata, api.encode(), allow_any_origin=True)
        expected, _ = patch_assets_cdn(expected_api, asset, allow_any_origin=True)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sink = NonSeekable()
            with zipfile.ZipFile(sink, 'w', zipfile.ZIP_DEFLATED) as archive:
                archive.writestr(APK_ENTRY, metadata)
                archive.writestr('AndroidManifest.xml', b'manifest')
                archive.writestr('classes.dex', b'dex')
            source = root / 'descriptor.apk'
            source.write_bytes(sink.getvalue())
            with zipfile.ZipFile(source) as archive:
                self.assertTrue(all(info.flag_bits & 0x8 for info in archive.infolist()))
            output = root / 'out.apk'
            self.run_node(source, output, api, asset, 'apk')
            with zipfile.ZipFile(output) as archive:
                self.assertIsNone(archive.testzip())
                self.assertEqual(expected, archive.read(APK_ENTRY))
                self.assertEqual(b'manifest', archive.read('AndroidManifest.xml'))
                self.assertEqual(b'dex', archive.read('classes.dex'))


class StaticServerTests(unittest.TestCase):
    def test_page_is_static_and_post_is_rejected(self):
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = 'http://127.0.0.1:%d' % server.server_address[1]
        try:
            with urllib.request.urlopen(base + '/', timeout=5) as response:
                page = response.read().decode('utf-8')
                self.assertIn("connect-src 'none'", response.headers['Content-Security-Policy'])
            self.assertIn('文件不会上传到服务器', page)
            with urllib.request.urlopen(base + '/app.js', timeout=5) as response:
                self.assertIn('text/javascript', response.headers['Content-Type'])
            request = urllib.request.Request(base + '/api/patch', data=b'not-a-zip', method='POST')
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(request, timeout=5)
            self.assertEqual(405, caught.exception.code)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == '__main__':
    unittest.main()
