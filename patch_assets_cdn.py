"""Relocate validated 5.1 IL2CPP asset URL strings for the private HTTPS CDN.
Original APK metadata only; never blind-replace bytes or modify native code.
"""
import hashlib
import struct
from urllib.parse import urlsplit
from patch_assets import OLD, STRING_TYPE_INDEX, _layout, _encode_length, _decode_length, patch as validate_original
from patch_metadata import literal_records

CDN_ORIGIN = 'https://assets-link-like.lovelive-series.cn'


def normalize_origin(value, *, allow_any_origin=False):
    if allow_any_origin:
        text = value.strip().rstrip('/')
        if (not text or text.endswith(':') or any(character.isspace() for character in text)
                or any(ord(character) < 0x20 or ord(character) == 0x7f for character in text)):
            raise ValueError('Expected an http(s) asset origin without a path')
        try:
            url = urlsplit(text)
            port = url.port
            encoded = text.encode('ascii')
        except (ValueError, UnicodeEncodeError) as error:
            raise ValueError('Asset URL has an invalid port or non-ASCII host') from error
        if (url.scheme not in ('http', 'https') or not url.hostname
                or not url.hostname.strip('.') or url.path
                or url.query or url.fragment or url.username or url.password
                or len(encoded) > 200
                or (port is not None and not 1 <= port <= 65535)):
            raise ValueError('Expected an http(s) asset origin without a path')
        return text
    if value not in (CDN_ORIGIN, CDN_ORIGIN + '/'):
        raise ValueError('Expected private HTTPS asset origin: ' + CDN_ORIGIN)
    return CDN_ORIGIN


def patch(data, origin=CDN_ORIGIN, *, string_type_index=None, allow_any_origin=False):
    target = normalize_origin(origin, allow_any_origin=allow_any_origin).encode('ascii')
    # Reuse strict discovery, overlap, type and full-string validation. Discard
    # the loopback output: its verified report identifies the original regions.
    _, discovery = validate_original(
        data, string_type_index=(STRING_TYPE_INDEX if string_type_index is None
                                 else string_type_index))
    records, refs, bounds = _layout(data)
    ops = {}
    for item in discovery['patches']:
        value = item['original_url'].encode('utf-8')
        replacement = target + value[len(OLD):]
        if item['kind'] == 'constant':
            start = item['length_record']
            content = _encode_length(len(replacement)) + replacement
        else:
            start = item['offset']
            content = replacement
        end = item['offset'] + item['old_length']
        if start in ops and ops[start] != (end, content):
            raise ValueError('Inconsistent shared string region')
        ops[start] = (end, content)
    edits = [(s, e, b) for s, (e, b) in sorted(ops.items())]
    if any(a[1] > b[0] for a, b in zip(edits, edits[1:])):
        raise ValueError('Overlapping asset strings')

    def moved(pos):
        shift = 0
        for start, end, content in edits:
            if pos >= end:
                shift += len(content) - (end - start)
            elif pos > start:
                raise ValueError('Metadata reference inside replaced string')
        return pos + shift

    output = bytearray()
    cursor = 0
    for start, end, content in edits:
        output.extend(data[cursor:start])
        output.extend(content)
        cursor = end
    output.extend(data[cursor:])
    # All section starts and byte lengths derive from the same position map.
    for header in range(8, 256, 8):
        start, size = struct.unpack_from('<2I', data, header)
        if size:
            struct.pack_into('<2I', output, header, moved(start), moved(start + size) - moved(start))
    pool = struct.unpack_from('<I', data, 16)[0]
    expected_literals = []
    for record, offset, length in records:
        value = data[offset:offset + length]
        expected = target + value[len(OLD):] if value.startswith(OLD) else value
        struct.pack_into('<2I', output, moved(record), len(expected), moved(offset) - moved(pool))
        expected_literals.append(expected)
    default_pool = struct.unpack_from('<I', data, 72)[0]
    for start, references in refs.items():
        for ref in references:
            struct.pack_into('<i', output, moved(ref['record']) + 8, moved(start) - moved(default_pool))
    result = bytes(output)
    new_records, new_refs, new_bounds = _layout(result)
    if len(new_records) != len(records):
        raise ValueError('Literal count changed')
    for (record, offset, length), expected in zip(new_records, expected_literals):
        if result[offset:offset + length] != expected:
            raise ValueError('Literal verification failed')
    patched_constants = {p['length_record']: p for p in discovery['patches'] if p['kind'] == 'constant'}
    for start, end in bounds.items():
        new_start, new_end = moved(start), moved(end)
        if new_start not in new_refs or new_bounds[new_start] != new_end:
            raise ValueError('Default-value reference/bounds changed incorrectly')
        if start in patched_constants:
            item = patched_constants[start]
            original = item['original_url'].encode('utf-8')
            expected = target + original[len(OLD):]
            n, prefix = _decode_length(result, new_start, new_end)
            if n != len(expected) or result[new_start + prefix:new_start + prefix + n] != expected:
                raise ValueError('Constant verification failed')
            old_end = item['offset'] + item['old_length']
            if result[new_start + prefix + n:new_end] != data[old_end:end]:
                raise ValueError('Default trailing data changed')
        elif result[new_start:new_end] != data[start:end]:
            raise ValueError('Unrelated default data changed')
    # Other metadata section bodies must be identical even though relocated.
    for header in range(8, 256, 8):
        start, size = struct.unpack_from('<2I', data, header)
        if size and header not in (8, 16, 56, 64, 72):
            if result[moved(start):moved(start + size)] != data[start:start + size]:
                raise ValueError('Unrelated metadata section changed')
    return result, {
        'applied': True, 'target': target.decode(),
        'literal_count': discovery['literal_count'], 'constant_count': discovery['constant_count'],
        'source_bytes': len(data), 'bytes': len(result), 'growth_bytes': len(result) - len(data),
        'source_sha256': hashlib.sha256(data).hexdigest(),
        'patched_sha256': hashlib.sha256(result).hexdigest(),
        'all_literals_verified': True, 'all_default_values_verified': True,
        'unrelated_sections_verified': True,
        'patches': [dict(p, replacement_url=target.decode() + p['original_url'][len(OLD):],
                        new_length=len(target) + p['old_length'] - len(OLD),
                        new_prefix_size=(4 if p['kind'] == 'literal' else len(_encode_length(
                            len(target) + p['old_length'] - len(OLD)))))
                    for p in discovery['patches']],
    }
