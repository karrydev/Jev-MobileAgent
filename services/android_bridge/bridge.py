"""Authenticated localhost HTTP receiver for Android accessibility observations."""

from __future__ import annotations

import copy
import http.client
import json
import re
import secrets
import threading
import time
import unicodedata
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable
from urllib.parse import parse_qs, urlparse

from .schema import (
    ANDROID_SCHEMA_VERSION,
    PROTOCOL_VERSION,
    SCHEMA_VERSION,
    SchemaValidationError,
    assert_observation_valid,
    assert_valid,
)

Clock = Callable[[], str]


_INPUT_GOAL_PATTERNS = (
    re.compile(r"^在中文输入框中?输入[“\"](?P<text>.*)[”\"]$"),
    re.compile(r"^在中文输入框中?输入(?P<text>.+)$"),
)


def _normalise_goal(goal: str) -> str:
    return unicodedata.normalize("NFKC", goal).strip()


def _parse_node_goal(goal: str) -> dict[str, Any]:
    """Map the small deterministic acceptance vocabulary to a complete action plan."""

    raw_goal = goal.strip()
    normalised = _normalise_goal(raw_goal).replace("“", "").replace("”", "").replace('"', "")
    if normalised in {
        "点击切换受控状态",
        "点击切换受控状态按钮",
        "点击Toggle controlled state",
        "点击Toggle controlled state按钮",
    }:
        return {
            "kind": "tap",
            "target_label": "Toggle controlled state",
            "target_role": "button",
            "expected_page_state": "controlled_state_completed",
    }
    for pattern in _INPUT_GOAL_PATTERNS:
        # Match the syntax on the original text so full-width punctuation and
        # Chinese input are preserved byte-for-byte in the action parameter.
        match = pattern.fullmatch(raw_goal)
        if match is None:
            match = pattern.fullmatch(_normalise_goal(raw_goal))
        if match is not None:
            value = match.group("text")
            if value:
                return {
                    "kind": "set_text",
                    "target_label": "中文输入框",
                    "target_role": "edit_text",
                    "expected_page_state": "input_applied",
                    "input_text": value,
                }
    raise BridgeRequestError(
        422,
        "unsupported_goal",
        "supported deterministic goals are clicking the controlled-state button or entering text in 中文输入框",
    )


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class BridgeRequestError(ValueError):
    def __init__(self, status: int, code: str, message: str):
        self.status = status
        self.code = code
        self.message = message
        super().__init__(message)


class AndroidBridge:
    _PENDING_CONTROL_TTL_SECONDS = 5.0

    """Stores one paired device's latest observation with freshness guards."""

    def __init__(
        self,
        *,
        token: str,
        device_id: str,
        clock: Clock = utc_now,
        freshness_seconds: float = 30.0,
    ):
        if not token:
            raise ValueError("Android bridge token must be supplied explicitly")
        if not device_id:
            raise ValueError("Android bridge device_id must be supplied explicitly")
        if freshness_seconds < 0:
            raise ValueError("freshness_seconds must be non-negative")
        self.token = token
        self.device_id = device_id
        self.clock = clock
        self.freshness_seconds = freshness_seconds
        self._lock = threading.RLock()
        self._paired = False
        self._client_name: str | None = None
        self._last_seen_at: str | None = None
        self._latest: dict[str, Any] | None = None
        self._observation_history: dict[str, dict[str, Any]] = {}
        self._received_at: str | None = None
        self._last_observation_monotonic: float | None = None
        self._tasks: dict[str, dict[str, Any]] = {}
        self._active_task_id: str | None = None
        # A control button can be pressed while the App's submit request is
        # still in flight.  Keep that intent briefly so the successful submit
        # consumes it before exposing an executable action.
        self._pending_controls: dict[str, dict[str, Any]] = {}

    @property
    def paired(self) -> bool:
        with self._lock:
            return self._paired

    def pair(self, payload: dict[str, Any]) -> dict[str, Any]:
        try:
            assert_valid(payload, "pair")
        except SchemaValidationError as exc:
            raise BridgeRequestError(400, "invalid_schema", str(exc)) from exc
        if payload["device_id"] != self.device_id:
            raise BridgeRequestError(403, "device_identity_mismatch", "pairing device does not match this bridge")
        with self._lock:
            if self._active_task_id is not None:
                active = self._tasks.get(self._active_task_id)
                if active is not None and active["state"] == "RUNNING":
                    active["state"] = "PAUSED"
                    active["failure"] = {
                        "code": "device_session_restarted",
                        "message": "pairing started a new observation session; user confirmation is required",
                    }
                    active["control"] = {
                        "command": "pause",
                        "reason": "device_session_restarted",
                        "requested_at": self.clock(),
                    }
            self._paired = True
            self._client_name = payload["client_name"]
            self._last_seen_at = self.clock()
            # Pairing starts a new observation session.  Do not expose a tree
            # captured by an earlier session while the app is still waiting
            # for its first fresh observation.
            self._latest = None
            self._observation_history.clear()
            self._received_at = None
            self._last_observation_monotonic = None
            self._pending_controls.clear()
        return {
            "schema_version": SCHEMA_VERSION,
            "android_schema_version": ANDROID_SCHEMA_VERSION,
            "device_id": self.device_id,
            "protocol_version": PROTOCOL_VERSION,
            "paired": True,
            "server_time": self.clock(),
        }

    def receive_observation(self, observation: dict[str, Any]) -> dict[str, Any]:
        try:
            assert_observation_valid(observation)
        except SchemaValidationError as exc:
            raise BridgeRequestError(400, "invalid_schema", str(exc)) from exc
        if observation["device_id"] != self.device_id:
            raise BridgeRequestError(403, "device_identity_mismatch", "observation device is not paired with this bridge")
        with self._lock:
            if not self._paired:
                raise BridgeRequestError(409, "device_not_paired", "pair the Android app before sending observations")
            if self._latest is not None:
                previous_version = self._latest["observation_version"]
                if observation["observation_version"] <= previous_version:
                    raise BridgeRequestError(
                        409,
                        "stale_observation",
                        "observation_version must increase monotonically for this device",
                    )
            received_at = self.clock()
            self._latest = copy.deepcopy(observation)
            self._observation_history[observation["observation_id"]] = copy.deepcopy(observation)
            if len(self._observation_history) > 128:
                oldest_id = next(iter(self._observation_history))
                del self._observation_history[oldest_id]
            self._received_at = received_at
            self._last_seen_at = received_at
            self._last_observation_monotonic = time.monotonic()
            self._maybe_verify_task_locked()
            return {
                "schema_version": SCHEMA_VERSION,
                "android_schema_version": ANDROID_SCHEMA_VERSION,
                "device_id": self.device_id,
                "observation_id": observation["observation_id"],
                "observation_version": observation["observation_version"],
                "accepted": True,
                "received_at": received_at,
            }

    @staticmethod
    def _bounds_contains(bounds: dict[str, Any], x: float, y: float) -> bool:
        return (
            isinstance(bounds, dict)
            and bounds.get("left", 0) <= x <= bounds.get("right", 0)
            and bounds.get("top", 0) <= y <= bounds.get("bottom", 0)
        )

    def _target_window_locked(self, observation: dict[str, Any], node_id: str) -> dict[str, Any] | None:
        nodes = {node["node_id"]: node for node in observation["nodes"]}
        windows_by_root = {window.get("root_node_id"): window for window in observation["windows"]}
        current = node_id
        visited: set[str] = set()
        while current and current not in visited:
            visited.add(current)
            window = windows_by_root.get(current)
            if window is not None:
                return window
            node = nodes.get(current)
            current = node.get("parent_node_id") if node else None
        return None

    def _find_target_locked(self, observation: dict[str, Any], plan: dict[str, Any]) -> dict[str, Any]:
        candidates: list[dict[str, Any]] = []
        for node in observation["nodes"]:
            label = plan["target_label"]
            if plan["kind"] == "tap":
                text = str(node.get("text") or "")
                description = str(node.get("content_description") or "")
                matches_label = (
                    text == label
                    or text.casefold() == label.casefold()
                    or description == label
                    or description.casefold() == f"{label} button".casefold()
                )
                matches_role = "button" in node.get("class_name", "").lower()
            else:
                matches_label = node.get("content_description") == label
                matches_role = bool(node.get("editable"))
            if matches_label and matches_role:
                candidates.append(node)
        if not candidates:
            raise BridgeRequestError(409, "target_node_not_found", "the requested node is not present in the current observation")
        node = candidates[0]
        if not node.get("enabled") or not node.get("visible_to_user"):
            raise BridgeRequestError(409, "target_node_not_actionable", "the requested node is disabled or not visible")
        bounds = node.get("bounds") or {}
        if bounds.get("right", 0) <= bounds.get("left", 0) or bounds.get("bottom", 0) <= bounds.get("top", 0):
            raise BridgeRequestError(409, "target_node_not_actionable", "the requested node has no actionable screen bounds")
        target_window = self._target_window_locked(observation, node["node_id"])
        if target_window is None or not target_window.get("active") or not target_window.get("focused"):
            raise BridgeRequestError(409, "target_window_not_active", "the target is not in the active focused window")
        center_x = (bounds["left"] + bounds["right"]) / 2
        center_y = (bounds["top"] + bounds["bottom"]) / 2
        target_layer = target_window.get("layer", 0)
        for window in observation["windows"]:
            if window is target_window or window.get("layer", 0) <= target_layer:
                continue
            if self._bounds_contains(window.get("bounds") or {}, center_x, center_y):
                raise BridgeRequestError(409, "target_obscured", "a higher accessibility window obscures the target")
        return node

    def _record_task_event_locked(self, task: dict[str, Any], kind: str, entity_id: str) -> None:
        sequence = len(task["trace"])
        task["trace"].append({
            "event_id": f"android-event-{task['task_id']}-{sequence}",
            "task_id": task["task_id"],
            "sequence": sequence,
            "kind": kind,
            "entity_id": entity_id,
            "recorded_at": self.clock(),
        })

    def _task_status_locked(self, task: dict[str, Any], *, mark_command: bool = False) -> dict[str, Any]:
        if mark_command and task["state"] == "RUNNING" and task["receipt"] is None and task.get("action") is not None:
            task["command_delivered"] = True
        next_action = None
        if task["state"] == "RUNNING" and task["receipt"] is None and not task.get("action_result_unknown"):
            next_action = copy.deepcopy(task.get("action"))
        status = {
            "schema_version": SCHEMA_VERSION,
            "android_schema_version": ANDROID_SCHEMA_VERSION,
            "task_id": task["task_id"],
            "device_id": self.device_id,
            "goal": task["goal"],
            "state": task["state"],
            "phase": task.get("phase", "RUNNING"),
            "action": copy.deepcopy(task.get("action")),
            "next_action": next_action,
            "receipt": copy.deepcopy(task.get("receipt")),
            "verification": copy.deepcopy(task.get("verification")),
            "failure": copy.deepcopy(task.get("failure")),
            "control": copy.deepcopy(task.get("control")),
            "before_observation_id": task.get("before_observation_id"),
            "after_observation_id": task.get("after_observation_id"),
            "action_result_unknown": bool(task.get("action_result_unknown")),
            "trace": copy.deepcopy(task["trace"]),
        }
        return status

    def submit_task(self, payload: dict[str, Any]) -> dict[str, Any]:
        try:
            assert_valid(payload, "task_submit")
        except SchemaValidationError as exc:
            raise BridgeRequestError(400, "invalid_schema", str(exc)) from exc
        if payload["device_id"] != self.device_id:
            raise BridgeRequestError(403, "device_identity_mismatch", "task device is not paired with this bridge")
        with self._lock:
            if not self._paired:
                raise BridgeRequestError(409, "device_not_paired", "pair the Android app before submitting a task")
            if self._active_task_id is not None:
                raise BridgeRequestError(409, "task_active", "one Android task is already active")
            if payload["task_id"] in self._tasks:
                raise BridgeRequestError(409, "task_exists", "task_id already exists")
            if self._latest is None or not self._is_current_locked():
                raise BridgeRequestError(409, "observation_unavailable", "a fresh Accessibility observation is required before starting a task")
            observation = self._latest
            if observation["task_id"] != payload["task_id"]:
                raise BridgeRequestError(409, "task_observation_mismatch", "task_id must match the paired App observation session")
            if observation["availability"] != "AVAILABLE":
                raise BridgeRequestError(409, "permission_unavailable", "the App has no current actionable Accessibility tree")
            plan = _parse_node_goal(payload["goal"])
            target = self._find_target_locked(observation, plan)
            task_id = payload["task_id"]
            session_id = f"android-session-{task_id}"
            action: dict[str, Any] = {
                "schema_version": SCHEMA_VERSION,
                "android_schema_version": ANDROID_SCHEMA_VERSION,
                "task_id": task_id,
                "device_id": self.device_id,
                "session_id": session_id,
                "action_id": f"android-action-{task_id}",
                "observation_id": observation["observation_id"],
                "observation_version": observation["observation_version"],
                "sequence": 1,
                "kind": plan["kind"],
                "target_node_id": target["node_id"],
                "target_node_label": plan["target_label"],
                "target_node_role": plan["target_role"],
                "expected_page_state": plan["expected_page_state"],
                "parameters": {"text": plan["input_text"]} if plan["kind"] == "set_text" else {},
                "source": "deterministic-android-node-strategy",
                "created_at": self.clock(),
            }
            try:
                assert_valid(action, "action")
            except SchemaValidationError as exc:  # pragma: no cover - contract regression guard
                raise BridgeRequestError(500, "bridge_contract_error", str(exc)) from exc
            task = {
                "task_id": task_id,
                "device_id": self.device_id,
                "goal": payload["goal"],
                "state": "RUNNING",
                "phase": "WAITING_RECEIPT",
                "action": action,
                "receipt": None,
                "verification": None,
                "failure": None,
                "control": None,
                "before_observation_id": observation["observation_id"],
                "after_observation_id": None,
                "action_result_unknown": False,
                "command_delivered": True,
                "post_observation_min_version": None,
                "post_observation_id": None,
                "post_observation_version": None,
                "trace": [],
            }
            self._tasks[task_id] = task
            self._active_task_id = task_id
            self._record_task_event_locked(task, "task.submitted", task_id)
            self._record_task_event_locked(task, "observation.captured", observation["observation_id"])
            self._record_task_event_locked(task, "action.dispatched", action["action_id"])
            pending_control = self._pending_controls.pop(task_id, None)
            if (
                pending_control is not None
                and time.monotonic() - pending_control["requested_monotonic"] > self._PENDING_CONTROL_TTL_SECONDS
            ):
                pending_control = None
            if pending_control is not None:
                # The control was requested before this submit completed, so
                # no action was delivered to the App.  Applying the intent
                # here closes the submit/control race without classifying a
                # never-run action as UNKNOWN.
                task["command_delivered"] = False
                self._apply_control_locked(task, pending_control["command"], pending_control["reason"])
            return self._task_status_locked(task)

    def task_status(self, task_id: str) -> dict[str, Any]:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                raise BridgeRequestError(404, "task_not_found", "task does not exist")
            return self._task_status_locked(task, mark_command=True)

    def control_task(self, task_id: str, command: str, reason: str | None = None) -> dict[str, Any]:
        if command not in {"pause", "cancel"}:
            raise BridgeRequestError(400, "invalid_control", "control command must be pause or cancel")
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                # The Android App may send the control request before the
                # submit response creates the task.  Preserve it locally and
                # keep the public 404 response honest; submit_task consumes
                # this intent atomically if it succeeds shortly afterwards.
                if self._active_task_id is None:
                    self._pending_controls[task_id] = {
                        "command": command,
                        "reason": reason or "user_" + command,
                        "requested_at": self.clock(),
                        "requested_monotonic": time.monotonic(),
                    }
                raise BridgeRequestError(404, "task_not_found", "task does not exist")
            if reason is None:
                reason = "user_" + command
            self._apply_control_locked(task, command, reason)
            return self._task_status_locked(task)

    def _apply_control_locked(self, task: dict[str, Any], command: str, reason: str) -> None:
        task_id = task["task_id"]
        if command == "pause" and task["state"] == "RUNNING":
            task["state"] = "PAUSED"
            task["phase"] = "PAUSED"
            task["control"] = {"command": command, "reason": reason, "requested_at": self.clock()}
            self._record_task_event_locked(task, "task.paused", task_id)
            if task.get("command_delivered") and task.get("receipt") is None:
                task["action_result_unknown"] = True
        elif command == "cancel" and task["state"] not in {"SUCCEEDED", "FAILED", "CANCELLED"}:
            task["state"] = "CANCELLED"
            task["phase"] = "CANCELLED"
            task["control"] = {"command": command, "reason": reason, "requested_at": self.clock()}
            self._record_task_event_locked(task, "task.cancelled", task_id)
            if task.get("command_delivered") and task.get("receipt") is None:
                task["action_result_unknown"] = True
            elif self._active_task_id == task_id:
                self._active_task_id = None

    def receive_receipt(self, task_id: str, receipt: dict[str, Any]) -> dict[str, Any]:
        try:
            assert_valid(receipt, "receipt")
        except SchemaValidationError as exc:
            raise BridgeRequestError(400, "invalid_schema", str(exc)) from exc
        after_id = receipt.get("after_observation_id")
        after_version = receipt.get("after_observation_version")
        if (after_id is None) != (after_version is None):
            raise BridgeRequestError(
                400,
                "invalid_schema",
                "after_observation_id and after_observation_version must be supplied together",
            )
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                raise BridgeRequestError(404, "task_not_found", "task does not exist")
            if receipt["task_id"] != task_id or receipt["device_id"] != self.device_id:
                raise BridgeRequestError(403, "device_identity_mismatch", "receipt is not associated with this task and device")
            action = task.get("action")
            if action is None or receipt["action_id"] != action["action_id"]:
                raise BridgeRequestError(409, "action_mismatch", "receipt does not match the current task action")
            associated_after = None
            if after_id is not None:
                if after_version <= action["observation_version"]:
                    raise BridgeRequestError(
                        409,
                        "stale_observation",
                        "the post-action observation must be newer than the action observation",
                    )
                associated_after = self._observation_history.get(after_id)
                if associated_after is None:
                    raise BridgeRequestError(
                        409,
                        "observation_unavailable",
                        "the explicitly associated post-action observation is not available",
                    )
                if (
                    associated_after["observation_version"] != after_version
                    or associated_after["task_id"] != task_id
                    or associated_after["device_id"] != self.device_id
                ):
                    raise BridgeRequestError(
                        409,
                        "observation_mismatch",
                        "the explicitly associated post-action observation does not match this task",
                    )
            if task.get("receipt") is not None:
                duplicate = copy.deepcopy(task["receipt"])
                duplicate["deduplicated"] = True
                return {**self._task_status_locked(task), "receipt": duplicate}
            task["receipt"] = copy.deepcopy(receipt)
            self._record_task_event_locked(task, "receipt.received", receipt["receipt_id"])
            if task["state"] in {"CANCELLED", "PAUSED"}:
                if task.get("action_result_unknown"):
                    task["action_result_unknown"] = False
                    if task["state"] == "CANCELLED" and self._active_task_id == task_id:
                        self._active_task_id = None
                return self._task_status_locked(task)
            task["phase"] = "WAITING_OBSERVATION"
            if not receipt["accepted"]:
                task["state"] = "FAILED"
                task["phase"] = "FAILED"
                task["failure"] = {
                    "code": receipt.get("error_code") or "action_rejected",
                    "message": receipt.get("error_message") or "the Android Accessibility service rejected the action",
                }
                self._active_task_id = None
                self._record_task_event_locked(task, "task.failed", task_id)
            else:
                # The latest observation may have been captured before the
                # Accessibility action actually ran.  Record the version
                # visible at receipt time and require a later upload before
                # evaluating the postcondition.  Verification is therefore
                # driven only from receive_observation, never from a receipt
                # alone.
                latest = self._latest
                task["post_observation_min_version"] = (
                    latest["observation_version"] if latest is not None else action["observation_version"]
                )
                if after_id is not None:
                    task["post_observation_id"] = after_id
                    task["post_observation_version"] = after_version
                    # An explicitly associated observation can have arrived
                    # before the receipt response was delivered.  It is still
                    # the only observation eligible for this action.
                    self._maybe_verify_task_locked()
            return self._task_status_locked(task)

    def _maybe_verify_task_locked(self) -> None:
        task_id = self._active_task_id
        if task_id is None:
            return
        task = self._tasks.get(task_id)
        if task is None or task["state"] != "RUNNING" or task.get("receipt") is None:
            return
        target_id = task.get("post_observation_id")
        if target_id is not None:
            observation = self._observation_history.get(target_id)
            if (
                observation is None
                or observation["observation_version"] != task.get("post_observation_version")
                or observation["observation_version"] <= task["action"]["observation_version"]
            ):
                return
        else:
            observation = self._latest
            minimum_version = task.get("post_observation_min_version")
            if (
                observation is None
                or minimum_version is None
                or observation["observation_version"] <= minimum_version
                or observation["task_id"] != task_id
                or observation["device_id"] != self.device_id
            ):
                return
        action = task["action"]
        if observation["availability"] != "AVAILABLE":
            task["state"] = "PAUSED"
            task["phase"] = "PAUSED"
            task["verification"] = {
                "schema_version": SCHEMA_VERSION,
                "android_schema_version": ANDROID_SCHEMA_VERSION,
                "verification_id": f"android-verification-{task_id}",
                "task_id": task_id,
                "action_id": action["action_id"],
                "before_observation_id": task["before_observation_id"],
                "after_observation_id": observation["observation_id"],
                "status": "UNKNOWN",
                "expected_page_state": action["expected_page_state"],
                "actual_page_state": observation["availability"],
                "reason": "post_observation_unavailable",
                "verified_at": self.clock(),
            }
            task["failure"] = {"code": "observation_unavailable", "message": "the post-action Accessibility observation is unavailable"}
            self._record_task_event_locked(task, "verification.recorded", task["verification"]["verification_id"])
            return
        successful = False
        if action["kind"] == "tap":
            successful = any(
                node.get("text") == "Controlled action state: completed"
                or node.get("content_description") == "Controlled action state completed"
                for node in observation["nodes"]
            )
        elif action["kind"] == "set_text":
            expected_text = (action.get("parameters") or {}).get("text", "")
            successful = any(
                node.get("content_description") == "中文输入框" and node.get("text") == expected_text
                for node in observation["nodes"]
            )
        verification = {
            "schema_version": SCHEMA_VERSION,
            "android_schema_version": ANDROID_SCHEMA_VERSION,
            "verification_id": f"android-verification-{task_id}",
            "task_id": task_id,
            "action_id": action["action_id"],
            "before_observation_id": task["before_observation_id"],
            "after_observation_id": observation["observation_id"],
            "status": "SUCCESS" if successful else "FAILURE",
            "expected_page_state": action["expected_page_state"],
            "actual_page_state": action["expected_page_state"] if successful else "observed",
            "reason": "postcondition_met" if successful else "postcondition_not_met",
            "verified_at": self.clock(),
        }
        task["verification"] = verification
        task["after_observation_id"] = observation["observation_id"]
        task["state"] = "SUCCEEDED" if successful else "FAILED"
        task["phase"] = task["state"]
        if not successful:
            task["failure"] = {"code": "postcondition_not_met", "message": "the fresh Accessibility observation did not satisfy the task goal"}
        self._active_task_id = None
        self._record_task_event_locked(task, "observation.after_captured", observation["observation_id"])
        self._record_task_event_locked(task, "verification.recorded", verification["verification_id"])
        self._record_task_event_locked(task, "task.completed" if successful else "task.failed", task_id)

    def _is_current_locked(self) -> bool:
        return (
            self._last_observation_monotonic is not None
            and time.monotonic() - self._last_observation_monotonic <= self.freshness_seconds
        )

    def _connection_status_locked(self) -> str:
        if not self._paired or not self._is_current_locked():
            return "DISCONNECTED"
        if self._latest is None:
            return "DISCONNECTED"
        if self._latest["availability"] in {"PERMISSION_UNAVAILABLE", "DISCONNECTED"}:
            return "DISCONNECTED"
        return "CONNECTED"

    def latest(self) -> dict[str, Any]:
        with self._lock:
            if self._latest is None or self._received_at is None:
                raise BridgeRequestError(404, "observation_unavailable", "no Android observation has been received")
            if not self._is_current_locked():
                raise BridgeRequestError(
                    409,
                    "observation_stale",
                    "the latest Android observation is no longer current; capture a new observation",
                )
            result = {
                "schema_version": SCHEMA_VERSION,
                "android_schema_version": ANDROID_SCHEMA_VERSION,
                "device_id": self.device_id,
                "connection_status": self._connection_status_locked(),
                "is_current": True,
                "received_at": self._received_at,
                "observation": copy.deepcopy(self._latest),
            }
        try:
            assert_valid(result, "latest_response")
        except SchemaValidationError as exc:  # pragma: no cover - defensive invariant
            raise BridgeRequestError(500, "bridge_contract_error", str(exc)) from exc
        return result

    def status(self) -> dict[str, Any]:
        with self._lock:
            latest = self._latest
            return {
                "schema_version": SCHEMA_VERSION,
                "android_schema_version": ANDROID_SCHEMA_VERSION,
                "device_id": self.device_id,
                "paired": self._paired,
                "connection_status": self._connection_status_locked(),
                "last_seen_at": self._last_seen_at,
                "latest_observation_id": latest["observation_id"] if latest else None,
                "latest_observation_version": latest["observation_version"] if latest else None,
                "latest_availability": latest["availability"] if latest else None,
            }


MAX_BODY_BYTES = 2 * 1024 * 1024


def _error_payload(code: str, message: str) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "android_schema_version": ANDROID_SCHEMA_VERSION,
        "error": {"code": code, "message": message},
    }


def _send_json(handler: BaseHTTPRequestHandler, status: int, payload: dict[str, Any]) -> None:
    body = json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def _read_json(handler: BaseHTTPRequestHandler) -> dict[str, Any]:
    content_type = handler.headers.get("Content-Type", "")
    if content_type.lower().split(";", 1)[0].strip() != "application/json":
        raise BridgeRequestError(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "application/json is required")
    try:
        content_length = int(handler.headers.get("Content-Length", "-1"))
    except ValueError:
        content_length = -1
    if content_length < 0 or content_length > MAX_BODY_BYTES:
        raise BridgeRequestError(HTTPStatus.BAD_REQUEST, "invalid_content_length", "request body length is invalid")
    try:
        value = json.loads(handler.rfile.read(content_length).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BridgeRequestError(HTTPStatus.BAD_REQUEST, "malformed_json", "request body is not valid JSON") from exc
    if not isinstance(value, dict):
        raise BridgeRequestError(HTTPStatus.BAD_REQUEST, "invalid_json_shape", "request body must be a JSON object")
    return value


def create_server(bridge: AndroidBridge, host: str = "127.0.0.1", port: int = 0) -> ThreadingHTTPServer:
    class AndroidBridgeHandler(BaseHTTPRequestHandler):
        server_version = "JevAndroidBridge/1"

        def _guard(self) -> None:
            supplied = self.headers.get("Authorization", "")
            if not secrets.compare_digest(supplied, f"Bearer {bridge.token}"):
                raise BridgeRequestError(HTTPStatus.UNAUTHORIZED, "unauthorized", "valid bearer authentication is required")
            supplied_protocol = self.headers.get("X-JEV-Protocol-Version")
            try:
                protocol = int(supplied_protocol) if supplied_protocol is not None else None
            except ValueError:
                protocol = None
            if protocol != PROTOCOL_VERSION:
                raise BridgeRequestError(
                    HTTPStatus.UPGRADE_REQUIRED,
                    "unsupported_protocol_version",
                    f"X-JEV-Protocol-Version must be {PROTOCOL_VERSION}",
                )
            if self.headers.get("X-JEV-Device-Id") != bridge.device_id:
                raise BridgeRequestError(HTTPStatus.FORBIDDEN, "device_identity_mismatch", "device identity is not recognized")

        def do_POST(self) -> None:  # noqa: N802 - stdlib handler API
            try:
                self._guard()
                path = urlparse(self.path).path
                if self.path == "/v1/android/pair":
                    _send_json(self, 200, bridge.pair(_read_json(self)))
                    return
                if self.path == "/v1/android/observations":
                    _send_json(self, 200, bridge.receive_observation(_read_json(self)))
                    return
                segments = [part for part in path.split("/") if part]
                task_prefix = segments[:2] in (["v1", "tasks"], ["v1", "android"])
                if task_prefix and len(segments) == 2 and segments[1] == "tasks":
                    _send_json(self, 200, bridge.submit_task(_read_json(self)))
                    return
                if task_prefix and len(segments) == 3 and segments[1] == "android" and segments[2] == "tasks":
                    _send_json(self, 200, bridge.submit_task(_read_json(self)))
                    return
                if len(segments) >= 3 and ((segments[:2] == ["v1", "tasks"]) or (segments[:3] == ["v1", "android", "tasks"])):
                    task_offset = 2 if segments[:2] == ["v1", "tasks"] else 3
                    if len(segments) == task_offset + 2 and segments[task_offset + 1] in {"pause", "cancel", "control"}:
                        payload = {}
                        if self.headers.get("Content-Length", "") not in {"", "0"}:
                            payload = _read_json(self)
                        command = payload.get("command", segments[task_offset + 1])
                        if segments[task_offset + 1] == "control" and command not in {"pause", "cancel"}:
                            raise BridgeRequestError(400, "invalid_control", "control command must be pause or cancel")
                        _send_json(self, 200, bridge.control_task(
                            segments[task_offset], command, payload.get("reason")))
                        return
                    if len(segments) == task_offset + 2 and segments[task_offset + 1] == "receipt":
                        _send_json(self, 200, bridge.receive_receipt(segments[task_offset], _read_json(self)))
                        return
                raise BridgeRequestError(404, "not_found", "Android bridge endpoint not found")
            except BridgeRequestError as exc:
                _send_json(self, exc.status, _error_payload(exc.code, exc.message))
            except Exception:
                _send_json(self, 500, _error_payload("bridge_internal_error", "Android bridge failed"))

        def do_GET(self) -> None:  # noqa: N802 - stdlib handler API
            try:
                self._guard()
                parsed = urlparse(self.path)
                path = parsed.path
                query = parse_qs(parsed.query)
                segments = [part for part in path.split("/") if part]
                if len(segments) == 3 and segments[:2] == ["v1", "tasks"]:
                    _send_json(self, 200, bridge.task_status(segments[2]))
                    return
                if len(segments) == 4 and segments[:3] == ["v1", "android", "tasks"]:
                    _send_json(self, 200, bridge.task_status(segments[3]))
                    return
                device_ids = query.get("device_id", [])
                if device_ids != [bridge.device_id]:
                    raise BridgeRequestError(400, "invalid_device_id", "device_id query parameter must match the paired device")
                if parsed.path == "/v1/android/observations/latest":
                    _send_json(self, 200, bridge.latest())
                    return
                if parsed.path == "/v1/android/status":
                    _send_json(self, 200, bridge.status())
                    return
                raise BridgeRequestError(404, "not_found", "Android bridge endpoint not found")
            except BridgeRequestError as exc:
                _send_json(self, exc.status, _error_payload(exc.code, exc.message))
            except Exception:
                _send_json(self, 500, _error_payload("bridge_internal_error", "Android bridge failed"))

        def log_message(self, format: str, *args: Any) -> None:
            return

    return ThreadingHTTPServer((host, port), AndroidBridgeHandler)
