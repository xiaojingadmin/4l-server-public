"""Asset metadata safety tests; real APK tests skip when source is unavailable.

Run: python -m unittest -v test_assets_patch
Build integration uses temporary fixtures and mocked APK packing/signing only.
"""
from contextlib import ExitStack, redirect_stdout
import io
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock
import zipfile

import patch_assets as assets
from patch_metadata import OLD as API_OLD, literal_records, patch as patch_api


def fixture(literals=None, constants=None, *, parameter_constants=()):
    """Construct independent v31 literal/default tables with explicit boundaries."""
    if literals is None:
        literals = [b'neighbor-before', assets.OLD + b'/android', b'neighbor-after']
    if constants is None:
        constants = [b'before', assets.OLD, b'after']
    data = bytearray(assets.HEADER_SIZE)
    struct.pack_into('<2I', data, 0, 0xFAB11BAF, 31)

    def section(header, payload):
        start = len(data)
        struct.pack_into('<2I', data, header, start, len(payload))
        data.extend(payload)
        return start

    table = bytearray()
    pool = bytearray()
    for value in literals:
        table.extend(struct.pack('<2I', len(value), len(pool)))
        pool.extend(value)
    section(8, table)
    section(16, pool)
    default_pool = bytearray()
    fields, parameters = bytearray(), bytearray()
    for owner, value in enumerate(constants):
        record = struct.pack('<3i', owner, assets.STRING_TYPE_INDEX, len(default_pool))
        (parameters if owner in parameter_constants else fields).extend(record)
        default_pool.extend(assets._encode_length(len(value)) + value)
    section(56, parameters)
    section(64, fields)
    section(72, default_pool)
    return bytes(data)


def literal_values(data):
    return [data[offset:offset + length] for _, offset, length in literal_records(data)]


def constant_values(data):
    _, refs, bounds = assets._layout(data)
    values = []
    for start in sorted(refs):
        length, size = assets._decode_length(data, start, bounds[start])
        values.append(data[start + size:start + size + length])
    return values


class AssetsPatchTests(unittest.TestCase):
    def test_exact_suffixes_size_and_adjacent_values(self):
        suffixes = [b'', b'/android', b'/raw', b'/raw/', b'/wiht_fes_images',
                    b'/other/path?x=1#fragment', '/raw/\u97f3\u697d'.encode()]
        values = [b'before'] + [assets.OLD + suffix for suffix in suffixes] + [b'after']
        original = fixture(values, values, parameter_constants=(1, 4))
        patched, report = assets.patch(original)
        expected = [b'before'] + [assets.TARGET + suffix for suffix in suffixes] + [b'after']
        self.assertEqual(len(original), len(patched))
        self.assertEqual(literal_values(patched), expected)
        self.assertEqual(constant_values(patched), expected)
        self.assertEqual(report['literal_count'], len(suffixes))
        self.assertEqual(report['constant_count'], len(suffixes))
        self.assertEqual(report['target'], 'http://127.0.0.1:8083')
        self.assertTrue(report['all_literals_verified'])
        self.assertTrue(report['unchanged_regions_verified'])
        changed = set()
        for item in report['patches']:
            start = item['length_record']
            if item['kind'] == 'literal':
                changed.update(range(start, start + 4))
                changed.update(range(item['offset'], item['offset'] + item['old_length']))
            else:
                changed.update(range(start, item['offset'] + item['old_length']))
        self.assertTrue(all(a == b or i in changed for i, (a, b) in enumerate(zip(original, patched))))

    def test_embedded_host_in_unrelated_strings_is_unchanged(self):
        unrelated = b'prefix:' + assets.OLD + b'/raw/'
        original = fixture([unrelated, assets.OLD + b'/raw'], [unrelated, assets.OLD])
        patched, report = assets.patch(original)
        self.assertEqual(literal_values(patched)[0], unrelated)
        self.assertEqual(constant_values(patched)[0], unrelated)
        self.assertEqual(report['literal_count'], 1)
        self.assertEqual(report['constant_count'], 1)

    def test_signed_compressed_prefix_width_changes(self):
        for length, old_width, new_width in ((37, 1, 1), (63, 1, 1), (64, 2, 1),
                                              (80, 2, 2), (8192, 4, 2), (8210, 4, 4)):
            with self.subTest(length=length):
                value = assets.OLD + b'/' + b'x' * (length - len(assets.OLD) - 1)
                original = fixture(constants=[value, b'unchanged'])
                patched, report = assets.patch(original)
                entry = next(item for item in report['patches'] if item['kind'] == 'constant')
                self.assertEqual((entry['old_prefix_size'], entry['new_prefix_size']),
                                 (old_width, new_width))
                self.assertEqual(constant_values(patched),
                                 [assets.TARGET + value[len(assets.OLD):], b'unchanged'])
                self.assertEqual(len(original), len(patched))

    def test_compressed_length_codec_boundaries(self):
        for value in (0, 1, 63, 64, 8191, 8192, 0xfffffff, 0x10000000, 0x7fffffff):
            encoded = assets._encode_length(value)
            self.assertEqual(assets._decode_length(encoded, 0, len(encoded)),
                             (value, len(encoded)))
        for encoded in (b'\x4b', b'\x80', b'\xc0\x01', b'\xf0\x00', b'\xfe',
                        b'\xff', b'\xe0', b'\x80\x4a'):
            with self.subTest(encoded=encoded), self.assertRaises(ValueError):
                assets._decode_length(encoded, 0, len(encoded))

    def test_reject_unknown_host_bytes_outside_string_values(self):
        with self.assertRaisesRegex(ValueError, 'Unrecognized asset URL layout'):
            assets.patch(fixture() + b'\x4a' + assets.OLD)

    def test_reject_unreferenced_constant(self):
        original = bytearray(fixture())
        table, _ = struct.unpack_from('<2I', original, 64)
        struct.pack_into('<i', original, table + 12 + 8, -1)
        with self.assertRaises(ValueError):
            assets.patch(bytes(original))

    def test_reject_unknown_constant_type(self):
        original = bytearray(fixture())
        table, _ = struct.unpack_from('<2I', original, 64)
        struct.pack_into('<i', original, table + 12 + 4, 123)
        with self.assertRaisesRegex(ValueError, 'Unknown asset constant type'):
            assets.patch(bytes(original))

    def test_reject_bad_constant_lengths(self):
        for prefix in (1, 2, 0x4b, 0x7e, 0xfe, 0xff):
            with self.subTest(prefix=prefix):
                original = bytearray(fixture())
                _, refs, _ = assets._layout(original)
                start = sorted(refs)[1]
                original[start] = prefix
                with self.assertRaises(ValueError):
                    assets.patch(bytes(original))

    def test_reject_bad_header_and_bounds(self):
        original = fixture()
        for offset, value in ((0, 0), (4, 29), (8, 8), (12, 7), (16, len(original)),
                              (20, len(original)), (60, 1), (68, 1), (72, 256)):
            with self.subTest(offset=offset, value=value):
                data = bytearray(original)
                struct.pack_into('<I', data, offset, value)
                with self.assertRaises(ValueError):
                    assets.patch(bytes(data))
        with self.assertRaises(ValueError):
            assets.patch(original[:24])
        with self.assertRaises(ValueError):
            assets.patch(bytearray(original))

    def test_reject_invalid_literal_index(self):
        original = bytearray(fixture())
        table, _ = struct.unpack_from('<2I', original, 8)
        struct.pack_into('<I', original, table + 4, len(original))
        with self.assertRaises(ValueError):
            assets.patch(bytes(original))

    def test_reject_invalid_default_index(self):
        for index in (-2, 999999):
            original = bytearray(fixture())
            table, _ = struct.unpack_from('<2I', original, 64)
            struct.pack_into('<i', original, table + 8, index)
            with self.assertRaises(ValueError):
                assets.patch(bytes(original))

    def test_reject_literal_overlap(self):
        original = bytearray(fixture([assets.OLD + b'/android', b'unchanged']))
        table, _ = struct.unpack_from('<2I', original, 8)
        struct.pack_into('<2I', original, table + 8, 4, 3)
        with self.assertRaisesRegex(ValueError, 'unrelated literal'):
            assets.patch(bytes(original))

    def test_shared_identical_literal_records(self):
        original = bytearray(fixture([assets.OLD, assets.OLD]))
        table, _ = struct.unpack_from('<2I', original, 8)
        # Preserve the second pool string as unrelated bytes with no host.
        pool, _ = struct.unpack_from('<2I', original, 16)
        original[pool + len(assets.OLD):pool + 2 * len(assets.OLD)] = b'x' * len(assets.OLD)
        struct.pack_into('<I', original, table + 12, 0)
        patched, report = assets.patch(bytes(original))
        self.assertEqual(literal_values(patched), [assets.TARGET, assets.TARGET])
        self.assertEqual(report['literal_count'], 2)

    def test_missing_targets_and_double_patch_fail_closed(self):
        for data in (fixture([b'no target']), fixture(constants=[b'no target'])):
            with self.assertRaises(ValueError):
                assets.patch(data)
        patched, _ = assets.patch(fixture())
        with self.assertRaises(ValueError):
            assets.patch(patched)

    def test_api_patch_then_assets_patch(self):
        from patch_metadata import PRODUCTION
        original = fixture([API_OLD, assets.OLD + b'/raw/', b'unchanged'],
                           [API_OLD, assets.OLD, b'unchanged'])
        for port in (8080, 8081):
            api_url = f'http://127.0.0.1:{port}'.encode()
            api_patched, _ = patch_api(original, api_url)
            patched, _ = assets.patch(api_patched)
            self.assertEqual(literal_values(patched), [api_url, assets.TARGET + b'/raw/', b'unchanged'])
            self.assertEqual(constant_values(patched), [api_url, assets.TARGET, b'unchanged'])
        grown, changes = patch_api(original, PRODUCTION)
        self.assertEqual(len(grown), len(original) + 2)
        self.assertEqual({item['kind'] for item in changes}, {'literal', 'constant'})
        patched, _ = assets.patch(grown)
        self.assertEqual(literal_values(patched), [PRODUCTION, assets.TARGET + b'/raw/', b'unchanged'])
        self.assertEqual(constant_values(patched), [PRODUCTION, assets.TARGET, b'unchanged'])


@unittest.skipUnless(assets.APK.is_file(), 'Original 5.1.0 APK unavailable')
class OriginalApkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(assets.APK) as archive:
            cls.original = archive.read(assets.ENTRY)

    def test_original_exact_urls_and_constant_prefixes(self):
        patched, report = assets.patch(self.original)
        self.assertEqual(report['literal_count'], 4)
        self.assertEqual(report['constant_count'], 2)
        literal_entries = [p for p in report['patches'] if p['kind'] == 'literal']
        constants = [p for p in report['patches'] if p['kind'] == 'constant']
        self.assertEqual([p['original_url'] for p in literal_entries],
                         [assets.OLD.decode() + suffix for suffix in
                          ('/android', '/raw', '/raw/', '/wiht_fes_images')])
        self.assertEqual([p['original_url'] for p in constants], [assets.OLD.decode()] * 2)
        for item in constants:
            self.assertEqual(self.original[item['length_record']], 0x4a)
            self.assertEqual(patched[item['length_record']], 0x2a)
            self.assertEqual(item['references'][0]['type_index'], 71244)
        self.assertEqual(len(self.original), 24383024)
        self.assertNotIn(assets.OLD, patched)
        self.assertIn(API_OLD, patched)
        print('\nOriginal APK asset evidence: ' + json.dumps({
            'literals': [p['original_url'] for p in literal_entries],
            'constant_urls': [p['original_url'] for p in constants],
            'constant_offsets': [p['offset'] for p in constants]}))

    def test_original_after_api_patch(self):
        original_api, _ = patch_api(self.original, b'http://127.0.0.1:8081')
        patched, report = assets.patch(original_api)
        self.assertEqual((report['literal_count'], report['constant_count']), (4, 2))
        self.assertEqual(len(patched), len(self.original))
        self.assertIn(b'http://127.0.0.1:8081', patched)
        self.assertNotIn(API_OLD, patched)
        self.assertNotIn(assets.OLD, patched)


class BuildIntegrationTests(unittest.TestCase):
    @unittest.skipUnless(importlib.util.find_spec('build_local51'),
                         'build_local51.py is not part of this checkout')
    def test_opt_in_outputs_reports_and_existing_flags(self):
        import build_local51 as build
        for enabled in (False, True):
            for push in (False, True):
                for guard in (False, True):
                    for fade in (False, True):
                        with self.subTest(asset_proxy=enabled, push=push, guard=guard, fade=fade), tempfile.TemporaryDirectory() as tmp:
                            root = Path(tmp)
                            base, config, out = root / 'base.apk', root / 'config.apk', root / 'out'
                            for path in (base, config):
                                with zipfile.ZipFile(path, 'w') as archive:
                                    archive.writestr(build.ENTRY, b'original metadata')
                                    archive.writestr('resources.arsc', b'resources')
                                    archive.writestr('AndroidManifest.xml', b'manifest')
                                    archive.writestr('lib/arm64-v8a/libil2cpp.so', b'native')
                            rollback = []
                            if enabled:
                                for previous in (out, out / 'push-experiment'):
                                    previous.mkdir(parents=True, exist_ok=True)
                                    for name in ('base-signed.apk', 'config-signed.apk',
                                                 'local51-signed.apk', 'build-report.json'):
                                        saved = previous / name
                                        saved.write_bytes(b'previous rollback output')
                                        rollback.append(saved)
                            argv = ['build_local51.py', '--port', '8080', '--debuggable',
                                    '--use-cached-requested-version']
                            if enabled:
                                argv.append('--asset-proxy')
                            if push:
                                argv.append('--skip-remote-push')
                            if not guard:
                                argv.append('--no-webview-null-guard')
                            if not fade:
                                argv.append('--no-skip-title-download-fade')
                            with ExitStack() as stack:
                                for name, value in (('BASE_APK', base), ('CONFIG_APK', config), ('OUT', out)):
                                    stack.enter_context(mock.patch.object(build, name, value))
                                stack.enter_context(mock.patch('sys.argv', argv))
                                api = stack.enter_context(mock.patch.object(build, 'patch_metadata',
                                                           return_value=(b'api metadata', [{'kind': 'api'}])))
                                asset = stack.enter_context(mock.patch('patch_assets.patch',
                                                           return_value=(b'asset metadata', {'applied': True})))
                                stack.enter_context(mock.patch.object(build, 'patch_resources', return_value=b'new resources'))
                                manifest = stack.enter_context(mock.patch.object(build, 'patch_manifest',
                                                               return_value=(b'new manifest', {'ok': True})))
                                webview = stack.enter_context(mock.patch('patch_webview51.patch',
                                                              return_value=(b'guard native', {'applied': True})))
                                fade_patch = stack.enter_context(mock.patch('patch_downloadfade51.patch',
                                                                 return_value=(b'fade native', {'applied': True})))
                                resver_patch = stack.enter_context(mock.patch('patch_resver51.patch',
                                                                   return_value=(b'resver native', {'applied': True})))
                                execwait_patch = stack.enter_context(mock.patch('patch_execwait51.patch',
                                                                     return_value=(b'execwait native', {'applied': True})))
                                remote = stack.enter_context(mock.patch('patch_push51.patch',
                                                             return_value=(b'push native', {'applied': True})))
                                tls_patch = stack.enter_context(mock.patch('patch_tls51.patch',
                                                                return_value=(b'tls native', {'applied': True})))
                                def fake_merge(base_apk, config_apk, dest, *, base_replacements,
                                               config_replacements):
                                    Path(dest).write_bytes(b'merged-apk')
                                    return {'native_files': ['lib/arm64-v8a/libil2cpp.so'], 'bytes': 10}
                                merge = stack.enter_context(mock.patch.object(
                                    build, 'merge_standalone', side_effect=fake_merge))
                                signer = stack.enter_context(mock.patch.object(build, 'sign',
                                                             side_effect=lambda src, dst: Path(dst).write_bytes(
                                                                 b'test signature stub: ' + Path(src).name.encode())))
                                stack.enter_context(redirect_stdout(io.StringIO()))
                                build.main()
                                expected_out = out / 'push-experiment' if push else out
                                if enabled:
                                    expected_out /= 'asset-proxy'
                                report = json.loads((expected_out / 'build-report.json').read_text(encoding='utf-8'))
                                self.assertEqual(report['asset_proxy'], enabled)
                                self.assertEqual(report['asset_report']['applied'], enabled)
                                self.assertEqual(report['skip_remote_push'], push)
                                self.assertEqual(report['webview_null_guard'], guard)
                                self.assertEqual(report['skip_title_download_fade'], fade)
                                self.assertTrue(report['use_cached_requested_version'])
                                self.assertTrue(report['skip_exec_error_wait'])
                                self.assertTrue(report['standalone'])
                                self.assertEqual(report['port'], 8080)
                                self.assertTrue(report['debuggable'])
                                api.assert_called_once_with(b'original metadata', b'http://127.0.0.1:8080')
                                if enabled:
                                    asset.assert_called_once_with(b'api metadata')
                                else:
                                    asset.assert_not_called()
                                self.assertEqual(webview.call_count, int(guard))
                                self.assertEqual(fade_patch.call_count, int(fade))
                                if fade:
                                    fade_patch.assert_called_once_with(b'guard native' if guard else b'native')
                                incoming = b'fade native' if fade else (b'guard native' if guard else b'native')
                                resver_patch.assert_called_once_with(incoming)
                                execwait_patch.assert_called_once_with(b'resver native')
                                if push:
                                    remote.assert_called_once_with(b'execwait native', package=build.PACKAGE)
                                    tls_patch.assert_called_once_with(b'push native', package=build.PACKAGE)
                                else:
                                    remote.assert_not_called()
                                    tls_patch.assert_called_once_with(b'execwait native', package=build.PACKAGE)
                                self.assertTrue(report['accept_private_tls'])
                                self.assertTrue(report['tls_report']['applied'])
                                self.assertEqual(manifest.call_count, 1)
                                self.assertTrue(manifest.call_args.kwargs['standalone'])
                                self.assertEqual(manifest.call_args.kwargs['debuggable'], True)
                                merge.assert_called_once()
                                self.assertEqual(merge.call_args.kwargs['base_replacements'][build.ENTRY],
                                                 b'asset metadata' if enabled else b'api metadata')
                                self.assertEqual(merge.call_args.args[2], expected_out / 'local51-repacked.apk')
                                self.assertEqual(signer.call_count, 1)
                                self.assertEqual(list(report['artifacts']), ['local51-signed.apk'])
                                for saved in rollback:
                                    self.assertEqual(saved.read_bytes(), b'previous rollback output')


if __name__ == '__main__':
    unittest.main()
