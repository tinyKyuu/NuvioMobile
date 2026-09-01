from __future__ import annotations

import http.client
import sys
import threading
import time
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

from phase0_download_test_server import (  # noqa: E402
    TEST_HEADER_NAME,
    TEST_HEADER_VALUE,
    TEST_SIGNED_TOKEN,
    create_server,
    deterministic_bytes,
)


class DownloadFixtureServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.server = create_server(
            "127.0.0.1",
            0,
            payload_size=4096,
            max_payload_size=128 * 1024,
        )
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        host, port = cls.server.server_address
        cls.host = host
        cls.port = port
        cls.base_url = f"http://{host}:{port}"

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def test_full_payload_is_deterministic(self) -> None:
        with urlopen(f"{self.base_url}/file", timeout=2) as response:
            body = response.read()

        self.assertEqual(200, response.status)
        self.assertEqual(deterministic_bytes(0, 4096), body)

    def test_explicit_max_size_allows_a_larger_opt_in_payload(self) -> None:
        requested_size = 96 * 1024
        with urlopen(f"{self.base_url}/file?size={requested_size}", timeout=2) as response:
            body = response.read()

        self.assertEqual(200, response.status)
        self.assertEqual(requested_size, len(body))
        self.assertEqual(deterministic_bytes(0, requested_size), body)

    def test_range_response_has_expected_slice_and_headers(self) -> None:
        request = Request(
            f"{self.base_url}/file",
            headers={"Range": "bytes=100-199"},
        )

        with urlopen(request, timeout=2) as response:
            body = response.read()

        self.assertEqual(206, response.status)
        self.assertEqual("bytes 100-199/4096", response.headers["Content-Range"])
        self.assertEqual(deterministic_bytes(100, 100), body)

    def test_ignore_range_returns_the_full_payload(self) -> None:
        request = Request(
            f"{self.base_url}/ignore-range",
            headers={"Range": "bytes=100-"},
        )

        with urlopen(request, timeout=2) as response:
            body = response.read()

        self.assertEqual(200, response.status)
        self.assertEqual(4096, len(body))

    def test_range_416_endpoint_accepts_a_fresh_retry(self) -> None:
        ranged_request = Request(
            f"{self.base_url}/range-416",
            headers={"Range": "bytes=100-"},
        )
        with self.assertRaises(HTTPError) as raised:
            urlopen(ranged_request, timeout=2)
        self.assertEqual(416, raised.exception.code)

        with urlopen(f"{self.base_url}/range-416", timeout=2) as response:
            body = response.read()
        self.assertEqual(200, response.status)
        self.assertEqual(4096, len(body))

    def test_redirect_and_required_header_paths(self) -> None:
        with urlopen(f"{self.base_url}/redirect?size=512", timeout=2) as response:
            redirected_body = response.read()
        self.assertEqual(512, len(redirected_body))

        with self.assertRaises(HTTPError) as raised:
            urlopen(f"{self.base_url}/headers", timeout=2)
        self.assertEqual(403, raised.exception.code)

        request = Request(
            f"{self.base_url}/headers",
            headers={TEST_HEADER_NAME: TEST_HEADER_VALUE},
        )
        with urlopen(request, timeout=2) as response:
            header_body = response.read()
        self.assertEqual(4096, len(header_body))

    def test_signed_path_rejects_expired_and_accepts_future_expiry(self) -> None:
        now = int(time.time())
        expired = f"{self.base_url}/signed?token={TEST_SIGNED_TOKEN}&expires={now - 1}"
        with self.assertRaises(HTTPError) as raised:
            urlopen(expired, timeout=2)
        self.assertEqual(403, raised.exception.code)

        valid = f"{self.base_url}/signed?token={TEST_SIGNED_TOKEN}&expires={now + 60}"
        with urlopen(valid, timeout=2) as response:
            body = response.read()
        self.assertEqual(4096, len(body))

    def test_disconnect_closes_before_content_length(self) -> None:
        connection = http.client.HTTPConnection(self.host, self.port, timeout=2)
        connection.request("GET", "/disconnect?size=4096&after=512")
        response = connection.getresponse()

        with self.assertRaises(http.client.IncompleteRead) as raised:
            response.read()

        self.assertEqual(512, len(raised.exception.partial))
        connection.close()


if __name__ == "__main__":
    unittest.main()
