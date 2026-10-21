"""Redirect asset strings in validated IL2CPP v31 metadata without resizing it.

The original 5.1.0 APK has four URL literals and two string constants. Constants
are located through field/parameter default-value records, never by a blind byte
replacement. The inspected 5.1.0 string defaults use native type index 71244
(APK) or 71115 (the matching 5.1.0 IPA); pass string_type_index for the target
platform, and any other types/layouts containing this host fail closed.
CLI validation is read-only.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

from patch_metadata import literal_records

HERE = Path(__file__).resolve().parent
APK = HERE / 'build/apk-analysis/5.1.0-original/com.oddno.lovelive.apk'
ENTRY = 'assets/bin/Data/Managed/Metadata/global-metadata.dat'
OLD = b'https://assets.link-like-lovelive.app'
TARGET = b'http://127.0.0.1:8083'
STRING_TYPE_INDEX = 71244
HEADER_SIZE = 256


def _encode_length(length):
    """IL2CPP compressed signed int, restricted to nonnegative string lengths."""
    if not 0 <= length <= 0x7fffffff:
        raise ValueError('Invalid string length')
    value = length << 1
    if value < 0x80:
        return bytes([value])
    if value < 0x4000:
        return bytes([0x80 | (value >> 8), value & 0xff])
    if value < 0x20000000:
        return bytes([0xc0 | (value >> 24), (value >> 16) & 0xff,
                      (value >> 8) & 0xff, value & 0xff])
    return b'\xf0' + struct.pack('<I', value)


def _decode_length(data, offset, end):
    if offset >= end:
        raise ValueError('Missing compressed string length')
    first = data[offset]
    if first < 0x80:
        size, value = 1, first
    elif first < 0xc0:
        size, value = 2, first & 0x3f
    elif first < 0xe0:
        size, value = 4, first & 0x1f
    elif first == 0xf0:
        size, value = 5, 0
    else:
        raise ValueError('Unknown compressed string length prefix')
    if offset + size > end:
        raise ValueError('Truncated compressed string length')
    if size == 5:
        value = struct.unpack_from('<I', data, offset + 1)[0]
    else:
        for byte in data[offset + 1:offset + size]:
            value = (value << 8) | byte
    if value & 1:
        raise ValueError('Negative compressed string length')
    length = value >> 1
    if _encode_length(length) != data[offset:offset + size]:
        raise ValueError('Noncanonical compressed string length')
    return length, size


def _layout(data):
    if len(data) < HEADER_SIZE:
        raise ValueError('Truncated version 31 metadata header')
    if struct.unpack_from('<2I', data) != (0xFAB11BAF, 31):
        raise ValueError('Only validated IL2CPP metadata version 31 is supported')
    # Header entries are (offset, byte count). Check every nonempty section so
    # target sections cannot alias unrelated metadata or the header itself.
    sections = []
    for header in range(8, HEADER_SIZE, 8):
        start, size = struct.unpack_from('<2I', data, header)
        if size:
            if start < HEADER_SIZE or start + size > len(data):
                raise ValueError(f'Invalid metadata section bounds at {header:#x}')
            sections.append((start, start + size))
    sections.sort()
    if any(left[1] > right[0] for left, right in zip(sections, sections[1:])):
        raise ValueError('Overlapping metadata sections')
    records = list(literal_records(data))
    pool, pool_size = struct.unpack_from('<2I', data, 72)
    refs = {}
    for header, kind in ((56, 'parameter'), (64, 'field')):
        table, size = struct.unpack_from('<2I', data, header)
        if size % 12:
            raise ValueError('Invalid default-value record size')
        for record in range(table, table + size, 12):
            owner, type_index, index = struct.unpack_from('<3i', data, record)
            if index == -1:
                continue
            if index < 0 or index >= pool_size:
                raise ValueError('Default value outside data pool')
            refs.setdefault(pool + index, []).append(
                {'kind': kind, 'record': record, 'owner': owner, 'type_index': type_index})
    offsets = sorted(refs)
    bounds = {start: end for start, end in zip(offsets, offsets[1:] + [pool + pool_size])}
    return records, refs, bounds


def patch(data, *, string_type_index=STRING_TYPE_INDEX):
    """Return (same-sized bytes, JSON-safe report); the proxy target is fixed.

    Suffixes are copied byte-for-byte, including trailing slashes. Only complete
    values starting with OLD are changed. Embedded occurrences in unrelated
    string values remain untouched; unclassified occurrences cause rejection.
    Applying this twice is an error, as is an overlapping target string layout.
    Pass string_type_index=71115 for the 5.1.0 IPA, which shares the v31
    metadata layout but marks its string defaults with a different type index.
    """
    if not isinstance(data, bytes):
        raise ValueError('Metadata input must be bytes')
    records, refs, bounds = _layout(data)
    literals = [(record, offset, data[offset:offset + length])
                for record, offset, length in records]
    constants = []
    for start, end in bounds.items():
        # This scan selects candidates only. Reference/type/prefix/bounds checks
        # establish a complete string value before any edit is scheduled.
        if OLD not in data[start:end]:
            continue
        if any(ref['type_index'] != string_type_index for ref in refs[start]):
            raise ValueError(f'Unknown asset constant type at {start:#x}')
        length, prefix_size = _decode_length(data, start, end)
        offset = start + prefix_size
        if offset + length > end:
            raise ValueError(f'Asset constant crosses default-value boundary at {start:#x}')
        value = data[offset:offset + length]
        if OLD not in value:
            raise ValueError(f'Asset host outside declared constant at {start:#x}')
        constants.append((start, offset, value))

    # Every host occurrence must belong to a known complete string value. This
    # search is validation, not replacement, and permits embedded URLs unchanged.
    covered = sorted([(offset, offset + len(value)) for _, offset, value in
                      literals + constants if OLD in value])
    position = 0
    interval = 0
    while (offset := data.find(OLD, position)) >= 0:
        while interval < len(covered) and covered[interval][1] < offset + len(OLD):
            interval += 1
        if interval == len(covered) or not covered[interval][0] <= offset:
            raise ValueError(f'Unrecognized asset URL layout at {offset:#x}')
        position = offset + len(OLD)

    output = bytearray(data)
    patches = []
    edits = {}
    expected_literals = {}
    expected_constants = {}

    def schedule(start, content):
        end = start + len(content)
        for other, existing in edits.items():
            if start == other and content == existing:
                return  # Shared literal/constant records may reference one value.
            if start < other + len(existing) and other < end:
                raise ValueError('Overlapping asset patch regions')
        edits[start] = content

    for kind, values in (('literal', literals), ('constant', constants)):
        for record, offset, value in values:
            if not value.startswith(OLD):
                continue
            replacement = TARGET + value[len(OLD):]
            if kind == 'literal':
                schedule(record, struct.pack('<I', len(replacement)))
                schedule(offset, replacement.ljust(len(value), b'\0'))
                expected_literals[record] = replacement
                old_prefix_size = new_prefix_size = 4
            else:
                prefix = _encode_length(len(replacement))
                old_prefix_size, new_prefix_size = offset - record, len(prefix)
                schedule(record, (prefix + replacement).ljust(
                    old_prefix_size + len(value), b'\0'))
                expected_constants[record] = replacement
            entry = {'kind': kind, 'offset': offset, 'length_record': record,
                     'old_length': len(value), 'new_length': len(replacement),
                     'old_prefix_size': old_prefix_size, 'new_prefix_size': new_prefix_size,
                     'original_url': value.decode('utf-8'),
                     'replacement_url': replacement.decode('utf-8')}
            if kind == 'constant':
                entry['references'] = refs[record]
            patches.append(entry)
    if not expected_literals or not expected_constants:
        raise ValueError('Expected both original asset literals and string constants')

    # Reject overlap even when bytes would coincidentally stay equal: aliases
    # could expose padding or a changed length through an unrelated value.
    for record, offset, value in literals:
        for start, content in edits.items():
            if start < offset + len(value) and offset < start + len(content):
                if record not in expected_literals or start != offset:
                    raise ValueError('Asset patch overlaps an unrelated literal')
    for start, content in edits.items():
        output[start:start + len(content)] = content
    if len(output) != len(data):
        raise ValueError('Asset patch changed metadata size')
    for (record, offset, value), (new_record, new_offset, length) in zip(
            literals, literal_records(output)):
        if (record, offset) != (new_record, new_offset):
            raise ValueError('Asset patch moved a literal')
        expected = expected_literals.get(record, value)
        if output[new_offset:new_offset + length] != expected:
            raise ValueError(f'Unexpected literal change at {offset:#x}')
    for record, offset, value in constants:
        length, size = _decode_length(output, record, bounds[record])
        expected = expected_constants.get(record, value)
        if output[record + size:record + size + length] != expected:
            raise ValueError(f'Unexpected constant change at {record:#x}')
    # All writes use validated fixed-size slices; explicitly check untouched gaps.
    cursor = 0
    for start, content in sorted(edits.items()):
        if output[cursor:start] != data[cursor:start]:
            raise ValueError('Unexpected change outside asset patch regions')
        cursor = start + len(content)
    if output[cursor:] != data[cursor:]:
        raise ValueError('Unexpected change outside asset patch regions')
    result = bytes(output)
    report = {'applied': True, 'target': TARGET.decode('ascii'), 'bytes': len(data),
              'literal_count': len(expected_literals), 'constant_count': len(expected_constants),
              'source_sha256': hashlib.sha256(data).hexdigest(),
              'patched_sha256': hashlib.sha256(result).hexdigest(),
              'all_literals_verified': True, 'unchanged_regions_verified': True,
              'patches': patches}
    return result, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=APK)
    args = parser.parse_args()
    with zipfile.ZipFile(args.apk) as archive:
        _, report = patch(archive.read(ENTRY))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
