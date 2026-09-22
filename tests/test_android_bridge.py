from __future__ import annotations

import copy
import json
import threading
import time
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from services.android_bridge.bridge import AndroidBridge, create_server
from services.android_bridge.schema import assert_observation_valid


class FixedClock:
    def __call__(self) -> str:
        return "2026-09-22T00:00:00Z"


def observation(version: int = 1, *, availability: str = "AVAILABLE") -> dict:
    available = availability == "AVAILABLE"
    nodes = [
        {
            "node_id": "window-1-node-0",
            "parent_node_id": None,
            "class_name": "android.widget.LinearLayout",
            "package_name": "com.example.controlled",
            "text": "Controlled page",
            "content_description": "",
            "state_description": "",
            "view_id_resource_name": "com.example:id/root",
            "enabled": True,
            "visible_to_user": True,
            "clickable": False,
            "focusable": False,
            "focused": False,
            "selected": False,
            "scrollable": False,
            "editable": False,
            "bounds": {"left": 0, "top": 0, "right": 1080, "bottom": 1920},
            "child_node_ids": ["window-1-node-1"] if available else [],
        },
        {
            "node_id": "window-1-node-1",
            "parent_node_id": "window-1-node-0",
            "class_name": "android.widget.TextView",
            "package_name": "com.example.controlled",
            "text": "Visible controlled text",
            "content_description": "",
            "state_description": "",
            "view_id_resource_name": "com.example:id/text",
            "enabled": True,
            "visible_to_user": True,
            "clickable": False,
            "focusable": False,
            "focused": False,
            "selected": False,
            "scrollable": False,
            "editable": False,
            "bounds": {"left": 20, "top": 80, "right": 900, "bottom": 160},
            "child_node_ids": [],
        },
    ] if available else []
    return {
        "schema_version": "1.0",
        "android_schema_version": "1.0",
        "task_id": "observation-session",
        "observation_id": f"android-emulator-01-obs-{version}",
        "device_id": "android-emulator-01",
        "observation_version": version,
        "captured_at": "2026-09-22T00:00:00Z",
        "page_state": "observed" if available else "unavailable",
        "availability": availability,
        "unavailable_reason": None if available else "accessibility_permission_disabled",
        "permission": {
            "service_enabled": available,
            "can_observe": available,
            "reason": None if available else "Accessibility service is disabled",
        },
        "screen": {"width_px": 1080, "height_px": 1920, "rotation": 0},
        "windows": [
            {
                "window_id": 1,
                "window_type": 1,
                "title": "Controlled page",
                "package_name": "com.example.controlled",
                "active": True,
                "focused": True,
                "layer": 0,
                "bounds": {"left": 0, "top": 0, "right": 1080, "bottom": 1920},
                "root_node_id": "window-1-node-0" if available else None,
            }
        ] if available else [],
        "nodes": nodes,
        "root_node_ids": ["window-1-node-0"] if available else [],
        "capabilities": ["accessibility_tree", "windows", "screen_metrics"],
    }


def node_task_observation(
    version: int = 1,
    *,
    task_id: str = "observation-session",
    device_id: str = "android-emulator-01",
    completed: bool = False,
    input_text: str = "",
    obscured: bool = False,
) -> dict:
    """Synthetic transport fixture for the Android task boundary.

    The physical acceptance uses the real Accessibility tree; this fixture
    only exercises task identity, delivery and negative protocol decisions.
    """

    state = "completed" if completed else "ready"
    nodes = [
        {
            "node_id": "window-1-0-node-0",
            "parent_node_id": None,
            "class_name": "android.widget.LinearLayout",
            "package_name": "com.jev.mobileagent",
            "text": "Controlled observation page",
            "content_description": "",
            "state_description": "",
            "view_id_resource_name": "com.jev.mobileagent:id/root",
            "enabled": True,
            "visible_to_user": True,
            "clickable": False,
            "focusable": False,
            "focused": False,
            "selected": False,
            "scrollable": False,
            "editable": False,
            "bounds": {"left": 0, "top": 0, "right": 1080, "bottom": 2400},
            "child_node_ids": [
                "window-1-0-node-0-0",
                "window-1-0-node-0-1",
                "window-1-0-node-0-2",
                "window-1-0-node-0-3",
            ],
        },
        {
            "node_id": "window-1-0-node-0-0",
            "parent_node_id": "window-1-0-node-0",
            "class_name": "android.widget.Button",
            "package_name": "com.jev.mobileagent",
            "text": "Toggle controlled state",
            "content_description": "Toggle controlled state button",
            "state_description": "",
            "view_id_resource_name": "",
            "enabled": True,
            "visible_to_user": True,
            "clickable": True,
            "focusable": True,
            "focused": False,
            "selected": False,
            "scrollable": False,
            "editable": False,
            "bounds": {"left": 20, "top": 200, "right": 900, "bottom": 300},
            "child_node_ids": [],
        },
        {
            "node_id": "window-1-0-node-0-1",
            "parent_node_id": "window-1-0-node-0",
            "class_name": "android.widget.EditText",
            "package_name": "com.jev.mobileagent",
            "text": input_text,
            "content_description": "中文输入框",
            "state_description": "",
            "view_id_resource_name": "",
            "enabled": True,
            "visible_to_user": True,
            "clickable": True,
            "focusable": True,
            "focused": False,
            "selected": False,
            "scrollable": False,
            "editable": True,
            "bounds": {"left": 20, "top": 320, "right": 900, "bottom": 440},
            "child_node_ids": [],
        },
        {
            "node_id": "window-1-0-node-0-2",
            "parent_node_id": "window-1-0-node-0",
            "class_name": "android.widget.TextView",
            "package_name": "com.jev.mobileagent",
            "text": f"Controlled action state: {state}",
            "content_description": f"Controlled action state {state}",
            "state_description": "",
            "view_id_resource_name": "",
            "enabled": True,
            "visible_to_user": True,
            "clickable": False,
            "focusable": False,
            "focused": False,
            "selected": False,
            "scrollable": False,
            "editable": False,
            "bounds": {"left": 20, "top": 100, "right": 900, "bottom": 180},
            "child_node_ids": [],
        },
        {
            "node_id": "window-1-0-node-0-3",
            "parent_node_id": "window-1-0-node-0",
            "class_name": "android.widget.TextView",
            "package_name": "com.jev.mobileagent",
            "text": f"Controlled input state: {input_text or 'empty'}",
            "content_description": f"Controlled input state {input_text or 'empty'}",
            "state_description": "",
            "view_id_resource_name": "",
            "enabled": True,
            "visible_to_user": True,
            "clickable": False,
            "focusable": False,
            "focused": False,
            "selected": False,
            "scrollable": False,
            "editable": False,
            "bounds": {"left": 20, "top": 450, "right": 900, "bottom": 530},
            "child_node_ids": [],
        },
    ]
    windows = [
        {
            "window_id": 1,
            "window_type": 1,
            "title": "Controlled observation page",
            "package_name": "com.jev.mobileagent",
            "active": True,
            "focused": True,
            "layer": 0,
            "bounds": {"left": 0, "top": 0, "right": 1080, "bottom": 2400},
            "root_node_id": "window-1-0-node-0",
        }
    ]
    if obscured:
        windows.append({
            "window_id": 2,
            "window_type": 2,
            "title": "Obscuring overlay",
            "package_name": "com.android.systemui",
            "active": True,
            "focused": True,
            "layer": 1,
            "bounds": {"left": 0, "top": 180, "right": 1080, "bottom": 500},
            "root_node_id": None,
        })
    return {
        "schema_version": "1.0",
        "android_schema_version": "1.0",
        "task_id": task_id,
        "observation_id": f"android-phone-01-node-obs-{version}",
        "device_id": device_id,
        "observation_version": version,
        "captured_at": "2026-09-22T00:00:00Z",
        "page_state": "observed",
        "availability": "AVAILABLE",
        "unavailable_reason": None,
        "permission": {"service_enabled": True, "can_observe": True, "reason": None},
        "screen": {"width_px": 1080, "height_px": 2400, "rotation": 0},
        "windows": windows,
        "nodes": nodes,
        "root_node_ids": ["window-1-0-node-0"],
        "capabilities": ["accessibility_tree", "windows", "tap", "set_text"],
    }


class AndroidBridgeHTTPTests(unittest.TestCase):
    def setUp(self) -> None:
        self.token = "bridge-test-token"
        self.device_id = "android-emulator-01"
        self.bridge = AndroidBridge(
            token=self.token,
            device_id=self.device_id,
            clock=FixedClock(),
            freshness_seconds=10,
        )
        self.server = create_server(self.bridge)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def _request(
        self,
        method: str,
        path: str,
        *,
        payload: dict | None = None,
        token: str | None = None,
        device_id: str | None = None,
        protocol: str = "1",
    ) -> tuple[int, dict]:
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.token if token is None else token}",
            "X-JEV-Protocol-Version": protocol,
            "X-JEV-Device-Id": self.device_id if device_id is None else device_id,
        }
        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
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

    def _pair(self) -> tuple[int, dict]:
        return self._request(
            "POST",
            "/v1/android/pair",
            payload={
                "schema_version": "1.0",
                "android_schema_version": "1.0",
                "task_id": "observation-session",
                "device_id": self.device_id,
                "client_name": "Jev Android test app",
                "capabilities": ["accessibility_tree"],
            },
        )

    def test_real_tree_fields_survive_pair_post_and_latest_read(self) -> None:
        status, pair = self._pair()
        self.assertEqual(status, 200)
        self.assertTrue(pair["paired"])
        payload = observation()
        assert_observation_valid(payload)
        status, accepted = self._request("POST", "/v1/android/observations", payload=payload)
        self.assertEqual(status, 200)
        self.assertTrue(accepted["accepted"])

        status, latest = self._request(
            "GET",
            "/v1/android/observations/latest?device_id=android-emulator-01",
        )
        self.assertEqual(status, 200)
        self.assertTrue(latest["is_current"])
        self.assertEqual(latest["observation"]["observation_version"], 1)
        self.assertEqual(latest["observation"]["nodes"][1]["text"], "Visible controlled text")
        self.assertEqual(latest["observation"]["screen"]["rotation"], 0)
        self.assertEqual(latest["observation"]["windows"][0]["package_name"], "com.example.controlled")

        status, bridge_status = self._request(
            "GET",
            "/v1/android/status?device_id=android-emulator-01",
        )
        self.assertEqual(status, 200)
        self.assertEqual(bridge_status["connection_status"], "CONNECTED")
        self.assertEqual(bridge_status["latest_observation_version"], 1)

    def test_permission_unavailable_and_empty_tree_are_explicit(self) -> None:
        self._pair()
        self._request("POST", "/v1/android/observations", payload=observation())
        unavailable = observation(2, availability="PERMISSION_UNAVAILABLE")
        status, accepted = self._request("POST", "/v1/android/observations", payload=unavailable)
        self.assertEqual(status, 200)
        self.assertTrue(accepted["accepted"])
        status, latest = self._request(
            "GET",
            "/v1/android/observations/latest?device_id=android-emulator-01",
        )
        self.assertEqual(status, 200)
        self.assertEqual(latest["observation"]["availability"], "PERMISSION_UNAVAILABLE")
        self.assertEqual(latest["observation"]["unavailable_reason"], "accessibility_permission_disabled")
        self.assertEqual(latest["connection_status"], "DISCONNECTED")
        status, bridge_status = self._request(
            "GET",
            "/v1/android/status?device_id=android-emulator-01",
        )
        self.assertEqual(status, 200)
        self.assertEqual(bridge_status["connection_status"], "DISCONNECTED")

        empty = observation(3, availability="EMPTY_TREE")
        empty["page_state"] = "empty"
        empty["unavailable_reason"] = "no_active_accessibility_root"
        empty["permission"] = {"service_enabled": True, "can_observe": True, "reason": None}
        status, accepted = self._request("POST", "/v1/android/observations", payload=empty)
        self.assertEqual(status, 200)
        self.assertTrue(accepted["accepted"])

    def test_pairing_clears_previous_tree_until_a_fresh_observation_arrives(self) -> None:
        self._pair()
        self._request("POST", "/v1/android/observations", payload=observation())

        status, pair = self._pair()
        self.assertEqual(status, 200)
        self.assertTrue(pair["paired"])

        status, result = self._request(
            "GET",
            "/v1/android/observations/latest?device_id=android-emulator-01",
        )
        self.assertEqual(status, 404)
        self.assertEqual(result["error"]["code"], "observation_unavailable")
        status, bridge_status = self._request(
            "GET",
            "/v1/android/status?device_id=android-emulator-01",
        )
        self.assertEqual(status, 200)
        self.assertEqual(bridge_status["connection_status"], "DISCONNECTED")
        self.assertIsNone(bridge_status["latest_observation_id"])

    def test_stale_observation_is_rejected_and_previous_value_remains(self) -> None:
        self._pair()
        self._request("POST", "/v1/android/observations", payload=observation(2))
        stale = observation(1)
        stale["nodes"][1]["text"] = "stale text"
        status, result = self._request("POST", "/v1/android/observations", payload=stale)
        self.assertEqual(status, 409)
        self.assertEqual(result["error"]["code"], "stale_observation")
        status, latest = self._request(
            "GET",
            "/v1/android/observations/latest?device_id=android-emulator-01",
        )
        self.assertEqual(status, 200)
        self.assertEqual(latest["observation"]["observation_version"], 2)
        self.assertEqual(latest["observation"]["nodes"][1]["text"], "Visible controlled text")

    def test_latest_refuses_old_tree_after_freshness_window(self) -> None:
        self._pair()
        self._request("POST", "/v1/android/observations", payload=observation())
        self.bridge.freshness_seconds = 0
        status, result = self._request(
            "GET",
            "/v1/android/observations/latest?device_id=android-emulator-01",
        )
        self.assertEqual(status, 409)
        self.assertEqual(result["error"]["code"], "observation_stale")
        status, bridge_status = self._request(
            "GET",
            "/v1/android/status?device_id=android-emulator-01",
        )
        self.assertEqual(status, 200)
        self.assertEqual(bridge_status["connection_status"], "DISCONNECTED")

    def test_guards_and_pairing_are_explicit(self) -> None:
        payload = observation()
        status, result = self._request("POST", "/v1/android/observations", payload=payload)
        self.assertEqual(status, 409)
        self.assertEqual(result["error"]["code"], "device_not_paired")

        status, result = self._request("POST", "/v1/android/pair", payload={})
        self.assertEqual(status, 400)
        self.assertEqual(result["error"]["code"], "invalid_schema")

        status, result = self._request("POST", "/v1/android/pair", payload={
            "schema_version": "1.0",
            "android_schema_version": "1.0",
            "task_id": "observation-session",
            "device_id": self.device_id,
            "client_name": "test",
            "capabilities": [],
        }, token="wrong")
        self.assertEqual(status, 401)
        self.assertEqual(result["error"]["code"], "unauthorized")

        status, result = self._request("POST", "/v1/android/pair", payload={
            "schema_version": "1.0",
            "android_schema_version": "2.0",
            "task_id": "observation-session",
            "device_id": self.device_id,
            "client_name": "test",
            "capabilities": [],
        })
        self.assertEqual(status, 400)
        self.assertEqual(result["error"]["code"], "invalid_schema")

    def _post_node_observation(self, version: int = 1, **kwargs: object) -> tuple[int, dict]:
        return self._request(
            "POST",
            "/v1/android/observations",
            payload=node_task_observation(version, **kwargs),
        )

    def _submit_node_task(self, goal: str) -> tuple[int, dict]:
        return self._request(
            "POST",
            "/v1/android/tasks",
            payload={
                "schema_version": "1.0",
                "android_schema_version": "1.0",
                "task_id": self.device_id.replace("android-emulator-01", "observation-session"),
                "device_id": self.device_id,
                "goal": goal,
                "source": "android-app-test",
            },
        )

    def test_android_task_input_preserves_chinese_and_verifies_fresh_observation(self) -> None:
        self._pair()
        status, accepted = self._post_node_observation()
        self.assertEqual(status, 200)
        self.assertTrue(accepted["accepted"])

        status, task = self._submit_node_task("在中文输入框输入“你好，Jev”")
        self.assertEqual(status, 200)
        self.assertEqual(task["state"], "RUNNING")
        action = task["next_action"]
        self.assertEqual(action["kind"], "set_text")
        self.assertEqual(action["parameters"]["text"], "你好，Jev")
        self.assertEqual(action["observation_version"], 1)

        receipt = {
            "schema_version": "1.0",
            "android_schema_version": "1.0",
            "task_id": self.device_id.replace("android-emulator-01", "observation-session"),
            "receipt_id": "android-receipt-input",
            "action_id": action["action_id"],
            "device_id": self.device_id,
            "accepted": True,
            "outcome": "EXECUTED",
            "received_at": "2026-09-22T00:00:01Z",
            "error_code": None,
            "error_message": None,
            "observation_id": action["observation_id"],
            "observation_version": action["observation_version"],
            "deduplicated": False,
        }
        status, waiting = self._request(
            "POST",
            "/v1/android/tasks/observation-session/receipt",
            payload=receipt,
        )
        self.assertEqual(status, 200)
        self.assertEqual(waiting["phase"], "WAITING_OBSERVATION")

        status, after = self._post_node_observation(2, input_text="你好，Jev")
        self.assertEqual(status, 200)
        self.assertTrue(after["accepted"])
        status, final = self._request("GET", "/v1/android/tasks/observation-session")
        self.assertEqual(status, 200)
        self.assertEqual(final["state"], "SUCCEEDED")
        self.assertEqual(final["verification"]["status"], "SUCCESS")
        self.assertEqual(final["verification"]["after_observation_id"], after["observation_id"] if "observation_id" in after else "android-phone-01-node-obs-2")

        status, duplicate = self._request(
            "POST",
            "/v1/android/tasks/observation-session/receipt",
            payload=receipt,
        )
        self.assertEqual(status, 200)
        self.assertTrue(duplicate["receipt"]["deduplicated"])

    def test_android_task_does_not_verify_a_newer_observation_seen_before_receipt(self) -> None:
        self._pair()
        self._post_node_observation(1)
        status, task = self._submit_node_task("在中文输入框输入“你好，Jev”")
        self.assertEqual(status, 200)
        action = task["next_action"]

        # The delayed Accessibility auto-capture can upload a newer frame
        # before the action receipt reaches the bridge.  It is still part of
        # the pre-action stream and cannot satisfy the postcondition.
        self._post_node_observation(2, input_text="")
        receipt = {
            "schema_version": "1.0",
            "android_schema_version": "1.0",
            "task_id": "observation-session",
            "receipt_id": "android-receipt-before-after",
            "action_id": action["action_id"],
            "device_id": self.device_id,
            "accepted": True,
            "outcome": "EXECUTED",
            "received_at": "2026-09-22T00:00:01Z",
            "error_code": None,
            "error_message": None,
            "observation_id": action["observation_id"],
            "observation_version": action["observation_version"],
            "deduplicated": False,
        }
        status, waiting = self._request(
            "POST", "/v1/android/tasks/observation-session/receipt", payload=receipt
        )
        self.assertEqual(status, 200)
        self.assertEqual(waiting["state"], "RUNNING")
        self.assertEqual(waiting["phase"], "WAITING_OBSERVATION")
        self.assertIsNone(waiting["verification"])
        self.assertIsNone(waiting["after_observation_id"])

        # Only an explicit capture after the receipt may close the task.
        self._post_node_observation(3, input_text="你好，Jev")
        status, final = self._request("GET", "/v1/android/tasks/observation-session")
        self.assertEqual(status, 200)
        self.assertEqual(final["state"], "SUCCEEDED")
        self.assertEqual(final["verification"]["after_observation_id"], "android-phone-01-node-obs-3")

    def test_android_task_control_before_submit_is_consumed_before_action_delivery(self) -> None:
        self._pair()
        self._post_node_observation()

        # This models a control click racing the submit HTTP request.  The
        # first response can still be 404, but the intent must be consumed by
        # the task creation and no executable action may be exposed.
        status, result = self._request(
            "POST", "/v1/android/tasks/observation-session/cancel", payload={}
        )
        self.assertEqual(status, 404)
        self.assertEqual(result["error"]["code"], "task_not_found")

        status, cancelled = self._submit_node_task("点击“切换受控状态”按钮")
        self.assertEqual(status, 200)
        self.assertEqual(cancelled["state"], "CANCELLED")
        self.assertIsNone(cancelled["next_action"])
        self.assertFalse(cancelled["action_result_unknown"])
        self.assertEqual(cancelled["control"]["command"], "cancel")

    def test_android_task_uses_explicit_after_observation_association(self) -> None:
        self._pair()
        self._post_node_observation(1)
        status, task = self._submit_node_task("点击“切换受控状态”按钮")
        self.assertEqual(status, 200)
        action = task["next_action"]
        status, after = self._post_node_observation(2, completed=True)
        self.assertEqual(status, 200)

        receipt = {
            "schema_version": "1.0",
            "android_schema_version": "1.0",
            "task_id": "observation-session",
            "receipt_id": "android-receipt-explicit-after",
            "action_id": action["action_id"],
            "device_id": self.device_id,
            "accepted": True,
            "outcome": "EXECUTED",
            "received_at": "2026-09-22T00:00:01Z",
            "error_code": None,
            "error_message": None,
            "observation_id": action["observation_id"],
            "observation_version": action["observation_version"],
            "after_observation_id": "android-phone-01-node-obs-2",
            "after_observation_version": 2,
            "deduplicated": False,
        }
        status, final = self._request(
            "POST", "/v1/android/tasks/observation-session/receipt", payload=receipt
        )
        self.assertEqual(status, 200)
        self.assertEqual(final["state"], "SUCCEEDED")
        self.assertEqual(final["verification"]["after_observation_id"], "android-phone-01-node-obs-2")

    def test_android_task_rejects_unsupported_or_obscured_targets(self) -> None:
        self._pair()
        self._post_node_observation()
        status, result = self._submit_node_task("点击不存在的按钮")
        self.assertEqual(status, 422)
        self.assertEqual(result["error"]["code"], "unsupported_goal")

        self._pair()
        self._post_node_observation(2, obscured=True)
        status, result = self._submit_node_task("点击“切换受控状态”按钮")
        self.assertEqual(status, 409)
        self.assertEqual(result["error"]["code"], "target_obscured")

    def test_android_task_click_accepts_android_button_all_caps_text(self) -> None:
        self._pair()
        payload = node_task_observation()
        payload["nodes"][1]["text"] = "TOGGLE CONTROLLED STATE"
        status, accepted = self._request("POST", "/v1/android/observations", payload=payload)
        self.assertEqual(status, 200)
        self.assertTrue(accepted["accepted"])
        status, task = self._submit_node_task("点击“切换受控状态”按钮")
        self.assertEqual(status, 200)
        self.assertEqual(task["next_action"]["kind"], "tap")

    def test_android_task_pause_cancel_and_late_receipt_keep_single_task_boundary(self) -> None:
        self._pair()
        self._post_node_observation()
        status, task = self._submit_node_task("点击“切换受控状态”按钮")
        self.assertEqual(status, 200)
        action = task["next_action"]
        status, paused = self._request("POST", "/v1/android/tasks/observation-session/pause", payload={})
        self.assertEqual(status, 200)
        self.assertEqual(paused["state"], "PAUSED")
        self.assertIsNone(paused["next_action"])

        late_receipt = {
            "schema_version": "1.0",
            "android_schema_version": "1.0",
            "task_id": "observation-session",
            "receipt_id": "android-receipt-late",
            "action_id": action["action_id"],
            "device_id": self.device_id,
            "accepted": True,
            "outcome": "EXECUTED",
            "received_at": "2026-09-22T00:00:01Z",
            "error_code": None,
            "error_message": None,
            "observation_id": action["observation_id"],
            "observation_version": action["observation_version"],
            "deduplicated": False,
        }
        status, late = self._request("POST", "/v1/android/tasks/observation-session/receipt", payload=late_receipt)
        self.assertEqual(status, 200)
        self.assertEqual(late["state"], "PAUSED")
        self.assertEqual(late["phase"], "PAUSED")
        status, blocked = self._submit_node_task("点击“切换受控状态”按钮")
        self.assertEqual(status, 409)
        self.assertEqual(blocked["error"]["code"], "task_active")

        status, cancelled = self._request("POST", "/v1/android/tasks/observation-session/cancel", payload={})
        self.assertEqual(status, 200)
        self.assertEqual(cancelled["state"], "CANCELLED")
        status, duplicate = self._request("POST", "/v1/android/tasks/observation-session/cancel", payload={})
        self.assertEqual(status, 200)
        self.assertEqual(duplicate["state"], "CANCELLED")

    def test_android_task_late_receipt_preserves_cancelled_terminal_phase(self) -> None:
        self._pair()
        self._post_node_observation()
        status, task = self._submit_node_task("点击“切换受控状态”按钮")
        self.assertEqual(status, 200)
        action = task["next_action"]

        status, cancelled = self._request("POST", "/v1/android/tasks/observation-session/cancel", payload={})
        self.assertEqual(status, 200)
        self.assertEqual(cancelled["state"], "CANCELLED")
        self.assertEqual(cancelled["phase"], "CANCELLED")
        self.assertTrue(cancelled["action_result_unknown"])

        status, after = self._post_node_observation(2, completed=True)
        self.assertEqual(status, 200)
        self.assertTrue(after["accepted"])
        late_receipt = {
            "schema_version": "1.0",
            "android_schema_version": "1.0",
            "task_id": "observation-session",
            "receipt_id": "android-receipt-cancelled-late",
            "action_id": action["action_id"],
            "device_id": self.device_id,
            "accepted": True,
            "outcome": "EXECUTED",
            "received_at": "2026-09-22T00:00:01Z",
            "error_code": None,
            "error_message": None,
            "observation_id": action["observation_id"],
            "observation_version": action["observation_version"],
            "after_observation_id": "android-phone-01-node-obs-2",
            "after_observation_version": 2,
            "deduplicated": False,
        }
        status, late = self._request(
            "POST", "/v1/android/tasks/observation-session/receipt", payload=late_receipt
        )
        self.assertEqual(status, 200)
        self.assertEqual(late["state"], "CANCELLED")
        self.assertEqual(late["phase"], "CANCELLED")
        self.assertFalse(late["action_result_unknown"])
        self.assertIsNone(late["after_observation_id"])
        self.assertEqual(late["receipt"]["after_observation_id"], "android-phone-01-node-obs-2")


if __name__ == "__main__":
    unittest.main()
