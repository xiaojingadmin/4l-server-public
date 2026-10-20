#!/usr/bin/env python3
"""Self-contained web upload service for APK/IPA address patching."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import html
import json
import logging
import os
from pathlib import Path
import re
import secrets
import shutil
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from string import Template
from urllib.parse import parse_qs, quote, unquote, urlsplit

try:
    from .patcher import PatchError, default_api_origin, default_asset_origin
    from .patcher import normalize_origin, patch_client
except ImportError:  # direct execution: python tools/client_patch_web/server.py
    if __package__:
        raise
    from patcher import PatchError, default_api_origin, default_asset_origin
    from patcher import normalize_origin, patch_client

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATA_DIR = ROOT / 'data' / 'client-patch-web'
DEFAULT_MAX_UPLOAD = 2 * 1024 * 1024 * 1024
DEFAULT_TTL_SECONDS = 60 * 60
TOKEN_RE = re.compile(r'^[0-9a-f]{32}$')

HTML = Template('''<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>LinkLike 客户端地址补丁</title>
  <style>
    :root{color-scheme:dark;--bg:#0e1117;--card:#171c26;--line:#2b3445;--text:#e8eefc;--muted:#96a2b8;--accent:#74b9ff;--ok:#58d68d;--warn:#f6c85f;--bad:#ff7b7b}
    *{box-sizing:border-box}body{margin:0;min-height:100vh;background:radial-gradient(circle at 15% 0%,#1b2940 0,transparent 38%),var(--bg);color:var(--text);font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif}
    main{width:min(820px,calc(100% - 32px));margin:48px auto}.title{font-size:30px;line-height:1.2;margin:0 0 10px}.sub{color:var(--muted);margin:0 0 26px}
    .card{background:linear-gradient(180deg,rgba(255,255,255,.035),rgba(255,255,255,.015)),var(--card);border:1px solid var(--line);border-radius:18px;padding:24px;box-shadow:0 22px 70px rgba(0,0,0,.3)}
    .drop{display:block;border:1.5px dashed #3c4b64;border-radius:14px;padding:38px 20px;text-align:center;cursor:pointer;transition:.2s}.drop:hover,.drop.drag{border-color:var(--accent);background:rgba(116,185,255,.06)}
    .icon{font-size:42px;line-height:1;margin-bottom:8px}.hint{color:var(--muted);font-size:13px}.button{display:inline-flex;align-items:center;justify-content:center;margin-top:18px;border:0;border-radius:10px;padding:12px 22px;background:var(--accent);color:#08111e;font-weight:750;cursor:pointer}.button:disabled{opacity:.45;cursor:not-allowed}
    .bar{height:8px;background:#0b1018;border-radius:99px;overflow:hidden;margin-top:18px;display:none}.bar>i{display:block;height:100%;width:0;background:linear-gradient(90deg,#4aa8ff,#8f7dff);transition:width .18s}
    .status{margin-top:14px;color:var(--muted);white-space:pre-wrap}.ok{color:var(--ok)}.warn{color:var(--warn)}.bad{color:var(--bad)}
    .result{display:none;margin-top:22px;padding:18px;border:1px solid var(--line);border-radius:12px;background:#101620}.result a{color:var(--accent);font-weight:700}.meta{font-size:13px;color:var(--muted);margin-top:8px;word-break:break-all}
    .config{display:flex;flex-wrap:wrap;gap:8px 20px;margin-top:16px;font-size:12px;color:var(--muted)}code{background:#0b1018;border:1px solid var(--line);padding:2px 6px;border-radius:6px;color:#cad7ee}
  </style>
</head>
<body><main>
  <h1 class="title">APK / IPA 地址自动补丁</h1>
  <p class="sub">上传原始客户端，服务器会在隔离临时目录中修改 API 与资源地址，并生成下载文件。</p>
  <section class="card">
    <label id="drop" class="drop">
      <div class="icon">⇪</div>
      <div id="fileName">点击选择或拖入 .apk / .ipa</div>
      <div class="hint">仅接受 5.1.0 IL2CPP v31 客户端 · 最大 $max_upload_mb MiB</div>
      <input id="file" type="file" accept=".apk,.ipa" hidden>
    </label>
    <button id="submit" class="button" disabled>开始自动 Patch</button>
    <div id="bar" class="bar"><i></i></div>
    <div id="status" class="status"></div>
    <div id="result" class="result"></div>
  </section>
  <div class="config">
    <span>API：<code>$api_origin</code></span>
    <span>资源：<code>$asset_origin</code></span>
  </div>
</main>
<script>
  const input=document.getElementById('file'),drop=document.getElementById('drop'),submit=document.getElementById('submit');
  const fileName=document.getElementById('fileName'),status=document.getElementById('status'),result=document.getElementById('result');
  const bar=document.getElementById('bar'),progress=bar.querySelector('i');let chosen=null,busy=false;
  const maxBytes=$max_upload_mb*1024*1024;
  function choose(file){if(!file)return;const n=file.name.toLowerCase();if(!n.endsWith('.apk')&&!n.endsWith('.ipa')){chosen=null;submit.disabled=true;setStatus('只支持 .apk 或 .ipa 文件','bad');return}if(file.size>maxBytes){chosen=null;submit.disabled=true;setStatus('文件超过服务器上限 '+formatBytes(maxBytes)+'，请换用更小的原始客户端。','bad');return}chosen=file;fileName.textContent=file.name+'  ('+formatBytes(file.size)+')';submit.disabled=false;result.style.display='none';setStatus('已选择文件，点击开始。','')}
  function formatBytes(n){if(n<1024)return n+' B';if(n<1048576)return (n/1024).toFixed(1)+' KiB';if(n<1073741824)return (n/1048576).toFixed(1)+' MiB';return (n/1073741824).toFixed(2)+' GiB'}
  function setStatus(text,cls){status.textContent=text;status.className='status '+(cls||'')}
  drop.onclick=()=>{if(!busy)input.click()};
  input.onchange=()=>choose(input.files[0]);
  ['dragenter','dragover'].forEach(e=>drop.addEventListener(e,ev=>{ev.preventDefault();drop.classList.add('drag')}));
  ['dragleave','drop'].forEach(e=>drop.addEventListener(e,ev=>{ev.preventDefault();drop.classList.remove('drag')}));
  drop.addEventListener('drop',ev=>{if(!busy)choose(ev.dataTransfer.files[0])});
  submit.onclick=()=>{if(!chosen||busy)return;busy=true;submit.disabled=true;bar.style.display='block';progress.style.width='0%';setStatus('正在上传…','');result.style.display='none';
    const xhr=new XMLHttpRequest();xhr.open('POST','/api/patch?name='+encodeURIComponent(chosen.name));xhr.setRequestHeader('Content-Type','application/octet-stream');
    xhr.upload.onprogress=e=>{if(e.lengthComputable){const p=Math.round(e.loaded*100/e.total);progress.style.width=p+'%';setStatus('上传中 '+p+'%','')}};
    xhr.onload=()=>{busy=false;submit.disabled=false;bar.style.display='none';let data={};try{data=JSON.parse(xhr.responseText||'{}')}catch(e){}
      if(xhr.status<200||xhr.status>=300){setStatus(data.error||('处理失败：HTTP '+xhr.status),'bad');return}
      setStatus('Patch 完成，可以下载。','ok');showResult(data)};
    xhr.onerror=()=>{busy=false;submit.disabled=false;bar.style.display='none';setStatus('网络错误或上传中断。','bad')};
    xhr.send(chosen)};
  function showResult(data){result.innerHTML='';const link=document.createElement('a');link.href=data.download_url;link.textContent='下载 '+data.filename;result.appendChild(link);
    if(data.warning){const w=document.createElement('div');w.className='meta warn';w.textContent=data.warning;result.appendChild(w)}
    const meta=document.createElement('div');meta.className='meta';meta.textContent='类型：'+data.kind.toUpperCase()+' · 已签名：'+(data.signed?'是':'否')+' · SHA-256：'+data.sha256;result.appendChild(meta);
    const report=document.createElement('a');report.href=data.report_url;report.textContent='查看 patch 报告';report.style.marginLeft='14px';result.appendChild(report);result.style.display='block'}
</script></body></html>''')


@dataclass
class Job:
    token: str
    directory: Path


class Jobs:
    def __init__(self, root: Path, ttl_seconds: int):
        self.root = root
        self.ttl_seconds = ttl_seconds
        self.root.mkdir(parents=True, exist_ok=True)

    def cleanup(self) -> None:
        cutoff = time.time() - self.ttl_seconds
        for child in self.root.iterdir():
            try:
                if child.is_dir() and child.stat().st_mtime < cutoff:
                    shutil.rmtree(child, ignore_errors=True)
            except OSError:
                continue

    def create(self) -> Job:
        self.cleanup()
        token = secrets.token_hex(16)
        directory = self.root / token
        directory.mkdir(mode=0o700)
        return Job(token=token, directory=directory)

    def get(self, token: str) -> Job | None:
        if not TOKEN_RE.fullmatch(token or ''):
            return None
        directory = self.root / token
        if not directory.is_dir():
            return None
        try:
            expired = time.time() - directory.stat().st_mtime > self.ttl_seconds
        except OSError:
            return None
        if expired:
            shutil.rmtree(directory, ignore_errors=True)
            return None
        return Job(token=token, directory=directory)


@dataclass
class App:
    data_dir: Path
    jobs: Jobs
    api_origin: str
    asset_origin: str
    max_upload: int
    semaphore: threading.BoundedSemaphore


def safe_filename(raw: str) -> str:
    name = Path(unquote(raw or '').replace('\\', '/')).name.strip().replace('\x00', '')
    if name.lower().endswith('.apk') or name.lower().endswith('.ipa'):
        stem = name.rsplit('.', 1)[0]
        stem = re.sub(r'[^\w.\-()\u4e00-\u9fff]+', '_', stem, flags=re.UNICODE).strip('._')
        stem = stem[:100] or 'client'
        return stem + name[name.rfind('.'):].lower()
    raise PatchError('只支持 .apk 或 .ipa 文件')


def _json_bytes(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')


def handler_factory(app: App):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def setup(self):
            super().setup()
            self.connection.settimeout(300)

        def log_message(self, fmt, *args):
            logging.info('%s - %s', self.address_string(), fmt % args)

        def end_headers(self):
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('X-Frame-Options', 'DENY')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.send_header(
                'Content-Security-Policy',
                "default-src 'none'; style-src 'unsafe-inline'; "
                "script-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:")
            super().end_headers()

        def send_json(self, status: int, payload: object, *, close: bool = False):
            body = _json_bytes(payload)
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            if close:
                self.send_header('Connection', 'close')
                self.close_connection = True
            self.end_headers()
            self.wfile.write(body)

        def send_file(self, path: Path, filename: str):
            if not path.is_file():
                self.send_json(HTTPStatus.NOT_FOUND, {'error': '文件不存在或已过期'})
                return
            size = path.stat().st_size
            ascii_name = filename.encode('ascii', 'replace').decode('ascii').replace('"', '_')
            if '?' in ascii_name:
                ascii_name = 'download' + Path(filename).suffix
            self.send_response(HTTPStatus.OK)
            self.send_header('Content-Type', 'application/octet-stream')
            self.send_header('Content-Length', str(size))
            self.send_header('Content-Disposition',
                             "attachment; filename=\"%s\"; filename*=UTF-8''%s"
                             % (ascii_name, quote(filename)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            with path.open('rb') as stream:
                shutil.copyfileobj(stream, self.wfile, 1024 * 1024)

        def do_GET(self):
            path = urlsplit(self.path).path
            if path == '/':
                page = HTML.substitute(
                    api_origin=html.escape(app.api_origin),
                    asset_origin=html.escape(app.asset_origin),
                    max_upload_mb=app.max_upload // (1024 * 1024))
                body = page.encode('utf-8')
                self.send_response(HTTPStatus.OK)
                self.send_header('Content-Type', 'text/html; charset=utf-8')
                self.send_header('Content-Length', str(len(body)))
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                self.wfile.write(body)
                return
            if path == '/health':
                self.send_json(HTTPStatus.OK, {
                    'status': 'ok',
                    'api_origin': app.api_origin,
                    'asset_origin': app.asset_origin,
                    'max_upload_bytes': app.max_upload,
                })
                return
            if path.startswith('/download/'):
                job = app.jobs.get(path.removeprefix('/download/'))
                meta = self._read_meta(job)
                if meta is None:
                    return
                self.send_file(job.directory / meta['filename'], meta['filename'])
                return
            if path.startswith('/report/'):
                job = app.jobs.get(path.removeprefix('/report/'))
                if job is None:
                    self.send_json(HTTPStatus.NOT_FOUND, {'error': '任务不存在或已过期'})
                    return
                self.send_file(job.directory / 'patch-report.json', 'patch-report.json')
                return
            if path == '/favicon.ico':
                self.send_response(HTTPStatus.NO_CONTENT)
                self.send_header('Content-Length', '0')
                self.end_headers()
                return
            self.send_json(HTTPStatus.NOT_FOUND, {'error': 'Not found'})

        def do_POST(self):
            parsed = urlsplit(self.path)
            if parsed.path != '/api/patch':
                self.send_json(HTTPStatus.NOT_FOUND, {'error': 'Not found'}, close=True)
                return
            if self.headers.get('Transfer-Encoding'):
                self.send_json(HTTPStatus.LENGTH_REQUIRED,
                               {'error': '只支持带 Content-Length 的上传'}, close=True)
                return
            length_header = self.headers.get('Content-Length')
            if length_header is None:
                self.send_json(HTTPStatus.LENGTH_REQUIRED, {'error': '缺少 Content-Length'},
                               close=True)
                return
            try:
                length = int(length_header)
            except ValueError:
                self.send_json(HTTPStatus.BAD_REQUEST, {'error': 'Content-Length 无效'},
                               close=True)
                return
            if length <= 0:
                self.send_json(HTTPStatus.BAD_REQUEST, {'error': '上传文件为空'},
                               close=True)
                return
            if length > app.max_upload:
                self.send_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                               {'error': '文件超过大小限制'}, close=True)
                return
            if not app.semaphore.acquire(timeout=5):
                self.send_json(HTTPStatus.TOO_MANY_REQUESTS,
                               {'error': '服务器正忙，请稍后重试'}, close=True)
                return
            job = None
            try:
                name = safe_filename(parse_qs(parsed.query).get('name', [''])[0])
                suffix = Path(name).suffix.lower()
                job = app.jobs.create()
                upload = job.directory / name
                remaining = length
                with upload.open('wb') as stream:
                    while remaining:
                        chunk = self.rfile.read(min(1024 * 1024, remaining))
                        if not chunk:
                            raise PatchError('上传中断')
                        stream.write(chunk)
                        remaining -= len(chunk)
                result = patch_client(upload, job.directory, kind='apk' if suffix == '.apk' else 'ipa',
                                      api_origin=app.api_origin, asset_origin=app.asset_origin,
                                      data_dir=app.data_dir)
                upload.unlink(missing_ok=True)
                meta = {
                    'filename': result.output.name,
                    'kind': result.report['kind'],
                    'signed': result.report.get('signed', False),
                    'warning': result.report.get('warning'),
                    'sha256': result.report['output_sha256'],
                }
                (job.directory / 'meta.json').write_text(
                    json.dumps(meta, ensure_ascii=False), encoding='utf-8')
                self.send_json(HTTPStatus.OK, {
                    'filename': result.output.name,
                    'download_url': '/download/' + job.token,
                    'report_url': '/report/' + job.token,
                    **meta,
                })
            except PatchError as error:
                self._discard(job)
                self.send_json(HTTPStatus.BAD_REQUEST, {'error': str(error)},
                               close=True)
            except Exception:
                logging.exception('patch job failed')
                self._discard(job)
                self.send_json(HTTPStatus.INTERNAL_SERVER_ERROR,
                               {'error': '处理失败，请查看服务端日志'})
            finally:
                app.semaphore.release()

        def _discard(self, job: Job | None) -> None:
            if job is not None:
                shutil.rmtree(job.directory, ignore_errors=True)

        def _read_meta(self, job: Job | None) -> dict | None:
            if job is None:
                self.send_json(HTTPStatus.NOT_FOUND, {'error': '任务不存在或已过期'})
                return None
            try:
                return json.loads((job.directory / 'meta.json').read_text(encoding='utf-8'))
            except (OSError, ValueError):
                self.send_json(HTTPStatus.NOT_FOUND, {'error': '结果不存在或已过期'})
                return None

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default=os.environ.get('PATCH_HOST', '127.0.0.1'))
    parser.add_argument('--port', type=int, default=int(os.environ.get('PATCH_PORT', '8090')))
    parser.add_argument('--data-dir', type=Path,
                        default=Path(os.environ.get('PATCH_DATA_DIR', DEFAULT_DATA_DIR)))
    parser.add_argument('--max-upload-mb', type=int,
                        default=int(os.environ.get(
                            'PATCH_MAX_UPLOAD_MB', DEFAULT_MAX_UPLOAD // (1024 * 1024))))
    parser.add_argument('--jobs', type=int, default=int(os.environ.get('PATCH_CONCURRENCY', '2')))
    parser.add_argument('--ttl-seconds', type=int,
                        default=int(os.environ.get('PATCH_JOB_TTL_SECONDS', DEFAULT_TTL_SECONDS)))
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO,
                        format='%(asctime)s %(levelname)s %(message)s')
    data_dir = args.data_dir.resolve()
    data_dir.mkdir(parents=True, exist_ok=True)
    app = App(
        data_dir=data_dir,
        jobs=Jobs(data_dir / 'jobs', args.ttl_seconds),
        api_origin=normalize_origin(default_api_origin(), label='PATCH_API_ORIGIN'),
        asset_origin=normalize_origin(default_asset_origin(), label='PATCH_ASSET_ORIGIN'),
        max_upload=max(1, args.max_upload_mb) * 1024 * 1024,
        semaphore=threading.BoundedSemaphore(max(1, args.jobs)),
    )
    server = ThreadingHTTPServer((args.host, args.port), handler_factory(app))
    server.daemon_threads = True
    stop_cleanup = threading.Event()

    def cleanup_loop():
        while not stop_cleanup.wait(60):
            try:
                app.jobs.cleanup()
            except Exception:
                logging.exception('client patch cleanup failed')

    cleanup_thread = threading.Thread(target=cleanup_loop, name='job-cleanup', daemon=True)
    cleanup_thread.start()
    logging.info('client patch web listening on http://%s:%d', args.host, args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop_cleanup.set()
        server.server_close()
        cleanup_thread.join(timeout=5)


if __name__ == '__main__':
    main()
