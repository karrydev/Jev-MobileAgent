from __future__ import annotations

import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from services.sim_loop.device import SimulatedDevice, create_device_server
from services.sim_loop.service import SimulationService, create_service_server


class RecoveryHTTPTests(unittest.TestCase):
    token = "recovery-token"

    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.service_server = None
        self.device_server = None
        self.service_thread = None
        self.device_thread = None

    def tearDown(self) -> None:
        self._stop_servers()
        self.tempdir.cleanup()

    def _start(
        self,
        *,
        fault_point: str | None = None,
        persist: bool = False,
        device_timeout: float = 1.0,
        action_delay: float = 0.0,
    ) -> None:
        root = Path(self.tempdir.name)
        self.device = SimulatedDevice(
            device_id="sim-device-01",
            token=self.token,
            state_path=root / "device.json" if persist else None,
            action_delay=action_delay,
        )
        self.device_server = create_device_server(self.device)
        self.device_thread = threading.Thread(target=self.device_server.serve_forever, daemon=True)
        self.device_thread.start()
        self.service = SimulationService(
            device_base_url=f"http://127.0.0.1:{self.device_server.server_address[1]}",
            token=self.token,
            device_id="sim-device-01",
            device_timeout=device_timeout,
            state_path=root / "service.json" if persist else None,
        )
        self.service_server = create_service_server(self.service)
        self.service_thread = threading.Thread(target=self.service_server.serve_forever, daemon=True)
        self.service_thread.start()
        self.fault_point = fault_point

    def _stop_servers(self) -> None:
        if self.service_server is not None:
            self.service_server.shutdown()
            self.service_server.server_close()
        if self.device_server is not None:
            self.device_server.shutdown()
            self.device_server.server_close()
        if self.service_thread is not None:
            self.service_thread.join(timeout=2)
        if self.device_thread is not None:
            self.device_thread.join(timeout=2)
        self.service_server = None
        self.device_server = None

    def _start_eligible_recovery(self, task_id: str = "resume-validation") -> dict:
        self._start(fault_point="before_dispatch")
        status, paused = self._request("POST", "/v1/tasks", self._payload(task_id, "before_dispatch"))
        self.assertEqual(status, 200)
        self.assertEqual(paused["state"], "PAUSED")
        status, reconciled = self._request("POST", f"/v1/tasks/{task_id}/reconcile", {})
        self.assertEqual(status, 200)
        self.assertEqual(reconciled["reconciliation"]["action_outcome"], "NOT_EXECUTED")
        self.assertTrue(reconciled["recovery"]["eligible"])
        return reconciled["recovery"]

    def _replace_device_without_history(self) -> tuple[SimulatedDevice, SimulatedDevice]:
        old_device = self.device
        old_server = self.device_server
        old_thread = self.device_thread
        old_server.shutdown()
        old_server.server_close()
        old_thread.join(timeout=2)

        self.device = SimulatedDevice(device_id="sim-device-01", token=self.token)
        self.device_server = create_device_server(self.device)
        self.device_thread = threading.Thread(target=self.device_server.serve_forever, daemon=True)
        self.device_thread.start()
        self.service.device_client.base_url = f"http://127.0.0.1:{self.device_server.server_address[1]}"
        return old_device, self.device

    def _request(self, method: str, path: str, payload: dict | None = None):
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.token}",
            "X-JEV-Protocol-Version": "1",
        }
        body = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(payload).encode("utf-8")
        request = Request(
            f"http://127.0.0.1:{self.service_server.server_address[1]}{path}",
            data=body,
            headers=headers,
            method=method,
        )
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except HTTPError as exc:
            return exc.code, json.loads(exc.read().decode("utf-8"))
        except (URLError, TimeoutError, OSError) as exc:
            return 599, {"error": {"code": "client_transport", "message": str(exc)}}

    @staticmethod
    def _payload(task_id: str, fault_point: str | None = None) -> dict:
        payload = {
            "schema_version": "1.0",
            "task_id": task_id,
            "device_id": "sim-device-01",
            "mode": "simulated",
            "goal": "advance_to_done",
            "scenario": "apply_effect",
        }
        if fault_point is not None:
            payload["fault_injection"] = {"point": fault_point}
        return payload

    def test_three_fault_boundaries_reconcile_and_resume_without_replay(self) -> None:
        for index, (point, expected_outcome, expected_initial_count, expected_receipt) in enumerate(
            (
                ("before_dispatch", "NOT_EXECUTED", 0, False),
                ("after_execute_before_receipt", "EXECUTED", 1, False),
                ("after_receipt_before_verification", "EXECUTED", 1, True),
            )
        ):
            with self.subTest(point=point):
                self._start(fault_point=point)
                task_id = f"recovery-boundary-{index}"
                status, paused = self._request("POST", "/v1/tasks", self._payload(task_id, point))
                self.assertEqual(status, 200)
                self.assertEqual(paused["state"], "PAUSED")
                self.assertEqual(self.device.action_count, expected_initial_count)
                self.assertEqual(bool(paused["receipt"]), expected_receipt)

                status, reconnected = self._request("POST", f"/v1/tasks/{task_id}/reconnect", {})
                self.assertEqual(status, 200)
                self.assertEqual(reconnected["state"], "PAUSED")
                status, no_confirmation = self._request("POST", f"/v1/tasks/{task_id}/resume", {})
                self.assertEqual(status, 400)
                self.assertEqual(no_confirmation["error"]["code"], "explicit_confirmation_required")

                status, reconciled = self._request("POST", f"/v1/tasks/{task_id}/reconcile", {})
                self.assertEqual(status, 200)
                self.assertEqual(reconciled["state"], "PAUSED")
                self.assertEqual(reconciled["reconciliation"]["action_outcome"], expected_outcome)
                self.assertTrue(reconciled["recovery"]["eligible"])
                recovery = reconciled["recovery"]

                status, stale = self._request(
                    "POST",
                    f"/v1/tasks/{task_id}/resume",
                    {"confirmed": True, "resume_token": "old-token", "observation_version": recovery["observation_version"]},
                )
                self.assertEqual(status, 409)
                self.assertEqual(stale["error"]["code"], "resume_confirmation_mismatch")
                status, resumed = self._request(
                    "POST",
                    f"/v1/tasks/{task_id}/resume",
                    {
                        "confirmed": True,
                        "resume_token": recovery["resume_token"],
                        "observation_version": recovery["observation_version"],
                    },
                )
                self.assertEqual(status, 200)
                self.assertEqual(resumed["state"], "SUCCEEDED")
                self.assertEqual(resumed["verification"]["status"], "SUCCESS")
                self.assertEqual(self.device.action_count, 1)
                self._stop_servers()

    def test_persistent_service_and_device_state_survive_new_instances(self) -> None:
        self._start(fault_point="after_execute_before_receipt", persist=True)
        payload = self._payload("persistent-recovery", "after_execute_before_receipt")
        status, paused = self._request("POST", "/v1/tasks", payload)
        self.assertEqual(status, 200)
        self.assertEqual(paused["state"], "PAUSED")
        self.assertEqual(self.device.action_count, 1)
        self._stop_servers()

        root = Path(self.tempdir.name)
        self.device = SimulatedDevice(device_id="sim-device-01", token=self.token, state_path=root / "device.json")
        self.device_server = create_device_server(self.device)
        self.device_thread = threading.Thread(target=self.device_server.serve_forever, daemon=True)
        self.device_thread.start()
        self.service = SimulationService(
            device_base_url=f"http://127.0.0.1:{self.device_server.server_address[1]}",
            token=self.token,
            device_id="sim-device-01",
            state_path=root / "service.json",
        )
        self.assertEqual(self.service.status("persistent-recovery")["state"], "PAUSED")
        self.assertEqual(self.service.status("persistent-recovery")["recovery"]["phase"], "RECONNECT_REQUIRED")
        self.service_server = create_service_server(self.service)
        self.service_thread = threading.Thread(target=self.service_server.serve_forever, daemon=True)
        self.service_thread.start()
        status, reconciled = self._request("POST", "/v1/tasks/persistent-recovery/reconnect", {})
        self.assertEqual(status, 200)
        self.assertEqual(reconciled["state"], "PAUSED")
        status, reconciled = self._request("POST", "/v1/tasks/persistent-recovery/reconcile", {})
        self.assertEqual(status, 200)
        self.assertEqual(reconciled["reconciliation"]["action_outcome"], "EXECUTED")
        recovery = reconciled["recovery"]
        status, resumed = self._request(
            "POST",
            "/v1/tasks/persistent-recovery/resume",
            {"confirmed": True, "resume_token": recovery["resume_token"], "observation_version": recovery["observation_version"]},
        )
        self.assertEqual(status, 200)
        self.assertEqual(resumed["state"], "SUCCEEDED")
        self.assertEqual(self.device.action_count, 1)

    def test_persisted_receipt_prevents_replay_after_device_restart(self) -> None:
        self._start(fault_point="after_receipt_before_verification", persist=True)
        task_id = "receipt-device-restart"
        status, paused = self._request("POST", "/v1/tasks", self._payload(task_id, "after_receipt_before_verification"))
        self.assertEqual(status, 200)
        self.assertEqual(paused["state"], "PAUSED")
        self.assertIsNotNone(paused["receipt"])
        self.assertEqual(self.device.action_count, 1)
        self._stop_servers()

        root = Path(self.tempdir.name)
        self.device = SimulatedDevice(device_id="sim-device-01", token=self.token)
        self.device_server = create_device_server(self.device)
        self.device_thread = threading.Thread(target=self.device_server.serve_forever, daemon=True)
        self.device_thread.start()
        self.service = SimulationService(
            device_base_url=f"http://127.0.0.1:{self.device_server.server_address[1]}",
            token=self.token,
            device_id="sim-device-01",
            state_path=root / "service.json",
        )
        self.service_server = create_service_server(self.service)
        self.service_thread = threading.Thread(target=self.service_server.serve_forever, daemon=True)
        self.service_thread.start()

        status, reconnected = self._request("POST", f"/v1/tasks/{task_id}/reconnect", {})
        self.assertEqual(status, 200)
        self.assertEqual(reconnected["state"], "PAUSED")
        status, reconciled = self._request("POST", f"/v1/tasks/{task_id}/reconcile", {})
        self.assertEqual(status, 200)
        self.assertEqual(reconciled["reconciliation"]["action_outcome"], "EXECUTED")
        recovery = reconciled["recovery"]
        status, resumed = self._request(
            "POST",
            f"/v1/tasks/{task_id}/resume",
            {
                "confirmed": True,
                "resume_token": recovery["resume_token"],
                "observation_version": recovery["observation_version"],
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(resumed["state"], "FAILED")
        self.assertEqual(self.device.action_count, 0)

    def test_unknown_reconciliation_stays_paused_and_does_not_replay(self) -> None:
        self._start(fault_point="after_execute_before_receipt", device_timeout=0.05)
        status, paused = self._request("POST", "/v1/tasks", self._payload("unknown-recovery", "after_execute_before_receipt"))
        self.assertEqual(status, 200)
        self.assertEqual(paused["state"], "PAUSED")
        self.device_server.shutdown()
        self.device_server.server_close()
        self.device_thread.join(timeout=2)
        status, reconciled = self._request("POST", "/v1/tasks/unknown-recovery/reconcile", {})
        self.assertEqual(status, 200)
        self.assertEqual(reconciled["state"], "PAUSED")
        self.assertEqual(reconciled["reconciliation"]["action_outcome"], "UNKNOWN")
        self.assertFalse(reconciled["recovery"]["eligible"])
        status, rejected = self._request("POST", "/v1/tasks/unknown-recovery/resume", {"confirmed": True})
        self.assertEqual(status, 409)
        self.assertEqual(rejected["error"]["code"], "recovery_not_eligible")
        self.assertEqual(self.service.active_task_id, "unknown-recovery")
        self.assertEqual(self.device.action_count, 1)

    def test_changed_scene_invalidates_reconciled_confirmation(self) -> None:
        self._start(fault_point="before_dispatch")
        status, _ = self._request("POST", "/v1/tasks", self._payload("changed-scene", "before_dispatch"))
        self.assertEqual(status, 200)
        status, reconciled = self._request("POST", "/v1/tasks/changed-scene/reconcile", {})
        self.assertEqual(status, 200)
        recovery = reconciled["recovery"]
        self.device.page_state = "done"
        status, rejected = self._request(
            "POST",
            "/v1/tasks/changed-scene/resume",
            {"confirmed": True, "resume_token": recovery["resume_token"], "observation_version": recovery["observation_version"]},
        )
        self.assertEqual(status, 409)
        self.assertEqual(rejected["error"]["code"], "recovery_observation_stale")
        self.assertEqual(self.service.status("changed-scene")["state"], "PAUSED")
        self.assertEqual(self.device.action_count, 0)

    def test_cancelled_unknown_action_is_reconciled_without_resurrection(self) -> None:
        self._start(fault_point="after_execute_before_receipt")
        status, paused = self._request("POST", "/v1/tasks", self._payload("cancelled-recovery", "after_execute_before_receipt"))
        self.assertEqual(status, 200)
        self.assertTrue(paused["action_result_unknown"])
        status, cancelled = self._request("POST", "/v1/tasks/cancelled-recovery/cancel", {})
        self.assertEqual(status, 200)
        self.assertEqual(cancelled["state"], "CANCELLED")
        self.assertEqual(self.service.active_task_id, "cancelled-recovery")
        status, reconciled = self._request("POST", "/v1/tasks/cancelled-recovery/reconcile", {})
        self.assertEqual(status, 200)
        self.assertEqual(reconciled["state"], "CANCELLED")
        self.assertEqual(reconciled["reconciliation"]["action_outcome"], "EXECUTED")
        self.assertFalse(reconciled["action_result_unknown"])
        self.assertIsNone(self.service.active_task_id)
        status, rejected = self._request("POST", "/v1/tasks/cancelled-recovery/resume", {})
        self.assertEqual(status, 409)
        self.assertEqual(rejected["error"]["code"], "task_terminal")
        self.assertEqual(self.device.action_count, 1)

    def test_late_response_requires_reconciliation_and_explicit_confirmation(self) -> None:
        self._start(action_delay=0.20)
        task_id = "late-response"
        result: list[dict] = []

        def submit() -> None:
            result.append(self.service.submit(self._payload(task_id)))

        submit_thread = threading.Thread(target=submit)
        submit_thread.start()
        deadline = time.monotonic() + 2.0
        while self.service.active_action_id is None and time.monotonic() < deadline:
            time.sleep(0.005)
        self.assertEqual(self.service.active_action_id, f"action-{task_id}")

        status, reconnected = self._request("POST", f"/v1/tasks/{task_id}/reconnect", {})
        self.assertEqual(status, 200)
        self.assertEqual(reconnected["state"], "PAUSED")
        status, early = self._request("POST", f"/v1/tasks/{task_id}/reconcile", {})
        self.assertEqual(status, 200)
        self.assertEqual(early["reconciliation"]["action_outcome"], "UNKNOWN")
        self.assertFalse(early["recovery"]["eligible"])

        submit_thread.join(timeout=3)
        self.assertFalse(submit_thread.is_alive())
        self.assertEqual(result[0]["state"], "PAUSED")
        status, late = self._request("POST", f"/v1/tasks/{task_id}/reconcile", {})
        self.assertEqual(status, 200)
        self.assertEqual(late["reconciliation"]["action_outcome"], "UNKNOWN")
        self.assertFalse(late["recovery"]["eligible"])

        status, stable = self._request("POST", f"/v1/tasks/{task_id}/reconcile", {})
        self.assertEqual(status, 200)
        self.assertEqual(stable["reconciliation"]["action_outcome"], "EXECUTED")
        recovery = stable["recovery"]
        status, resumed = self._request(
            "POST",
            f"/v1/tasks/{task_id}/resume",
            {
                "confirmed": True,
                "resume_token": recovery["resume_token"],
                "observation_version": recovery["observation_version"],
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(resumed["state"], "SUCCEEDED")
        self.assertEqual(self.device.action_count, 1)

    def test_resume_rejects_boolean_like_confirmation_values(self) -> None:
        recovery = self._start_eligible_recovery("resume-confirmed-type")
        for value in (False, "false", 0, None):
            with self.subTest(value=value):
                status, rejected = self._request(
                    "POST",
                    "/v1/tasks/resume-confirmed-type/resume",
                    {
                        "confirmed": value,
                        "resume_token": recovery["resume_token"],
                        "observation_version": recovery["observation_version"],
                    },
                )
                self.assertEqual(status, 400)
                self.assertEqual(rejected["error"]["code"], "explicit_confirmation_required")
        self.assertEqual(self.service.status("resume-confirmed-type")["state"], "PAUSED")
        self.assertEqual(self.device.action_count, 0)

    def test_resume_rejects_confirmation_aliases(self) -> None:
        recovery = self._start_eligible_recovery("resume-confirmation-alias")
        for payload in (
            {"confirm": True, "resume_token": recovery["resume_token"], "observation_version": recovery["observation_version"]},
            {
                "explicit_confirmation": True,
                "resume_token": recovery["resume_token"],
                "observation_version": recovery["observation_version"],
            },
            {
                "confirmation": {"confirmed": True},
                "resume_token": recovery["resume_token"],
                "observation_version": recovery["observation_version"],
            },
        ):
            with self.subTest(payload=payload):
                status, rejected = self._request("POST", "/v1/tasks/resume-confirmation-alias/resume", payload)
                self.assertEqual(status, 400)
                self.assertEqual(rejected["error"]["code"], "explicit_confirmation_required")

    def test_resume_rejects_missing_confirmation_fields(self) -> None:
        self._start_eligible_recovery("resume-confirmation-fields")
        status, rejected = self._request(
            "POST",
            "/v1/tasks/resume-confirmation-fields/resume",
            {"confirmed": True},
        )
        self.assertEqual(status, 400)
        self.assertEqual(rejected["error"]["code"], "invalid_resume_confirmation")

    def test_resume_rejects_non_string_resume_token(self) -> None:
        recovery = self._start_eligible_recovery("resume-token-type")
        for token in (None, 0, False, [recovery["resume_token"]]):
            with self.subTest(token=token):
                status, rejected = self._request(
                    "POST",
                    "/v1/tasks/resume-token-type/resume",
                    {
                        "confirmed": True,
                        "resume_token": token,
                        "observation_version": recovery["observation_version"],
                    },
                )
                self.assertEqual(status, 400)
                self.assertEqual(rejected["error"]["code"], "invalid_resume_confirmation")

    def test_resume_rejects_non_string_observation_version(self) -> None:
        recovery = self._start_eligible_recovery("resume-version-type")
        for version in (None, 0, False, [recovery["observation_version"]]):
            with self.subTest(version=version):
                status, rejected = self._request(
                    "POST",
                    "/v1/tasks/resume-version-type/resume",
                    {
                        "confirmed": True,
                        "resume_token": recovery["resume_token"],
                        "observation_version": version,
                    },
                )
                self.assertEqual(status, 400)
                self.assertEqual(rejected["error"]["code"], "invalid_resume_confirmation")

    def test_resume_rejects_unknown_confirmation_fields(self) -> None:
        recovery = self._start_eligible_recovery("resume-extra-field")
        status, rejected = self._request(
            "POST",
            "/v1/tasks/resume-extra-field/resume",
            {
                "confirmed": True,
                "resume_token": recovery["resume_token"],
                "observation_version": recovery["observation_version"],
                "observation_id": recovery["observation_id"],
            },
        )
        self.assertEqual(status, 400)
        self.assertEqual(rejected["error"]["code"], "invalid_resume_confirmation")

    def test_device_restart_without_durable_history_keeps_unknown(self) -> None:
        self._start(fault_point="after_execute_before_receipt")
        task_id = "device-only-restart"
        status, paused = self._request("POST", "/v1/tasks", self._payload(task_id, "after_execute_before_receipt"))
        self.assertEqual(status, 200)
        self.assertEqual(paused["state"], "PAUSED")
        self.assertTrue(paused["action_result_unknown"])
        old_device, new_device = self._replace_device_without_history()
        self.assertEqual(old_device.action_count, 1)

        status, reconciled = self._request("POST", f"/v1/tasks/{task_id}/reconcile", {})
        self.assertEqual(status, 200)
        self.assertEqual(reconciled["state"], "PAUSED")
        self.assertEqual(reconciled["reconciliation"]["action_outcome"], "UNKNOWN")
        self.assertFalse(reconciled["recovery"]["eligible"])
        self.assertIsNone(reconciled["recovery"]["resume_token"])
        self.assertEqual(reconciled["recovery"]["reason"], "non_durable_action_history_inconclusive")
        status, rejected = self._request(
            "POST",
            f"/v1/tasks/{task_id}/resume",
            {"confirmed": True},
        )
        self.assertEqual(status, 409)
        self.assertEqual(rejected["error"]["code"], "recovery_not_eligible")
        self.assertEqual(old_device.action_count, 1)
        self.assertEqual(new_device.action_count, 0)


if __name__ == "__main__":
    unittest.main()
