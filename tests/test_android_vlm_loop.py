from __future__ import annotations

import copy
import sys
import time
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))

from test_android_bridge import node_task_observation, observation, screenshot_for  # noqa: E402

from services.android_bridge.bridge import AndroidBridge, BridgeRequestError  # noqa: E402
from services.live_vlm.transport import ProductionVlmTransport, VlmTransportError  # noqa: E402


class FakeRoleTransport:
    """Offline role replies with the same v3.5 response sections."""

    def __init__(self, **kwargs):
        self.calls = 0

    def predict(self, **kwargs):
        self.calls += 1
        role = kwargs["role"]
        if role == "manager" and self.calls == 1:
            text = "### Thought ### plan\n### Plan ### 1. click the controlled button"
        elif role == "executor":
            text = (
                "### Thought ### click\n### Action ### "
                '{"action":"click","coordinate":[500,500]}\n'
                "### Description ### click the controlled button"
            )
        elif role == "action_reflector":
            text = "### Outcome ### A\n### Error Description ### None"
        else:
            text = "### Thought ### complete\n### Plan ### Finished"
        return text, [], {"usage": {"prompt_tokens": 10, "completion_tokens": 3}}

    def summary(self):
        return {
            "requests": self.calls,
            "estimated_cost_cny": 0.001,
            "usage_missing": False,
            "attempts": [],
        }


def _pair_payload():
    return {
        "schema_version": "1.0",
        "android_schema_version": "1.0",
        "task_id": "observation-session",
        "device_id": "android-emulator-01",
        "client_name": "test",
        "capabilities": ["accessibility_tree"],
    }


def _vlm_payload(*, goal: str = "点击“切换受控状态”按钮", key: str = "test-api-key"):
    return {
        "schema_version": "1.0",
        "android_schema_version": "1.0",
        "task_id": "observation-session",
        "device_id": "android-emulator-01",
        "goal": goal,
        "source": "test",
        "model": {
            "provider": "GUI-Plus",
            "endpoint": "https://example.test/v1/chat/completions",
            "model": "gui-plus-2026-02-26",
            "api_key": key,
            "max_steps": 5,
            "max_requests": 25,
            "max_tokens": 1024,
            "budget_cny": 1.0,
        },
    }


class AndroidVlmLoopTests(unittest.TestCase):
    def _bridge_with_before(self, before=None):
        bridge = AndroidBridge(token="bridge-token", device_id="android-emulator-01", freshness_seconds=30)
        bridge.pair(_pair_payload())
        before = before or node_task_observation(1)
        bridge.receive_observation(before)
        bridge.receive_screenshot(screenshot_for(before, screenshot_id="before-1", capture_type="BEFORE"))
        return bridge

    @staticmethod
    def _wait_for_action(bridge: AndroidBridge):
        for _ in range(100):
            status = bridge.task_status("observation-session")
            action = status.get("next_action")
            if action is not None:
                return action
            time.sleep(0.01)
        raise AssertionError("VLM runner did not publish an action")

    @staticmethod
    def _receipt(action, *, after=None, screenshot_id=None, missing=None):
        receipt = {
            "schema_version": "1.0",
            "android_schema_version": "1.0",
            "task_id": action["task_id"],
            "receipt_id": "receipt-" + action["action_id"],
            "action_id": action["action_id"],
            "device_id": action["device_id"],
            "accepted": True,
            "outcome": "EXECUTED",
            "received_at": "2026-09-23T00:00:00Z",
            "observation_id": action["observation_id"],
            "observation_version": action["observation_version"],
            "deduplicated": False,
        }
        if after is not None:
            receipt.update({
                "after_observation_id": after["observation_id"],
                "after_observation_version": after["observation_version"],
            })
        if screenshot_id is not None:
            receipt["after_screenshot_id"] = screenshot_id
        if missing is not None:
            receipt["after_screenshot_missing_reason"] = missing
        return receipt

    def test_vlm_action_is_observation_bound_and_independently_judged(self):
        bridge = self._bridge_with_before()
        with patch("services.live_vlm.transport.ProductionVlmTransport", FakeRoleTransport):
            status = bridge.submit_vlm_task(_vlm_payload())
            self.assertEqual(status["mode"], "vlm")
            action = self._wait_for_action(bridge)
            self.assertEqual(action["observation_id"], "android-phone-01-node-obs-1")
            self.assertEqual(action["before_screenshot_id"], "before-1")

            after = node_task_observation(2, completed=True)
            bridge.receive_observation(after)
            bridge.receive_screenshot(screenshot_for(after, screenshot_id="after-2", capture_type="AFTER"))
            bridge.receive_receipt("observation-session", self._receipt(action, after=after, screenshot_id="after-2"))

            # The real App captures another fresh BEFORE frame before the
            # model can receive its next role prompt.
            next_before = node_task_observation(3, completed=True)
            bridge.receive_observation(next_before)
            bridge.receive_screenshot(screenshot_for(next_before, screenshot_id="before-3", capture_type="BEFORE"))
            for _ in range(100):
                status = bridge.task_status("observation-session")
                if status["state"] != "RUNNING":
                    break
                time.sleep(0.01)

        self.assertEqual(status["state"], "SUCCEEDED")
        self.assertEqual(status["actual_effect"]["status"], "EXECUTED")
        self.assertEqual(status["vlm_completion"]["status"], "COMPLETED")
        self.assertEqual(status["independent_result"]["status"], "SUCCESS")
        self.assertEqual(status["after_observation_id"], "android-phone-01-node-obs-2")
        self.assertNotIn("test-api-key", str(status))

    def test_vlm_action_reuses_window_bound_frame_from_before_observation(self):
        before = node_task_observation(1)
        window_bounds = {"left": 16, "top": 64, "right": 1064, "bottom": 2336}
        before["screen"].update({
            "content_width_px": 1048,
            "content_height_px": 2272,
            "system_bar_insets": {"left": 16, "top": 64, "right": 16, "bottom": 64},
            "window_offset": {"x": 16, "y": 64},
        })
        before["windows"][0]["bounds"] = copy.deepcopy(window_bounds)
        before["nodes"][0]["bounds"] = copy.deepcopy(window_bounds)
        bridge = self._bridge_with_before(before)

        with patch("services.live_vlm.transport.ProductionVlmTransport", FakeRoleTransport):
            bridge.submit_vlm_task(_vlm_payload())
            action = self._wait_for_action(bridge)
            frame = action["coordinate_frame"]

            self.assertEqual(action["observation_id"], before["observation_id"])
            self.assertEqual(action["before_screenshot_id"], "before-1")
            self.assertEqual(frame["active_window_id"], 1)
            self.assertEqual(frame["active_window_package"], "com.jev.mobileagent")
            self.assertEqual(frame["active_window_bounds"], window_bounds)
            self.assertEqual(frame["system_bar_insets"], before["screen"]["system_bar_insets"])
            self.assertEqual(frame["window_offset"], before["screen"]["window_offset"])
            self.assertEqual(frame["content_width_px"], 1048)
            self.assertEqual(frame["content_height_px"], 2272)
            bridge.control_task("observation-session", "cancel")

    def test_accepted_receipt_without_observed_goal_effect_stays_unknown(self):
        bridge = self._bridge_with_before()
        with patch("services.live_vlm.transport.ProductionVlmTransport", FakeRoleTransport):
            bridge.submit_vlm_task(_vlm_payload())
            action = self._wait_for_action(bridge)
            after = node_task_observation(2, completed=False)
            bridge.receive_observation(after)
            bridge.receive_screenshot(screenshot_for(after, screenshot_id="after-no-effect", capture_type="AFTER"))

            status = bridge.receive_receipt(
                "observation-session",
                self._receipt(action, after=after, screenshot_id="after-no-effect"),
            )
            self.assertTrue(status["receipt"]["accepted"])
            self.assertEqual(status["receipt"]["outcome"], "EXECUTED")
            self.assertEqual(status["actual_effect"]["status"], "UNKNOWN")
            self.assertEqual(status["actual_effect"]["reason"], "controlled_page_not_completed")
            self.assertEqual(status["independent_result"]["status"], "UNKNOWN")
            self.assertIsNone(status["independent_result"]["success"])

            bridge.control_task("observation-session", "cancel")
            status = bridge.task_status("observation-session")

        self.assertEqual(status["state"], "CANCELLED")
        self.assertEqual(status["actual_effect"]["status"], "UNKNOWN")

    def test_cancelled_task_rejects_late_receipt_without_dispatching_again(self):
        bridge = self._bridge_with_before()
        with patch("services.live_vlm.transport.ProductionVlmTransport", FakeRoleTransport):
            bridge.submit_vlm_task(_vlm_payload())
            action = self._wait_for_action(bridge)
            cancelled = bridge.control_task("observation-session", "cancel")
            self.assertEqual(cancelled["state"], "CANCELLED")
            after = node_task_observation(2, completed=True)
            bridge.receive_observation(after)
            bridge.receive_screenshot(screenshot_for(after, screenshot_id="after-late", capture_type="AFTER"))
            status = bridge.receive_receipt(
                "observation-session",
                self._receipt(action, after=after, screenshot_id="after-late"),
            )
            time.sleep(0.05)
            status = bridge.task_status("observation-session")

        self.assertEqual(status["state"], "CANCELLED")
        self.assertIsNone(status["next_action"])
        self.assertFalse(status["action_result_unknown"])
        self.assertEqual(sum(event["kind"] == "action.dispatched" for event in status["trace"]), 1)

    def test_missing_screenshot_and_permission_are_clear_failures(self):
        bridge = AndroidBridge(token="bridge-token", device_id="android-emulator-01", freshness_seconds=30)
        bridge.pair(_pair_payload())
        before = node_task_observation(1)
        bridge.receive_observation(before)
        with self.assertRaises(BridgeRequestError) as missing:
            bridge.submit_vlm_task(_vlm_payload())
        self.assertEqual(missing.exception.code, "before_screenshot_required")

        unavailable = observation(2, availability="PERMISSION_UNAVAILABLE")
        bridge.receive_observation(unavailable)
        with self.assertRaises(BridgeRequestError) as permission:
            bridge.submit_vlm_task(_vlm_payload())
        self.assertEqual(permission.exception.code, "permission_unavailable")

    def test_transport_stops_on_invalid_usage_and_redacts_provider_key(self):
        key = "secret-key"

        def request(endpoint, credential, payload, timeout):
            return 200, {
                "choices": [{
                    "message": {"content": '{"action":"click","note":"secret-key"}'},
                    "finish_reason": "stop",
                }],
                "usage": {"prompt_tokens": 2, "completion_tokens": 1, "echo": key},
            }, key, None

        transport = ProductionVlmTransport(
            endpoint="https://example.test/v1/chat/completions",
            model="gui-plus-2026-02-26",
            provider="GUI-Plus",
            api_key=key,
            request_fn=request,
        )
        with self.assertRaises(VlmTransportError) as error:
            transport.predict(role="manager", prompt="test", images=[], step=0)
        self.assertEqual(error.exception.code, "provider_response_redacted")
        self.assertNotIn(key, str(transport.summary()))

        calls = []

        def missing_usage(endpoint, credential, payload, timeout):
            calls.append(1)
            return 200, {
                "choices": [{"message": {"content": "ok"}, "finish_reason": "stop"}],
            }, "", None

        guarded = ProductionVlmTransport(
            endpoint="https://example.test/v1/chat/completions",
            model="gui-plus-2026-02-26",
            provider="GUI-Plus",
            api_key=key,
            request_fn=missing_usage,
        )
        with self.assertRaises(VlmTransportError) as usage_error:
            guarded.predict(role="manager", prompt="test", images=[], step=0)
        self.assertEqual(usage_error.exception.code, "usage_missing")
        with self.assertRaises(VlmTransportError) as gated_error:
            guarded.predict(role="manager", prompt="test", images=[], step=1)
        self.assertEqual(gated_error.exception.code, "usage_gate")
        self.assertEqual(len(calls), 1)
