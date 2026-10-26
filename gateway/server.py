"""HTTP and Server-Sent Events (SSE) server for The Chain emergency mesh gateway.

Serves:
  1. REST API under /api/*
  2. Persistent SSE event stream at /api/events
  3. Production SPA frontend from frontend/dist (with SPA fallback to index.html)
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import os
import queue
import re
import socket
import sys
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Optional, Tuple
from urllib.parse import parse_qs, urlparse

from .state import GatewayState

# Mime types
mimetypes.init()
mimetypes.add_type("application/javascript", ".js")
mimetypes.add_type("text/javascript", ".js")
mimetypes.add_type("text/css", ".css")
mimetypes.add_type("application/json", ".json")
mimetypes.add_type("image/svg+xml", ".svg")
mimetypes.add_type("application/manifest+json", ".webmanifest")


def get_lan_ip() -> str:
    """Best-effort detection of local LAN IP address."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # Does not actually connect
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = "127.0.0.1"
    finally:
        s.close()
    return ip


class GatewayRequestHandler(BaseHTTPRequestHandler):
    state: GatewayState
    dist_dir: str

    def log_message(self, format: str, *args: Any):
        # Concise logging, suppress noisy asset polling if needed
        msg = format % args
        if "/api/events" in msg and "200" in msg:
            return
        sys.stderr.write(f"[{self.log_date_time_string()}] {msg}\n")

    def _send_json(self, status: int, data: Any):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.end_headers()
        self.wfile.write(body)

    def _send_error(self, status: int, code: str, message: str):
        self._send_json(status, {"code": code, "message": message})

    def _read_json(self, max_bytes: int = 65536) -> Optional[dict]:
        try:
            length_header = self.headers.get("Content-Length")
            if not length_header:
                return {}
            content_length = int(length_header)
            if content_length > max_bytes:
                self._send_error(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "PAYLOAD_TOO_LARGE", f"Max payload {max_bytes} bytes")
                return None
            body = self.rfile.read(content_length)
            if not body:
                return {}
            return json.loads(body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as e:
            self._send_error(HTTPStatus.BAD_REQUEST, "INVALID_JSON", f"Malformed JSON: {e}")
            return None

    def do_OPTIONS(self):
        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.end_headers()

    def do_GET(self):
        try:
            url = urlparse(self.path)
            path = url.path.rstrip("/")
            if not path:
                path = "/"

            if path.startswith("/api/"):
                self._handle_api_get(path, parse_qs(url.query))
            else:
                self._serve_static(url.path)
        except Exception as e:
            import traceback
            traceback.print_exc()
            self._send_error(HTTPStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR", str(e))

    def _handle_api_get(self, path: str, query: dict):
        if path == "/api/requests":
            data = self.state.get_requests()
            self._send_json(HTTPStatus.OK, data)
        elif path == "/api/mesh":
            data = self.state.get_mesh()
            self._send_json(HTTPStatus.OK, data)
        elif path == "/api/map":
            data = self.state.get_map()
            self._send_json(HTTPStatus.OK, data)
        elif path == "/api/me":
            data = self.state.get_profile()
            self._send_json(HTTPStatus.OK, data)
        elif path == "/api/events":
            self._handle_sse()
        else:
            self._send_error(HTTPStatus.NOT_FOUND, "NOT_FOUND", f"Endpoint {path} not found")

    def _handle_sse(self):
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache, no-transform")
        self.send_header("Connection", "keep-alive")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()

        q = self.state.subscribe_sse()
        try:
            # Initial greeting event
            init_msg = f"event: connected\ndata: {json.dumps({'time': self.state.now, 'node': self.state.node_me.id})}\n\n"
            self.wfile.write(init_msg.encode("utf-8"))
            self.wfile.flush()

            while self.state._running:
                try:
                    payload = q.get(timeout=10.0)
                    self.wfile.write(payload.encode("utf-8"))
                    self.wfile.flush()
                except queue.Empty:
                    # Keepalive ping
                    self.wfile.write(b": keepalive\n\n")
                    self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            self.state.unsubscribe_sse(q)

    # ---- POST Router -------------------------------------------------------------------------
    def do_POST(self):
        try:
            url = urlparse(self.path)
            path = url.path.rstrip("/")

            if not path.startswith("/api/"):
                self._send_error(HTTPStatus.NOT_FOUND, "NOT_FOUND", "POST supported only on /api endpoints")
                return

            body = self._read_json()
            if body is None:
                return  # Error already sent

            # POST /api/claims
            if path == "/api/claims":
                text = body.get("text")
                if not isinstance(text, str) or not text.strip() or len(text) > 1000:
                    self._send_error(HTTPStatus.BAD_REQUEST, "INVALID_TEXT", "Field 'text' must be a non-empty string <= 1000 chars")
                    return

                lang = body.get("lang", "en")
                if lang not in ("en", "hi", "mr", "EN", "HI", "MR"):
                    self._send_error(HTTPStatus.BAD_REQUEST, "INVALID_LANG", "Field 'lang' must be 'en', 'hi', or 'mr'")
                    return
                lang = lang.lower()

                pos = body.get("pos")
                if pos is not None:
                    if not (isinstance(pos, (list, tuple)) and len(pos) == 2 and all(isinstance(v, (int, float)) for v in pos)):
                        self._send_error(HTTPStatus.BAD_REQUEST, "INVALID_POS", "Field 'pos' must be [x, y] coordinates")
                        return

                res = self.state.analyze_claim(text, lang=lang, pos=pos)
                self._send_json(HTTPStatus.OK, res)

            # POST /api/claims/{id}/broadcast
            elif re.match(r"^/api/claims/[^/]+/broadcast$", path):
                claim_id = path.split("/")[3]
                res = self.state.broadcast_claim(claim_id)
                self._send_json(HTTPStatus.OK, res)

            # POST /api/requests/{id}/respond
            elif re.match(r"^/api/requests/[^/]+/respond$", path):
                claim_id = path.split("/")[3]
                action = body.get("action")
                if action not in ("accept", "decline", "refer"):
                    self._send_error(HTTPStatus.BAD_REQUEST, "INVALID_ACTION", "Action must be 'accept', 'decline', or 'refer'")
                    return
                reason = body.get("reason")
                referral = body.get("referral")
                res = self.state.respond_request(claim_id, action, reason=reason, referral=referral)
                self._send_json(HTTPStatus.OK, res)

            # POST /api/sim/partition
            elif path == "/api/sim/partition":
                start_x = body.get("start_x", 300.0)
                res = self.state.sim_partition(start_x=float(start_x))
                self._send_json(HTTPStatus.OK, res)

            # POST /api/sim/heal
            elif path == "/api/sim/heal":
                res = self.state.sim_heal()
                self._send_json(HTTPStatus.OK, res)

            # POST /api/sim/attack
            elif path == "/api/sim/attack":
                attack_type = body.get("type", "blackhole")
                if attack_type not in ("blackhole", "greyhole", "replay", "eclipse"):
                    self._send_error(HTTPStatus.BAD_REQUEST, "INVALID_ATTACK", "Attack must be blackhole, greyhole, replay, or eclipse")
                    return
                res = self.state.sim_attack(attack_type)
                self._send_json(HTTPStatus.OK, res)

            # POST /api/sim/kill_node
            elif path == "/api/sim/kill_node":
                node_id = body.get("node_id")
                if not isinstance(node_id, str):
                    self._send_error(HTTPStatus.BAD_REQUEST, "INVALID_NODE", "Field 'node_id' required")
                    return
                res = self.state.sim_kill_node(node_id)
                self._send_json(HTTPStatus.OK, res)

            else:
                self._send_error(HTTPStatus.NOT_FOUND, "NOT_FOUND", f"Endpoint {path} not found")
        except Exception as e:
            import traceback
            traceback.print_exc()
            self._send_error(HTTPStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR", str(e))

    # ---- DELETE Router -----------------------------------------------------------------------
    def do_DELETE(self):
        try:
            url = urlparse(self.path)
            path = url.path.rstrip("/")

            # DELETE /api/claims/{id}
            if re.match(r"^/api/claims/[^/]+$", path):
                claim_id = path.split("/")[3]
                res = self.state.abort_claim(claim_id)
                self._send_json(HTTPStatus.OK, res)
            else:
                self._send_error(HTTPStatus.NOT_FOUND, "NOT_FOUND", f"Endpoint {path} not found")
        except Exception as e:
            import traceback
            traceback.print_exc()
            self._send_error(HTTPStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR", str(e))

    # ---- PUT Router --------------------------------------------------------------------------
    def do_PUT(self):
        try:
            url = urlparse(self.path)
            path = url.path.rstrip("/")

            # PUT /api/me
            if path == "/api/me":
                body = self._read_json()
                if body is None:
                    return
                res = self.state.update_profile(body)
                self._send_json(HTTPStatus.OK, res)
            else:
                self._send_error(HTTPStatus.NOT_FOUND, "NOT_FOUND", f"Endpoint {path} not found")
        except Exception as e:
            import traceback
            traceback.print_exc()
            self._send_error(HTTPStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR", str(e))

    # ---- Static File Serving & SPA Fallback --------------------------------------------------
    def _serve_static(self, req_path: str):
        if not os.path.exists(self.dist_dir):
            self._send_error(HTTPStatus.NOT_FOUND, "DIST_NOT_FOUND",
                             f"Frontend distribution directory '{self.dist_dir}' does not exist. Run 'npm run build' first.")
            return

        clean_path = req_path.lstrip("/")
        if not clean_path or clean_path == "/":
            clean_path = "index.html"

        target_file = os.path.normpath(os.path.join(self.dist_dir, clean_path))
        # Directory traversal prevention
        if not target_file.startswith(os.path.abspath(self.dist_dir)):
            self._send_error(HTTPStatus.FORBIDDEN, "FORBIDDEN", "Path traversal forbidden")
            return

        # SPA fallback: if file does not exist, serve index.html
        if not os.path.isfile(target_file):
            target_file = os.path.join(self.dist_dir, "index.html")

        if not os.path.isfile(target_file):
            self._send_error(HTTPStatus.NOT_FOUND, "INDEX_NOT_FOUND", "index.html missing from dist directory")
            return

        ctype, _ = mimetypes.guess_type(target_file)
        if not ctype:
            ctype = "application/octet-stream"

        try:
            with open(target_file, "rb") as f:
                content = f.read()
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Cache-Control", "no-cache" if target_file.endswith(".html") else "public, max-age=31536000")
            self.end_headers()
            self.wfile.write(content)
        except Exception as e:
            self._send_error(HTTPStatus.INTERNAL_SERVER_ERROR, "IO_ERROR", str(e))


def run_server(host: str = "0.0.0.0", port: int = 8000, dist_dir: Optional[str] = None) -> ThreadingHTTPServer:
    """Create and start the HTTP server with GatewayState."""
    base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    dist = dist_dir or os.path.join(base_dir, "frontend", "dist")

    state = GatewayState()
    state.start()

    class Handler(GatewayRequestHandler):
        pass

    Handler.state = state
    Handler.dist_dir = dist

    server = ThreadingHTTPServer((host, port), Handler)
    lan_ip = get_lan_ip()

    banner = f"""
======================================================================
  THE CHAIN - DECENTRALIZED EMERGENCY MESH GATEWAY
======================================================================
  * Local URL:    http://localhost:{port}
  * LAN URL:      http://{lan_ip}:{port}
  * USB Debug:    adb reverse tcp:{port} tcp:{port} -> http://localhost:{port}
  * Frontend:     {dist}
  * Sim Nodes:    {', '.join(state.node_ids)}
======================================================================
"""
    print(banner)
    return server


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="The Chain Mesh Gateway")
    parser.add_argument("--host", default="0.0.0.0", help="Binding host (default 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8000, help="Binding port (default 8000)")
    parser.add_argument("--dist", default=None, help="Path to frontend dist directory")
    args = parser.parse_args()

    server = run_server(host=args.host, port=args.port, dist_dir=args.dist)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down gateway...")
        server.shutdown()
        server.server_close()
