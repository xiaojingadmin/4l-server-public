#!/usr/bin/env python3
"""Serve the browser-only APK/IPA patcher. Nothing is uploaded or patched here."""
from __future__ import annotations

import argparse
import logging
import os
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent


class Handler(SimpleHTTPRequestHandler):
    extensions_map = {
        **SimpleHTTPRequestHandler.extensions_map,
        '.js': 'text/javascript; charset=utf-8',
        '.mjs': 'text/javascript; charset=utf-8',
        '.json': 'application/json; charset=utf-8',
    }

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(HERE), **kwargs)

    def end_headers(self):
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('X-Frame-Options', 'DENY')
        self.send_header('Referrer-Policy', 'no-referrer')
        self.send_header('Cache-Control', 'no-store')
        self.send_header(
            'Content-Security-Policy',
            "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            "img-src 'self' data:; connect-src 'none'; base-uri 'none'; form-action 'none'")
        super().end_headers()

    def do_POST(self):
        self.close_connection = True
        self.send_error(HTTPStatus.METHOD_NOT_ALLOWED,
                        'This server only serves static files; patching runs in the browser.')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default=os.environ.get('PATCH_HOST', '127.0.0.1'))
    parser.add_argument('--port', type=int, default=int(os.environ.get('PATCH_PORT', '8090')))
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    logging.info('client patch page: http://%s:%d/ (static files only)', args.host, args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
