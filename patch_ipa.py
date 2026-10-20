"""Patch the official LLLL 5.1.0 IPA to point at the private server.

The API base and resource CDN are stored as plain IL2CPP v31 metadata string
literals/constants inside Data/Managed/Metadata/global-metadata.dat, exactly as
in the APK. This script reuses the APK patch functions, keyed to the IPA's
string-default type index (71115), then repacks the IPA replacing that one
entry.

API URL targets:
  https://linklike.lovelive-series.cn        (default, HTTPS private server)
  http://127.0.0.1:8080|8081                (loopback; needs a reverse tunnel
                                             on a real device and ATS below)

Asset URL targets:
  --asset-proxy  http://127.0.0.1:8083      (local asset proxy; enables ATS)
  --asset-url    https://assets-link-like.lovelive-series.cn (HTTPS CDN)

When any replacement is plain HTTP (--port or --asset-proxy), the Info.plist
is updated with NSAllowsArbitraryLoads/NSAllowsLocalNetworking so App
Transport Security does not block the loopback traffic. HTTPS targets need no
plist change.

IMPORTANT: modifying any bundle file invalidates the iOS code signature. After
repacking, re-sign the .app (codesign / zsign with your Apple ID or ad-hoc
certificate), or re-sign on install with a sideload tool. Nothing here touches
the original IPA.

Only the produced IPA is modified; the original file and all app payload
entries other than global-metadata.dat (and Info.plist for HTTP targets) stay
byte-identical.
"""
import argparse
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path
import zipfile

HERE = Path(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_IPA = HERE / 'LLLL_5.1.0.ipa'
DEFAULT_OUT = HERE / 'build' / 'ipa-local'
METADATA_SUFFIX = 'Data/Managed/Metadata/global-metadata.dat'
INFO_PLIST_SUFFIX = 'Info.plist'
API_STRING_TYPE_INDEX = 71115  # IPA layout; the 5.1.0 APK uses 71244.

if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
from patch_metadata import encode_api_url, patch as patch_api, OLD as OLD_API
from patch_assets import OLD as OLD_ASSET, patch as patch_assets_proxy, STRING_TYPE_INDEX
from patch_assets_cdn import CDN_ORIGIN, patch as patch_assets_cdn, normalize_origin
import patch_webview_ipa

ATS_BLOCK = (
    b'\t<key>NSAppTransportSecurity</key>\n'
    b'\t<dict>\n'
    b'\t\t<key>NSAllowsArbitraryLoads</key>\n\t\t<true/>\n'
    b'\t\t<key>NSAllowsLocalNetworking</key>\n\t\t<true/>\n'
    b'\t</dict>\n'
)


def find_entry(archive, suffix):
    """Return the archive member whose name ends with suffix (systematic case)."""
    for info in archive.infolist():
        name = info.filename.replace('\\', '/')
        if name.endswith(suffix) and not name.startswith('__MACOSX'):
            return info
    raise ValueError('Archive member ending in %r not found' % suffix)


def find_app_plist(archive):
    """Return the package-root Info.plist: Payload/<name>.app/Info.plist."""
    for info in archive.infolist():
        name = info.filename.replace('\\', '/')
        parts = name.split('/')
        if (not name.startswith('__MACOSX') and len(parts) == 3
                and parts[0] == 'Payload' and parts[1].endswith('.app')
                and parts[2] == 'Info.plist'):
            return info
    raise ValueError('Package-root Info.plist not found')


def add_ats(plist):
    """Insert ATS exceptions before the root </dict> of an XML plist."""
    marker = b'</dict>'
    root = plist.rfind(marker)
    if root < 0:
        raise ValueError('Info.plist has no closing dict')
    if b'NSAppTransportSecurity' in plist:
        raise ValueError('Info.plist already declares NSAppTransportSecurity')
    return plist[:root] + ATS_BLOCK + plist[root:]


def _is_apk_signature_entry(name):
    upper = name.upper()
    return (upper.startswith('META-INF/')
            and (upper.endswith('.SF') or upper.endswith('.RSA')
                 or upper.endswith('.DSA') or upper.endswith('.EC')
                 or upper == 'META-INF/MANIFEST.MF'))


def repack(source, destination, replacements, report, *, drop_signatures=False):
    """Copy every zip member, replacing a few entries, preserving permissions.

    Rewriting the whole zip is required because global-metadata.dat sits inside
    Payload/ProductName.app and iOS validates bundle contents at re-sign time.
    """
    with zipfile.ZipFile(source) as src:
        infos = src.infolist()
        with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as out:
            written = {'replaced': [], 'copied': 0}
            out.comment = src.comment
            for info in infos:
                name = info.filename.replace('\\', '/')
                info.filename = name
                if drop_signatures and _is_apk_signature_entry(name):
                    continue
                if name in replacements:
                    out.writestr(info, replacements[name])
                    written['replaced'].append(name)
                else:
                    # Stream members so a large APK/IPA does not have to hold an
                    # entire unpacked asset (or a compressed bomb) in memory.
                    with src.open(info) as source_entry, out.open(
                            info, 'w', force_zip64=info.file_size >= 2 ** 31) as target_entry:
                        shutil.copyfileobj(source_entry, target_entry, 1024 * 1024)
                written['copied'] += 1
            report['repack'] = written
    report['output'] = str(destination)
    report['output_sha256'] = hashlib.sha256(destination.read_bytes()).hexdigest()
    report['output_size'] = destination.stat().st_size


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ipa', type=Path, default=DEFAULT_IPA,
                        help='Input IPA (default LLLL_5.1.0.ipa)')
    parser.add_argument('--api-url', default=None,
                        help='API origin (default https://linklike.lovelive-series.cn)')
    parser.add_argument('--port', type=int, default=None, choices=(8080, 8081),
                        help='If set, write http://127.0.0.1:PORT instead of --api-url')
    parser.add_argument('--asset-proxy', action='store_true',
                        help='Redirect asset URLs to http://127.0.0.1:8083')
    parser.add_argument('--asset-url', default=None,
                        help='Private HTTPS resource origin (default none)')
    parser.add_argument('--out', type=Path, default=DEFAULT_OUT,
                        help='Output directory (default build/ipa-local)')
    parser.add_argument('--out-name', default=None,
                        help='Output file name stem (default <ipa stem>-patched)')
    parser.add_argument('--assume-ios-type-index', type=int, default=None,
                        help='Override the string-default type index (default 71115)')
    parser.add_argument('--webview', action='store_true',
                        help='Also apply the UnityFramework null-header WebView crash guard')
    parser.add_argument('--check', action='store_true',
                        help='Validate patchability of the IPA without writing output')
    args = parser.parse_args()
    if args.asset_proxy and args.asset_url:
        parser.error('--asset-proxy and --asset-url are mutually exclusive')
    if not args.ipa.exists():
        parser.error(f'Missing IPA: {args.ipa}')
    type_index = args.assume_ios_type_index or API_STRING_TYPE_INDEX
    api_url = encode_api_url(api_url=args.api_url, port=args.port)
    http_target = api_url.startswith(b'http://')
    if args.asset_url:
        args.asset_url = normalize_origin(args.asset_url)

    report = {'source': str(args.ipa),
              'source_sha256': hashlib.sha256(args.ipa.read_bytes()).hexdigest(),
              'ipcc_version': None, 'api_url': api_url.decode('ascii'),
              'asset_proxy': args.asset_proxy, 'asset_url': args.asset_url,
              'string_type_index': type_index, 'webview_patch': None}

    with zipfile.ZipFile(args.ipa) as archive:
        meta_info = find_entry(archive, METADATA_SUFFIX)
        metadata = archive.read(meta_info)
        assert metadata[:4] == b'\xaf\x1b\xb1\xfa', 'Bad IL2CPP metadata magic (0xFAB11BAF expected)'
        replacements = {}
    report['metadata_entry'] = meta_info.filename

    patched, api_report = patch_api(metadata, api_url)
    stage = [{'kind': p['kind'], 'old_length': p['old_length'],
              'new_length': p['new_length']} for p in api_report]
    report['api_patch'] = stage

    asset_report = {'applied': False}
    if args.asset_proxy:
        patched, asset_report = patch_assets_proxy(patched, string_type_index=type_index)
    elif args.asset_url:
        patched, asset_report = patch_assets_cdn(
            patched, args.asset_url, string_type_index=type_index)
    if asset_report.get('applied'):
        report['asset_patch'] = [{'kind': p['kind'], 'original_url': p['original_url'],
                                  'replacement_url': p['replacement_url']}
                                 for p in asset_report['patches']]

    leftovers = []
    if OLD_API in patched:
        leftovers.append(OLD_API.decode('ascii'))
    if asset_report.get('applied') and OLD_ASSET in patched:
        leftovers.append(OLD_ASSET.decode('ascii'))
    report['leftover_official_urls'] = leftovers
    if leftovers:
        raise ValueError('Patch left an official URL intact')

    report['patched_metadata_sha256'] = hashlib.sha256(patched).hexdigest()
    report['patched_metadata_bytes'] = len(patched)
    if http_target:
        with zipfile.ZipFile(args.ipa) as archive:
            plist_entry = find_app_plist(archive)
            plist = archive.read(plist_entry)
        plist = add_ats(plist)
        replacements[plist_entry.filename] = plist
        report['plist_entry'] = plist_entry.filename
        report['ats'] = {'added': True, 'reason': 'http target requires local networking'}
    if args.webview:
        with zipfile.ZipFile(args.ipa) as archive:
            framework_entry = find_entry(archive, patch_webview_ipa.ENTRY_SUFFIX)
            framework = archive.read(framework_entry)
        framework, webview_report = patch_webview_ipa.patch(framework)
        webview_report['entry'] = framework_entry.filename
        webview_report['patched_sha256'] = hashlib.sha256(framework).hexdigest()
        report['webview_patch'] = webview_report
        replacements[framework_entry.filename] = framework
    report['replacements'] = [meta_info.filename] + [p for p in replacements if p != meta_info.filename]

    if args.check:
        print(json.dumps(report, indent=2))
        return
    out_dir = args.out
    out_dir.mkdir(parents=True, exist_ok=True)
    destination = out_dir / ((args.out_name or args.ipa.stem + '-patched') + '.ipa')
    replacements[meta_info.filename] = patched  # only added when writing output
    repack(args.ipa, destination, replacements, report)
    (out_dir / 'patch-report.json').write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    print(json.dumps({'output': str(destination), 'report': str(out_dir / 'patch-report.json'),
                      'api_url': report['api_url'],
                      'asset': report['asset_url'] or ('proxy' if args.asset_proxy else 'unchanged'),
                      'webview': bool(report['webview_patch']),
                      'need_resign': True}, ensure_ascii=True))


if __name__ == '__main__':
    main()
