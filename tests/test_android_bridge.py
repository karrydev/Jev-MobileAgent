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


if __name__ == "__main__":
    unittest.main()
