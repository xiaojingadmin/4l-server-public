"""Build a local-only IL2CPP metadata patch from the untouched original APK.

Version 31 only. String literals use (length, dataIndex) records, not NUL
termination. Constant string values use a compressed signed length prefix.
Never patch an already patched file. Shorter replacements stay in place and
are NUL-padded. The production HTTPS origin is one byte longer than the
official API URL, so that replacement inserts one byte in the string pool
and one byte in default-value data, then shifts later section offsets and
indexes. Unrelated metadata sections are not rewritten except for those
shifted indexes/offsets.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
from urllib.parse import urlsplit
import zipfile

HERE = Path(__file__).resolve().parent
APK = HERE / 'xapk_extracted/com.oddno.lovelive.apk'
ENTRY = 'assets/bin/Data/Managed/Metadata/global-metadata.dat'
OLD = b'https://api.link-like-lovelive.app'
PRODUCTION = b'https://linklike.lovelive-series.cn'
OUTPUT = HERE / 'build/global-metadata.dat'
HEADER_SIZE = 256
DEFAULT_VALUE_HEADER = 0x48
DEFAULT_RECORD_HEADERS = (0x38, 0x40)


def encode_api_url(*, api_url=None, port=None):
    """Local reverse-tunnel URL when port is set; otherwise the production HTTPS API."""
    if port is not None:
        if port not in (8080, 8081):
            raise ValueError('port must be 8080 or 8081')
        return ('http://127.0.0.1:%d' % port).encode('ascii')
    if api_url is None:
        return PRODUCTION
    value = api_url.encode('ascii') if isinstance(api_url, str) else api_url
    if value.endswith(b'/') and not value.endswith(b'://'):
        value = value[:-1]
    return value


def _allowed_replacement(replacement, *, allow_any_origin=False):
    text = replacement.decode('ascii')
    if (not text or text.endswith(':') or any(character.isspace() for character in text)
            or any(ord(character) < 0x20 or ord(character) == 0x7f for character in text)):
        raise ValueError('API URL must be a scheme://host origin without a path')
    url = urlsplit(text)
    if url.path or url.query or url.fragment or url.username or url.password:
        raise ValueError('API URL must be a scheme://host origin without a path')
    if allow_any_origin:
        try:
            port = url.port
        except ValueError as error:
            raise ValueError('API URL has an invalid port') from error
        if (url.scheme not in ('http', 'https') or not url.hostname
                or not url.hostname.strip('.')
                or len(replacement) > 200 or (port is not None and not 1 <= port <= 65535)):
            raise ValueError('Expected an http(s) origin without credentials or a path')
        return
    local = (url.scheme == 'http' and url.hostname == '127.0.0.1'
             and url.port in (8080, 8081))
    remote = (url.scheme == 'https' and url.hostname == 'linklike.lovelive-series.cn'
              and url.port is None)
    if not local and not remote:
        raise ValueError(
            'Expected http://127.0.0.1:8080, http://127.0.0.1:8081, '
            'or https://linklike.lovelive-series.cn')


def literal_records(data):
    magic, version, table, table_size, pool, pool_size = struct.unpack_from('<6I', data)
    if magic != 0xFAB11BAF or version != 31:
        raise ValueError('Only validated IL2CPP metadata version 31 is supported')
    if table_size % 8 or table + table_size > len(data) or pool + pool_size > len(data):
        raise ValueError('Invalid literal table bounds')
    for record in range(table, table + table_size, 8):
        length, index = struct.unpack_from('<2I', data, record)
        if index + length > pool_size:
            raise ValueError('Literal outside data pool')
        yield record, pool + index, length


def _shift_header(buf, insert_at, delta):
    for header in range(8, HEADER_SIZE, 8):
        start, size = struct.unpack_from('<2I', buf, header)
        if not size:
            continue
        if start >= insert_at:
            struct.pack_into('<I', buf, header, start + delta)
        elif start < insert_at <= start + size:
            struct.pack_into('<I', buf, header + 4, size + delta)


def _insert(buf, at, extra):
    if at < HEADER_SIZE or at > len(buf):
        raise ValueError('Refusing to insert inside the metadata header')
    buf[at:at] = extra
    _shift_header(buf, at, len(extra))


def _shift_literal_indexes(buf, after_index, delta):
    table, tsize = struct.unpack_from('<2I', buf, 8)
    if tsize % 8:
        raise ValueError('Invalid literal table size')
    for record in range(table, table + tsize, 8):
        index = struct.unpack_from('<I', buf, record + 4)[0]
        if index >= after_index:
            struct.pack_into('<I', buf, record + 4, index + delta)


def _shift_default_indexes(buf, after_index, delta):
    for header in DEFAULT_RECORD_HEADERS:
        table, size = struct.unpack_from('<2I', buf, header)
        if size % 12:
            raise ValueError('Invalid default-value record size')
        for record in range(table, table + size, 12):
            index = struct.unpack_from('<i', buf, record + 8)[0]
            if index >= after_index:
                struct.pack_into('<i', buf, record + 8, index + delta)


def _find_targets(data, old):
    records = list(literal_records(data))
    literals = [(record, offset, length) for record, offset, length in records
                if data[offset:offset + length] == old]
    if not literals:
        raise ValueError('Original API URL missing from literal table')
    constants = []
    position = 0
    literal_offsets = {offset for _, offset, _ in literals}
    while True:
        offset = data.find(old, position)
        if offset < 0:
            break
        position = offset + len(old)
        if offset in literal_offsets:
            continue
        if len(old) * 2 >= 128 or data[offset - 1] != len(old) * 2:
            raise ValueError(f'Unrecognized constant length prefix at {offset:#x}')
        constants.append(offset)
    if len(constants) != 1:
        raise ValueError('Expected exactly one string constant in this APK')
    return records, literals, constants[0]


def _patch_grow(data, replacement, old):
    delta = len(replacement) - len(old)
    extra = replacement[len(old):]
    if delta <= 0 or extra != replacement[len(old):]:
        raise ValueError('Grow patch requires a longer replacement')
    if len(replacement) * 2 >= 128:
        raise ValueError('Replacement is too long for a one-byte constant prefix')
    records, literals, const_off = _find_targets(data, old)
    buf = bytearray(data)
    ops = [{'kind': 'constant', 'offset': const_off, 'record': const_off - 1,
            'old_length': len(old)}]
    for record, offset, length in literals:
        ops.append({'kind': 'literal', 'offset': offset, 'record': record,
                    'old_length': length})
    ops.sort(key=lambda item: item['offset'], reverse=True)
    for op in ops:
        offset = op['offset']
        insert_at = offset + len(old)
        if op['kind'] == 'constant':
            pool = struct.unpack_from('<I', buf, DEFAULT_VALUE_HEADER)[0]
            rel_after = insert_at - pool
            _insert(buf, insert_at, extra)
            buf[offset:offset + len(replacement)] = replacement
            buf[op['record']] = len(replacement) * 2
            _shift_default_indexes(buf, rel_after, delta)
        else:
            pool = struct.unpack_from('<I', buf, 16)[0]
            rel_after = insert_at - pool
            _insert(buf, insert_at, extra)
            buf[offset:offset + len(replacement)] = replacement
            struct.pack_into('<I', buf, op['record'], len(replacement))
            _shift_literal_indexes(buf, rel_after, delta)
        for other in ops:
            if other is op:
                continue
            if other['offset'] >= insert_at:
                other['offset'] += delta
            if other['record'] >= insert_at:
                other['record'] += delta
    output = bytes(buf)
    if old in output or output.count(replacement) != len(literals) + 1:
        raise ValueError('Patch missed a URL occurrence')
    expected_size = len(data) + delta * (len(literals) + 1)
    if len(output) != expected_size:
        raise ValueError('Unexpected metadata size after growing the API URL')
    old_by_record = {record: data[offset:offset + length]
                     for record, offset, length in records}
    new_records = list(literal_records(output))
    if len(new_records) != len(records):
        raise ValueError('Literal table count changed')
    for (record, offset, length), (new_record, new_offset, new_length) in zip(records, new_records):
        if record != new_record:
            raise ValueError('Literal table moved')
        original = old_by_record[record]
        expected = replacement if original == old else original
        if output[new_offset:new_offset + new_length] != expected:
            raise ValueError(f'Unexpected literal change at {offset:#x}')
    report = [{'kind': op['kind'], 'offset': op['offset'], 'length_record': op['record'],
               'old_length': op['old_length'], 'new_length': len(replacement),
               'grew': delta} for op in ops]
    return output, report


def patch(data, replacement, *, old=OLD, allow_any_origin=False):
    if not isinstance(old, bytes) or not old:
        raise ValueError('Original URL must be non-empty')
    if old == replacement:
        raise ValueError('Original and replacement URLs must differ')
    if b'\0' in replacement:
        raise ValueError('URL must contain no NULs')
    _allowed_replacement(replacement, allow_any_origin=allow_any_origin)
    if len(replacement) > len(old):
        return _patch_grow(data, replacement, old)
    output = bytearray(data)
    changed = set()
    report = []
    records, literals, const_off = _find_targets(data, old)
    literal_offsets = {offset for _, offset, _ in literals}

    def put(offset, content):
        output[offset:offset + len(content)] = content
        changed.update(range(offset, offset + len(content)))

    for record, offset, length in literals:
        put(record, struct.pack('<I', len(replacement)))
        put(offset, replacement.ljust(length, b'\0'))
        report.append({'kind': 'literal', 'offset': offset, 'length_record': record,
                       'old_length': length, 'new_length': len(replacement)})
    put(const_off - 1, bytes([len(replacement) * 2]))
    put(const_off, replacement.ljust(len(old), b'\0'))
    report.append({'kind': 'constant', 'offset': const_off, 'length_record': const_off - 1,
                   'old_length': len(old), 'new_length': len(replacement)})
    if len(output) != len(data) or old in output:
        raise ValueError('Patch changed file size or missed a URL occurrence')
    for (record, offset, length), (new_record, new_offset, new_length) in zip(records, literal_records(output)):
        assert (record, offset) == (new_record, new_offset)
        expected = replacement if offset in literal_offsets else data[offset:offset + length]
        if output[new_offset:new_offset + new_length] != expected:
            raise ValueError(f'Unexpected literal change at {offset:#x}')
    if any(a != b and i not in changed for i, (a, b) in enumerate(zip(data, output))):
        raise ValueError('Unexpected change outside patch regions')
    return bytes(output), report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Validate without writing output')
    parser.add_argument('--apk', type=Path, default=APK, help='Source APK (defaults to shipped 6.0.0 base)')
    parser.add_argument('--entry', default=ENTRY, help='Metadata archive entry')
    parser.add_argument('--out', type=Path, default=OUTPUT, help='Patched metadata output')
    parser.add_argument('--api-url', default=None,
                        help='API origin (default https://linklike.lovelive-series.cn)')
    parser.add_argument('--target-host', default='127.0.0.1', help='Local API host (127.0.0.1)')
    parser.add_argument('--target-port', type=int, default=None, choices=(8080, 8081),
                        help='If set, write http://127.0.0.1:PORT instead of --api-url')
    args = parser.parse_args()
    if args.target_port is not None:
        if args.target_host != '127.0.0.1':
            parser.error('target-host must be 127.0.0.1')
        replacement = encode_api_url(port=args.target_port)
    else:
        replacement = encode_api_url(api_url=args.api_url)
    with zipfile.ZipFile(args.apk) as archive:
        original = archive.read(args.entry)
    patched, report = patch(original, replacement)
    evidence = {'source_apk': str(args.apk), 'source_sha256': hashlib.sha256(original).hexdigest(),
                'patched_sha256': hashlib.sha256(patched).hexdigest(), 'bytes': len(patched),
                'patches': report, 'all_literals_verified': True}
    print(json.dumps(evidence, indent=2))
    if not args.check:
        if any(p['kind'] == 'constant' for p in report):
            # Constant strings also appear in default-value data; drop the expected constant
            # count requirement only for legacy-compatible builds.
            pass
        args.out.parent.mkdir(exist_ok=True)
        args.out.write_bytes(patched)
        args.out.with_suffix('.report.json').write_text(json.dumps(evidence, indent=2), encoding='utf-8')
        print('Output:', args.out)


if __name__ == '__main__':
    main()
