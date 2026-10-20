// IL2CPP v31 address patching, ported from patch_metadata.py and
// patch_assets_cdn.py. Everything runs in the browser; no bytes leave the page.

export const API_OLD = ascii('https://api.link-like-lovelive.app');
export const ASSET_OLD = ascii('https://assets.link-like-lovelive.app');
export const API_STRING_TYPE_INDEX = 71244;
export const IPA_STRING_TYPE_INDEX = 71115;

const HEADER_SIZE = 256;
const DEFAULT_VALUE_HEADER = 0x48;
const DEFAULT_RECORD_HEADERS = [56, 64];
const MAGIC = 0xfab11baf;
const VERSION = 31;

export class PatchError extends Error {
  constructor(message) {
    super(message);
    this.name = 'PatchError';
  }
}

function ascii(text) {
  const bytes = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i);
  return bytes;
}

export function utf8(text) {
  return new TextEncoder().encode(text);
}

export function decodeUtf8(bytes) {
  return new TextDecoder('utf-8').decode(bytes);
}

function view(bytes) {
  return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
}

function u16(bytes, offset) {
  return view(bytes).getUint16(offset, true);
}

function u32(bytes, offset) {
  return view(bytes).getUint32(offset, true);
}

function i32(bytes, offset) {
  return view(bytes).getInt32(offset, true);
}

function putU32(bytes, offset, value) {
  view(bytes).setUint32(offset, value, true);
}

function putI32(bytes, offset, value) {
  view(bytes).setInt32(offset, value, true);
}

export function findBytes(haystack, needle, from = 0) {
  const n = needle.length;
  const m = haystack.length;
  if (n === 0) return from <= m ? from : -1;
  if (from < 0) from = 0;
  if (n > m - from) return -1;
  const skip = new Int32Array(256);
  skip.fill(n);
  for (let i = 0; i < n - 1; i++) skip[needle[i]] = n - 1 - i;
  let position = from;
  while (position <= m - n) {
    let j = n - 1;
    while (j >= 0 && haystack[position + j] === needle[j]) j--;
    if (j < 0) return position;
    position += skip[haystack[position + n - 1]];
  }
  return -1;
}

export function equalBytes(left, right) {
  if (left.length !== right.length) return false;
  for (let i = 0; i < left.length; i++) {
    if (left[i] !== right[i]) return false;
  }
  return true;
}

function startsWithBytes(value, prefix) {
  if (value.length < prefix.length) return false;
  for (let i = 0; i < prefix.length; i++) {
    if (value[i] !== prefix[i]) return false;
  }
  return true;
}

function concatBytes(...parts) {
  let size = 0;
  for (const part of parts) size += part.length;
  const result = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.length;
  }
  return result;
}

function insertBytes(bytes, at, extra) {
  if (at < HEADER_SIZE || at > bytes.length) {
    throw new PatchError('拒绝在 metadata 头部内插入数据');
  }
  const result = new Uint8Array(bytes.length + extra.length);
  result.set(bytes.subarray(0, at), 0);
  result.set(extra, at);
  result.set(bytes.subarray(at), at + extra.length);
  return result;
}

function countBytes(haystack, needle) {
  let count = 0;
  let position = 0;
  while ((position = findBytes(haystack, needle, position)) >= 0) {
    count++;
    position += needle.length;
  }
  return count;
}

export function normalizeOrigin(value) {
  let text = String(value ?? '').trim();
  while (text.endsWith('/')) text = text.slice(0, -1);
  if (!text || text.endsWith(':') || text.includes('?') || text.includes('#')
      || /[\s\x00-\x1f\x7f]/.test(text)) {
    throw new PatchError('地址必须是 http(s)://host[:port] 形式，不能带路径、参数或凭据');
  }
  for (const character of text) {
    if (character.codePointAt(0) > 0x7f) {
      throw new PatchError('地址只能使用 ASCII 主机名');
    }
  }
  let url;
  try {
    url = new URL(text);
  } catch (error) {
    throw new PatchError('地址格式无效');
  }
  if (!['http:', 'https:'].includes(url.protocol)
      || !url.hostname || !url.hostname.replace(/^\.+|\.+$/g, '')
      || (url.pathname && url.pathname !== '/') || url.search || url.hash
      || url.username || url.password || text.length > 200) {
    throw new PatchError('地址必须是 http(s)://host[:port] 形式，不能带路径、参数或凭据');
  }
  if (url.port && !(Number(url.port) >= 1 && Number(url.port) <= 65535)) {
    throw new PatchError('地址端口无效');
  }
  return text;
}

function literalRecords(data) {
  if (data.length < 24) throw new PatchError('metadata 头部不完整');
  const dv = view(data);
  const magic = dv.getUint32(0, true);
  const version = dv.getUint32(4, true);
  const table = dv.getUint32(8, true);
  const tableSize = dv.getUint32(12, true);
  const pool = dv.getUint32(16, true);
  const poolSize = dv.getUint32(20, true);
  if (magic !== MAGIC || version !== VERSION) {
    throw new PatchError('只支持已校验的 IL2CPP metadata v31');
  }
  if (tableSize % 8 || table + tableSize > data.length || pool + poolSize > data.length) {
    throw new PatchError('metadata 字面量表边界无效');
  }
  const records = [];
  for (let record = table; record < table + tableSize; record += 8) {
    const length = dv.getUint32(record, true);
    const index = dv.getUint32(record + 4, true);
    if (index + length > poolSize) throw new PatchError('字面量超出字符串池');
    records.push({record, offset: pool + index, length});
  }
  return records;
}

function layout(data) {
  if (data.length < HEADER_SIZE) throw new PatchError('metadata 头部不完整');
  const dv = view(data);
  if (dv.getUint32(0, true) !== MAGIC || dv.getUint32(4, true) !== VERSION) {
    throw new PatchError('只支持已校验的 IL2CPP metadata v31');
  }
  const sections = [];
  for (let header = 8; header < HEADER_SIZE; header += 8) {
    const start = dv.getUint32(header, true);
    const size = dv.getUint32(header + 4, true);
    if (!size) continue;
    if (start < HEADER_SIZE || start + size > data.length) {
      throw new PatchError('metadata 段边界无效');
    }
    sections.push([start, start + size]);
  }
  sections.sort((left, right) => left[0] - right[0]);
  for (let i = 1; i < sections.length; i++) {
    if (sections[i - 1][1] > sections[i][0]) throw new PatchError('metadata 段重叠');
  }

  const records = literalRecords(data);
  const defaultPool = dv.getUint32(72, true);
  const defaultSize = dv.getUint32(76, true);
  const refs = new Map();
  for (const [header, kind] of [[56, 'parameter'], [64, 'field']]) {
    const table = dv.getUint32(header, true);
    const size = dv.getUint32(header + 4, true);
    if (size % 12) throw new PatchError('默认值记录大小无效');
    for (let record = table; record < table + size; record += 12) {
      const owner = dv.getInt32(record, true);
      const typeIndex = dv.getInt32(record + 4, true);
      const index = dv.getInt32(record + 8, true);
      if (index === -1) continue;
      if (index < 0 || index >= defaultSize) throw new PatchError('默认值索引超出字符串池');
      const start = defaultPool + index;
      if (!refs.has(start)) refs.set(start, []);
      refs.get(start).push({kind, record, owner, typeIndex});
    }
  }
  const offsets = [...refs.keys()].sort((left, right) => left - right);
  const bounds = new Map();
  for (let i = 0; i < offsets.length; i++) {
    bounds.set(offsets[i], i + 1 < offsets.length ? offsets[i + 1] : defaultPool + defaultSize);
  }
  return {records, refs, bounds, defaultPool, defaultSize};
}

function encodeLength(length) {
  if (!(length >= 0 && length <= 0x7fffffff)) throw new PatchError('字符串长度无效');
  const value = length * 2;
  if (value < 0x80) return Uint8Array.of(value);
  if (value < 0x4000) return Uint8Array.of(0x80 | (value >> 8), value & 0xff);
  if (value < 0x20000000) {
    return Uint8Array.of(0xc0 | (value >> 24), (value >> 16) & 0xff,
      (value >> 8) & 0xff, value & 0xff);
  }
  const result = new Uint8Array(5);
  result[0] = 0xf0;
  view(result).setUint32(1, value, true);
  return result;
}

function decodeLength(data, offset, end) {
  if (offset >= end) throw new PatchError('缺少压缩字符串长度');
  const first = data[offset];
  let size;
  let value;
  if (first < 0x80) {
    size = 1;
    value = first;
  } else if (first < 0xc0) {
    size = 2;
    value = first & 0x3f;
  } else if (first < 0xe0) {
    size = 4;
    value = first & 0x1f;
  } else if (first === 0xf0) {
    size = 5;
    value = 0;
  } else {
    throw new PatchError('未知的压缩字符串长度前缀');
  }
  if (offset + size > end) throw new PatchError('压缩字符串长度被截断');
  if (size === 5) {
    value = view(data).getUint32(offset + 1, true);
  } else {
    for (let i = 1; i < size; i++) value = (value * 256) + data[offset + i];
  }
  if (value & 1) throw new PatchError('压缩字符串长度为负数');
  const length = Math.floor(value / 2);
  const encoded = encodeLength(length);
  if (!equalBytes(encoded, data.subarray(offset, offset + size))) {
    throw new PatchError('压缩字符串长度不是规范编码');
  }
  return {length, size};
}

function shiftHeader(bytes, insertAt, delta) {
  const dv = view(bytes);
  for (let header = 8; header < HEADER_SIZE; header += 8) {
    const start = dv.getUint32(header, true);
    const size = dv.getUint32(header + 4, true);
    if (!size) continue;
    if (start >= insertAt) {
      dv.setUint32(header, start + delta, true);
    } else if (start < insertAt && insertAt <= start + size) {
      dv.setUint32(header + 4, size + delta, true);
    }
  }
}

function shiftLiteralIndexes(bytes, afterIndex, delta) {
  const dv = view(bytes);
  const table = dv.getUint32(8, true);
  const size = dv.getUint32(12, true);
  if (size % 8) throw new PatchError('字面量表大小无效');
  for (let record = table; record < table + size; record += 8) {
    const index = dv.getUint32(record + 4, true);
    if (index >= afterIndex) dv.setUint32(record + 4, index + delta, true);
  }
}

function shiftDefaultIndexes(bytes, afterIndex, delta) {
  const dv = view(bytes);
  for (const header of DEFAULT_RECORD_HEADERS) {
    const table = dv.getUint32(header, true);
    const size = dv.getUint32(header + 4, true);
    if (size % 12) throw new PatchError('默认值记录大小无效');
    for (let record = table; record < table + size; record += 12) {
      const index = dv.getInt32(record + 8, true);
      if (index >= afterIndex) dv.setInt32(record + 8, index + delta, true);
    }
  }
}

function findApiTargets(data) {
  const records = literalRecords(data);
  const literals = records.filter((item) =>
    equalBytes(data.subarray(item.offset, item.offset + item.length), API_OLD));
  if (!literals.length) throw new PatchError('metadata 中找不到原始 API 地址');
  const literalOffsets = new Set(literals.map((item) => item.offset));
  const constants = [];
  let position = 0;
  while ((position = findBytes(data, API_OLD, position)) >= 0) {
    const offset = position;
    position += API_OLD.length;
    if (literalOffsets.has(offset)) continue;
    if (API_OLD.length * 2 >= 128 || data[offset - 1] !== API_OLD.length * 2) {
      throw new PatchError('无法识别 API 字符串常量长度前缀');
    }
    constants.push(offset);
  }
  if (constants.length !== 1) throw new PatchError('API 字符串常量数量不是 1');
  return {records, literals, constant: constants[0]};
}

function patchApiEqual(data, replacement) {
  const {records, literals, constant: constantOffset} = findApiTargets(data);
  const output = data.slice();
  const changed = new Uint8Array(data.length);
  const mark = (offset, content) => {
    output.set(content, offset);
    changed.fill(1, offset, offset + content.length);
  };
  const report = [];
  for (const item of literals) {
    mark(item.record, Uint8Array.of(
      replacement.length & 0xff,
      (replacement.length >>> 8) & 0xff,
      (replacement.length >>> 16) & 0xff,
      (replacement.length >>> 24) & 0xff));
    const padded = new Uint8Array(item.length);
    padded.set(replacement);
    mark(item.offset, padded);
    report.push({kind: 'literal', offset: item.offset, length_record: item.record,
      old_length: item.length, new_length: replacement.length});
  }
  mark(constantOffset - 1, Uint8Array.of(replacement.length * 2));
  const padded = new Uint8Array(API_OLD.length);
  padded.set(replacement);
  mark(constantOffset, padded);
  report.push({kind: 'constant', offset: constantOffset, length_record: constantOffset - 1,
    old_length: API_OLD.length, new_length: replacement.length});

  if (output.length !== data.length || findBytes(output, API_OLD) >= 0) {
    throw new PatchError('API 补丁改变了 metadata 大小或遗漏了地址');
  }
  const literalOffsets = new Set(literals.map((item) => item.offset));
  const newRecords = literalRecords(output);
  for (let i = 0; i < records.length; i++) {
    const oldItem = records[i];
    const newItem = newRecords[i];
    if (oldItem.record !== newItem.record || oldItem.offset !== newItem.offset) {
      throw new PatchError('API 补丁移动了字面量表');
    }
    const expected = literalOffsets.has(oldItem.offset)
      ? replacement : data.subarray(oldItem.offset, oldItem.offset + oldItem.length);
    if (!equalBytes(output.subarray(newItem.offset, newItem.offset + newItem.length), expected)) {
      throw new PatchError('API 补丁字面量校验失败');
    }
  }
  for (let i = 0; i < data.length; i++) {
    if (data[i] !== output[i] && !changed[i]) {
      throw new PatchError('API 补丁修改了预期之外的区域');
    }
  }
  return {metadata: output, report};
}

function patchApiGrow(data, replacement) {
  const delta = replacement.length - API_OLD.length;
  const extra = replacement.subarray(API_OLD.length);
  if (delta <= 0) throw new PatchError('增长补丁要求更长的替换地址');
  if (replacement.length * 2 >= 128) throw new PatchError('API 地址过长');
  const {records, literals, constant: constantOffset} = findApiTargets(data);
  let bytes = data.slice();
  const ops = [{kind: 'constant', offset: constantOffset, record: constantOffset - 1,
    old_length: API_OLD.length}];
  for (const item of literals) {
    ops.push({kind: 'literal', offset: item.offset, record: item.record,
      old_length: item.length});
  }
  ops.sort((left, right) => right.offset - left.offset);
  for (const op of ops) {
    const insertAt = op.offset + API_OLD.length;
    if (op.kind === 'constant') {
      const pool = u32(bytes, DEFAULT_VALUE_HEADER);
      const relativeAfter = insertAt - pool;
      bytes = insertBytes(bytes, insertAt, extra);
      shiftHeader(bytes, insertAt, delta);
      bytes.set(replacement, op.offset);
      bytes[op.record] = replacement.length * 2;
      shiftDefaultIndexes(bytes, relativeAfter, delta);
    } else {
      const pool = u32(bytes, 16);
      const relativeAfter = insertAt - pool;
      bytes = insertBytes(bytes, insertAt, extra);
      shiftHeader(bytes, insertAt, delta);
      bytes.set(replacement, op.offset);
      putU32(bytes, op.record, replacement.length);
      shiftLiteralIndexes(bytes, relativeAfter, delta);
    }
    for (const other of ops) {
      if (other === op) continue;
      if (other.offset >= insertAt) other.offset += delta;
      if (other.record >= insertAt) other.record += delta;
    }
  }
  if (findBytes(bytes, API_OLD) >= 0 || countBytes(bytes, replacement) !== literals.length + 1) {
    throw new PatchError('API 补丁遗漏了地址');
  }
  if (bytes.length !== data.length + delta * (literals.length + 1)) {
    throw new PatchError('API 补丁后的 metadata 大小不符合预期');
  }
  const oldByRecord = new Map();
  for (const item of records) {
    oldByRecord.set(item.record, data.subarray(item.offset, item.offset + item.length));
  }
  const newRecords = literalRecords(bytes);
  if (newRecords.length !== records.length) throw new PatchError('字面量记录数量发生变化');
  for (let i = 0; i < records.length; i++) {
    const oldItem = records[i];
    const newItem = newRecords[i];
    if (oldItem.record !== newItem.record) throw new PatchError('字面量表位置发生变化');
    const original = oldByRecord.get(oldItem.record);
    const expected = equalBytes(original, API_OLD) ? replacement : original;
    if (!equalBytes(bytes.subarray(newItem.offset, newItem.offset + newItem.length), expected)) {
      throw new PatchError('API 补丁字面量校验失败');
    }
  }
  const report = ops.map((op) => ({kind: op.kind, offset: op.offset,
    length_record: op.record, old_length: op.old_length,
    new_length: replacement.length, grew: delta}));
  return {metadata: bytes, report};
}

export function patchApi(data, origin) {
  if (!(data instanceof Uint8Array)) throw new PatchError('metadata 必须是字节数组');
  const replacement = utf8(normalizeOrigin(origin));
  if (equalBytes(replacement, API_OLD)) throw new PatchError('原始地址和替换地址不能相同');
  return replacement.length > API_OLD.length
    ? patchApiGrow(data, replacement) : patchApiEqual(data, replacement);
}

function discoverAssetPatches(data, stringTypeIndex) {
  const {records, refs, bounds, defaultPool} = layout(data);
  const literals = records.map((item) => ({
    record: item.record,
    offset: item.offset,
    value: data.subarray(item.offset, item.offset + item.length),
  }));
  const constants = [];
  for (const [start, end] of bounds) {
    if (findBytes(data.subarray(start, end), ASSET_OLD) < 0) continue;
    const startRefs = refs.get(start) || [];
    if (startRefs.some((ref) => ref.typeIndex !== stringTypeIndex)) {
      throw new PatchError('资源字符串常量的类型索引未知');
    }
    const {length, size} = decodeLength(data, start, end);
    const offset = start + size;
    if (offset + length > end) throw new PatchError('资源字符串常量越过默认值边界');
    const value = data.subarray(offset, offset + length);
    if (findBytes(value, ASSET_OLD) < 0) throw new PatchError('默认值边界内找不到资源地址');
    constants.push({start, offset, value});
  }
  const covered = [];
  for (const item of literals.concat(constants)) {
    if (findBytes(item.value, ASSET_OLD) >= 0) {
      covered.push([item.offset, item.offset + item.value.length]);
    }
  }
  covered.sort((left, right) => left[0] - right[0]);
  let position = 0;
  let interval = 0;
  while ((position = findBytes(data, ASSET_OLD, position)) >= 0) {
    const end = position + ASSET_OLD.length;
    while (interval < covered.length && covered[interval][1] < end) interval++;
    if (interval === covered.length || covered[interval][0] > position) {
      throw new PatchError('无法识别的资源地址布局');
    }
    position += ASSET_OLD.length;
  }
  const patches = [];
  for (const item of literals) {
    if (startsWithBytes(item.value, ASSET_OLD)) {
      patches.push({kind: 'literal', offset: item.offset, length_record: item.record,
        old_length: item.value.length, value: item.value,
        original_url: decodeUtf8(item.value)});
    }
  }
  for (const item of constants) {
    if (startsWithBytes(item.value, ASSET_OLD)) {
      patches.push({kind: 'constant', offset: item.offset, length_record: item.start,
        old_length: item.value.length, value: item.value,
        original_url: decodeUtf8(item.value), references: refs.get(item.start)});
    }
  }
  const literalCount = patches.filter((item) => item.kind === 'literal').length;
  const constantCount = patches.filter((item) => item.kind === 'constant').length;
  if (!literalCount || !constantCount) {
    throw new PatchError('资源地址必须同时包含字面量和字符串常量');
  }
  return {records, refs, bounds, defaultPool, patches, literalCount, constantCount};
}

export function patchAssets(data, origin, stringTypeIndex = API_STRING_TYPE_INDEX) {
  const target = utf8(normalizeOrigin(origin));
  const discovery = discoverAssetPatches(data, stringTypeIndex);
  const {records, refs, bounds, defaultPool, patches} = discovery;
  const pool = u32(data, 16);
  const operations = new Map();
  for (const item of patches) {
    const replacement = concatBytes(target, item.value.subarray(ASSET_OLD.length));
    let start;
    let content;
    if (item.kind === 'constant') {
      start = item.length_record;
      content = concatBytes(encodeLength(replacement.length), replacement);
    } else {
      start = item.offset;
      content = replacement;
    }
    const end = item.offset + item.old_length;
    const previous = operations.get(start);
    if (previous) {
      if (previous.end !== end || !equalBytes(previous.content, content)) {
        throw new PatchError('资源字符串共享区域不一致');
      }
    } else {
      operations.set(start, {start, end, content});
    }
  }
  const edits = [...operations.values()].sort((left, right) => left.start - right.start);
  for (let i = 1; i < edits.length; i++) {
    if (edits[i - 1].end > edits[i].start) throw new PatchError('资源地址区域重叠');
  }

  const moved = (position) => {
    let shift = 0;
    for (const edit of edits) {
      if (position >= edit.end) {
        shift += edit.content.length - (edit.end - edit.start);
      } else if (position > edit.start) {
        throw new PatchError('metadata 引用落在被替换字符串内部');
      }
    }
    return position + shift;
  };

  let outputSize = data.length;
  for (const edit of edits) outputSize += edit.content.length - (edit.end - edit.start);
  const output = new Uint8Array(outputSize);
  let outputOffset = 0;
  let cursor = 0;
  for (const edit of edits) {
    output.set(data.subarray(cursor, edit.start), outputOffset);
    outputOffset += edit.start - cursor;
    output.set(edit.content, outputOffset);
    outputOffset += edit.content.length;
    cursor = edit.end;
  }
  output.set(data.subarray(cursor), outputOffset);

  const dv = view(data);
  for (let header = 8; header < HEADER_SIZE; header += 8) {
    const start = dv.getUint32(header, true);
    const size = dv.getUint32(header + 4, true);
    if (size) {
      putU32(output, header, moved(start));
      putU32(output, header + 4, moved(start + size) - moved(start));
    }
  }

  const expectedLiterals = [];
  for (const item of records) {
    const value = data.subarray(item.offset, item.offset + item.length);
    const expected = startsWithBytes(value, ASSET_OLD)
      ? concatBytes(target, value.subarray(ASSET_OLD.length)) : value;
    putU32(output, moved(item.record), expected.length);
    putU32(output, moved(item.record) + 4, moved(item.offset) - moved(pool));
    expectedLiterals.push(expected);
  }
  for (const [start, references] of refs) {
    for (const ref of references) {
      putI32(output, moved(ref.record) + 8, moved(start) - moved(defaultPool));
    }
  }

  const newLayout = layout(output);
  if (newLayout.records.length !== records.length) throw new PatchError('字面量数量发生变化');
  for (let i = 0; i < expectedLiterals.length; i++) {
    const item = newLayout.records[i];
    if (!equalBytes(output.subarray(item.offset, item.offset + item.length), expectedLiterals[i])) {
      throw new PatchError('资源补丁字面量校验失败');
    }
  }
  const patchedConstants = new Map();
  for (const item of patches) {
    if (item.kind === 'constant') patchedConstants.set(item.length_record, item);
  }
  for (const [start, end] of bounds) {
    const newStart = moved(start);
    const newEnd = moved(end);
    if (!newLayout.bounds.has(newStart) || newLayout.bounds.get(newStart) !== newEnd) {
      throw new PatchError('资源补丁默认值边界发生变化');
    }
    const patched = patchedConstants.get(start);
    if (patched) {
      const expected = concatBytes(target, patched.value.subarray(ASSET_OLD.length));
      const {length, size} = decodeLength(output, newStart, newEnd);
      if (length !== expected.length
          || !equalBytes(output.subarray(newStart + size, newStart + size + length), expected)) {
        throw new PatchError('资源补丁字符串常量校验失败');
      }
      const oldEnd = patched.offset + patched.old_length;
      if (!equalBytes(output.subarray(newStart + size + length, newEnd), data.subarray(oldEnd, end))) {
        throw new PatchError('资源补丁默认值尾部数据发生变化');
      }
    } else if (!equalBytes(output.subarray(newStart, newEnd), data.subarray(start, end))) {
      throw new PatchError('资源补丁修改了无关默认值');
    }
  }
  for (let header = 8; header < HEADER_SIZE; header += 8) {
    const start = dv.getUint32(header, true);
    const size = dv.getUint32(header + 4, true);
    if (size && ![8, 16, 56, 64, 72].includes(header)) {
      if (!equalBytes(output.subarray(moved(start), moved(start + size)),
        data.subarray(start, start + size))) {
        throw new PatchError('资源补丁修改了无关 metadata 段');
      }
    }
  }
  return {
    metadata: output,
    report: {
      applied: true,
      target: decodeUtf8(target),
      literal_count: discovery.literalCount,
      constant_count: discovery.constantCount,
      source_bytes: data.length,
      bytes: output.length,
      growth_bytes: output.length - data.length,
      all_literals_verified: true,
      all_default_values_verified: true,
      unrelated_sections_verified: true,
      patches: patches.map((item) => ({
        kind: item.kind,
        offset: item.offset,
        length_record: item.length_record,
        old_length: item.old_length,
        original_url: item.original_url,
        ...(item.references ? {references: item.references} : {}),
        replacement_url: decodeUtf8(target) + item.original_url.slice(ASSET_OLD.length),
        new_length: target.length + item.old_length - ASSET_OLD.length,
        new_prefix_size: item.kind === 'literal'
          ? 4 : encodeLength(target.length + item.old_length - ASSET_OLD.length).length,
      })),
    },
  };
}

export function patchMetadata(data, {apiOrigin, assetOrigin, kind}) {
  const api = patchApi(data, apiOrigin);
  const assets = patchAssets(api.metadata, assetOrigin,
    kind === 'ipa' ? IPA_STRING_TYPE_INDEX : API_STRING_TYPE_INDEX);
  if (findBytes(assets.metadata, API_OLD) >= 0 || findBytes(assets.metadata, ASSET_OLD) >= 0) {
    throw new PatchError('补丁后仍残留官方地址');
  }
  return {metadata: assets.metadata, apiPatch: api.report, assetPatch: assets.report};
}

export const ATS_BLOCK = ascii(
  '\t<key>NSAppTransportSecurity</key>\n' +
  '\t<dict>\n' +
  '\t\t<key>NSAllowsArbitraryLoads</key>\n\t\t<true/>\n' +
  '\t\t<key>NSAllowsLocalNetworking</key>\n\t\t<true/>\n' +
  '\t</dict>\n');

export function addAts(plist) {
  if (findBytes(plist, ascii('NSAppTransportSecurity')) >= 0) {
    throw new PatchError('Info.plist 已声明 NSAppTransportSecurity');
  }
  const marker = ascii('</dict>');
  let root = -1;
  let position = 0;
  while ((position = findBytes(plist, marker, position)) >= 0) {
    root = position;
    position += marker.length;
  }
  if (root < 0) throw new PatchError('Info.plist 没有根 dict 结束标记');
  return concatBytes(plist.subarray(0, root), ATS_BLOCK, plist.subarray(root));
}

export function isHttpOrigin(origin) {
  return new URL(normalizeOrigin(origin)).protocol === 'http:';
}
