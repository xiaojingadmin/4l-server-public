import { normalizeOrigin, patchMetadata, addAts, isHttpOrigin } from './il2cpp.js';
import {
  MAX_METADATA_BYTES,
  findAppPlist,
  findMetadataEntry,
  openZip,
  readEntryBytes,
  writeZip,
} from './zip.js';

const MAX_INPUT_BYTES = 4 * 1024 * 1024 * 1024;
const fileInput = document.getElementById('file');
const drop = document.getElementById('drop');
const fileName = document.getElementById('fileName');
const apiInput = document.getElementById('apiOrigin');
const assetInput = document.getElementById('assetOrigin');
const submit = document.getElementById('submit');
const status = document.getElementById('status');
const bar = document.getElementById('bar');
const progress = bar.querySelector('i');
const result = document.getElementById('result');
let chosen = null;
let busy = false;
let outputUrl = null;
let reportUrl = null;

function formatBytes(value) {
  if (value < 1024) return value + ' B';
  if (value < 1048576) return (value / 1024).toFixed(1) + ' KiB';
  if (value < 1073741824) return (value / 1048576).toFixed(1) + ' MiB';
  return (value / 1073741824).toFixed(2) + ' GiB';
}

function setStatus(text, kind = '') {
  status.textContent = text;
  status.className = 'status ' + kind;
}

function setProgress(value) {
  bar.style.display = 'block';
  progress.style.width = Math.max(0, Math.min(100, value)) + '%';
}

function setBusy(value) {
  busy = value;
  fileInput.disabled = value;
  submit.disabled = value || !chosen;
}

function choose(file) {
  if (!file) return;
  const name = file.name.toLowerCase();
  if (!name.endsWith('.apk') && !name.endsWith('.ipa')) {
    chosen = null;
    submit.disabled = true;
    setStatus('只支持 .apk 或 .ipa 文件', 'bad');
    return;
  }
  if (file.size > MAX_INPUT_BYTES) {
    chosen = null;
    submit.disabled = true;
    setStatus('文件超过浏览器端 4 GiB 上限', 'bad');
    return;
  }
  chosen = file;
  fileName.textContent = file.name + '  (' + formatBytes(file.size) + ')';
  submit.disabled = false;
  result.style.display = 'none';
  setStatus('已选择文件，点击开始；文件不会上传。', '');
}

function downloadName(name, kind) {
  return name.replace(/\.[^.]+$/, '') + '-patched.' + kind;
}

function showResult(blob, name, report) {
  result.textContent = '';
  if (outputUrl) URL.revokeObjectURL(outputUrl);
  if (reportUrl) URL.revokeObjectURL(reportUrl);
  outputUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = outputUrl;
  link.download = name;
  link.textContent = '下载 ' + name + '  (' + formatBytes(blob.size) + ')';
  result.appendChild(link);

  const warning = document.createElement('div');
  warning.className = 'meta warn';
  warning.textContent = report.warning;
  result.appendChild(warning);

  const info = document.createElement('div');
  info.className = 'meta';
  info.textContent = '类型：' + report.kind.toUpperCase()
    + ' · 浏览器内处理 · 未签名 · metadata：' + report.metadata_entry;
  result.appendChild(info);

  const details = document.createElement('details');
  const summary = document.createElement('summary');
  summary.textContent = '查看补丁报告';
  details.appendChild(summary);
  const pre = document.createElement('pre');
  pre.textContent = JSON.stringify(report, null, 2);
  details.appendChild(pre);
  const reportBlob = new Blob([JSON.stringify(report, null, 2)], {type: 'application/json'});
  reportUrl = URL.createObjectURL(reportBlob);
  const reportLink = document.createElement('a');
  reportLink.href = reportUrl;
  reportLink.download = name.replace(/\.(apk|ipa)$/, '') + '-patch-report.json';
  reportLink.textContent = '下载报告 JSON';
  reportLink.className = 'report-link';
  details.appendChild(reportLink);
  result.appendChild(details);
  result.style.display = 'block';
}

async function runPatch() {
  if (!chosen || busy) return;
  let apiOrigin;
  let assetOrigin;
  try {
    apiOrigin = normalizeOrigin(apiInput.value);
    assetOrigin = normalizeOrigin(assetInput.value);
  } catch (error) {
    setStatus(error.message, 'bad');
    return;
  }
  const kind = chosen.name.toLowerCase().endsWith('.apk') ? 'apk' : 'ipa';
  setBusy(true);
  result.style.display = 'none';
  setProgress(3);
  setStatus('读取 ZIP 目录…');
  try {
    const zip = await openZip(chosen);
    setProgress(10);
    setStatus('定位 global-metadata.dat…');
    const metadataEntry = findMetadataEntry(zip, kind);
    setProgress(18);
    setStatus('解压 metadata…');
    const metadata = await readEntryBytes(zip, metadataEntry, MAX_METADATA_BYTES);
    setProgress(32);
    setStatus('替换 API 地址…（全部在浏览器本地完成）');
    await new Promise((resolve) => setTimeout(resolve, 0));
    const patched = patchMetadata(metadata, {apiOrigin, assetOrigin, kind});
    setProgress(55);
    const replacements = new Map([[metadataEntry.name, patched.metadata]]);
    let ats = false;
    if (kind === 'ipa' && (isHttpOrigin(apiOrigin) || isHttpOrigin(assetOrigin))) {
      setStatus('为 HTTP 地址补 Info.plist ATS…');
      const plistEntry = findAppPlist(zip);
      const plist = await readEntryBytes(zip, plistEntry, 16 * 1024 * 1024);
      replacements.set(plistEntry.name, addAts(plist));
      ats = true;
    }
    setProgress(65);
    setStatus('重新打包 APK/IPA…');
    const output = await writeZip(zip, replacements, {
      dropSignatures: kind === 'apk',
      onProgress: (done, total) => {
        setProgress(65 + (done / total) * 30);
        setStatus('重新打包 ' + done + ' / ' + total + ' 个条目…');
      },
    });
    setProgress(100);
    const name = downloadName(chosen.name, kind);
    const report = {
      kind,
      source_name: chosen.name,
      source_size: chosen.size,
      api_origin: apiOrigin,
      asset_origin: assetOrigin,
      metadata_entry: metadataEntry.name,
      api_patch: patched.apiPatch,
      asset_patch: patched.assetPatch,
      ats,
      signed: false,
      output_name: name,
      output_size: output.size,
      warning: kind === 'apk'
        ? '浏览器内只完成地址替换并移除了旧签名，下载后必须用 apksigner 重新签名才能安装。'
        : '浏览器内只完成地址替换，iOS 签名已失效，下载后必须用自己的 Apple 证书重签。',
    };
    showResult(output, name, report);
    setStatus('Patch 完成；文件未离开本机。', 'ok');
  } catch (error) {
    console.error(error);
    setStatus(error && error.message ? error.message : '处理失败', 'bad');
    bar.style.display = 'none';
  } finally {
    setBusy(false);
  }
}

fileInput.addEventListener('change', () => choose(fileInput.files[0]));
drop.addEventListener('click', () => {
  if (!busy) fileInput.click();
});
for (const event of ['dragenter', 'dragover']) {
  drop.addEventListener(event, (ev) => {
    ev.preventDefault();
    if (!busy) drop.classList.add('drag');
  });
}
for (const event of ['dragleave', 'drop']) {
  drop.addEventListener(event, (ev) => {
    ev.preventDefault();
    drop.classList.remove('drag');
  });
}
drop.addEventListener('drop', (ev) => {
  if (!busy) choose(ev.dataTransfer.files[0]);
});
submit.addEventListener('click', runPatch);
