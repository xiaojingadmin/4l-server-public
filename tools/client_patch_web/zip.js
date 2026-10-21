// Minimal ZIP reader/writer used by the browser-only client patcher. It copies
// compressed payloads byte-for-byte and only rewrites the entries we replace.

import { PatchError } from './il2cpp.js';

export const MAX_ENTRIES = 200000;
export const MAX_UNCOMPRESSED_BYTES = 8 * 1024 * 1024 * 1024;
export const MAX_METADATA_BYTES = 512 * 1024 * 1024;

const EOCD = 0x06054b50;
const ZIP64_EOCD = 0x06064b50;
const ZIP64_LOCATOR = 0x07064b50;
const CENTRAL = 0x02014b50;
const LOCAL = 0x04034b50;

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let value = i;
    for (let bit = 0; bit < 8; bit++) {
      value = (value & 1) ? (0xedb88320 ^ (value >>> 1)) : (value >>> 1);
    }
    table[i] = value >>> 0;
  }
  return table;
})();

export function crc32(bytes) {
  let crc = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) {
    crc = CRC_TABLE[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function view(bytes) {
  return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
}

function getU64(dv, offset) {
  const value = dv.getBigUint64(offset, true);
  if (value > BigInt(Number.MAX_SAFE_INTEGER)) {
    throw new PatchError('ZIP64 数值超出浏览器安全整数范围');
  }
  return Number(value);
}

function decodeName(bytes) {
  return new TextDecoder('utf-8').decode(bytes).replace(/\\/g, '/');
}

function isApkSignatureEntry(name) {
  const upper = name.toUpperCase();
  return upper.startsWith('META-INF/')
    && (upper.endsWith('.SF') || upper.endsWith('.RSA') || upper.endsWith('.DSA')
      || upper.endsWith('.EC') || upper === 'META-INF/MANIFEST.MF');
}

function findEocd(tail) {
  const dv = view(tail);
  for (let index = tail.length - 22; index >= 0; index--) {
    if (dv.getUint32(index, true) !== EOCD) continue;
    if (index + 22 + dv.getUint16(index + 20, true) === tail.length) return index;
  }
  return -1;
}

function applyZip64Extra(extra, entry) {
  const dv = view(extra);
  let position = 0;
  let found = false;
  while (position + 4 <= extra.length) {
    const id = dv.getUint16(position, true);
    const size = dv.getUint16(position + 2, true);
    const start = position + 4;
    const end = start + size;
    if (end > extra.length) throw new PatchError('ZIP64 扩展字段被截断');
    if (id === 0x0001) {
      found = true;
      let cursor = start;
      if (entry.needsZip64[0]) {
        if (cursor + 8 > end) throw new PatchError('ZIP64 缺少原始大小');
        entry.uncompSize = getU64(dv, cursor);
        cursor += 8;
      }
      if (entry.needsZip64[1]) {
        if (cursor + 8 > end) throw new PatchError('ZIP64 缺少压缩大小');
        entry.compSize = getU64(dv, cursor);
        cursor += 8;
      }
      if (entry.needsZip64[2]) {
        if (cursor + 8 > end) throw new PatchError('ZIP64 缺少本地头偏移');
        entry.localOffset = getU64(dv, cursor);
        cursor += 8;
      }
      if (entry.needsZip64[3]) {
        if (cursor + 4 > end) throw new PatchError('ZIP64 缺少磁盘号');
        entry.diskStart = dv.getUint32(cursor, true);
      }
    }
    position = end;
  }
  if (!found) throw new PatchError('ZIP64 扩展字段不存在');
}

export async function openZip(blob) {
  if (blob.size < 22) throw new PatchError('不是有效的 ZIP/APK/IPA 文件');
  const tailStart = Math.max(0, blob.size - 65557);
  const tail = new Uint8Array(await blob.slice(tailStart).arrayBuffer());
  const eocdIndex = findEocd(tail);
  if (eocdIndex < 0) throw new PatchError('ZIP 中央目录结束标记不存在');
  const eocdOffset = tailStart + eocdIndex;
  const dv = view(tail);
  let diskNumber = dv.getUint16(eocdIndex + 4, true);
  let cdDisk = dv.getUint16(eocdIndex + 6, true);
  let entriesOnDisk = dv.getUint16(eocdIndex + 8, true);
  let totalEntries = dv.getUint16(eocdIndex + 10, true);
  let cdSize = dv.getUint32(eocdIndex + 12, true);
  let cdOffset = dv.getUint32(eocdIndex + 16, true);
  const comment = tail.slice(eocdIndex + 22, eocdIndex + 22 + dv.getUint16(eocdIndex + 20, true));

  if (entriesOnDisk === 0xffff || totalEntries === 0xffff
      || cdSize === 0xffffffff || cdOffset === 0xffffffff) {
    if (eocdOffset < 20) throw new PatchError('ZIP64 定位记录缺失');
    const locator = new Uint8Array(await blob.slice(eocdOffset - 20, eocdOffset).arrayBuffer());
    const locatorView = view(locator);
    if (locatorView.getUint32(0, true) !== ZIP64_LOCATOR) {
      throw new PatchError('ZIP64 定位记录签名无效');
    }
    const zip64Offset = getU64(locatorView, 8);
    if (zip64Offset + 56 > blob.size) throw new PatchError('ZIP64 中央目录结束记录越界');
    const record = new Uint8Array(await blob.slice(zip64Offset, zip64Offset + 56).arrayBuffer());
    const recordView = view(record);
    if (recordView.getUint32(0, true) !== ZIP64_EOCD) {
      throw new PatchError('ZIP64 中央目录结束记录签名无效');
    }
    diskNumber = recordView.getUint32(16, true);
    cdDisk = recordView.getUint32(20, true);
    entriesOnDisk = getU64(recordView, 24);
    totalEntries = getU64(recordView, 32);
    cdSize = getU64(recordView, 40);
    cdOffset = getU64(recordView, 48);
  }
  if (diskNumber !== 0 || cdDisk !== 0 || entriesOnDisk !== totalEntries) {
    throw new PatchError('不支持分卷 ZIP');
  }
  if (totalEntries === 0 || totalEntries > MAX_ENTRIES) {
    throw new PatchError('压缩包条目数量无效或过多');
  }
  if (cdOffset + cdSize > blob.size) throw new PatchError('ZIP 中央目录越界');
  const cd = new Uint8Array(await blob.slice(cdOffset, cdOffset + cdSize).arrayBuffer());
  const cdView = view(cd);
  const entries = [];
  const names = new Set();
  let totalUncompressed = 0;
  let position = 0;

  for (let index = 0; index < totalEntries; index++) {
    if (position + 46 > cd.length || cdView.getUint32(position, true) !== CENTRAL) {
      throw new PatchError('ZIP 中央目录记录损坏');
    }
    const versionMadeBy = cdView.getUint16(position + 4, true);
    const versionNeeded = cdView.getUint16(position + 6, true);
    const flags = cdView.getUint16(position + 8, true);
    const method = cdView.getUint16(position + 10, true);
    const time = cdView.getUint16(position + 12, true);
    const date = cdView.getUint16(position + 14, true);
    const entryCrc = cdView.getUint32(position + 16, true);
    const nameLength = cdView.getUint16(position + 28, true);
    const extraLength = cdView.getUint16(position + 30, true);
    const commentLength = cdView.getUint16(position + 32, true);
    const nameStart = position + 46;
    const extraStart = nameStart + nameLength;
    const commentStart = extraStart + extraLength;
    const next = commentStart + commentLength;
    if (next > cd.length) throw new PatchError('ZIP 中央目录条目越界');
    if (flags & 0x0041) throw new PatchError('不支持加密的 APK/IPA 压缩包');
    const nameBytes = cd.slice(nameStart, extraStart);
    const extra = cd.slice(extraStart, commentStart);
    const entryComment = cd.slice(commentStart, next);
    const name = decodeName(nameBytes);
    if (names.has(name)) throw new PatchError('压缩包包含重复条目：' + name);
    names.add(name);

    const entry = {
      name,
      nameBytes,
      comment: entryComment,
      versionMadeBy,
      versionNeeded,
      flags,
      method,
      time,
      date,
      crc: entryCrc,
      compSize: cdView.getUint32(position + 20, true),
      uncompSize: cdView.getUint32(position + 24, true),
      diskStart: cdView.getUint16(position + 34, true),
      internalAttr: cdView.getUint16(position + 36, true),
      externalAttr: cdView.getUint32(position + 38, true),
      localOffset: cdView.getUint32(position + 42, true),
      needsZip64: [
        cdView.getUint32(position + 24, true) === 0xffffffff,
        cdView.getUint32(position + 20, true) === 0xffffffff,
        cdView.getUint32(position + 42, true) === 0xffffffff,
        cdView.getUint16(position + 34, true) === 0xffff,
      ],
      localInfo: null,
    };
    if (entry.needsZip64.some(Boolean)) applyZip64Extra(extra, entry);
    if (entry.diskStart !== 0) throw new PatchError('不支持分卷 ZIP 条目');
    if (entry.method === 0 && entry.compSize !== entry.uncompSize) {
      throw new PatchError('ZIP 存储条目大小不一致');
    }
    totalUncompressed += entry.uncompSize;
    if (totalUncompressed > MAX_UNCOMPRESSED_BYTES) {
      throw new PatchError('解压后体积超过限制');
    }
    if (entry.localOffset >= blob.size || entry.localOffset + 30 > blob.size) {
      throw new PatchError('ZIP 本地文件头越界');
    }
    entries.push(entry);
    position = next;
  }

  return {blob, entries, comment, cdOffset, cdSize, totalEntries};
}

async function localInfo(zip, entry) {
  if (entry.localInfo) return entry.localInfo;
  const head = new Uint8Array(await zip.blob.slice(entry.localOffset, entry.localOffset + 30).arrayBuffer());
  if (head.length !== 30) throw new PatchError('ZIP 本地文件头被截断');
  const dv = view(head);
  if (dv.getUint32(0, true) !== LOCAL) throw new PatchError('ZIP 本地文件头签名无效');
  const method = dv.getUint16(8, true);
  if (method !== entry.method) throw new PatchError('ZIP 本地/中央压缩方式不一致');
  const nameLength = dv.getUint16(26, true);
  const extraLength = dv.getUint16(28, true);
  const dataStart = entry.localOffset + 30 + nameLength + extraLength;
  if (dataStart + entry.compSize > zip.blob.size) throw new PatchError('ZIP 条目数据越界');
  entry.localInfo = {dataStart};
  return entry.localInfo;
}

async function readLimited(stream, maxBytes) {
  const reader = stream.getReader();
  const chunks = [];
  let total = 0;
  try {
    for (;;) {
      const {done, value} = await reader.read();
      if (done) break;
      total += value.length;
      if (total > maxBytes) {
        await reader.cancel();
        throw new PatchError('解压后数据超过限制');
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock?.();
  }
  const result = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.length;
  }
  return result;
}

export async function readEntryBytes(zip, entry, maxBytes = MAX_METADATA_BYTES) {
  if (entry.uncompSize > maxBytes) throw new PatchError('条目解压后过大，拒绝处理');
  const local = await localInfo(zip, entry);
  const data = zip.blob.slice(local.dataStart, local.dataStart + entry.compSize);
  let bytes;
  if (entry.method === 0) {
    bytes = new Uint8Array(await data.arrayBuffer());
  } else if (entry.method === 8) {
    if (typeof DecompressionStream === 'undefined') {
      throw new PatchError('当前浏览器不支持 DecompressionStream，请升级浏览器');
    }
    const stream = data.stream().pipeThrough(new DecompressionStream('deflate-raw'));
    bytes = await readLimited(stream, Math.min(maxBytes, entry.uncompSize + 1));
  } else {
    throw new PatchError('只支持 stored/deflate 压缩的 metadata 条目');
  }
  if (bytes.length !== entry.uncompSize) throw new PatchError('条目解压后大小不一致');
  if (crc32(bytes) !== entry.crc) throw new PatchError('条目 CRC 校验失败');
  return bytes;
}

export function findMetadataEntry(zip, kind) {
  if (kind === 'apk') {
    if (!zip.entries.some((entry) => entry.name === 'AndroidManifest.xml')) {
      throw new PatchError('APK 缺少 AndroidManifest.xml');
    }
    const matches = zip.entries.filter(
      (entry) => entry.name === 'assets/bin/Data/Managed/Metadata/global-metadata.dat');
    if (matches.length !== 1) {
      throw new PatchError('APK 不是 5.1.0 单包结构：找不到唯一的 global-metadata.dat');
    }
    return matches[0];
  }
  const matches = zip.entries.filter(
    (entry) => entry.name.endsWith('Data/Managed/Metadata/global-metadata.dat')
      && !entry.name.startsWith('__MACOSX'));
  if (matches.length !== 1) {
    throw new PatchError('IPA 不是 5.1.0 结构：找不到唯一的 global-metadata.dat');
  }
  return matches[0];
}

export function findAppPlist(zip) {
  const matches = zip.entries.filter((entry) => {
    const parts = entry.name.split('/');
    return parts.length === 3 && parts[0] === 'Payload' && parts[1].endsWith('.app')
      && parts[2] === 'Info.plist';
  });
  if (matches.length !== 1) throw new PatchError('IPA 中找不到唯一的包根 Info.plist');
  return matches[0];
}

function alignmentExtra(offset, nameLength, alignment) {
  const pad = (alignment - ((offset + 30 + nameLength + 4) % alignment)) % alignment;
  const extra = new Uint8Array(4 + pad);
  const dv = view(extra);
  dv.setUint16(0, 0xd935, true);
  dv.setUint16(2, pad, true);
  return extra;
}

function partSize(part) {
  return typeof part === 'string' ? new TextEncoder().encode(part).length : part.size ?? part.byteLength;
}

function writeLocal(entry, offset, nameBytes, method, flags, crc, compSize, uncompSize,
                    time, date, extra, versionNeeded) {
  const header = new Uint8Array(30 + nameBytes.length + extra.length);
  const dv = view(header);
  dv.setUint32(0, LOCAL, true);
  dv.setUint16(4, versionNeeded, true);
  dv.setUint16(6, flags, true);
  dv.setUint16(8, method, true);
  dv.setUint16(10, time, true);
  dv.setUint16(12, date, true);
  dv.setUint32(14, crc, true);
  dv.setUint32(18, compSize, true);
  dv.setUint32(22, uncompSize, true);
  dv.setUint16(26, nameBytes.length, true);
  dv.setUint16(28, extra.length, true);
  header.set(nameBytes, 30);
  header.set(extra, 30 + nameBytes.length);
  return header;
}

function writeCentral(entry, nameBytes, flags, method, crc, compSize, uncompSize,
                      time, date, localOffset) {
  const comment = entry.comment || new Uint8Array(0);
  if (nameBytes.length > 0xffff || comment.length > 0xffff) {
    throw new PatchError('ZIP 条目名称或注释过长');
  }
  const header = new Uint8Array(46 + nameBytes.length + comment.length);
  const dv = view(header);
  dv.setUint32(0, CENTRAL, true);
  dv.setUint16(4, entry.versionMadeBy, true);
  dv.setUint16(6, entry.versionNeeded || 20, true);
  dv.setUint16(8, flags, true);
  dv.setUint16(10, method, true);
  dv.setUint16(12, time, true);
  dv.setUint16(14, date, true);
  dv.setUint32(16, crc, true);
  dv.setUint32(20, compSize, true);
  dv.setUint32(24, uncompSize, true);
  dv.setUint16(28, nameBytes.length, true);
  dv.setUint16(30, 0, true);
  dv.setUint16(32, comment.length, true);
  dv.setUint16(34, 0, true);
  dv.setUint16(36, entry.internalAttr, true);
  dv.setUint32(38, entry.externalAttr, true);
  dv.setUint32(42, localOffset, true);
  header.set(nameBytes, 46);
  header.set(comment, 46 + nameBytes.length);
  return header;
}

export async function writeZip(zip, replacements, {dropSignatures = false, onProgress} = {}) {
  const parts = [];
  const central = [];
  let offset = 0;
  let index = 0;
  const total = zip.entries.length;
  for (const entry of zip.entries) {
    index++;
    if (dropSignatures && isApkSignatureEntry(entry.name)) continue;
    const local = await localInfo(zip, entry);
    const replacement = replacements.get(entry.name);
    const flags = entry.flags & ~0x0008;
    let method;
    let crc;
    let compSize;
    let uncompSize;
    let dataPart;
    let alignment = 1;
    if (replacement) {
      method = 0;
      crc = crc32(replacement);
      compSize = replacement.length;
      uncompSize = replacement.length;
      dataPart = replacement;
      alignment = entry.name.endsWith('.so') ? 16384 : 4;
    } else {
      method = entry.method;
      crc = entry.crc;
      compSize = entry.compSize;
      uncompSize = entry.uncompSize;
      dataPart = zip.blob.slice(local.dataStart, local.dataStart + entry.compSize);
      if (method === 0) alignment = entry.name.endsWith('.so') ? 16384 : 4;
    }
    const nameBytes = new TextEncoder().encode(entry.name);
    let outputFlags = flags;
    if (entry.name !== new TextDecoder('utf-8').decode(nameBytes)
        || nameBytes.some((byte) => byte > 0x7f)) {
      outputFlags |= 0x0800;
    }
    if (compSize > 0xffffffff || uncompSize > 0xffffffff || offset > 0xffffffff) {
      throw new PatchError('输出文件超过 ZIP32 限制，请减小客户端体积');
    }
    const extra = alignment > 1 ? alignmentExtra(offset, nameBytes.length, alignment) : new Uint8Array(0);
    if (nameBytes.length > 0xffff || extra.length > 0xffff) {
      throw new PatchError('ZIP 条目名称或扩展字段过长');
    }
    const header = writeLocal(entry, offset, nameBytes, method, outputFlags, crc,
      compSize, uncompSize, entry.time, entry.date, extra, entry.versionNeeded || 20);
    const localOffset = offset;
    parts.push(header, dataPart);
    offset += header.length + partSize(dataPart);
    central.push({entry, nameBytes, flags: outputFlags, method, crc, compSize, uncompSize,
      time: entry.time, date: entry.date, localOffset});
    if (onProgress && index % 100 === 0) onProgress(index, total);
  }
  if (!central.length) throw new PatchError('补丁后压缩包为空');
  const centralStart = offset;
  for (const item of central) {
    const header = writeCentral(item.entry, item.nameBytes, item.flags, item.method,
      item.crc, item.compSize, item.uncompSize, item.time, item.date, item.localOffset);
    parts.push(header);
    offset += header.length;
  }
  const centralSize = offset - centralStart;
  if (central.length > 0xffff || centralStart > 0xffffffff || centralSize > 0xffffffff) {
    throw new PatchError('输出文件超过 ZIP32 限制，请减小客户端体积');
  }
  const comment = zip.comment || new Uint8Array(0);
  const eocd = new Uint8Array(22 + comment.length);
  const dv = view(eocd);
  dv.setUint32(0, EOCD, true);
  dv.setUint16(4, 0, true);
  dv.setUint16(6, 0, true);
  dv.setUint16(8, central.length, true);
  dv.setUint16(10, central.length, true);
  dv.setUint32(12, centralSize, true);
  dv.setUint32(16, centralStart, true);
  dv.setUint16(20, comment.length, true);
  eocd.set(comment, 22);
  parts.push(eocd);
  return new Blob(parts, {type: 'application/octet-stream'});
}
