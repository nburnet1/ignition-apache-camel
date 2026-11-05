#!/usr/bin/env python3
"""Local development adapter for external Hawtio and the disposable 9488 Gateway.

Never use this helper with a production Gateway. It holds the acceptance admin
session in memory and supplies Ignition's native CSRF token on every POST.
"""
import base64
import getpass
import hmac
import importlib.util
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import urllib.error
import urllib.parse


def acceptance_client():
    spec = importlib.util.spec_from_file_location("camel_acceptance", Path(__file__).with_name("camel-acceptance.py"))
    client = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(client)
    client.BASE = "http://localhost:9488"
    return client


class RenewableSession:
    """Reauthenticate an expired test session once, never retry delivery failures."""

    def __init__(self, client):
        self.client = client
        self.MODULE = client.MODULE

    def api(self, *args):
        try:
            return self.client.api(*args)
        except urllib.error.HTTPError as error:
            if error.code != 401:
                raise
            error.close()
        if not self.client.bootstrap():
            raise OSError("Disposable Gateway session could not be renewed")
        return self.client.api(*args)


def make_server(client, password, port=9190, console_origin="http://127.0.0.1:9390", bind_host="127.0.0.1"):
    expected = "Basic " + base64.b64encode(("hawtio:" + password).encode()).decode()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass  # Never log credentials, cookies, query strings or request bodies.

        def reply(self, status, value, challenge=False):
            body = json.dumps(value).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            if challenge:
                self.send_header("WWW-Authenticate", 'Basic realm="Camel acceptance Hawtio"')
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            self.forward(False)

        def do_POST(self):
            self.forward(True)

        def forward(self, post):
            self.connection.settimeout(15)
            # Hawtio's server-side proxy forwards the browser's Origin header.
            # Accept only the explicitly trusted local console; never enable CORS here.
            origin = self.headers.get("Origin")
            if (origin is not None and origin != console_origin) or self.headers.get("Transfer-Encoding"):
                return self.reply(403, {"error": "Use Hawtio's server-side connection proxy"})
            if not hmac.compare_digest(self.headers.get("Authorization", "").encode(), expected.encode()):
                return self.reply(401, {"error": "Authenticate as hawtio with the local proxy password"}, True)
            path = urllib.parse.urlsplit(self.path).path
            if path not in ("/jolokia", "/jolokia/", "/jolokia/version"):
                return self.reply(404, {"error": "Unknown management path"})
            try:
                if post:
                    length = int(self.headers.get("Content-Length", "0"))
                    if length < 1 or length > 65536:
                        return self.reply(413, {"error": "Use a JSON management body up to 64 KiB"})
                    if self.headers.get_content_type() != "application/json":
                        return self.reply(415, {"error": "Use application/json"})
                    body = json.loads(self.rfile.read(length))
                    result = client.api("POST", client.MODULE + "/camel-jolokia", body)
                else:
                    result = client.api("GET", client.MODULE + "/camel-jolokia/version")
                self.reply(200, result)
            except urllib.error.HTTPError as error:
                # No response headers, cookies, native credentials or raw error pages forwarded.
                with error:
                    self.reply(error.code, {"error": "Gateway rejected the management request"})
            except (ValueError, TimeoutError, OSError):
                self.reply(502, {"error": "Invalid request or acceptance Gateway unavailable"})

    server = HTTPServer((bind_host, port), Handler)
    server.timeout = 15
    return server


def serve(client, password, port=9190, console_origin="http://127.0.0.1:9390"):
    server = make_server(client, password, port, console_origin)
    try:
        print("Local development proxy: http://127.0.0.1:%s/jolokia/ (username: hawtio)" % server.server_port, flush=True)
        server.serve_forever()
    finally:
        server.server_close()


def main():
    password = os.environ.get("CAMEL_HAWTIO_PROXY_PASSWORD") or getpass.getpass("Choose a local Hawtio proxy password: ")
    if len(password) < 12:
        raise SystemExit("Use at least 12 characters for the local proxy password")
    client = acceptance_client()
    if not client.bootstrap():
        raise SystemExit("Could not log into the disposable Gateway at localhost:9488")
    result = client.api("GET", client.MODULE + "/camel-jolokia/version")
    if result.get("status") != 200:
        raise SystemExit("Enable the module's Hawtio integration on the acceptance Gateway first")
    console_origin = os.environ.get("CAMEL_HAWTIO_CONSOLE_ORIGIN", "http://127.0.0.1:9390")
    origin = urllib.parse.urlsplit(console_origin)
    if origin.scheme not in ("http", "https") or origin.hostname not in ("127.0.0.1", "localhost") \
            or origin.username or origin.password or origin.path or origin.query or origin.fragment:
        raise SystemExit("CAMEL_HAWTIO_CONSOLE_ORIGIN must be an exact local HTTP(S) origin, without a path")
    serve(client, password, console_origin=console_origin)


if __name__ == "__main__":
    main()
