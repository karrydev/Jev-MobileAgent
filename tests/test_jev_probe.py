from __future__ import annotations

import io
import json
import os
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from services.jev_probe.cli import main
from services.jev_probe.probe import DEFAULT_ENDPOINT, run_probe


class _Response:
    def __init__(self, status: int, body: bytes):
        self.status = status
        self._body = body

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return self._body


def _valid_response(choice: str = "click_continue", *, usage: dict | None = None) -> bytes:
    return json.dumps(
        {
            "model": "jev-1.13.0",
            "answers": {
                "next_action": {
                    "type": "choice",
                    "choice": choice,
                    "probabilities": {"click_continue": 0.9, "click_cancel": 0.1},
                    "confidence": 0.9,
                }
            },
            "usage": usage if usage is not None else {"input_tokens": 31, "output_tokens": 0},
        }
    ).encode("utf-8")


class JevProbeTests(unittest.TestCase):
    def test_default_is_dry_run_and_plans_only_one_choice_request(self):
        fixture_key = "do-not-read"
        with patch.dict(os.environ, {"JEV_API_KEY": fixture_key}, clear=False):
            with patch("services.jev_probe.probe.urlopen") as opener:
                report = run_probe()
        opener.assert_not_called()
        self.assertEqual(report["mode"], "dry-run")
        self.assertEqual(report["network"]["calls"], 0)
        self.assertEqual([item["kind"] for item in report["requests"]], ["choice"])
        self.assertFalse(report["requests"][0]["sent"])
        self.assertNotIn(fixture_key, json.dumps(report, ensure_ascii=False))

    def test_live_protocol_and_explicit_error_probe(self):
        calls = []

        def opener(request, timeout):
            calls.append((request, timeout, json.loads(request.data.decode("utf-8"))))
            if len(calls) == 1:
                return _Response(200, _valid_response())
            raise HTTPError(
                request.full_url,
                400,
                "invalid request",
                {},
                io.BytesIO(b'{"error":{"code":"invalid_request"}}'),
            )

        with patch.dict(os.environ, {"JEV_API_KEY": "test-key"}, clear=False):
            report = run_probe(live=True, error_probe=True, opener=opener)
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0][2]["model"], "jev-1.13.0")
        self.assertEqual(calls[0][2]["questions"]["next_action"]["type"], "choice")
        self.assertEqual(calls[1][2]["questions"]["invalid_probe"]["type"], "unsupported_probe_type")
        self.assertEqual(report["run"]["status"], "ok")
        self.assertEqual(report["selection"]["semantic_status"], "expected")
        self.assertEqual(report["requests"][0]["usage"]["status"], "known")
        self.assertEqual(report["error_probe"]["status"], "invalid_request_rejected")
        self.assertFalse(report["integrity"]["raw_response_recorded"])

    def test_http_401_and_429_are_reported_once_without_retry_or_key_reflection(self):
        secret = "test-key"
        for status in (401, 429):
            with self.subTest(status=status):
                def opener(request, timeout, status=status):
                    raise HTTPError(
                        request.full_url,
                        status,
                        "provider error",
                        {},
                        io.BytesIO(f'{{"detail":"{secret}"}}'.encode("utf-8")),
                    )

                with patch.dict(os.environ, {"JEV_API_KEY": secret}, clear=False):
                    report = run_probe(live=True, opener=opener)
                self.assertEqual(report["network"]["calls"], 1)
                self.assertEqual(len(report["requests"]), 1)
                self.assertEqual(report["requests"][0]["http_status"], status)
                self.assertEqual(report["requests"][0]["error"]["class"], "http_error")
                self.assertFalse(report["requests"][0]["retry_scheduled"])
                self.assertEqual(report["run"]["stop_reason"], "http_error")
                self.assertNotIn(secret, json.dumps(report, ensure_ascii=False))

    def test_missing_usage_stops_before_optional_error_probe(self):
        calls = 0

        def opener(request, timeout):
            nonlocal calls
            calls += 1
            return _Response(200, _valid_response(usage=None).replace(b', "usage": {"input_tokens": 31, "output_tokens": 0}', b""))

        with patch.dict(os.environ, {"JEV_API_KEY": "test-key"}, clear=False):
            report = run_probe(live=True, error_probe=True, opener=opener)
        self.assertEqual(calls, 1)
        self.assertEqual(len(report["requests"]), 1)
        self.assertEqual(report["requests"][0]["usage"]["status"], "missing")
        self.assertEqual(report["run"]["status"], "protocol_error")
        self.assertEqual(report["run"]["stop_reason"], "usage_missing")
        self.assertEqual(report["selection"]["protocol_status"], "protocol_error")

    def test_error_probe_auth_and_rate_limit_are_distinct_non_ok_results(self):
        for status, expected_status, expected_code in (
            (401, "authentication_error", "authentication_failed"),
            (429, "rate_limit_error", "rate_limited"),
        ):
            with self.subTest(status=status):
                calls = []

                def opener(request, timeout):
                    calls.append(request)
                    if len(calls) == 1:
                        return _Response(200, _valid_response())
                    raise HTTPError(
                        request.full_url,
                        status,
                        "provider error",
                        {},
                        io.BytesIO(b'{"error":{"code":"rejected"}}'),
                    )

                with patch.dict(os.environ, {"JEV_API_KEY": "test-key"}, clear=False):
                    report = run_probe(live=True, error_probe=True, opener=opener)

                self.assertEqual(len(calls), 2)
                error_record = report["requests"][1]
                self.assertEqual(error_record["error"]["class"], expected_status)
                self.assertEqual(error_record["error"]["code"], expected_code)
                self.assertEqual(error_record["usage"]["status"], "unknown")
                self.assertEqual(report["error_probe"]["status"], expected_status)
                self.assertEqual(report["run"]["status"], expected_status)
                self.assertEqual(report["cost"]["status"], "unknown")
                self.assertFalse(error_record["retry_scheduled"])
                self.assertFalse(report["integrity"]["retry_scheduled"])

    def test_error_probe_accepts_http_422_as_invalid_request_rejection(self):
        calls = []

        def opener(request, timeout):
            calls.append(request)
            if len(calls) == 1:
                return _Response(200, _valid_response())
            raise HTTPError(request.full_url, 422, "invalid request", {}, io.BytesIO(b"{}"))

        with patch.dict(os.environ, {"JEV_API_KEY": "test-key"}, clear=False):
            report = run_probe(live=True, error_probe=True, opener=opener)

        self.assertEqual(len(calls), 2)
        self.assertEqual(report["error_probe"], {"status": "invalid_request_rejected", "http_status": 422})
        self.assertEqual(report["requests"][1]["protocol_status"], "invalid_request_rejected")
        self.assertEqual(report["run"]["status"], "ok")

    def test_cli_does_not_report_rejected_endpoint_credentials(self):
        marker = "PROBE_MARKER"
        rejected_endpoint = f"https://user:{marker}@example.invalid/v1"
        with tempfile.TemporaryDirectory() as output_dir:
            output_path = os.path.join(output_dir, "report.json")
            stdout = io.StringIO()
            with patch("sys.stdout", stdout):
                exit_code = main(["--endpoint", rejected_endpoint, "--output", output_path])

            rendered_output = stdout.getvalue()
            with open(output_path, encoding="utf-8") as output_file:
                output_report = output_file.read()
            report = json.loads(output_report)

        self.assertEqual(exit_code, 2)
        self.assertEqual(report["run"]["status"], "configuration_error")
        self.assertIsNone(report["config"]["endpoint"])
        self.assertEqual(report["network"]["calls"], 0)
        self.assertNotIn(marker, rendered_output)
        self.assertNotIn(rejected_endpoint, rendered_output)
        self.assertNotIn(marker, output_report)
        self.assertNotIn(rejected_endpoint, output_report)


if __name__ == "__main__":
    unittest.main()
