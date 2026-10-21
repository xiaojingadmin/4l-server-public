import unittest
import zipfile
from patch_assets_cdn import patch, CDN_ORIGIN, normalize_origin
from patch_metadata import patch as patch_api, PRODUCTION, OLD as API_OLD
import patch_assets as assets
from test_assets_patch import fixture, literal_values, constant_values


class CdnPatchTests(unittest.TestCase):
    def test_exact_target_and_reject_arbitrary_urls(self):
        self.assertEqual(normalize_origin(CDN_ORIGIN + '/'), CDN_ORIGIN)
        for value in ('http://assets-link-like.lovelive-series.cn', CDN_ORIGIN + '/raw',
                      CDN_ORIGIN + '?a=1', CDN_ORIGIN + '.evil', 'https://other.example'):
            with self.assertRaises(ValueError):
                normalize_origin(value)

    def test_growth_suffixes_and_unrelated_strings(self):
        values = [b'before', assets.OLD, assets.OLD + b'/android', assets.OLD + b'/raw/',
                  b'embedded:' + assets.OLD, b'after']
        source = fixture(values, values, parameter_constants=(2,))
        result, report = patch(source)
        expected = [CDN_ORIGIN.encode() + s[len(assets.OLD):] if s.startswith(assets.OLD) else s for s in values]
        self.assertEqual(literal_values(result), expected)
        self.assertEqual(constant_values(result), expected)
        self.assertGreater(len(result), len(source))
        self.assertTrue(report['all_default_values_verified'])

    def test_prefix_width_growth(self):
        value = assets.OLD + b'/' + b'x' * (63 - len(assets.OLD) - 1)
        result, _ = patch(fixture(constants=[value, b'after']))
        self.assertEqual(constant_values(result), [CDN_ORIGIN.encode() + value[len(assets.OLD):], b'after'])

    def test_api_preserved(self):
        source = fixture([API_OLD, assets.OLD + b'/raw'], [API_OLD, assets.OLD])
        source, _ = patch_api(source, PRODUCTION)
        result, _ = patch(source)
        self.assertEqual(literal_values(result), [PRODUCTION, CDN_ORIGIN.encode() + b'/raw'])
        self.assertEqual(constant_values(result), [PRODUCTION, CDN_ORIGIN.encode()])

    def test_double_patch_rejected(self):
        result, _ = patch(fixture())
        with self.assertRaises(ValueError):
            patch(result)

    @unittest.skipUnless(assets.APK.exists(), 'Original APK unavailable')
    def test_original_apk_after_production_api(self):
        with zipfile.ZipFile(assets.APK) as z:
            source = z.read(assets.ENTRY)
        source, _ = patch_api(source, PRODUCTION)
        result, report = patch(source)
        self.assertEqual((report['literal_count'], report['constant_count']), (4, 2))
        self.assertNotIn(assets.OLD, result)
        self.assertNotIn(assets.TARGET, result)
        self.assertIn(PRODUCTION, result)
        self.assertEqual(result.count(CDN_ORIGIN.encode()), 6)


if __name__ == '__main__':
    unittest.main()
