"""Patch APK/IPA addresses and optionally re-sign the produced artifact."""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import stat
import struct
import subprocess
import sys
import threading
from urllib.parse import urlsplit
import zipfile

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from patch_assets import OLD as OFFICIAL_ASSET  # noqa: E402
from patch_assets_cdn import patch as patch_asset_urls  # noqa: E402
from patch_metadata import OLD as OFFICIAL_API  # noqa: E402
from patch_metadata import PRODUCTION, patch as patch_api_url  # noqa: E402
import patch_ipa as ipa_tool  # noqa: E402

APK_METADATA_ENTRY = 'assets/bin/Data/Managed/Metadata/global-metadata.dat'
IPA_METADATA_SUFFIX = 'Data/Managed/Metadata/global-metadata.dat'
DEFAULT_ASSET_ORIGIN = 'https://assets-link-like.lovelive-series.cn'
MAX_METADATA_BYTES = int(os.environ.get('PATCH_MAX_METADATA_MB', '512')) * 1024 * 1024
MAX_UNCOMPRESSED_BYTES = int(os.environ.get('PATCH_MAX_UNCOMPRESSED_MB', '8192')) * 1024 * 1024
MAX_ARCHIVE_ENTRIES = int(os.environ.get('PATCH_MAX_ARCHIVE_ENTRIES', '200000'))
_KEYSTORE_LOCK = threading.Lock()


class PatchError(ValueError):
    """Uploaded file cannot be safely patched."""


@dataclass
class PatchResult:
    output: Path
    report: dict


def default_api_origin() -> str:
    return os.environ.get('PATCH_API_ORIGIN', PRODUCTION.decode('ascii'))


def default_asset_origin() -> str:
    return os.environ.get('PATCH_ASSET_ORIGIN', DEFAULT_ASSET_ORIGIN)


def normalize_origin(value: str, *, label: str) -> str:
    text = (value or '').strip().rstrip('/')
    if (not text or text.endswith(':') or any(character.isspace() for character in text)
            or any(ord(character) < 0x20 or ord(character) == 0x7f for character in text)):
        raise PatchError(f'{label} 必须是 http(s)://host[:port] 形式，不能带路径、参数或凭据')
    try:
        url = urlsplit(text)
        port = url.port
    except ValueError as error:
        raise PatchError(f'{label} 端口无效') from error
    try:
        encoded = text.encode('ascii')
    except UnicodeEncodeError as error:
        raise PatchError(f'{label} 只能使用 ASCII 主机名') from error
    if (url.scheme not in ('http', 'https') or not url.hostname
            or not url.hostname.strip('.') or url.path
            or url.query or url.fragment or url.username or url.password
            or len(encoded) > 200
            or (port is not None and not 1 <= port <= 65535)):
        raise PatchError(f'{label} 必须是 http(s)://host[:port] 形式，不能带路径、参数或凭据')
    return text


def detect_kind(source: Path) -> str:
    suffix = source.suffix.lower()
    if suffix == '.apk':
        return 'apk'
    if suffix == '.ipa':
        return 'ipa'
    raise PatchError('只支持 .apk 或 .ipa 文件')


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def _archive_metadata_entry(archive: zipfile.ZipFile, kind: str) -> zipfile.ZipInfo:
    names = [item.filename.replace('\\', '/') for item in archive.infolist()]
    if kind == 'apk':
        if 'AndroidManifest.xml' not in names:
            raise PatchError('APK 缺少 AndroidManifest.xml')
        matches = [item for item in archive.infolist()
                   if item.filename.replace('\\', '/') == APK_METADATA_ENTRY]
        if len(matches) != 1:
            raise PatchError('APK 不是 5.1.0 单包结构：找不到唯一的 global-metadata.dat')
        return matches[0]
    matches = [item for item in archive.infolist()
               if item.filename.replace('\\', '/').endswith(IPA_METADATA_SUFFIX)
               and not item.filename.startswith('__MACOSX')]
    if len(matches) != 1:
        raise PatchError('IPA 不是 5.1.0 结构：找不到唯一的 global-metadata.dat')
    return matches[0]


def _validate_archive(archive: zipfile.ZipFile) -> None:
    """Reject ambiguous, encrypted, or decompression-bomb style archives."""
    infos = archive.infolist()
    if len(infos) > MAX_ARCHIVE_ENTRIES:
        raise PatchError('压缩包条目过多，拒绝处理')
    names = set()
    total = 0
    for info in infos:
        if info.flag_bits & 0x1:
            raise PatchError('不支持加密的 APK/IPA 压缩包')
        name = info.filename.replace('\\', '/')
        if name in names:
            raise PatchError('压缩包包含重复条目：' + name)
        names.add(name)
        if info.is_dir():
            continue
        total += max(0, info.file_size)
        if total > MAX_UNCOMPRESSED_BYTES:
            raise PatchError('解压后体积超过限制，拒绝处理')


def _patch_metadata(metadata: bytes, api_origin: str, asset_origin: str,
                    kind: str) -> tuple[bytes, dict, dict]:
    if metadata[:4] != b'\xaf\x1b\xb1\xfa':
        raise PatchError('global-metadata.dat 不是受支持的 IL2CPP metadata')
    try:
        patched, api_report = patch_api_url(
            metadata, api_origin.encode('ascii'), allow_any_origin=True)
        type_index = ipa_tool.API_STRING_TYPE_INDEX if kind == 'ipa' else None
        patched, asset_report = patch_asset_urls(
            patched, asset_origin, string_type_index=type_index,
            allow_any_origin=True)
    except (ValueError, UnicodeError) as error:
        raise PatchError('地址补丁失败：' + str(error)) from error
    if OFFICIAL_API in patched or OFFICIAL_ASSET in patched:
        raise PatchError('补丁后仍残留官方地址，已拒绝输出')
    return patched, api_report, asset_report


def _add_ipa_ats_if_needed(archive: zipfile.ZipFile, replacements: dict[str, bytes],
                           api_origin: str, asset_origin: str) -> None:
    if urlsplit(api_origin).scheme != 'http' and urlsplit(asset_origin).scheme != 'http':
        return
    try:
        plist_info = ipa_tool.find_app_plist(archive)
        plist = archive.read(plist_info)
        replacements[plist_info.filename.replace('\\', '/')] = ipa_tool.add_ats(plist)
    except ValueError as error:
        raise PatchError('HTTP 地址需要修改 Info.plist，但该 IPA 不支持自动加 ATS') from error


def _run(command: list[str], *, timeout: int = 300) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(command, check=True, capture_output=True, text=True,
                              encoding='utf-8', errors='replace', timeout=timeout,
                              stdin=subprocess.DEVNULL)
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        detail = ''
        if isinstance(error, subprocess.CalledProcessError):
            detail = (error.stderr or error.stdout or '').strip()[-800:]
        raise PatchError('外部工具执行失败：' + (detail or str(error))) from error


def _find_tool(env_name: str, name: str) -> str | None:
    configured = os.environ.get(env_name)
    return configured or shutil.which(name)


def _keystore(data_dir: Path) -> tuple[Path, str, str]:
    with _KEYSTORE_LOCK:
        return _keystore_locked(data_dir)


def _keystore_locked(data_dir: Path) -> tuple[Path, str, str]:
    configured = os.environ.get('PATCH_APK_KEYSTORE')
    keystore = Path(configured) if configured else data_dir / 'apk-debug.keystore'
    password = os.environ.get('PATCH_APK_KEYSTORE_PASSWORD')
    password_file = keystore.with_suffix('.pass')
    alias = os.environ.get('PATCH_APK_KEY_ALIAS', 'client-patch')
    if password is None and password_file.exists():
        password = password_file.read_text(encoding='utf-8').strip()
        try:
            password_file.chmod(stat.S_IRUSR | stat.S_IWUSR)
        except OSError:
            pass
    if keystore.exists():
        if not password:
            raise PatchError('APK keystore 已存在但缺少 PATCH_APK_KEYSTORE_PASSWORD 或 .pass 文件')
        return keystore, password, alias

    keytool = _find_tool('PATCH_KEYTOOL', 'keytool')
    if not keytool:
        raise PatchError('找不到 keytool，无法生成 APK 签名证书；请安装 JDK 或配置 PATCH_APK_KEYSTORE')
    keystore.parent.mkdir(parents=True, exist_ok=True)
    password = password or secrets.token_urlsafe(24)
    _run([keytool, '-genkeypair', '-keystore', str(keystore),
          '-storepass', password, '-keypass', password, '-alias', alias,
          '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
          '-dname', 'CN=LinkLike Local Patch,OU=Local,O=Local,C=CN'])
    keystore.chmod(stat.S_IRUSR | stat.S_IWUSR)
    password_file.write_text(password, encoding='utf-8')
    password_file.chmod(stat.S_IRUSR | stat.S_IWUSR)
    return keystore, password, alias


def _sign_apk(unsigned: Path, data_dir: Path, report: dict) -> Path:
    apksigner = _find_tool('PATCH_APKSIGNER', 'apksigner')
    if not apksigner:
        if os.environ.get('PATCH_REQUIRE_APK_SIGNATURE') == '1':
            raise PatchError('找不到 apksigner，无法生成可安装 APK')
        report['signed'] = False
        report['warning'] = '已生成补丁 APK，但服务器未找到 apksigner；下载后需要自行签名。'
        return unsigned

    keystore, password, alias = _keystore(data_dir)
    key_password = os.environ.get('PATCH_APK_KEY_PASSWORD') or password
    zipalign = _find_tool('PATCH_ZIPALIGN', 'zipalign')
    aligned = unsigned.with_name(unsigned.stem + '-aligned.apk')
    sign_input = unsigned
    if zipalign:
        _run([zipalign, '-f', '4', str(unsigned), str(aligned)])
        sign_input = aligned
    else:
        report['alignment_warning'] = '未找到 zipalign，已跳过 4 字节对齐。'

    signed = unsigned.with_name(unsigned.stem.removesuffix('-patched') + '-signed.apk')
    _run([apksigner, 'sign', '--ks', str(keystore), '--ks-key-alias', alias,
          '--ks-pass', 'pass:' + password, '--key-pass', 'pass:' + key_password,
          '--out', str(signed), str(sign_input)])
    if unsigned.exists():
        unsigned.unlink()
    if aligned.exists():
        aligned.unlink()
    report['signed'] = True
    return signed


def _sign_ipa(unsigned: Path, report: dict) -> Path:
    zsign = _find_tool('PATCH_ZSIGN', 'zsign')
    certificate = os.environ.get('PATCH_IPA_CERT')
    provision = os.environ.get('PATCH_IPA_PROVISION')
    password = os.environ.get('PATCH_IPA_CERT_PASSWORD', '')
    if not (zsign and certificate and provision):
        report['signed'] = False
        report['warning'] = '已生成补丁 IPA，但未配置 zsign/证书；下载后必须用你的证书重签才能安装。'
        return unsigned

    signed = unsigned.with_name(unsigned.stem.removesuffix('-patched') + '-signed.ipa')
    _run([zsign, '-k', certificate, '-p', password, '-m', provision,
          '-o', str(signed), str(unsigned)])
    if unsigned.exists():
        unsigned.unlink()
    report['signed'] = True
    return signed


def patch_client(source: Path, output_dir: Path, *, kind: str | None = None,
                 api_origin: str | None = None,
                 asset_origin: str | None = None,
                 sign_apk: bool = True,
                 data_dir: Path | None = None) -> PatchResult:
    """Patch one upload and return the produced artifact plus a JSON-safe report."""
    source = Path(source)
    output_dir = Path(output_dir)
    kind = kind or detect_kind(source)
    if kind not in ('apk', 'ipa'):
        raise PatchError('只支持 .apk 或 .ipa 文件')
    api_origin = normalize_origin(api_origin or default_api_origin(), label='API 地址')
    asset_origin = normalize_origin(asset_origin or default_asset_origin(), label='资源地址')
    if not source.is_file():
        raise PatchError('上传文件不存在')
    if not zipfile.is_zipfile(source):
        raise PatchError('上传文件不是有效的 APK/IPA zip 包')

    try:
        with zipfile.ZipFile(source) as archive:
            _validate_archive(archive)
            metadata_info = _archive_metadata_entry(archive, kind)
            if metadata_info.file_size > MAX_METADATA_BYTES:
                raise PatchError('global-metadata.dat 过大，拒绝处理')
            metadata = archive.read(metadata_info)
            patched, api_report, asset_report = _patch_metadata(
                metadata, api_origin, asset_origin, kind)
            replacements = {metadata_info.filename.replace('\\', '/'): patched}
            if kind == 'ipa':
                _add_ipa_ats_if_needed(archive, replacements, api_origin, asset_origin)
    except (zipfile.BadZipFile, zipfile.LargeZipFile) as error:
        raise PatchError('上传文件 zip 结构损坏') from error
    except (struct.error, RuntimeError) as error:
        raise PatchError('metadata 或 zip 结构损坏，无法安全补丁') from error

    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / (source.stem + '-patched.' + kind)
    report = {
        'kind': kind,
        'source_name': source.name,
        'source_sha256': _sha256_file(source),
        'api_origin': api_origin,
        'asset_origin': asset_origin,
        'metadata_entry': metadata_info.filename,
        'api_patch': api_report,
        'asset_patch': asset_report,
        'signed': False,
    }
    try:
        ipa_tool.repack(source, output, replacements, report,
                        drop_signatures=(kind == 'apk'))
    except (zipfile.BadZipFile, RuntimeError) as error:
        raise PatchError('重新打包 APK/IPA 失败') from error
    if kind == 'apk' and sign_apk:
        output = _sign_apk(output, Path(data_dir or output_dir), report)
    elif kind == 'ipa':
        output = _sign_ipa(output, report)
    report['output_name'] = output.name
    report['output'] = output.name
    report['output_sha256'] = _sha256_file(output)
    report['output_size'] = output.stat().st_size
    (output_dir / 'patch-report.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    return PatchResult(output=output, report=report)
