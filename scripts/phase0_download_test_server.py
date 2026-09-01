#!/usr/bin/env python3
"""Deterministic HTTP fixture for Nuvio download lifecycle tests."""

from __future__ import annotations

import argparse
import json
import socket
import sys
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlsplit


DEFAULT_SIZE = 4 * 1024 * 1024
MAX_SIZE = 64 * 1024 * 1024
DEFAULT_CHUNK_SIZE = 64 * 1024
TEST_HEADER_NAME = "X-Nuvio-Test"
TEST_HEADER_VALUE = "phase0"
TEST_SIGNED_TOKEN = "phase0"
BYTE_PATTERN = bytes(range(256))


def deterministic_bytes(offset: int, length: int) -> bytes:
    """Return a stable byte sequence without keeping a large media file on disk."""
    if length <= 0:
        return b""
    pattern_offset = offset % len(BYTE_PATTERN)
    rotated = BYTE_PATTERN[pattern_offset:] + BYTE_PATTERN[:pattern_offset]
    repetitions = (length + len(rotated) - 1) // len(rotated)
    return (rotated * repetitions)[:length]


def bounded_int(values: dict[str, list[str]], key: str, default: int, maximum: int) -> int:
    try:
        value = int(values.get(key, [str(default)])[0])
    except ValueError:
        return default
    return max(0, min(value, maximum))


def parse_range_header(header_value: str | None, size: int) -> tuple[int, int] | None:
    if not header_value or not header_value.startswith("bytes="):
        return None
    value = header_value.removeprefix("bytes=").strip()
    if "," in value or "-" not in value:
        raise ValueError("unsupported range")
    start_text, end_text = value.split("-", 1)
    if not start_text:
        raise ValueError("suffix ranges are not supported")
    start = int(start_text)
    end = int(end_text) if end_text else size - 1
    if start < 0 or start >= size or end < start:
        raise ValueError("range is outside the payload")
    return start, min(end, size - 1)


class DownloadFixtureServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        server_address: tuple[str, int],
        payload_size: int,
        max_payload_size: int,
    ):
        super().__init__(server_address, DownloadFixtureHandler)
        self.payload_size = payload_size
        self.max_payload_size = max_payload_size

    def handle_error(self, request: object, client_address: object) -> None:
        error = sys.exc_info()[1]
        if isinstance(error, (BrokenPipeError, ConnectionResetError)):
            return
        super().handle_error(request, client_address)


class DownloadFixtureHandler(BaseHTTPRequestHandler):
    server: DownloadFixtureServer
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:
        parsed = urlsplit(self.path)
        query = parse_qs(parsed.query, keep_blank_values=True)
        size = bounded_int(
            query,
            "size",
            self.server.payload_size,
            self.server.max_payload_size,
        )

        if parsed.path == "/health":
            self._send_json({"status": "ok", "defaultSize": self.server.payload_size})
        elif parsed.path == "/redirect":
            self.send_response(HTTPStatus.FOUND)
            self.send_header("Location", f"/file?{urlencode({'size': size})}")
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif parsed.path == "/file":
            self._send_payload(size=size, honor_range=True)
        elif parsed.path == "/ignore-range":
            self._send_payload(size=size, honor_range=False)
        elif parsed.path == "/range-416":
            if self.headers.get("Range"):
                self._send_range_not_satisfiable(size)
            else:
                self._send_payload(size=size, honor_range=False)
        elif parsed.path == "/slow":
            delay_ms = bounded_int(query, "delay_ms", 40, 5_000)
            chunk_size = bounded_int(query, "chunk_size", DEFAULT_CHUNK_SIZE, 1024 * 1024)
            self._send_payload(
                size=size,
                honor_range=True,
                delay_seconds=delay_ms / 1000.0,
                chunk_size=max(1, chunk_size),
            )
        elif parsed.path == "/disconnect":
            disconnect_after = bounded_int(
                query,
                "after",
                min(DEFAULT_CHUNK_SIZE, size),
                size,
            )
            self._send_disconnected_payload(size=size, disconnect_after=disconnect_after)
        elif parsed.path == "/headers":
            if self.headers.get(TEST_HEADER_NAME) != TEST_HEADER_VALUE:
                self._send_error_without_details(HTTPStatus.FORBIDDEN)
            else:
                self._send_payload(size=size, honor_range=True)
        elif parsed.path == "/signed":
            token = query.get("token", [""])[0]
            expires = bounded_int(query, "expires", 0, 4_102_444_800)
            if token != TEST_SIGNED_TOKEN or expires <= int(time.time()):
                self._send_error_without_details(HTTPStatus.FORBIDDEN)
            else:
                self._send_payload(size=size, honor_range=True)
        else:
            self._send_error_without_details(HTTPStatus.NOT_FOUND)

    def log_message(self, format_string: str, *args: object) -> None:
        path = urlsplit(self.path).path
        print(f"fixture method={self.command} path={path}", flush=True)

    def _send_json(self, value: dict[str, object]) -> None:
        payload = json.dumps(value, sort_keys=True).encode("utf-8")
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _send_payload(
        self,
        size: int,
        honor_range: bool,
        delay_seconds: float = 0.0,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> None:
        start = 0
        end = size - 1
        status = HTTPStatus.OK

        if honor_range:
            try:
                requested_range = parse_range_header(self.headers.get("Range"), size)
            except (TypeError, ValueError):
                self._send_range_not_satisfiable(size)
                return
            if requested_range is not None:
                start, end = requested_range
                status = HTTPStatus.PARTIAL_CONTENT

        length = max(0, end - start + 1)
        self.send_response(status)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(length))
        if status == HTTPStatus.PARTIAL_CONTENT:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()

        offset = start
        remaining = length
        try:
            while remaining > 0:
                write_size = min(chunk_size, remaining)
                self.wfile.write(deterministic_bytes(offset, write_size))
                self.wfile.flush()
                offset += write_size
                remaining -= write_size
                if delay_seconds > 0 and remaining > 0:
                    time.sleep(delay_seconds)
        except (BrokenPipeError, ConnectionResetError):
            return

    def _send_disconnected_payload(self, size: int, disconnect_after: int) -> None:
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(size))
        self.end_headers()
        offset = 0
        while offset < disconnect_after:
            write_size = min(16 * 1024, disconnect_after - offset)
            self.wfile.write(deterministic_bytes(offset, write_size))
            self.wfile.flush()
            offset += write_size
            time.sleep(0.02)
        if disconnect_after > 0:
            time.sleep(0.1)
        self.close_connection = True
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.connection.close()

    def _send_range_not_satisfiable(self, size: int) -> None:
        self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
        self.send_header("Content-Range", f"bytes */{size}")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _send_error_without_details(self, status: HTTPStatus) -> None:
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()


def create_server(
    host: str,
    port: int,
    payload_size: int = DEFAULT_SIZE,
    max_payload_size: int = MAX_SIZE,
) -> DownloadFixtureServer:
    normalized_max_size = max(1, max_payload_size)
    normalized_payload_size = max(1, min(payload_size, normalized_max_size))
    return DownloadFixtureServer(
        (host, port),
        payload_size=normalized_payload_size,
        max_payload_size=normalized_max_size,
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--size", type=int, default=DEFAULT_SIZE)
    parser.add_argument(
        "--max-size",
        type=int,
        default=MAX_SIZE,
        help="Maximum request size; raise explicitly for long-running smoke tests.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    server = create_server(args.host, args.port, args.size, args.max_size)
    host, port = server.server_address
    print(
        "Nuvio download fixture listening "
        f"base_url=http://{host}:{port} "
        f"default_size={server.payload_size} "
        f"max_size={server.max_payload_size}",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
