from __future__ import annotations

import copy
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from test_android_bridge import node_task_observation, screenshot_for
from test_android_vlm_loop import FakeRoleTransport, _vlm_payload

from services.android_bridge.bridge import AndroidBridge, BridgeRequestError, _android_scene_fingerprint
from services.android_bridge.schema import validate_document
from services.live_vlm.transport import ProductionVlmTransport


_JOURNALED_ACTION_RECOVERY_CAPABILITY = "durable_action_journal_then_server_intent_then_effect_v1"


def _pair_payload() -> dict:
    return {
        "schema_version": "1.0",
        "android_schema_version": "1.0",
        "task_id": "observation-session",
        "device_id": "android-emulator-01",
        "client_name": "recovery-test",
        "capabilities": ["accessibility_tree"],
    }


def _submit_payload() -> dict:
    return {
        "schema_version": "1.0",
        "android_schema_version": "1.0",
        "task_id": "observation-session",
        "device_id": "android-emulator-01",
        "goal": "点击“切换受控状态”按钮",
        "source": "recovery-test",
    }


def _pair_payload_with_journaled_dispatch() -> dict:
    payload = _pair_payload()
    payload["recovery_capabilities"] = [_JOURNALED_ACTION_RECOVERY_CAPABILITY]
    return payload


def _submit_payload_with_journaled_dispatch() -> dict:
    payload = _submit_payload()
    payload["recovery_capability"] = _JOURNALED_ACTION_RECOVERY_CAPABILITY
    return payload


def _device_receipt(action: dict) -> dict:
    return {
        "schema_version": "1.0",
        "android_schema_version": "1.0",
        "task_id": action["task_id"],
        "receipt_id": "device-receipt-" + action["action_id"],
        "action_id": action["action_id"],
        "device_id": action["device_id"],
        "accepted": True,
        "outcome": "EXECUTED",
        "received_at": "2026-09-24T00:00:00Z",
        "error_code": None,
        "error_message": None,
        "observation_id": action["observation_id"],
        "observation_version": action["observation_version"],
        "deduplicated": False,
    }


def _observation_with_recovery_controls(
    version: int,
    *,
    reconcile_text: str = "RECONCILE",
    reconcile_enabled: bool = True,
    reconcile_package: str = "com.jev.mobileagent",
    recovery_message: str = "Recovery status: local task/action history retained; reconcile before resuming",
) -> dict:
    observation = node_task_observation(version)
    root = observation["nodes"][0]
    for index, node in enumerate(
        (
            {
                "node_id": "recovery-reconcile",
                "class_name": "android.widget.Button",
                "package_name": reconcile_package,
                "text": reconcile_text,
                "content_description": "",
                "enabled": reconcile_enabled,
                "clickable": True,
                "bounds": {"left": 54, "top": 1892, "right": 534, "bottom": 2036},
            },
            {
                "node_id": "recovery-status",
                "class_name": "android.widget.TextView",
                "package_name": "com.jev.mobileagent",
                "text": recovery_message,
                "content_description": recovery_message,
                "enabled": True,
                "clickable": False,
                "bounds": {"left": 48, "top": 2042, "right": 1032, "bottom": 2141},
            },
        )
    ):
        root["child_node_ids"].append(node["node_id"])
        observation["nodes"].append(
            {
                "parent_node_id": root["node_id"],
                "state_description": "",
                "view_id_resource_name": "",
                "visible_to_user": True,
                "focusable": index == 0,
                "focused": False,
                "selected": False,
                "scrollable": False,
                "editable": False,
                "child_node_ids": [],
                **node,
            }
        )
    return observation


class AndroidRecoveryTests(unittest.TestCase):
    def test_journaled_dispatch_capability_schema_is_explicit_and_optional_for_old_clients(self) -> None:
        self.assertEqual(validate_document(_pair_payload(), "pair"), [])
        self.assertEqual(validate_document(_submit_payload(), "task_submit"), [])
        self.assertEqual(
            validate_document(_pair_payload_with_journaled_dispatch(), "pair"),
            [],
        )
        self.assertEqual(
            validate_document(_submit_payload_with_journaled_dispatch(), "task_submit"),
            [],
        )

        invalid_pair = _pair_payload_with_journaled_dispatch()
        invalid_pair["recovery_capabilities"] = ["unrecognized_dispatch_claim"]
        self.assertTrue(validate_document(invalid_pair, "pair"))
        invalid_task_capability = _submit_payload_with_journaled_dispatch()
        invalid_task_capability["recovery_capability"] = "unrecognized_dispatch_claim"
        self.assertTrue(validate_document(invalid_task_capability, "task_submit"))
        invalid_task = _submit_payload_with_journaled_dispatch()
        invalid_task["unexpected"] = True
        self.assertTrue(validate_document(invalid_task, "task_submit"))

    def test_manual_goal_completion_succeeds_without_claiming_agent_action_executed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=Path(directory) / "bridge.json",
            )
            bridge.pair(_pair_payload_with_journaled_dispatch())
            bridge.receive_observation(node_task_observation(1))
            bridge.submit_task(_submit_payload_with_journaled_dispatch())
            session_id = bridge._device_session_id
            first = bridge.reconcile_task(
                "observation-session",
                {"device_session_id": session_id, "device_record": None},
            )
            self.assertTrue(first["eligible"])
            self.assertEqual(first["action_outcome"], "NOT_EXECUTED")

            bridge.receive_observation(node_task_observation(2, completed=True))
            recovery = bridge.reconcile_task(
                "observation-session",
                {"device_session_id": session_id, "device_record": None},
            )
            task, confirmation = bridge.resume_task(
                "observation-session",
                {
                    "confirmed": True,
                    "resume_token": recovery["resume_token"],
                    "observation_version": recovery["observation_version"],
                    "device_session_id": session_id,
                    "model": None,
                },
            )

            self.assertEqual(task["state"], "SUCCEEDED")
            self.assertEqual(task["phase"], "SUCCEEDED")
            self.assertTrue(task["independent_result"]["success"])
            self.assertEqual(task["actual_effect"]["status"], "NOT_EXECUTED")
            self.assertEqual(
                task["actual_effect"]["reason"],
                "goal_already_complete_without_agent_action",
            )
            self.assertNotIn("receipt_id", task["actual_effect"])
            self.assertEqual(confirmation["phase"], "RESUMED")
            self.assertTrue(confirmation["confirmed"])
            self.assertEqual(confirmation["reason"], "goal_already_confirmed_by_current_observation")
            self.assertEqual(confirmation["task_id"], "observation-session")
            self.assertEqual(confirmation["device_session_id"], session_id)

    def test_new_client_can_resume_only_when_no_intent_and_no_device_record_exist(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state_file = Path(directory) / "bridge.json"
            original = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=state_file,
            )
            original.pair(_pair_payload_with_journaled_dispatch())
            original.receive_observation(node_task_observation(1))
            submitted = original.submit_task(_submit_payload_with_journaled_dispatch())
            action = submitted["next_action"]
            self.assertEqual(
                original._tasks["observation-session"]["recovery_capability"],
                _JOURNALED_ACTION_RECOVERY_CAPABILITY,
            )

            # The capability is bound to the durable task at creation, so a
            # later server process can still prove the current App's ordering.
            restarted = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=state_file,
            )
            pair = restarted.pair(_pair_payload_with_journaled_dispatch())
            restarted.receive_observation(node_task_observation(1))
            recovery = restarted.reconcile_task(
                "observation-session",
                {"device_session_id": pair["device_session_id"], "device_record": None},
            )
            self.assertEqual(recovery["action_id"], action["action_id"])
            self.assertEqual(recovery["action_outcome"], "NOT_EXECUTED")
            self.assertEqual(
                recovery["reason"],
                "durable_client_order_proves_action_was_not_dispatched",
            )
            self.assertTrue(recovery["eligible"])

            resumed, confirmation = restarted.resume_task(
                "observation-session",
                {
                    "confirmed": True,
                    "resume_token": recovery["resume_token"],
                    "observation_version": recovery["observation_version"],
                    "device_session_id": pair["device_session_id"],
                    "model": None,
                },
            )
            self.assertEqual(resumed["state"], "RUNNING")
            self.assertEqual(confirmation["phase"], "RESUMED")
            self.assertTrue(confirmation["confirmed"])
            self.assertNotEqual(resumed["next_action"]["action_id"], action["action_id"])

    def test_vlm_task_persists_the_paired_recovery_capability_at_creation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=Path(directory) / "bridge.json",
            )
            bridge.pair(_pair_payload_with_journaled_dispatch())
            before = node_task_observation(1)
            bridge.receive_observation(before)
            bridge.receive_screenshot(screenshot_for(before, screenshot_id="before-1", capture_type="BEFORE"))
            payload = _vlm_payload()
            payload["recovery_capability"] = _JOURNALED_ACTION_RECOVERY_CAPABILITY
            with patch.object(bridge, "_run_vlm_task", return_value=None):
                bridge.submit_vlm_task(payload)

            saved = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=Path(directory) / "bridge.json",
            )
            self.assertEqual(
                saved._tasks["observation-session"]["recovery_capability"],
                _JOURNALED_ACTION_RECOVERY_CAPABILITY,
            )

    def test_unproven_or_ambiguous_dispatch_history_stays_unknown(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=Path(directory) / "bridge.json",
            )
            bridge.pair(_pair_payload())
            bridge.receive_observation(node_task_observation(1))
            with self.assertRaises(BridgeRequestError) as unpaired_claim:
                bridge.submit_task(_submit_payload_with_journaled_dispatch())
            self.assertEqual(unpaired_claim.exception.code, "recovery_capability_not_paired")

            submitted = bridge.submit_task(_submit_payload())
            action = submitted["next_action"]
            pair_session = bridge._device_session_id
            legacy = bridge.reconcile_task(
                "observation-session",
                {"device_session_id": pair_session, "device_record": None},
            )
            self.assertEqual(legacy["action_outcome"], "UNKNOWN")
            self.assertFalse(legacy["eligible"])

        with tempfile.TemporaryDirectory() as directory:
            bridge = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=Path(directory) / "bridge.json",
            )
            # Pair advertisement alone does not authorize inference; the
            # capability must be copied into this specific task at creation.
            bridge.pair(_pair_payload_with_journaled_dispatch())
            bridge.receive_observation(node_task_observation(1))
            bridge.submit_task(_submit_payload())
            omitted_task_claim = bridge.reconcile_task(
                "observation-session",
                {"device_session_id": bridge._device_session_id, "device_record": None},
            )
            self.assertEqual(omitted_task_claim["action_outcome"], "UNKNOWN")
            self.assertFalse(omitted_task_claim["eligible"])

        with tempfile.TemporaryDirectory() as directory:
            bridge = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=Path(directory) / "bridge.json",
            )
            bridge.pair(_pair_payload_with_journaled_dispatch())
            bridge.receive_observation(node_task_observation(1))
            submitted = bridge.submit_task(_submit_payload_with_journaled_dispatch())
            action = submitted["next_action"]
            session_id = bridge._device_session_id
            bridge.record_command_intent(
                "observation-session",
                {"action_id": action["action_id"], "device_session_id": session_id},
            )
            granted_intent = bridge.reconcile_task(
                "observation-session",
                {"device_session_id": session_id, "device_record": None},
            )
            self.assertEqual(granted_intent["action_outcome"], "UNKNOWN")
            self.assertFalse(granted_intent["eligible"])

        with tempfile.TemporaryDirectory() as directory:
            bridge = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=Path(directory) / "bridge.json",
            )
            bridge.pair(_pair_payload_with_journaled_dispatch())
            bridge.receive_observation(node_task_observation(1))
            submitted = bridge.submit_task(_submit_payload_with_journaled_dispatch())
            action = submitted["next_action"]
            pending = bridge.reconcile_task(
                "observation-session",
                {
                    "device_session_id": bridge._device_session_id,
                    "device_record": {"action_id": action["action_id"], "phase": "PENDING", "receipt": None},
                },
            )
            self.assertEqual(pending["action_outcome"], "UNKNOWN")
            self.assertFalse(pending["eligible"])

    def test_late_model_generation_cannot_dispatch_after_explicit_resume(self) -> None:
        entered = threading.Event()
        release = threading.Event()

        class BlockingTransport(FakeRoleTransport):
            first_manager = True
            gate_lock = threading.Lock()

            def predict(self, **kwargs):
                if kwargs["role"] == "manager":
                    with self.gate_lock:
                        should_block = self.first_manager
                        if should_block:
                            self.first_manager = False
                    if should_block:
                        entered.set()
                        if not release.wait(timeout=3):
                            raise TimeoutError("test model release timed out")
                return super().predict(**kwargs)

            def summary(self):
                # The fake transport is an offline zero-cost replay.
                return {"requests": 0, "estimated_cost_cny": 0.0, "usage_missing": False, "attempts": []}

        with tempfile.TemporaryDirectory() as directory:
            bridge = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=Path(directory) / "bridge.json",
            )
            bridge.pair(_pair_payload())
            before = node_task_observation(1)
            bridge.receive_observation(before)
            bridge.receive_screenshot(screenshot_for(before, screenshot_id="before-1", capture_type="BEFORE"))

            try:
                with patch("services.live_vlm.transport.ProductionVlmTransport", BlockingTransport):
                    bridge.submit_vlm_task(_vlm_payload())
                    self.assertTrue(entered.wait(timeout=2), "old model generation did not start")
                    bridge.control_task("observation-session", "pause", "test_pause")
                    paired = bridge._device_session_id
                    recovery = bridge.reconcile_task(
                        "observation-session",
                        {"device_session_id": paired, "device_record": None},
                    )
                    self.assertTrue(recovery["eligible"])
                    resumed, _ = bridge.resume_task(
                        "observation-session",
                        {
                            "confirmed": True,
                            "resume_token": recovery["resume_token"],
                            "observation_version": recovery["observation_version"],
                            "device_session_id": paired,
                            "model": _vlm_payload()["model"],
                        },
                    )
                    self.assertEqual(resumed["state"], "RUNNING")
                release.set()
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    with bridge._lock:
                        dispatched = [item for item in bridge._tasks["observation-session"]["trace"] if item["kind"] == "action.dispatched"]
                    if dispatched:
                        break
                    time.sleep(0.01)
                time.sleep(0.05)
                with bridge._lock:
                    task = bridge._tasks["observation-session"]
                    dispatched = [item for item in task["trace"] if item["kind"] == "action.dispatched"]
                    self.assertEqual(task["run_generation"], 4)
                    self.assertEqual(len(dispatched), 1)
                    self.assertEqual(task["state"], "RUNNING")
                bridge.control_task("observation-session", "pause", "test_cleanup")
            finally:
                release.set()

    def test_resume_hides_previous_vlm_action_until_fresh_generation_dispatches(self) -> None:
        resume_manager_entered = threading.Event()
        release_resume_manager = threading.Event()

        class BlockingResumeTransport(FakeRoleTransport):
            instance_count = 0
            instance_lock = threading.Lock()

            def __init__(self, **kwargs):
                super().__init__(**kwargs)
                with self.instance_lock:
                    type(self).instance_count += 1
                    self.block_manager = type(self).instance_count == 2

            def predict(self, **kwargs):
                if self.block_manager and kwargs["role"] == "manager":
                    resume_manager_entered.set()
                    if not release_resume_manager.wait(timeout=3):
                        raise TimeoutError("test resume manager release timed out")
                return super().predict(**kwargs)

            def summary(self):
                return {"requests": 0, "estimated_cost_cny": 0.0, "usage_missing": False, "attempts": []}

        with tempfile.TemporaryDirectory() as directory:
            state_file = Path(directory) / "bridge.json"
            original = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=state_file,
            )
            original.pair(_pair_payload_with_journaled_dispatch())
            before = node_task_observation(1)
            original.receive_observation(before)
            original.receive_screenshot(screenshot_for(before, screenshot_id="before-1", capture_type="BEFORE"))
            payload = _vlm_payload()
            payload["recovery_capability"] = _JOURNALED_ACTION_RECOVERY_CAPABILITY

            resumed_bridge = None
            try:
                with patch("services.live_vlm.transport.ProductionVlmTransport", BlockingResumeTransport):
                    original.submit_vlm_task(payload)
                    old_action = None
                    deadline = time.monotonic() + 2
                    while time.monotonic() < deadline:
                        with original._condition:
                            task = original._tasks["observation-session"]
                            if task.get("action") is not None and task.get("phase") == "WAITING_RECEIPT":
                                old_action = task["action"].copy()
                                self.assertFalse(task["command_delivered"])
                                self.assertIsNone(task["command_intent"])
                                break
                        time.sleep(0.01)
                    self.assertIsNotNone(old_action, "initial VLM generation did not create its action")

                    original.control_task(
                        "observation-session",
                        "pause",
                        "interrupted_before_command_delivery",
                        session_id=original._device_session_id,
                    )

                    resumed_bridge = AndroidBridge(
                        token="bridge-token",
                        device_id="android-emulator-01",
                        freshness_seconds=30,
                        state_path=state_file,
                    )
                    pair = resumed_bridge.pair(_pair_payload_with_journaled_dispatch())
                    fresh_before = node_task_observation(2)
                    resumed_bridge.receive_observation(fresh_before)
                    resumed_bridge.receive_screenshot(
                        screenshot_for(fresh_before, screenshot_id="before-2", capture_type="BEFORE")
                    )
                    recovery = resumed_bridge.reconcile_task(
                        "observation-session",
                        {"device_session_id": pair["device_session_id"], "device_record": None},
                    )
                    self.assertEqual(recovery["action_outcome"], "NOT_EXECUTED")
                    self.assertTrue(recovery["eligible"])

                    resumed, _ = resumed_bridge.resume_task(
                        "observation-session",
                        {
                            "confirmed": True,
                            "resume_token": recovery["resume_token"],
                            "observation_version": recovery["observation_version"],
                            "device_session_id": pair["device_session_id"],
                            "model": payload["model"],
                        },
                    )
                    self.assertEqual(resumed["state"], "RUNNING")
                    self.assertIsNone(resumed["action"])
                    self.assertIsNone(resumed["next_action"])
                    self.assertTrue(resume_manager_entered.wait(timeout=2), "resumed VLM manager did not start")

                    held_status = resumed_bridge.task_status(
                        "observation-session", session_id=pair["device_session_id"]
                    )
                    self.assertEqual(held_status["state"], "RUNNING")
                    self.assertIsNone(held_status["action"])
                    self.assertIsNone(held_status["next_action"])
                    self.assertFalse(resumed_bridge._tasks["observation-session"]["command_delivered"])

                    with self.assertRaises(BridgeRequestError) as stale_intent:
                        resumed_bridge.record_command_intent(
                            "observation-session",
                            {
                                "action_id": old_action["action_id"],
                                "device_session_id": pair["device_session_id"],
                            },
                        )
                    self.assertEqual(stale_intent.exception.code, "action_mismatch")
                    with self.assertRaises(BridgeRequestError) as stale_receipt:
                        resumed_bridge.receive_receipt(
                            "observation-session",
                            _device_receipt(old_action),
                            session_id=pair["device_session_id"],
                        )
                    self.assertEqual(stale_receipt.exception.code, "action_mismatch")
                    self.assertIsNone(resumed_bridge._tasks["observation-session"]["command_intent"])
                    self.assertIsNone(resumed_bridge._tasks["observation-session"]["receipt"])

                    release_resume_manager.set()
                    fresh_action = None
                    deadline = time.monotonic() + 2
                    while time.monotonic() < deadline:
                        status = resumed_bridge.task_status(
                            "observation-session", session_id=pair["device_session_id"]
                        )
                        fresh_action = status.get("next_action")
                        if fresh_action is not None:
                            break
                        time.sleep(0.01)
                    self.assertIsNotNone(fresh_action, "resumed VLM generation did not publish a fresh action")
                    self.assertNotEqual(fresh_action["action_id"], old_action["action_id"])
                    self.assertEqual(fresh_action["observation_id"], fresh_before["observation_id"])
                    self.assertEqual(fresh_action["observation_version"], fresh_before["observation_version"])
                    self.assertEqual(
                        resumed_bridge._tasks["observation-session"]["action_generation"],
                        resumed_bridge._tasks["observation-session"]["run_generation"],
                    )
                    resumed_bridge.control_task(
                        "observation-session", "cancel", session_id=pair["device_session_id"]
                    )
            finally:
                release_resume_manager.set()
                if resumed_bridge is not None:
                    try:
                        resumed_bridge.control_task(
                            "observation-session",
                            "cancel",
                            session_id=resumed_bridge._device_session_id,
                        )
                    except BridgeRequestError:
                        pass

    def test_settled_model_usage_accumulates_across_restart_without_budget_reset(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state_file = Path(directory) / "bridge.json"
            bridge = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=state_file,
            )
            bridge._tasks["observation-session"] = {
                "task_id": "observation-session",
                "device_id": "android-emulator-01",
                "goal": "点击“切换受控状态”按钮",
                "mode": "vlm",
                "state": "RUNNING",
                "phase": "WAITING_MODEL",
                "action": None,
                "receipt": None,
                "last_receipt": None,
                "usage": None,
                "usage_reservations": [],
                "usage_history_unknown": False,
                "run_generation": 1,
                "step_budget_used": 0,
                "vlm_limits": {
                    "max_steps": 5,
                    "max_requests": 2,
                    "max_tokens": 1024,
                    "budget_cny": 1.0,
                },
                "vlm_resume_config": {
                    "provider": "GUI-Plus",
                    "endpoint": "https://example.test/v1/chat/completions",
                    "model": "gui-plus-2026-02-26",
                    "max_steps": 5,
                    "max_requests": 2,
                    "max_tokens": 1024,
                    "budget_cny": 1.0,
                    "timeout_seconds": 30.0,
                },
                "trace": [],
            }
            bridge._active_task_id = "observation-session"
            bridge._persist_locked()
            config = {
                "provider": "GUI-Plus",
                "endpoint": "https://example.test/v1/chat/completions",
                "model": "gui-plus-2026-02-26",
                "api_key": "private-test-key",
                "max_steps": 5,
                "max_requests": 2,
                "max_tokens": 1024,
                "budget_cny": 1.0,
                "timeout_seconds": 30.0,
            }
            response = (
                200,
                {
                    "choices": [{"message": {"content": "offline replay"}, "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 100, "completion_tokens": 10},
                },
                "",
                None,
            )
            with patch.object(ProductionVlmTransport, "_post_json", return_value=response):
                transport = bridge._checkpointed_vlm_transport(
                    "observation-session", 1, config, ProductionVlmTransport
                )
                transport.predict(role="manager", prompt="first", images=[], step=1)
                transport.predict(role="executor", prompt="second", images=[], step=1)

            saved = bridge._tasks["observation-session"]["usage"]
            self.assertEqual(saved["requests"], 2)
            self.assertAlmostEqual(saved["estimated_cost_cny"], 0.00039, places=9)
            self.assertFalse(saved["usage_missing"])
            self.assertNotIn("private-test-key", state_file.read_text())

            restarted = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=state_file,
            )
            pair = restarted.pair(_pair_payload())
            restarted.receive_observation(node_task_observation(1))
            recovery = restarted.reconcile_task(
                "observation-session",
                {"device_session_id": pair["device_session_id"], "device_record": None},
            )
            self.assertEqual(recovery["usage"]["requests"], 2)
            self.assertAlmostEqual(recovery["usage"]["estimated_cost_cny"], 0.00039, places=9)
            self.assertFalse(recovery["eligible"])
            self.assertEqual(recovery["reason"], "model_task_budget_exhausted")

    def test_persisted_receipt_before_verification_stays_paused_and_effect_unknown(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state_file = Path(directory) / "bridge.json"
            original = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=state_file,
            )
            original.pair(_pair_payload())
            original.receive_observation(node_task_observation(1))
            submitted = original.submit_task(_submit_payload())
            action = submitted["next_action"]
            original.receive_receipt("observation-session", _device_receipt(action))

            restarted = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=state_file,
            )
            pair = restarted.pair(_pair_payload())
            restarted.receive_observation(node_task_observation(1))
            recovery = restarted.reconcile_task(
                "observation-session",
                {"device_session_id": pair["device_session_id"], "device_record": None},
            )
            status = restarted.task_status("observation-session")
            self.assertEqual(recovery["action_outcome"], "EXECUTED")
            self.assertEqual(recovery["actual_effect"]["status"], "UNKNOWN")
            self.assertEqual(status["state"], "PAUSED")
            self.assertIsNone(status["next_action"])

    def test_bridge_restart_before_device_send_keeps_outcome_unknown(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state_file = Path(directory) / "bridge.json"
            original = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=state_file,
            )
            original.pair(_pair_payload())
            original.receive_observation(node_task_observation(1))
            submitted = original.submit_task(_submit_payload())
            action = submitted["next_action"]
            self.assertIsNotNone(action)
            self.assertFalse(submitted["action_result_unknown"])

            # No status poll, command intent, or device receipt happened before
            # this simulated process death. The server's durable action alone
            # cannot prove whether the Android side effect was sent.
            restarted = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=state_file,
            )
            restored = restarted.task_status("observation-session")
            self.assertEqual(restored["state"], "PAUSED")
            self.assertIsNone(restored["next_action"])
            self.assertTrue(restored["action_result_unknown"])

            pair = restarted.pair(_pair_payload())
            restarted.receive_observation(node_task_observation(1))
            recovery = restarted.reconcile_task(
                "observation-session",
                {
                    "device_session_id": pair["device_session_id"],
                    "device_record": {
                        "action_id": action["action_id"],
                        "phase": "PENDING",
                        "receipt": None,
                    },
                },
            )
            self.assertEqual(recovery["action_id"], action["action_id"])
            self.assertEqual(recovery["action_outcome"], "UNKNOWN")
            self.assertFalse(recovery["eligible"])
            self.assertIsNone(recovery["resume_token"])
            self.assertEqual(restarted.task_status("observation-session")["state"], "PAUSED")

    def test_device_receipt_proves_execution_but_not_effect_and_resume_replans(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state_file = Path(directory) / "bridge.json"
            original = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=state_file,
            )
            original.pair(_pair_payload())
            original.receive_observation(node_task_observation(1))
            submitted = original.submit_task(_submit_payload())
            old_action = submitted["next_action"]

            restarted = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=state_file,
            )
            pair = restarted.pair(_pair_payload())
            restarted.receive_observation(node_task_observation(1))
            recovery = restarted.reconcile_task(
                "observation-session",
                {
                    "device_session_id": pair["device_session_id"],
                    "device_record": {
                        "action_id": old_action["action_id"],
                        "phase": "RECEIPT_RECORDED",
                        "receipt": _device_receipt(old_action),
                    },
                },
            )
            self.assertEqual(recovery["action_outcome"], "EXECUTED")
            self.assertTrue(recovery["eligible"])
            self.assertEqual(recovery["actual_effect"]["status"], "UNKNOWN")

            # A repeated capture advances observation_version but leaves the
            # actual target/window unchanged; it must not stale the confirm.
            restarted.receive_observation(node_task_observation(2))
            task, resumed = restarted.resume_task(
                "observation-session",
                {
                    "confirmed": True,
                    "resume_token": recovery["resume_token"],
                    "observation_version": recovery["observation_version"],
                    "device_session_id": pair["device_session_id"],
                    "model": None,
                },
            )
            self.assertEqual(resumed["phase"], "RESUMED")
            self.assertTrue(resumed["confirmed"])
            self.assertEqual(task["state"], "RUNNING")
            self.assertIsNotNone(task["next_action"])
            self.assertNotEqual(task["next_action"]["action_id"], old_action["action_id"])

    def test_scene_change_invalidates_confirmation_and_keeps_task_paused(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state_file = Path(directory) / "bridge.json"
            bridge = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=state_file,
            )
            bridge.pair(_pair_payload())
            bridge.receive_observation(node_task_observation(1))
            submitted = bridge.submit_task(_submit_payload())
            action = submitted["next_action"]
            restarted = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=state_file,
            )
            pair = restarted.pair(_pair_payload())
            restarted.receive_observation(node_task_observation(1))
            recovery = restarted.reconcile_task(
                "observation-session",
                {
                    "device_session_id": pair["device_session_id"],
                    "device_record": {
                        "action_id": action["action_id"],
                        "phase": "RECEIPT_RECORDED",
                        "receipt": _device_receipt(action),
                    },
                },
            )
            self.assertTrue(recovery["eligible"])
            restarted.receive_observation(node_task_observation(2, completed=True))
            with self.assertRaises(BridgeRequestError) as raised:
                restarted.resume_task(
                    "observation-session",
                    {
                        "confirmed": True,
                        "resume_token": recovery["resume_token"],
                        "observation_version": recovery["observation_version"],
                        "device_session_id": pair["device_session_id"],
                        "model": None,
                    },
                )
            self.assertEqual(raised.exception.code, "scene_changed_since_reconciliation")
            after = restarted.task_recovery_status("observation-session")
            self.assertFalse(after["eligible"])
            self.assertIsNone(after["resume_token"])
            self.assertEqual(restarted.task_status("observation-session")["state"], "PAUSED")

    def test_app_reconcile_busy_state_is_ignored_but_other_scene_changes_are_not(self) -> None:
        baseline = _observation_with_recovery_controls(1, reconcile_enabled=True)
        busy = _observation_with_recovery_controls(
            2,
            reconcile_enabled=False,
            recovery_message="Recovery status: capturing a fresh observation before resume",
        )
        self.assertEqual(_android_scene_fingerprint(baseline), _android_scene_fingerprint(busy))

        # These are the two exact recovery-button labels rendered by the app's
        # activities. The same label in another app remains scene evidence.
        for label in ("RECONCILE", "RECONCILE BEFORE RESUME"):
            with self.subTest(label=label):
                first = _observation_with_recovery_controls(1, reconcile_text=label, reconcile_enabled=True)
                second = _observation_with_recovery_controls(2, reconcile_text=label, reconcile_enabled=False)
                self.assertEqual(_android_scene_fingerprint(first), _android_scene_fingerprint(second))
                external_first = _observation_with_recovery_controls(
                    1, reconcile_text=label, reconcile_enabled=True, reconcile_package="com.example.other"
                )
                external_second = _observation_with_recovery_controls(
                    2, reconcile_text=label, reconcile_enabled=False, reconcile_package="com.example.other"
                )
                self.assertNotEqual(
                    _android_scene_fingerprint(external_first),
                    _android_scene_fingerprint(external_second),
                )

        changed_target_button = copy.deepcopy(baseline)
        target_button = next(node for node in changed_target_button["nodes"] if node["text"] == "Toggle controlled state")
        target_button["enabled"] = False
        self.assertNotEqual(_android_scene_fingerprint(baseline), _android_scene_fingerprint(changed_target_button))

        changed_target_position = copy.deepcopy(baseline)
        target_button = next(node for node in changed_target_position["nodes"] if node["text"] == "Toggle controlled state")
        target_button["bounds"]["left"] += 1
        self.assertNotEqual(_android_scene_fingerprint(baseline), _android_scene_fingerprint(changed_target_position))

        changed_window = copy.deepcopy(baseline)
        changed_window["windows"][0]["focused"] = False
        self.assertNotEqual(_android_scene_fingerprint(baseline), _android_scene_fingerprint(changed_window))

        changed_screen = copy.deepcopy(baseline)
        changed_screen["screen"]["rotation"] = 1
        self.assertNotEqual(_android_scene_fingerprint(baseline), _android_scene_fingerprint(changed_screen))

        changed_permission = copy.deepcopy(baseline)
        changed_permission["permission"]["can_observe"] = False
        self.assertNotEqual(_android_scene_fingerprint(baseline), _android_scene_fingerprint(changed_permission))

    def test_reconcile_busy_state_does_not_make_explicit_confirmation_stale(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=Path(directory) / "bridge.json",
            )
            pair = bridge.pair(_pair_payload_with_journaled_dispatch())
            bridge.receive_observation(_observation_with_recovery_controls(1, reconcile_enabled=True))
            bridge.submit_task(_submit_payload_with_journaled_dispatch())
            recovery = bridge.reconcile_task(
                "observation-session",
                {"device_session_id": pair["device_session_id"], "device_record": None},
            )
            self.assertTrue(recovery["eligible"])
            self.assertEqual(recovery["action_outcome"], "NOT_EXECUTED")

            bridge.receive_observation(
                _observation_with_recovery_controls(
                    2,
                    reconcile_enabled=False,
                    recovery_message="Recovery status: capturing a fresh observation before resume",
                )
            )
            resumed, confirmation = bridge.resume_task(
                "observation-session",
                {
                    "confirmed": True,
                    "resume_token": recovery["resume_token"],
                    "observation_version": recovery["observation_version"],
                    "device_session_id": pair["device_session_id"],
                    "model": None,
                },
            )
            self.assertEqual(resumed["state"], "RUNNING")
            self.assertEqual(confirmation["phase"], "RESUMED")

    def test_cancelled_known_not_executed_reconciliation_releases_the_task_slot(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=Path(directory) / "bridge.json",
            )
            pair = bridge.pair(_pair_payload_with_journaled_dispatch())
            bridge.receive_observation(node_task_observation(1))
            bridge.submit_task(_submit_payload_with_journaled_dispatch())
            bridge.task_status("observation-session", session_id=pair["device_session_id"])
            first = bridge.reconcile_task(
                "observation-session",
                {"device_session_id": pair["device_session_id"], "device_record": None},
            )
            self.assertTrue(first["eligible"])
            cancelled = bridge.control_task(
                "observation-session", "cancel", session_id=pair["device_session_id"]
            )
            self.assertEqual(cancelled["state"], "CANCELLED")
            self.assertTrue(cancelled["action_result_unknown"])

            settled = bridge.reconcile_task(
                "observation-session",
                {"device_session_id": pair["device_session_id"], "device_record": None},
            )
            self.assertEqual(settled["state"], "CANCELLED")
            self.assertEqual(settled["phase"], "NOT_REQUIRED")
            self.assertEqual(settled["action_outcome"], "NOT_EXECUTED")
            self.assertFalse(settled["eligible"])
            self.assertFalse(bridge.task_status("observation-session")["action_result_unknown"])
            self.assertEqual(settled["actual_effect"]["status"], "NOT_EXECUTED")
            self.assertIsNone(bridge._active_task_id)

            next_task_id = "observation-session-next"
            bridge.receive_observation(node_task_observation(2, task_id=next_task_id))
            next_payload = _submit_payload_with_journaled_dispatch()
            next_payload["task_id"] = next_task_id
            next_task = bridge.submit_task(next_payload, session_id=pair["device_session_id"])
            self.assertEqual(next_task["task_id"], next_task_id)
            self.assertEqual(next_task["state"], "RUNNING")

    def test_cancelled_unknown_or_executed_action_keeps_recovery_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = AndroidBridge(
                token="bridge-token",
                device_id="android-emulator-01",
                freshness_seconds=30,
                state_path=Path(directory) / "bridge.json",
            )
            pair = bridge.pair(_pair_payload())
            bridge.receive_observation(node_task_observation(1))
            submitted = bridge.submit_task(_submit_payload())
            action = submitted["next_action"]
            bridge.task_status("observation-session", session_id=pair["device_session_id"])
            bridge.reconcile_task(
                "observation-session",
                {"device_session_id": pair["device_session_id"], "device_record": None},
            )
            bridge.control_task("observation-session", "cancel", session_id=pair["device_session_id"])

            unknown = bridge.reconcile_task(
                "observation-session",
                {"device_session_id": pair["device_session_id"], "device_record": None},
            )
            self.assertEqual(unknown["phase"], "RECONCILIATION_REQUIRED")
            self.assertEqual(unknown["action_outcome"], "UNKNOWN")
            self.assertTrue(bridge.task_status("observation-session")["action_result_unknown"])
            self.assertEqual(bridge._active_task_id, "observation-session")

            executed = bridge.reconcile_task(
                "observation-session",
                {
                    "device_session_id": pair["device_session_id"],
                    "device_record": {
                        "action_id": action["action_id"],
                        "phase": "RECEIPT_RECORDED",
                        "receipt": _device_receipt(action),
                    },
                },
            )
            self.assertEqual(executed["phase"], "RECONCILIATION_REQUIRED")
            self.assertEqual(executed["action_outcome"], "EXECUTED")
            self.assertEqual(executed["actual_effect"]["status"], "UNKNOWN")
            self.assertEqual(bridge._active_task_id, "observation-session")


if __name__ == "__main__":
    unittest.main()
