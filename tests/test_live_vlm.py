from __future__ import annotations

import base64
import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from PIL import Image

from services.live_vlm.images import synthetic_probe_images
from services.live_vlm.probe import _build_requests, _parse_phone, _parse_role, _simulate_click, run_probe


PHONE_OUTPUT = 'Action: click\n<tool_call>\n{"name":"mobile_use","arguments":{"action":"click","coordinate":[500,500]}}\n</tool_call>'
MANAGER_OUTPUT = "### Thought ###\nThe red button is the first subgoal.\n### Plan ###\n1. Click the red button."
EXECUTOR_OUTPUT = '### Thought ###\nThe red button is visible.\n### Action ###\n{"action":"click","coordinate":[500,500]}\n### Description ###\nClick the red button.'
REFLECTOR_OUTPUT = "### Outcome ###\nA\n### Error Description ###\nNone"
NOTETAKER_OUTPUT = "### Important Notes ###\nThe red button is visible in the center."


class FixtureServer:
    def __init__(self, statuses: list[int] | None = None, usage: list[dict | None] | None = None, finish_reasons: list[str | None] | None = None, error_messages: list[str] | None = None) -> None:
        self.requests: list[dict] = []
        self.statuses = statuses or [200] * 5
        self.usage = usage or [{"prompt_tokens": 156, "completion_tokens": 4}] * 5
        self.finish_reasons = finish_reasons or [None] * 5
        self.error_messages = error_messages or ["fixture failure"] * 5
        self.outputs = [PHONE_OUTPUT, MANAGER_OUTPUT, EXECUTOR_OUTPUT, REFLECTOR_OUTPUT, NOTETAKER_OUTPUT]
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802 - stdlib handler API
                length = int(self.headers.get("Content-Length", "0"))
                body = json.loads(self.rfile.read(length).decode("utf-8"))
                owner.requests.append({"headers": dict(self.headers), "body": body})
                index = len(owner.requests) - 1
                status = owner.statuses[min(index, len(owner.statuses) - 1)]
                if status == 200:
                    response: dict = {
                        "choices": [{"message": {"role": "assistant", "content": owner.outputs[index % len(owner.outputs)]}}]
                    }
                    finish_reason = owner.finish_reasons[min(index, len(owner.finish_reasons) - 1)]
                    if finish_reason is not None:
                        response["choices"][0]["finish_reason"] = finish_reason
                    selected_usage = owner.usage[min(index, len(owner.usage) - 1)]
                    if selected_usage is not None:
                        response["usage"] = selected_usage
                else:
                    response = {"error": {"code": "rate_limited", "message": owner.error_messages[min(index, len(owner.error_messages) - 1)]}}
                raw = json.dumps(response, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def log_message(self, format: str, *args: object) -> None:
                return

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def endpoint(self) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}/v1/chat/completions"

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


class LiveVlmProbeTests(unittest.TestCase):
    def test_images_are_real_deterministic_pngs(self) -> None:
        images = synthetic_probe_images()
        self.assertEqual([image["image_id"] for image in images], ["probe-red-64", "probe-blue-64"])
        for image in images:
            raw = base64.b64decode(image["data"])
            self.assertTrue(raw.startswith(b"\x89PNG\r\n\x1a\n"))
            with Image.open(__import__("io").BytesIO(raw)) as decoded:
                self.assertEqual(decoded.size, (64, 64))
                self.assertEqual(decoded.format, "PNG")

    def test_dry_run_builds_original_five_requests_without_network(self) -> None:
        with patch.dict(os.environ, {}, clear=False):
            report = run_probe(
                endpoint="http://127.0.0.1:9/v1/chat/completions",
                model="fixture-model",
                credential_env="JEV_LIVE_TEST_KEY_MISSING",
            )
        self.assertEqual(report["status"], "ready")
        self.assertFalse(report["network_call"])
        self.assertEqual([check["name"] for check in report["checks"]], ["mobile_tool_call", "manager", "executor", "action_reflector", "notetaker"])
        self.assertTrue(all(check["status"] == "not_executed" for check in report["checks"]))
        self.assertEqual(report["max_tokens"], 1024)
        self.assertEqual(len(report["images"]), 2)
        self.assertTrue(all(image["valid_png"] for image in report["images"]))

    def test_execute_runs_each_original_parser_and_device_confirms_click(self) -> None:
        fixture = FixtureServer()
        try:
            with patch.dict(os.environ, {"JEV_LIVE_TEST_KEY": "fixture-secret"}, clear=False):
                report = run_probe(
                    endpoint=fixture.endpoint,
                    model="fixture-model",
                    credential_env="JEV_LIVE_TEST_KEY",
                    execute=True,
                )
        finally:
            fixture.close()
        self.assertEqual(report["status"], "success")
        self.assertEqual(report["compatible_checks"], 5)
        self.assertEqual(len(fixture.requests), 5)
        self.assertTrue(all(request["body"]["max_tokens"] == 1024 for request in fixture.requests))
        self.assertTrue(all(request["body"]["enable_thinking"] is False for request in fixture.requests))
        self.assertTrue(all(request["headers"]["Authorization"] == "Bearer fixture-secret" for request in fixture.requests))
        encoded_report = json.dumps(report, ensure_ascii=False)
        self.assertNotIn("fixture-secret", encoded_report)
        phone = report["checks"][0]
        self.assertTrue(phone["parse"]["parse_ok"])
        self.assertTrue(phone["simulation"]["independent_confirmation"])
        self.assertEqual(phone["simulation"]["receipt"]["outcome"], "EXECUTED")
        self.assertEqual(phone["simulation"]["action_status"]["status"], "EXECUTED")
        self.assertEqual(phone["simulation"]["after_observation"]["page_state"], "done")
        executor = report["checks"][2]
        self.assertTrue(executor["simulation"]["independent_confirmation"])
        self.assertEqual(report["budget"]["estimated_spend_cny"], 0.00126)


    def test_errors_usage_missing_and_no_retry_are_recorded(self) -> None:
        fixture = FixtureServer(statuses=[429, 200, 200, 200, 200], usage=[None, None, {"prompt_tokens": 10, "completion_tokens": 2}, {"prompt_tokens": 10, "completion_tokens": 2}, {"prompt_tokens": 10, "completion_tokens": 2}])
        try:
            with patch.dict(os.environ, {"JEV_LIVE_TEST_KEY": "fixture-secret"}, clear=False):
                report = run_probe(endpoint=fixture.endpoint, model="fixture-model", credential_env="JEV_LIVE_TEST_KEY", execute=True)
        finally:
            fixture.close()
        self.assertEqual(len(fixture.requests), 5)
        self.assertEqual(report["checks"][0]["status"], "request_failed")
        self.assertEqual(report["checks"][0]["http_status"], 429)
        self.assertFalse(report["checks"][0]["usage_present"])
        self.assertEqual(report["checks"][0]["retry_count"], 0)
        self.assertTrue(report["budget"]["usage_missing"])

    def test_truncated_provider_response_is_not_role_compatible(self) -> None:
        fixture = FixtureServer(finish_reasons=["length", None, None, None, None])
        try:
            with patch.dict(os.environ, {"JEV_LIVE_TEST_KEY": "fixture-secret"}, clear=False):
                report = run_probe(endpoint=fixture.endpoint, model="fixture-model", credential_env="JEV_LIVE_TEST_KEY", execute=True)
        finally:
            fixture.close()
        self.assertEqual(report["checks"][0]["status"], "response_truncated")
        self.assertEqual(report["checks"][0]["finish_reason"], "length")
        self.assertFalse(report["checks"][0]["parse"]["parse_ok"])
        self.assertEqual(report["status"], "partial")

    def test_transport_failure_has_explicit_unknown_usage(self) -> None:
        with patch.dict(os.environ, {"JEV_LIVE_TEST_KEY": "fixture-secret"}, clear=False):
            report = run_probe(
                endpoint="http://127.0.0.1:9/v1/chat/completions",
                model="fixture-model",
                credential_env="JEV_LIVE_TEST_KEY",
                execute=True,
                max_requests=1,
                timeout=1,
            )
        check = report["checks"][0]
        self.assertEqual(check["status"], "request_failed")
        self.assertFalse(check["usage_present"])
        self.assertIsNone(check["usage"])
        self.assertIsNone(check["estimated_cost_cny"])
        self.assertTrue(report["budget"]["usage_missing"])

    def test_provider_error_cannot_echo_key_into_report(self) -> None:
        fixture = FixtureServer(statuses=[500, 500, 500, 500, 500], error_messages=["echo fixture-secret"] * 5)
        try:
            with patch.dict(os.environ, {"JEV_LIVE_TEST_KEY": "fixture-secret"}, clear=False):
                report = run_probe(endpoint=fixture.endpoint, model="fixture-model", credential_env="JEV_LIVE_TEST_KEY", execute=True)
        finally:
            fixture.close()
        self.assertNotIn("fixture-secret", json.dumps(report, ensure_ascii=False))
        self.assertIn("<redacted>", json.dumps(report, ensure_ascii=False))

    def test_successful_parser_fields_cannot_echo_key_into_report(self) -> None:
        fixture = FixtureServer()
        fixture.outputs[0] = 'Action: click\n<tool_call>\n{"name":"mobile_use","arguments":{"action":"click","coordinate":[500,500],"note":"fixture-secret"}}\n</tool_call>'
        try:
            with patch.dict(os.environ, {"JEV_LIVE_TEST_KEY": "fixture-secret"}, clear=False):
                report = run_probe(endpoint=fixture.endpoint, model="fixture-model", credential_env="JEV_LIVE_TEST_KEY", execute=True)
        finally:
            fixture.close()
        self.assertEqual(report["checks"][0]["status"], "compatible")
        self.assertNotIn("fixture-secret", json.dumps(report, ensure_ascii=False))
        self.assertIn("<redacted>", json.dumps(report["checks"][0]["parse"], ensure_ascii=False))

    def test_source_loader_ignores_a_foreign_utils_module(self) -> None:
        import sys
        import types

        foreign = types.ModuleType("utils")
        foreign.__file__ = "/tmp/foreign-utils.py"
        with patch.dict(sys.modules, {"utils": foreign}):
            requests, _ = _build_requests()
        phone = next(request for request in requests if request.name == "mobile_tool_call")
        self.assertIn("# Tools", phone.prompt)
        self.assertIn("<tool_call>", "\n".join(item["text"] for item in phone.messages[0]["content"] if item.get("type") == "text"))

    def test_non_click_or_outside_click_cannot_be_independently_confirmed(self) -> None:
        requests, _ = _build_requests()
        phone = next(request for request in requests if request.name == "mobile_tool_call")
        terminate = '<tool_call>\n{"name":"mobile_use","arguments":{"action":"terminate"}}\n</tool_call>'
        self.assertFalse(_parse_phone(phone.parse_response, terminate)["parse_ok"])
        outside = {"name": "mobile_use", "arguments": {"action": "click", "coordinate": [0, 0]}}
        self.assertFalse(_simulate_click(outside, "mobile_tool_call")["independent_confirmation"])

    def test_strict_original_parsing_does_not_accept_missing_sections_or_prefix_outcome(self) -> None:
        requests, _ = _build_requests()
        manager = next(request for request in requests if request.name == "manager")
        reflector = next(request for request in requests if request.name == "action_reflector")
        phone = next(request for request in requests if request.name == "mobile_tool_call")
        self.assertFalse(_parse_role("manager", manager.parse_response, "The plan is to click." )["parse_ok"])
        self.assertFalse(_parse_role("action_reflector", reflector.parse_response, "### Outcome ###\nA: Successful\n### Error Description ###\nNone")["parse_ok"])
        self.assertFalse(_parse_phone(phone.parse_response, '<tool_call>{"name":"mobile_use","arguments":{"action":"click"}}</tool_call>')["parse_ok"])

    def test_budget_is_checked_before_any_network_call(self) -> None:
        fixture = FixtureServer()
        try:
            with patch.dict(os.environ, {"JEV_LIVE_TEST_KEY": "fixture-secret"}, clear=False):
                report = run_probe(endpoint=fixture.endpoint, model="fixture-model", credential_env="JEV_LIVE_TEST_KEY", execute=True, budget_cny=0.000001)
        finally:
            fixture.close()
        self.assertEqual(report["status"], "budget_exceeded_before_execution")
        self.assertFalse(report["network_call"])
        self.assertEqual(fixture.requests, [])


if __name__ == "__main__":
    unittest.main()
