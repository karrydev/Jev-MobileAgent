from __future__ import annotations

import json
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from services.sim_loop.device import SimulatedDevice, create_device_server
from services.sim_loop.schema import check_fixtures
from services.sim_loop.service import SimulationService, create_service_server


class FixedClock:
    def __call__(self) -> str:
        return "2026-09-22T00:00:00Z"


class ForeignObservationDevice(SimulatedDevice):
    def observe(self, task_id: str) -> dict:
        observation = super().observe(task_id)
        observation["task_id"] = "foreign-task"
        return observation


class ForeignReceiptDevice(SimulatedDevice):
    def execute(self, action: dict) -> dict:
        receipt = super().execute(action)
        receipt["action_id"] = "foreign-action"
        return receipt


class RepeatedAfterObservationDevice(SimulatedDevice):
    def observe(self, task_id: str) -> dict:
        observation = super().observe(task_id)
        if observation["observation_id"].endswith("-after"):
            observation["observation_id"] = f"obs-{task_id}-before"
        return observation


class MalformedObservationHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 - stdlib handler API
        body = b"{malformed"
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        return


class SimLoopHTTPTests(unittest.TestCase):
    def setUp(self) -> None:
        self.token = "test-token"
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

    def _request(self, method: str, path: str, *, payload=None, token: str | None = None, protocol: str = "1"):
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.token if token is None else token}",
            "X-JEV-Protocol-Version": protocol,
        }
        data = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            data = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
        request = Request(self._url(path), data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except HTTPError as exc:
            return exc.code, json.loads(exc.read().decode("utf-8"))

    def _payload(self, task_id: str = "task-001", scenario: str = "apply_effect") -> dict:
        return {
            "schema_version": "1.0",
            "task_id": task_id,
            "device_id": "sim-device-01",
            "mode": "simulated",
            "goal": "advance_to_done",
            "scenario": scenario,
        }

    def _restart_with_device(self, device: SimulatedDevice, *, timeout: float = 2.0) -> None:
        self.tearDown()
        self.device = device
        self.device_server = create_device_server(self.device)
        self.device_thread = threading.Thread(target=self.device_server.serve_forever, daemon=True)
        self.device_thread.start()
        self.service = SimulationService(
            device_base_url=f"http://127.0.0.1:{self.device_server.server_address[1]}",
            token=self.token,
            device_id=self.device.device_id,
            behavior=self.device.behavior,
            clock=FixedClock(),
            device_timeout=timeout,
        )
        self.service_server = create_service_server(self.service)
        self.service_thread = threading.Thread(target=self.service_server.serve_forever, daemon=True)
        self.service_thread.start()

    def test_success_has_independent_postcondition_and_linked_trace(self) -> None:
        status, result = self._request("POST", "/v1/tasks", payload=self._payload())
        self.assertEqual(status, 200)
        self.assertEqual(result["state"], "SUCCEEDED")
        self.assertEqual(result["receipt"]["outcome"], "EXECUTED")
        self.assertTrue(result["receipt"]["accepted"])
        self.assertEqual(result["verification"]["status"], "SUCCESS")
        self.assertEqual(result["verification"]["actual_page_state"], "done")
        self.assertEqual(len(result["observations"]), 2)
        self.assertEqual(result["observations"][0]["page_state"], "landing")
        self.assertEqual(result["observations"][1]["page_state"], "done")
        self.assertEqual(len(result["trace"]), 7)
        for sequence, event in enumerate(result["trace"]):
            self.assertEqual(event["sequence"], sequence)
            self.assertEqual(event["links"]["task_id"], result["task_id"])
            if sequence:
                self.assertEqual(event["previous_event_id"], result["trace"][sequence - 1]["event_id"])
        status_code, status_result = self._request("GET", "/v1/tasks/task-001")
        self.assertEqual(status_code, 200)
        self.assertEqual(status_result, result)

    def test_receipt_without_effect_fails_postcondition(self) -> None:
        self.tearDown()
        self.device = SimulatedDevice(
            device_id="sim-device-01",
            token=self.token,
            behavior="receipt_without_effect",
            clock=FixedClock(),
        )
        self.device_server = create_device_server(self.device)
        self.device_thread = threading.Thread(target=self.device_server.serve_forever, daemon=True)
        self.device_thread.start()
        self.service = SimulationService(
            device_base_url=f"http://127.0.0.1:{self.device_server.server_address[1]}",
            token=self.token,
            device_id="sim-device-01",
            behavior="receipt_without_effect",
            clock=FixedClock(),
        )
        self.service_server = create_service_server(self.service)
        self.service_thread = threading.Thread(target=self.service_server.serve_forever, daemon=True)
        self.service_thread.start()

        status, result = self._request(
            "POST",
            "/v1/tasks",
            payload=self._payload("task-no-effect", "receipt_without_effect"),
        )
        self.assertEqual(status, 200)
        self.assertEqual(result["state"], "FAILED")
        self.assertEqual(result["receipt"]["outcome"], "EXECUTED")
        self.assertTrue(result["receipt"]["accepted"])
        self.assertEqual(result["observations"][1]["page_state"], "landing")
        self.assertEqual(result["verification"]["status"], "FAILURE")
        self.assertEqual(result["verification"]["reason"], "postcondition_not_met")

    def test_second_task_rejects_already_satisfied_initial_state(self) -> None:
        status, first = self._request("POST", "/v1/tasks", payload=self._payload("task-first-done"))
        self.assertEqual(status, 200)
        self.assertEqual(first["state"], "SUCCEEDED")

        status, second = self._request("POST", "/v1/tasks", payload=self._payload("task-second-done"))
        self.assertEqual(status, 200)
        self.assertEqual(second["state"], "FAILED")
        self.assertEqual(second["failure"]["code"], "action_not_applicable")
        self.assertIsNone(second["action"])
        self.assertIsNone(second["receipt"])
        self.assertIsNone(second["verification"])
        self.assertEqual(len(second["observations"]), 1)
        self.assertEqual(second["observations"][0]["page_state"], "done")
        self.assertFalse(second["observations"][0]["nodes"][0]["enabled"])
        self.assertEqual(self.device.action_count, 1)

        status, stored = self._request("GET", "/v1/tasks/task-second-done")
        self.assertEqual(status, 200)
        self.assertEqual(stored, second)

    def test_closed_device_transport_fails_task_and_persists_terminal_status(self) -> None:
        closed_port = self.device_server.server_address[1]
        self.device_server.shutdown()
        self.device_server.server_close()
        self.device_thread.join(timeout=2)
        self.service.device_client.base_url = f"http://127.0.0.1:{closed_port}"

        status, result = self._request("POST", "/v1/tasks", payload=self._payload("task-closed-device"))
        self.assertEqual(status, 200)
        self.assertEqual(result["state"], "FAILED")
        self.assertEqual(result["failure"]["code"], "device_transport_error")
        self.assertIsNone(self.service.active_task_id)
        self.assertEqual(result["trace"][-1]["kind"], "task.failed")

        status, stored = self._request("GET", "/v1/tasks/task-closed-device")
        self.assertEqual(status, 200)
        self.assertEqual(stored, result)

    def test_malformed_device_response_fails_task_without_http_500(self) -> None:
        self.device_server.shutdown()
        self.device_server.server_close()
        self.device_thread.join(timeout=2)
        malformed_server = ThreadingHTTPServer(("127.0.0.1", 0), MalformedObservationHandler)
        malformed_thread = threading.Thread(target=malformed_server.serve_forever, daemon=True)
        malformed_thread.start()
        self.service.device_client.base_url = f"http://127.0.0.1:{malformed_server.server_address[1]}"
        try:
            status, result = self._request("POST", "/v1/tasks", payload=self._payload("task-malformed-device"))
            self.assertEqual(status, 200)
            self.assertEqual(result["state"], "FAILED")
            self.assertEqual(result["failure"]["code"], "device_transport_error")
            self.assertEqual(result["trace"][-1]["kind"], "task.failed")
        finally:
            malformed_server.shutdown()
            malformed_server.server_close()
            malformed_thread.join(timeout=2)

    def test_schema_valid_foreign_observation_is_rejected_before_dispatch(self) -> None:
        self._restart_with_device(
            ForeignObservationDevice(
                device_id="sim-device-01",
                token=self.token,
                behavior="apply_effect",
                clock=FixedClock(),
            )
        )

        status, result = self._request("POST", "/v1/tasks", payload=self._payload("task-foreign-observation"))
        self.assertEqual(status, 200)
        self.assertEqual(result["state"], "FAILED")
        self.assertEqual(result["failure"]["code"], "device_response_mismatch")
        self.assertEqual(len(result["observations"]), 0)
        self.assertIsNone(result["action"])
        self.assertIsNone(result["receipt"])
        self.assertEqual(self.device.action_count, 0)
        self.assertEqual(result["trace"][-1]["kind"], "task.failed")

    def test_schema_valid_foreign_receipt_is_rejected_before_next_observation(self) -> None:
        self._restart_with_device(
            ForeignReceiptDevice(
                device_id="sim-device-01",
                token=self.token,
                behavior="apply_effect",
                clock=FixedClock(),
            )
        )

        status, result = self._request("POST", "/v1/tasks", payload=self._payload("task-foreign-receipt"))
        self.assertEqual(status, 200)
        self.assertEqual(result["state"], "FAILED")
        self.assertEqual(result["failure"]["code"], "device_response_mismatch")
        self.assertEqual(len(result["observations"]), 1)
        self.assertIsNotNone(result["action"])
        self.assertIsNone(result["receipt"])
        self.assertIsNone(result["verification"])
        self.assertEqual(self.device.action_count, 1)
        self.assertEqual(
            [event["kind"] for event in result["trace"]],
            ["task.submitted", "observation.captured", "action.dispatched", "task.failed"],
        )

    def test_repeated_after_observation_is_rejected_before_verification(self) -> None:
        self._restart_with_device(
            RepeatedAfterObservationDevice(
                device_id="sim-device-01",
                token=self.token,
                behavior="apply_effect",
                clock=FixedClock(),
            )
        )

        status, result = self._request("POST", "/v1/tasks", payload=self._payload("task-repeated-observation"))
        self.assertEqual(status, 200)
        self.assertEqual(result["state"], "FAILED")
        self.assertEqual(result["failure"]["code"], "device_response_mismatch")
        self.assertEqual(len(result["observations"]), 1)
        self.assertIsNotNone(result["receipt"])
        self.assertIsNone(result["verification"])
        self.assertEqual(result["trace"][-1]["kind"], "task.failed")

    def test_auth_version_and_schema_rejection_do_not_create_tasks(self) -> None:
        status, result = self._request("POST", "/v1/tasks", payload=self._payload("task-auth"), token="wrong-token")
        self.assertEqual(status, 401)
        self.assertEqual(result["error"]["code"], "unauthorized")
        status, result = self._request("POST", "/v1/tasks", payload=self._payload("task-version"), protocol="2")
        self.assertEqual(status, 426)
        self.assertEqual(result["error"]["code"], "unsupported_protocol_version")
        status, result = self._request("POST", "/v1/tasks", payload=b"not-json")
        self.assertEqual(status, 400)
        self.assertEqual(result["error"]["code"], "malformed_json")
        invalid = self._payload("task-schema")
        del invalid["goal"]
        status, result = self._request("POST", "/v1/tasks", payload=invalid)
        self.assertEqual(status, 400)
        self.assertEqual(result["error"]["code"], "invalid_schema")
        status, result = self._request("POST", "/v1/tasks", payload={**self._payload("task-device"), "device_id": "other-device"})
        self.assertEqual(status, 403)
        self.assertEqual(result["error"]["code"], "device_identity_mismatch")
        self.assertIsNone(self.service.status("task-auth"))
        self.assertIsNone(self.service.status("task-version"))
        self.assertIsNone(self.service.status("task-schema"))
        self.assertIsNone(self.service.status("task-device"))
        self.assertEqual(self.device.action_count, 0)

    def test_device_transport_rejects_wrong_identity_and_version(self) -> None:
        device_url = f"http://127.0.0.1:{self.device_server.server_address[1]}/v1/simulated/observations?task_id=task-direct"
        for device_id, protocol, expected_status, expected_code in (
            ("wrong-device", "1", 403, "device_identity_mismatch"),
            ("sim-device-01", "2", 426, "unsupported_protocol_version"),
        ):
            request = Request(
                device_url,
                headers={
                    "Authorization": f"Bearer {self.token}",
                    "X-JEV-Device-Id": device_id,
                    "X-JEV-Protocol-Version": protocol,
                },
                method="GET",
            )
            with self.assertRaises(HTTPError) as raised:
                urlopen(request, timeout=3)
            self.assertEqual(raised.exception.code, expected_status)
            result = json.loads(raised.exception.read().decode("utf-8"))
            self.assertEqual(result["error"]["code"], expected_code)
        self.assertEqual(self.device.action_count, 0)

    def test_one_active_task_rejects_second_submission(self) -> None:
        self.tearDown()
        self.device = SimulatedDevice(
            device_id="sim-device-01",
            token=self.token,
            behavior="apply_effect",
            clock=FixedClock(),
            action_delay=0.25,
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
        first_result = []

        def submit_first() -> None:
            first_result.append(self._request("POST", "/v1/tasks", payload=self._payload("task-first")))

        thread = threading.Thread(target=submit_first)
        thread.start()
        for _ in range(100):
            if self.service.active_task_id == "task-first":
                break
            time.sleep(0.005)
        self.assertEqual(self.service.active_task_id, "task-first")
        status, result = self._request("POST", "/v1/tasks", payload=self._payload("task-second"))
        self.assertEqual(status, 409)
        self.assertEqual(result["error"]["code"], "task_active")
        thread.join(timeout=3)
        self.assertEqual(first_result[0][0], 200)
        self.assertEqual(first_result[0][1]["state"], "SUCCEEDED")
        self.assertEqual(self.device.action_count, 1)

    def test_fixed_clock_and_identifiers_make_repeatable_behavior(self) -> None:
        first = self._request("POST", "/v1/tasks", payload=self._payload("task-repeat"))[1]
        self.tearDown()
        self.setUp()
        second = self._request("POST", "/v1/tasks", payload=self._payload("task-repeat"))[1]
        self.assertEqual(first, second)


class ContractFixtureTests(unittest.TestCase):
    def test_all_versioned_fixtures_have_expected_result(self) -> None:
        self.assertEqual(check_fixtures(), (6, 3))


if __name__ == "__main__":
    unittest.main()
