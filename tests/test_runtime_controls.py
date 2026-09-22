from __future__ import annotations

import json
import os
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch

from services.sim_loop.device import SimulatedDevice, create_device_server
from services.sim_loop.model import minimal_probe
from services.sim_loop.service import SimulationService, create_service_server
from services.sim_loop.transport import TransportError


class FixedClock:
    def __call__(self) -> str:
        return "2026-09-22T00:00:00Z"


class OpenAICompatFixture:
    def __init__(self) -> None:
        self.requests: list[dict] = []
        self.status = 200
        self.response = {
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": json.dumps(
                            {
                                "kind": "action",
                                "action": {
                                    "kind": "tap",
                                    "target_node_id": "start-button",
                                    "expected_page_state": "done",
                                },
                            }
                        ),
                    }
                }
            ],
            "usage": {"prompt_tokens": 21, "completion_tokens": 7, "total_tokens": 28},
        }
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802 - stdlib handler API
                length = int(self.headers.get("Content-Length", "0"))
                body = json.loads(self.rfile.read(length).decode("utf-8"))
                owner.requests.append({"path": self.path, "headers": dict(self.headers), "body": body})
                encoded = json.dumps(owner.response, ensure_ascii=False).encode("utf-8")
                self.send_response(owner.status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

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


class LiveModelFixtureTests(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = OpenAICompatFixture()

    def tearDown(self) -> None:
        self.fixture.close()

    def test_probe_is_read_only_until_execute_and_sends_multimodal_chinese_request(self) -> None:
        with patch.dict(os.environ, {"JEV_TEST_LIVE_KEY": "fixture-token"}, clear=False):
            inspected = minimal_probe(
                endpoint=self.fixture.endpoint,
                model="fixture-model",
                credential_env="JEV_TEST_LIVE_KEY",
                clock=FixedClock(),
            )
            self.assertTrue(inspected["ready"])
            self.assertEqual(inspected["status"], "ready")
            self.assertFalse(inspected["network_call"])
            self.assertEqual(self.fixture.requests, [])

            executed = minimal_probe(
                endpoint=self.fixture.endpoint,
                model="fixture-model",
                credential_env="JEV_TEST_LIVE_KEY",
                timeout=5,
                execute=True,
                clock=FixedClock(),
            )

        self.assertTrue(executed["ready"])
        self.assertEqual(executed["status"], "success")
        self.assertTrue(executed["network_call"])
        self.assertEqual(executed["usage"]["total_tokens"], 28)
        self.assertEqual(len(self.fixture.requests), 1)
        request = self.fixture.requests[0]
        self.assertEqual(request["headers"]["Authorization"], "Bearer fixture-token")
        self.assertEqual(request["body"]["model"], "fixture-model")
        content = request["body"]["messages"][0]["content"]
        self.assertEqual(content[0], {"type": "text", "text": "请返回一个结构化 tap action。"})
        self.assertEqual(len(content[1:]), 2)
        self.assertTrue(content[1]["image_url"]["url"].startswith("data:image/png;base64,"))

    def test_probe_reports_missing_configuration_and_provider_error_without_replay(self) -> None:
        with patch.dict(os.environ, {}, clear=False):
            missing = minimal_probe(credential_env="JEV_MISSING_KEY", clock=FixedClock())
        self.assertFalse(missing["ready"])
        self.assertEqual(missing["status"], "credentials_required")
        self.assertFalse(missing["network_call"])
        self.assertEqual(self.fixture.requests, [])

        self.fixture.status = 429
        self.fixture.response = {
            "error": {"code": "rate_limited", "message": "fixture limit"},
            "usage": {"prompt_tokens": 3},
        }
        with patch.dict(os.environ, {"JEV_TEST_LIVE_KEY": "fixture-token"}, clear=False):
            failed = minimal_probe(
                endpoint=self.fixture.endpoint,
                model="fixture-model",
                credential_env="JEV_TEST_LIVE_KEY",
                execute=True,
                clock=FixedClock(),
            )
        self.assertFalse(failed["ready"])
        self.assertEqual(failed["status"], "request_failed")
        self.assertTrue(failed["network_call"])
        self.assertEqual(failed["error"]["code"], "rate_limited")
        self.assertEqual(failed["usage"]["prompt_tokens"], 3)


class RuntimeHTTPTests(unittest.TestCase):
    def setUp(self) -> None:
        self.token = "runtime-token"
        self.device = SimulatedDevice(
            device_id="sim-device-01",
            token=self.token,
            behavior="apply_effect",
            clock=FixedClock(),
        )
        self.device_server = create_device_server(self.device)
        self.device_thread = threading.Thread(target=self.device_server.serve_forever, daemon=True)
        self.device_thread.start()
        self.service = SimulationService(
            device_base_url=f"http://127.0.0.1:{self.device_server.server_address[1]}",
            token=self.token,
            device_id="sim-device-01",
            behavior="apply_effect",
            clock=FixedClock(),
        )
        self.service_server = create_service_server(self.service)
        self.service_thread = threading.Thread(target=self.service_server.serve_forever, daemon=True)
        self.service_thread.start()

    def tearDown(self) -> None:
        self.service_server.shutdown()
        self.service_server.server_close()
        self.device_server.shutdown()
        self.device_server.server_close()
        self.service_thread.join(timeout=2)
        self.device_thread.join(timeout=2)

    def _url(self, path: str) -> str:
        return f"http://127.0.0.1:{self.service_server.server_address[1]}{path}"

    def _request(self, method: str, path: str, *, payload=None):
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.token}",
            "X-JEV-Protocol-Version": "1",
        }
        data = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = Request(self._url(path), data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except HTTPError as exc:
            return exc.code, json.loads(exc.read().decode("utf-8"))

    def _device_request(self, method: str, path: str, *, payload=None, session_id: str | None = None):
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.token}",
            "X-JEV-Device-Id": "sim-device-01",
            "X-JEV-Protocol-Version": "1",
        }
        if session_id is not None:
            headers["X-JEV-Session-Id"] = session_id
        data = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = Request(
            f"http://127.0.0.1:{self.device_server.server_address[1]}{path}",
            data=data,
            headers=headers,
            method=method,
        )
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except HTTPError as exc:
            return exc.code, json.loads(exc.read().decode("utf-8"))

    def _payload(self, task_id: str = "runtime-task") -> dict:
        return {
            "schema_version": "1.0",
            "task_id": task_id,
            "device_id": "sim-device-01",
            "mode": "simulated",
            "goal": "advance_to_done",
            "scenario": "apply_effect",
        }

    def _wait_for(self, predicate, timeout: float = 2.0) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.005)
        self.fail("condition did not become true before timeout")

    def test_pause_before_dispatch_suppresses_action_and_is_idempotent(self) -> None:
        self.service.dispatch_delay = 0.15
        submitted: list[tuple[int, dict]] = []

        thread = threading.Thread(
            target=lambda: submitted.append(self._request("POST", "/v1/tasks", payload=self._payload("pause-before")))
        )
        thread.start()
        self._wait_for(lambda: self.service.active_task_id == "pause-before")
        status, paused = self._request("POST", "/v1/tasks/pause-before/pause", payload={})
        self.assertEqual(status, 200)
        self.assertEqual(paused["state"], "PAUSED")
        status, repeated = self._request("POST", "/v1/tasks/pause-before/pause", payload={})
        self.assertEqual(status, 200)
        self.assertEqual(repeated["state"], paused["state"])
        self.assertEqual(repeated["control"]["command"], paused["control"]["command"])
        thread.join(timeout=3)
        self.assertFalse(thread.is_alive())
        self.assertEqual(submitted[0][0], 200)
        result = submitted[0][1]
        self.assertEqual(result["state"], "PAUSED")
        self.assertIsNone(result["action"])
        self.assertEqual(self.device.action_count, 0)
        self.assertNotIn("action.dispatched", [event["kind"] for event in result["trace"]])

    def test_cancel_in_flight_preserves_terminal_state_and_marks_effect_unknown(self) -> None:
        self.device.action_delay = 0.15
        submitted: list[tuple[int, dict]] = []
        thread = threading.Thread(
            target=lambda: submitted.append(self._request("POST", "/v1/tasks", payload=self._payload("cancel-flight")))
        )
        thread.start()
        self._wait_for(lambda: (self.service.status("cancel-flight") or {}).get("action") is not None)
        status, cancelled = self._request("POST", "/v1/tasks/cancel-flight/cancel", payload={})
        self.assertEqual(status, 200)
        self.assertEqual(cancelled["state"], "CANCELLED")
        thread.join(timeout=3)
        self.assertFalse(thread.is_alive())
        result = submitted[0][1]
        self.assertEqual(result["state"], "CANCELLED")
        self.assertEqual(result["receipt"]["outcome"], "EXECUTED")
        self.assertEqual(result["verification"]["status"], "UNKNOWN")
        self.assertEqual(self.device.action_count, 1)
        status, repeated = self._request("POST", "/v1/tasks/cancel-flight/cancel", payload={})
        self.assertEqual(status, 200)
        self.assertEqual(repeated["state"], "CANCELLED")

    def test_action_timeout_after_dispatch_is_unknown_and_does_not_retry(self) -> None:
        self.device.action_delay = 0.15
        self.service.device_client.timeout = 0.02
        status, result = self._request("POST", "/v1/tasks", payload=self._payload("timeout-after-dispatch"))
        self.assertEqual(status, 200)
        self.assertEqual(result["state"], "PAUSED")
        self.assertEqual(result["failure"]["code"], "action_result_unknown")
        self.assertEqual(result["verification"]["status"], "UNKNOWN")
        self._wait_for(lambda: self.device.action_count == 1)
        self.assertEqual(self.device.action_count, 1)
        status, cancelled = self._request("POST", "/v1/tasks/timeout-after-dispatch/cancel")
        self.assertEqual(status, 200)
        self.assertEqual(cancelled["state"], "CANCELLED")
        self.assertEqual(self.service.active_task_id, "timeout-after-dispatch")
        status, second = self._request("POST", "/v1/tasks", payload=self._payload("second-after-unknown"))
        self.assertEqual(status, 409)
        self.assertEqual(second["error"]["code"], "task_active")

    def test_explicit_stale_observation_rejection_fails_and_releases_reservation(self) -> None:
        self.service.dispatch_delay = 0.15
        submitted: list[tuple[int, dict]] = []
        thread = threading.Thread(
            target=lambda: submitted.append(self._request("POST", "/v1/tasks", payload=self._payload("stale-action")))
        )
        thread.start()
        self._wait_for(lambda: len((self.service.status("stale-action") or {}).get("observations", [])) == 1)

        status, external_observation = self._device_request(
            "GET",
            "/v1/simulated/observations?task_id=stale-action",
            session_id="session-stale-action",
        )
        self.assertEqual(status, 200)
        self.assertEqual(external_observation["session_id"], "session-stale-action")
        thread.join(timeout=3)
        self.assertFalse(thread.is_alive())

        status, result = submitted[0]
        self.assertEqual(status, 200)
        self.assertEqual(result["state"], "FAILED")
        self.assertEqual(result["failure"]["code"], "stale_observation")
        self.assertFalse(result["action_result_unknown"])
        self.assertIsNone(result["receipt"])
        self.assertEqual(self.device.action_count, 0)
        self.assertIsNone(self.service.active_task_id)

        status, second = self._request("POST", "/v1/tasks", payload=self._payload("after-stale-rejection"))
        self.assertEqual(status, 200)
        self.assertEqual(second["state"], "SUCCEEDED")

    def test_proxy_http_5xx_is_not_an_explicit_device_rejection(self) -> None:
        error = TransportError(500, {"error": {"code": "stale_observation", "message": "proxy response"}})
        self.assertFalse(error.is_explicit_device_rejection)

    def test_pause_keeps_single_active_task_and_second_task_is_rejected(self) -> None:
        self.service.dispatch_delay = 0.15
        submitted: list[tuple[int, dict]] = []
        thread = threading.Thread(
            target=lambda: submitted.append(self._request("POST", "/v1/tasks", payload=self._payload("paused-active")))
        )
        thread.start()
        self._wait_for(lambda: self.service.active_task_id == "paused-active")
        status, _ = self._request("POST", "/v1/tasks/paused-active/pause", payload={})
        self.assertEqual(status, 200)
        status, result = self._request("POST", "/v1/tasks", payload=self._payload("second-task"))
        self.assertEqual(status, 409)
        self.assertEqual(result["error"]["code"], "task_active")
        thread.join(timeout=3)

    def test_replay_records_chinese_multi_image_attempt_and_usage(self) -> None:
        payload = self._payload("replay-success")
        payload["model"] = {
            "mode": "replay",
            "prompt": "请点击开始按钮",
            "images": [
                {"image_id": "screen-before", "media_type": "image/png", "data": "base64-before"},
                {"image_id": "screen-context", "media_type": "image/png", "data": "base64-context"},
            ],
            "responses": [
                {
                    "kind": "action",
                    "action": {"kind": "tap", "target_node_id": "start-button", "expected_page_state": "done"},
                    "usage": {"input_tokens": 12, "output_tokens": 3},
                }
            ],
        }
        status, result = self._request("POST", "/v1/tasks", payload=payload)
        self.assertEqual(status, 200)
        self.assertEqual(result["state"], "SUCCEEDED")
        self.assertEqual(result["attempts"][0]["request"]["prompt"], "请点击开始按钮")
        self.assertEqual(len(result["attempts"][0]["request"]["images"]), 2)
        self.assertEqual(result["attempts"][0]["usage"]["input_tokens"], 12)
        self.assertEqual(result["action"]["source"], "replay-model")

    def test_replay_retries_bounded_service_errors_and_keeps_attempts(self) -> None:
        payload = self._payload("replay-retry")
        payload["model"] = {
            "mode": "replay",
            "max_attempts": 3,
            "responses": [
                {"kind": "error", "code": "timeout", "retryable": True},
                {"kind": "error", "code": "rate_limited", "retryable": True},
                {
                    "kind": "action",
                    "action": {
                        "kind": "tap",
                        "target_node_id": "start-button",
                        "expected_page_state": "done",
                    },
                },
            ],
        }
        status, result = self._request("POST", "/v1/tasks", payload=payload)
        self.assertEqual(status, 200)
        self.assertEqual(result["state"], "SUCCEEDED")
        self.assertEqual(len(result["attempts"]), 3)
        self.assertEqual([item["error"]["code"] for item in result["attempts"][:2]], ["timeout", "rate_limited"])

    def test_replay_malformed_response_does_not_dispatch(self) -> None:
        payload = self._payload("replay-malformed")
        payload["model"] = {"mode": "replay", "responses": [{"kind": "malformed", "value": []}]}
        status, result = self._request("POST", "/v1/tasks", payload=payload)
        self.assertEqual(status, 200)
        self.assertEqual(result["state"], "FAILED")
        self.assertEqual(result["failure"]["code"], "model_invalid_response")
        self.assertIsNone(result["action"])
        self.assertEqual(self.device.action_count, 0)

    def test_cancel_suppresses_late_replay_response(self) -> None:
        payload = self._payload("replay-cancelled")
        payload["model"] = {
            "mode": "replay",
            "delay_ms": 150,
            "responses": [
                {
                    "kind": "action",
                    "action": {
                        "kind": "tap",
                        "target_node_id": "start-button",
                        "expected_page_state": "done",
                    },
                }
            ],
        }
        submitted: list[tuple[int, dict]] = []
        thread = threading.Thread(
            target=lambda: submitted.append(self._request("POST", "/v1/tasks", payload=payload))
        )
        thread.start()
        self._wait_for(lambda: self.service.active_task_id == "replay-cancelled")
        status, result = self._request("POST", "/v1/tasks/replay-cancelled/cancel")
        self.assertEqual(status, 200)
        self.assertEqual(result["state"], "CANCELLED")
        thread.join(timeout=3)
        self.assertFalse(thread.is_alive())
        self.assertEqual(submitted[0][1]["state"], "CANCELLED")
        self.assertIsNone(submitted[0][1]["action"])
        self.assertEqual(self.device.action_count, 0)

    def test_replay_missing_target_is_rejected_before_dispatch(self) -> None:
        payload = self._payload("replay-missing-target")
        payload["model"] = {
            "mode": "replay",
            "responses": [{"kind": "action", "action": {"kind": "tap", "expected_page_state": "done"}}],
        }
        status, result = self._request("POST", "/v1/tasks", payload=payload)
        self.assertEqual(status, 200)
        self.assertEqual(result["state"], "FAILED")
        self.assertEqual(result["failure"]["code"], "model_invalid_response")
        self.assertIsNone(result["action"])
        self.assertEqual(self.device.action_count, 0)

    def test_live_mode_without_credentials_is_explicit(self) -> None:
        payload = self._payload("live-no-credentials")
        payload["model"] = {"mode": "live"}
        status, result = self._request("POST", "/v1/tasks", payload=payload)
        self.assertEqual(status, 200)
        self.assertEqual(result["state"], "FAILED")
        self.assertEqual(result["failure"]["code"], "model_credentials_required")
        self.assertEqual(result["attempts"][0]["error"]["code"], "credentials_required")

    def test_live_mode_uses_local_fixture_without_replay_fallback(self) -> None:
        fixture = OpenAICompatFixture()
        try:
            payload = self._payload("live-fixture")
            payload["model"] = {
                "mode": "live",
                "endpoint": fixture.endpoint,
                "model": "fixture-model",
                "credential_env": "JEV_TEST_LIVE_KEY",
                "timeout": 5,
                "prompt": "请点击开始按钮",
                "images": [
                    {"image_id": "before", "media_type": "image/png", "data": "fixture-before"},
                    {"image_id": "context", "media_type": "image/png", "data": "fixture-context"},
                ],
            }
            with patch.dict(os.environ, {"JEV_TEST_LIVE_KEY": "fixture-token"}, clear=False):
                status, result = self._request("POST", "/v1/tasks", payload=payload)
            self.assertEqual(status, 200)
            self.assertEqual(result["state"], "SUCCEEDED")
            self.assertEqual(result["action"]["source"], "live-model")
            self.assertEqual(result["attempts"][0]["usage"]["total_tokens"], 28)
            self.assertEqual(len(fixture.requests), 1)
        finally:
            fixture.close()


class ActionDeliveryTests(unittest.TestCase):
    def setUp(self) -> None:
        self.token = "action-token"
        self.device = SimulatedDevice(device_id="sim-device-01", token=self.token, clock=FixedClock())
        self.server = create_device_server(self.device)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def _request(self, method: str, path: str, payload=None, *, session_id: str | None = None):
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.token}",
            "X-JEV-Device-Id": "sim-device-01",
            "X-JEV-Protocol-Version": "1",
        }
        if session_id is not None:
            headers["X-JEV-Session-Id"] = session_id
        data = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(payload).encode("utf-8")
        request = Request(
            f"http://127.0.0.1:{self.server.server_address[1]}{path}",
            data=data,
            headers=headers,
            method=method,
        )
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except HTTPError as exc:
            return exc.code, json.loads(exc.read().decode("utf-8"))

    def _action(self, action_id: str = "action-1", **extra) -> dict:
        action = {
            "schema_version": "1.0",
            "task_id": "action-task",
            "action_id": action_id,
            "observation_id": "obs-action-task-before",
            "kind": "tap",
            "target_node_id": "start-button",
            "expected_page_state": "done",
            "source": "simulated-planner",
            "created_at": "2026-09-22T00:00:00Z",
            "session_id": "session-current",
            "sequence": 1,
        }
        action.update(extra)
        return action

    def test_duplicate_action_is_deduplicated_and_old_session_is_rejected(self) -> None:
        status, observation = self._request("GET", "/v1/simulated/observations?task_id=action-task")
        self.assertEqual(status, 200)
        status, first = self._request("POST", "/v1/simulated/actions", self._action())
        self.assertEqual(status, 200)
        self.assertFalse(first.get("deduplicated", False))
        status, duplicate = self._request("POST", "/v1/simulated/actions", self._action())
        self.assertEqual(status, 200)
        self.assertTrue(duplicate["deduplicated"])
        self.assertEqual(duplicate["receipt_id"], first["receipt_id"])
        self.assertEqual(self.device.action_count, 1)
        status, old = self._request(
            "POST",
            "/v1/simulated/actions",
            self._action("action-old", session_id="session-old", sequence=2),
        )
        self.assertEqual(status, 409)
        self.assertEqual(old["error"]["code"], "stale_session")

    def test_observation_binds_session_before_first_action(self) -> None:
        status, observation = self._request(
            "GET",
            "/v1/simulated/observations?task_id=session-bound",
            session_id="session-current",
        )
        self.assertEqual(status, 200)
        self.assertEqual(observation["session_id"], "session-current")

        old = self._action("action-old-before-dispatch", session_id="session-old")
        old["task_id"] = "session-bound"
        old["observation_id"] = observation["observation_id"]
        status, rejected = self._request("POST", "/v1/simulated/actions", old)
        self.assertEqual(status, 409)
        self.assertEqual(rejected["error"]["code"], "stale_session")
        self.assertEqual(self.device.action_count, 0)

        current = self._action("action-current", session_id="session-current")
        current["task_id"] = "session-bound"
        current["observation_id"] = observation["observation_id"]
        status, receipt = self._request("POST", "/v1/simulated/actions", current)
        self.assertEqual(status, 200)
        self.assertEqual(receipt["outcome"], "EXECUTED")

        status, missing = self._request("GET", "/v1/simulated/observations?task_id=session-bound")
        self.assertEqual(status, 409)
        self.assertEqual(missing["error"]["code"], "stale_session")

    def test_out_of_order_and_stale_node_are_rejected_without_effect(self) -> None:
        self._request("GET", "/v1/simulated/observations?task_id=action-task")
        status, result = self._request(
            "POST",
            "/v1/simulated/actions",
            self._action("action-out-of-order", sequence=2),
        )
        self.assertEqual(status, 409)
        self.assertEqual(result["error"]["code"], "action_out_of_order")
        status, result = self._request(
            "POST",
            "/v1/simulated/actions",
            self._action("action-missing-node", target_node_id="gone"),
        )
        self.assertEqual(status, 409)
        self.assertEqual(result["error"]["code"], "target_node_not_found")
        self.assertEqual(self.device.action_count, 0)

    def test_action_must_bind_to_the_latest_observation_version(self) -> None:
        self._request("GET", "/v1/simulated/observations?task_id=latest-observation")
        status, latest = self._request("GET", "/v1/simulated/observations?task_id=latest-observation")
        self.assertEqual(status, 200)
        action = self._action("latest-action")
        action["task_id"] = "latest-observation"
        action["observation_id"] = latest["observation_id"]
        action["observation_version"] = latest["observation_version"]
        status, receipt = self._request("POST", "/v1/simulated/actions", action)
        self.assertEqual(status, 200)
        self.assertEqual(receipt["outcome"], "EXECUTED")
        self.assertEqual(self.device.action_count, 1)


if __name__ == "__main__":
    unittest.main()
