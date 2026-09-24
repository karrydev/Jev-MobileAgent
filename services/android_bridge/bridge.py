"""Authenticated localhost HTTP receiver for Android accessibility observations."""

from __future__ import annotations

import copy
import hashlib
import http.client
import json
import math
import os
import re
import secrets
import threading
import time
import unicodedata
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from numbers import Real
from pathlib import Path
from typing import Any, Callable
from urllib.parse import parse_qs, urlparse
from uuid import uuid4

from services.sim_loop.persistence import load_json, save_json

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
_COORDINATE_GOAL_PATTERN = re.compile(
    r"^(?:点击|点击坐标|坐标点击|点击屏幕坐标)\s*[（(]\s*(?P<x>\d+(?:\.\d+)?)\s*[,，]\s*(?P<y>\d+(?:\.\d+)?)\s*[）)]$"
)


def _normalise_goal(goal: str) -> str:
    return unicodedata.normalize("NFKC", goal).strip()


def _android_scene_fingerprint(observation: dict[str, Any]) -> str:
    """Fingerprint Android-visible scene state, excluding per-capture churn.

    Android observations increment their version on every capture, unlike the
    simulated loop's stable scene version. The App also renders volatile
    ``Task: ...`` and ``Recovery status: ...`` lines inside its activities. Its
    own Reconcile control reports whether recovery is busy, so only that
    control's enabled state is ignored. Window/focus/geometry, task-visible
    controls, permissions, and every other node state remain part of this
    binding.
    """

    nodes = []
    for raw_node in observation.get("nodes", []):
        node = copy.deepcopy(raw_node)
        text = node.get("text")
        description = node.get("content_description")
        is_app_reconcile_control = (
            node.get("package_name") == "com.jev.mobileagent"
            and node.get("class_name") == "android.widget.Button"
            and text in {"RECONCILE", "RECONCILE BEFORE RESUME"}
            and node.get("clickable") is True
        )
        if is_app_reconcile_control:
            # Both app activities disable this internal control while a
            # reconciliation request is in flight. That busy marker is not a
            # change to the device scene the user confirmed.
            node["enabled"] = True
        if (
            isinstance(text, str)
            and (text.startswith("Task:") or text.startswith("Recovery status:"))
        ) or (
            isinstance(description, str)
            and description.startswith("Recovery status:")
        ):
            node["text"] = ""
            if isinstance(description, str) and description.startswith("Recovery status:"):
                node["content_description"] = ""
        nodes.append(node)
    scene = {
        "device_id": observation.get("device_id"),
        "task_id": observation.get("task_id"),
        "page_state": observation.get("page_state"),
        "availability": observation.get("availability"),
        "permission": observation.get("permission"),
        "screen": observation.get("screen"),
        "windows": observation.get("windows"),
        "nodes": nodes,
        "capabilities": observation.get("capabilities"),
    }
    encoded = json.dumps(scene, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


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
    if normalised in {
        "长按切换受控状态",
        "长按切换受控状态按钮",
        "长按Toggle controlled state",
        "长按Toggle controlled state按钮",
    }:
        return {
            "kind": "long_press",
            "target_label": "Toggle controlled state",
            "target_role": "button",
            "expected_page_state": "long_press_completed",
            "parameters": {"duration_ms": 800},
            "requires_screenshot": True,
        }
    if normalised in {"长按视觉目标", "长按自绘目标"}:
        return {
            "kind": "long_press",
            "target_label": "",
            "target_role": "visual_surface",
            "expected_page_state": "long_press_completed",
            "parameters": {
                "fraction_x": 0.5,
                # The controlled page keeps its visual surface around the
                # display center in both portrait and landscape.  Keep the
                # deterministic fixture on that center line so the same
                # strategy remains inside the surface after rotation.
                "fraction_y": 0.5,
                "duration_ms": 800,
                "coordinate_space": "screen",
            },
            "requires_screenshot": True,
        }
    if normalised in {"滑动视觉目标", "滑动自绘目标", "向右滑动视觉目标", "向右滑动"}:
        return {
            "kind": "swipe",
            "target_label": "",
            "target_role": "visual_surface",
            "expected_page_state": "swipe_completed",
            "parameters": {
                "start_fraction_x": 0.25,
                "end_fraction_x": 0.75,
                "fraction_y": 0.5,
                "duration_ms": 600,
                "coordinate_space": "screen",
            },
            "requires_screenshot": True,
        }
    if normalised in {"系统返回", "返回", "按系统返回", "system back", "back"}:
        return {
            "kind": "system_back",
            "target_label": "",
            "target_role": "system_navigation",
            "expected_page_state": "system_back_completed",
            "parameters": {},
            "requires_screenshot": True,
        }
    coordinate_match = _COORDINATE_GOAL_PATTERN.fullmatch(_normalise_goal(raw_goal))
    if coordinate_match is not None:
        return {
            "kind": "coordinate_tap",
            "target_label": "",
            "target_role": "visual_surface",
            "expected_page_state": "coordinate_tap_completed",
            "parameters": {
                "x": float(coordinate_match.group("x")),
                "y": float(coordinate_match.group("y")),
                "coordinate_space": "screen",
            },
            "requires_screenshot": True,
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
                    "parameters": {"text": value},
                }
    raise BridgeRequestError(
        422,
        "unsupported_goal",
        "supported deterministic goals are node click/text, long press, visual swipe, coordinate tap, or system back",
    )


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class BridgeRequestError(ValueError):
    def __init__(self, status: int, code: str, message: str):
        self.status = status
        self.code = code
        self.message = message
        super().__init__(message)


class _VlmTaskStopped(RuntimeError):
    """Internal signal used when a user control stops the model loop."""


class _VlmTaskFailure(RuntimeError):
    def __init__(self, code: str, message: str, *, pause: bool = False):
        self.code = code
        self.message = message
        self.pause = pause
        super().__init__(message)


class AndroidBridge:
    _PENDING_CONTROL_TTL_SECONDS = 5.0
    _JOURNALED_ACTION_RECOVERY_CAPABILITY = "durable_action_journal_then_server_intent_then_effect_v1"

    """Stores one paired device's latest observation with freshness guards."""

    def __init__(
        self,
        *,
        token: str,
        device_id: str,
        clock: Clock = utc_now,
        freshness_seconds: float = 30.0,
        state_path: str | Path | None = None,
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
        self._condition = threading.Condition(self._lock)
        self._paired = False
        self._client_name: str | None = None
        self._recovery_capabilities: set[str] = set()
        self._last_seen_at: str | None = None
        self._latest: dict[str, Any] | None = None
        self._observation_history: dict[str, dict[str, Any]] = {}
        self._received_at: str | None = None
        self._last_observation_monotonic: float | None = None
        self._tasks: dict[str, dict[str, Any]] = {}
        self._active_task_id: str | None = None
        self._screenshots: dict[str, dict[str, Any]] = {}
        self._screenshot_events: dict[str, dict[str, Any]] = {}
        self._screenshot_by_observation: dict[tuple[str, str], str] = {}
        # A control button can be pressed while the App's submit request is
        # still in flight.  Keep that intent briefly so the successful submit
        # consumes it before exposing an executable action.
        self._pending_controls: dict[str, dict[str, Any]] = {}
        self._state_path = Path(state_path).expanduser() if state_path is not None else None
        self._device_session_id: str | None = None
        self._restore_state()

    _PERSISTED_TASK_FIELDS = (
        "task_id", "device_id", "goal", "mode", "state", "phase", "action", "receipt",
        "last_receipt", "verification", "failure", "control", "before_observation_id",
        "after_observation_id", "action_result_unknown", "command_delivered",
        "post_observation_min_version", "post_observation_id", "post_observation_version",
        "requires_screenshot", "before_screenshot_id", "after_screenshot_id", "before_visual",
        "after_visual", "actual_effect", "vlm_completion", "independent_result", "usage",
        "model_info", "vlm_limits", "trace", "recovery", "recovery_required",
        "recovery_reason", "device_session_id", "command_intent", "run_generation",
        "step_budget_used", "vlm_resume_config", "usage_reservations", "usage_history_unknown",
        "action_generation",
        "recovery_action_sequence", "recovery_capability",
    )

    def _restore_state(self) -> None:
        if self._state_path is None:
            return
        restored = load_json(self._state_path)
        if restored is None:
            return
        if restored.get("format") != "jev-android-bridge-state-v1" or restored.get("device_id") != self.device_id:
            raise ValueError("Android bridge state file has an incompatible format or device_id")
        self._device_session_id = restored.get("device_session_id")
        tasks = restored.get("tasks")
        if not isinstance(tasks, dict):
            raise ValueError("Android bridge state file has invalid task records")
        for task_id, saved in tasks.items():
            if not isinstance(saved, dict) or saved.get("task_id") != task_id:
                raise ValueError("Android bridge state file has an invalid task identity")
            task = copy.deepcopy(saved)
            task.setdefault("trace", [])
            task.setdefault("receipt", None)
            task.setdefault("last_receipt", task.get("receipt"))
            task.setdefault("action_result_unknown", False)
            task.setdefault("command_delivered", False)
            if task.get("mode") == "vlm" and "usage_reservations" not in task:
                # Older checkpoints did not record the request boundary. They
                # cannot safely start another paid request after a restart.
                task["usage_history_unknown"] = True
                task["usage_reservations"] = []
            for reservation in task.get("usage_reservations", []):
                if isinstance(reservation, dict) and reservation.get("state") == "IN_FLIGHT":
                    reservation["state"] = "UNKNOWN"
                    reservation["reason"] = "bridge_restarted_during_model_request"
                    task["usage_history_unknown"] = True
            task.pop("vlm_after_observation", None)
            task.pop("vlm_after_screenshot", None)
            task.pop("_vlm_action_started_monotonic", None)
            if task.get("state") not in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                task["state"] = "PAUSED"
                task["phase"] = "PAUSED"
                task["recovery_required"] = True
                task["recovery_reason"] = "bridge_process_restarted"
                recovery_action = self._action_for_recovery_locked(task)
                task["recovery"] = {
                    "phase": "RECONCILIATION_REQUIRED",
                    "reason": "bridge_process_restarted",
                    "eligible": False,
                    "confirmed": False,
                    "resume_token": None,
                    "action_outcome": "UNKNOWN" if recovery_action is not None and task.get("receipt") is None else None,
                    "observation_id": None,
                    "observation_version": None,
                    "scene_fingerprint": None,
                }
                if recovery_action is not None and task.get("receipt") is None:
                    task["action_result_unknown"] = True
                    task["actual_effect"] = {
                        "status": "UNKNOWN",
                        "action_id": recovery_action.get("action_id"),
                        "reason": "bridge_process_restarted_before_receipt",
                    }
                else:
                    task["action_result_unknown"] = False
                task["failure"] = {
                    "code": "bridge_process_restarted",
                    "message": "the bridge restarted; observe and reconcile before explicit resume",
                }
                completion = task.get("vlm_completion")
                if isinstance(completion, dict) and completion.get("status") == "RUNNING":
                    completion["status"] = "PAUSED"
                if task.get("mode") == "vlm":
                    task["usage"] = self._usage_summary_locked(task)
                self._active_task_id = task_id
            self._tasks[task_id] = task
        self._persist_locked()

    def _persist_locked(self) -> None:
        if self._state_path is None:
            return
        self._state_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        try:
            os.chmod(self._state_path.parent, 0o700)
        except OSError:
            pass
        snapshot = {
            "format": "jev-android-bridge-state-v1",
            "device_id": self.device_id,
            "device_session_id": self._device_session_id,
            "tasks": {
                task_id: {key: copy.deepcopy(task[key]) for key in self._PERSISTED_TASK_FIELDS if key in task}
                for task_id, task in self._tasks.items()
            },
        }
        save_json(self._state_path, snapshot)
        try:
            os.chmod(self._state_path, 0o600)
        except OSError:
            pass

    def health(self) -> dict[str, Any]:
        with self._lock:
            pending = sum(
                1 for task in self._tasks.values()
                if task.get("recovery_required") or task.get("state") == "PAUSED" and task.get("recovery")
            )
            return {
                "service_status": "ok",
                "persistence_status": "enabled" if self._state_path is not None else "disabled",
                "pending_reconciliation_count": pending,
            }

    def _check_device_session_locked(self, supplied: str | None) -> None:
        # The additive session header lets upgraded Apps fence requests from a
        # previous process/connection. Missing header remains accepted for old
        # clients; it never grants a new recovery confirmation by itself.
        if supplied is not None and supplied != self._device_session_id:
            raise BridgeRequestError(409, "device_session_mismatch", "request belongs to an older Android App session")

    @staticmethod
    def _usage_summary_locked(task: dict[str, Any]) -> dict[str, Any] | None:
        reservations = task.get("usage_reservations")
        if not isinstance(reservations, list):
            return copy.deepcopy(task.get("usage"))
        original_limits = task.get("vlm_limits") or {}
        total_cost = 0.0
        usage_missing = bool(task.get("usage_history_unknown"))
        attempts: list[dict[str, Any]] = []
        for item in reservations:
            if not isinstance(item, dict):
                usage_missing = True
                continue
            state = item.get("state")
            cost = item.get("estimated_cost_cny")
            if state != "SETTLED" or isinstance(cost, bool) or not isinstance(cost, (int, float)):
                usage_missing = True
            else:
                total_cost += float(cost)
            attempts.append({
                "attempt": item.get("attempt"),
                "role": item.get("role", "unknown"),
                "step": item.get("step", 0),
                "http_status": item.get("http_status"),
                "usage_present": isinstance(item.get("usage"), dict),
                "usage": copy.deepcopy(item.get("usage")),
                "estimated_cost_cny": cost,
                "transport_error": item.get("transport_error"),
            })
        reported = task.get("usage")
        reported_count = reported.get("requests") if isinstance(reported, dict) else None
        if isinstance(reported_count, int) and reported_count > len(reservations):
            # A test/custom transport or a pre-recovery implementation may
            # report requests without durable per-request boundaries.
            usage_missing = True
            for attempt in range(len(reservations) + 1, reported_count + 1):
                attempts.append({
                    "attempt": attempt,
                    "role": "unknown",
                    "step": 0,
                    "http_status": None,
                    "usage_present": False,
                    "usage": None,
                    "estimated_cost_cny": None,
                    "transport_error": "missing_durable_usage_record",
                })
            total_cost = max(
                total_cost,
                float(reported.get("estimated_cost_cny", 0.0))
                if isinstance(reported.get("estimated_cost_cny"), (int, float))
                and not isinstance(reported.get("estimated_cost_cny"), bool)
                else 0.0,
            )
        return {
            "requests": max(len(reservations), reported_count or 0) if isinstance(reported_count, int) else len(reservations),
            "max_requests": original_limits.get("max_requests"),
            "max_tokens": original_limits.get("max_tokens"),
            "estimated_cost_cny": round(total_cost, 9),
            "usage_missing": usage_missing,
            "halted_reason": "usage_missing_or_invalid" if usage_missing else None,
            "attempts": attempts,
        }

    @staticmethod
    def _usage_accounting_locked(task: dict[str, Any]) -> tuple[int, float, bool]:
        summary = AndroidBridge._usage_summary_locked(task)
        if summary is None:
            return 0, 0.0, False
        count = summary.get("requests")
        cost = summary.get("estimated_cost_cny")
        missing = bool(summary.get("usage_missing"))
        if not isinstance(count, int) or isinstance(count, bool):
            return 0, 0.0, True
        if isinstance(cost, bool) or not isinstance(cost, (int, float)):
            return count, 0.0, True
        return count, float(cost), missing

    def _checkpointed_vlm_transport(self, task_id: str, generation: int, model_config: dict[str, Any], transport_type: Any) -> Any:
        """Create the production transport with a durable before/after call ledger."""

        transport_ref: list[Any] = []

        def request_fn(endpoint: str, credential: str, payload: dict[str, Any], timeout: float):
            with self._condition:
                task = self._tasks.get(task_id)
                if (
                    task is None
                    or task["state"] != "RUNNING"
                    or int(task.get("run_generation", 0)) != generation
                ):
                    raise _VlmTaskStopped("task generation is no longer current")
                attempts = task.setdefault("usage_reservations", [])
                transport = transport_ref[0] if transport_ref else None
                model_attempt = transport.attempts[-1] if transport is not None and transport.attempts else {}
                reservation = {
                    "attempt": len(attempts) + 1,
                    "state": "IN_FLIGHT",
                    "role": model_attempt.get("role", "unknown"),
                    "step": model_attempt.get("step", 0),
                    "usage": None,
                    "usage_present": False,
                    "estimated_cost_cny": None,
                    "http_status": None,
                    "transport_error": None,
                }
                attempts.append(reservation)
                task["usage"] = self._usage_summary_locked(task)
                # This fsync-backed ledger write is before any provider bytes
                # leave the process. A surviving IN_FLIGHT entry blocks retry.
                self._persist_locked()

            try:
                result = transport_type._post_json(endpoint, credential, payload, timeout)
            except BaseException as exc:
                with self._condition:
                    task = self._tasks.get(task_id)
                    if task is not None:
                        reservation["state"] = "UNKNOWN"
                        reservation["reason"] = "model_request_ended_without_accounted_response"
                        reservation["transport_error"] = type(exc).__name__
                        task["usage"] = self._usage_summary_locked(task)
                        self._persist_locked()
                raise

            status, response, _response_text, transport_error = result
            raw_usage = response.get("usage") if isinstance(response, dict) else None
            usage: dict[str, Any] | None = None
            cost = transport_type._usage_cost(raw_usage)
            if cost is not None and isinstance(raw_usage, dict):
                usage = {}
                for key in ("prompt_tokens", "input_tokens", "completion_tokens", "output_tokens"):
                    value = raw_usage.get(key)
                    if isinstance(value, Real) and not isinstance(value, bool):
                        numeric = float(value)
                        if math.isfinite(numeric) and numeric >= 0 and numeric == int(numeric):
                            usage[key] = int(numeric)
            with self._condition:
                task = self._tasks.get(task_id)
                if task is not None:
                    reservation["http_status"] = status
                    reservation["transport_error"] = transport_error
                    reservation["usage"] = usage
                    reservation["usage_present"] = usage is not None
                    reservation["estimated_cost_cny"] = cost
                    reservation["state"] = "SETTLED" if cost is not None else "UNKNOWN"
                    if cost is None:
                        reservation["reason"] = "provider_usage_missing_or_invalid"
                    task["usage"] = self._usage_summary_locked(task)
                    # Keep accounting even if this worker was paused while the
                    # request was in flight; its late response cannot dispatch.
                    self._persist_locked()
                    self._condition.notify_all()
            return result

        transport = transport_type(
            endpoint=model_config["endpoint"],
            model=model_config["model"],
            api_key=model_config["api_key"],
            provider=model_config["provider"],
            max_requests=model_config["max_requests"],
            max_tokens=model_config["max_tokens"],
            budget_cny=model_config["budget_cny"],
            timeout_seconds=model_config["timeout_seconds"],
            request_fn=request_fn,
        )
        transport_ref.append(transport)
        return transport

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
                    active["phase"] = "PAUSED"
                    active["failure"] = {
                        "code": "device_session_restarted",
                        "message": "pairing started a new observation session; user confirmation is required",
                    }
                    if active.get("mode") == "vlm":
                        active["vlm_completion"] = {
                            **(active.get("vlm_completion") or {}),
                            "status": "PAUSED",
                        }
                    active["control"] = {
                        "command": "pause",
                        "reason": "device_session_restarted",
                        "requested_at": self.clock(),
                    }
                    active["recovery_required"] = True
                    active["recovery_reason"] = "device_session_restarted"
                    active["recovery"] = {
                        "phase": "RECONCILIATION_REQUIRED",
                        "reason": "device_session_restarted",
                        "eligible": False,
                        "confirmed": False,
                        "resume_token": None,
                        "action_outcome": "UNKNOWN" if self._action_for_recovery_locked(active) is not None and active.get("receipt") is None else None,
                        "observation_id": None,
                        "observation_version": None,
                        "scene_fingerprint": None,
                    }
                    recovery_action = self._action_for_recovery_locked(active)
                    if recovery_action is not None and active.get("receipt") is None:
                        active["action_result_unknown"] = True
                        active["actual_effect"] = {
                            "status": "UNKNOWN",
                            "action_id": recovery_action.get("action_id"),
                            "reason": "device_session_restarted_before_receipt",
                        }
                    active["run_generation"] = int(active.get("run_generation", 0)) + 1
            self._device_session_id = uuid4().hex
            self._paired = True
            self._client_name = payload["client_name"]
            self._recovery_capabilities = set(payload.get("recovery_capabilities", []))
            self._last_seen_at = self.clock()
            # Pairing starts a new observation session.  Do not expose a tree
            # captured by an earlier session while the app is still waiting
            # for its first fresh observation.
            self._latest = None
            self._observation_history.clear()
            self._screenshots.clear()
            self._screenshot_events.clear()
            self._screenshot_by_observation.clear()
            self._received_at = None
            self._last_observation_monotonic = None
            self._pending_controls.clear()
            self._persist_locked()
            self._condition.notify_all()
        return {
            "schema_version": SCHEMA_VERSION,
            "android_schema_version": ANDROID_SCHEMA_VERSION,
            "device_id": self.device_id,
            "protocol_version": PROTOCOL_VERSION,
            "device_session_id": self._device_session_id,
            "paired": True,
            "server_time": self.clock(),
        }

    def receive_observation(self, observation: dict[str, Any], session_id: str | None = None) -> dict[str, Any]:
        try:
            assert_observation_valid(observation)
        except SchemaValidationError as exc:
            raise BridgeRequestError(400, "invalid_schema", str(exc)) from exc
        if observation["device_id"] != self.device_id:
            raise BridgeRequestError(403, "device_identity_mismatch", "observation device is not paired with this bridge")
        with self._lock:
            self._check_device_session_locked(session_id)
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
            # The deterministic task verifier owns its terminal state.  A VLM
            # runner consumes the same fresh frame through the condition
            # below, then binds each action to this exact observation.
            if not (
                self._active_task_id is not None
                and self._tasks.get(self._active_task_id, {}).get("mode") == "vlm"
            ):
                self._maybe_verify_task_locked()
            self._persist_locked()
            self._condition.notify_all()
            return {
                "schema_version": SCHEMA_VERSION,
                "android_schema_version": ANDROID_SCHEMA_VERSION,
                "device_id": self.device_id,
                "observation_id": observation["observation_id"],
                "observation_version": observation["observation_version"],
                "accepted": True,
                "received_at": received_at,
            }

    def receive_screenshot(self, screenshot: dict[str, Any], session_id: str | None = None) -> dict[str, Any]:
        """Store a screenshot only when it names an already accepted observation.

        The image is evidence attached to a tree version; it is never accepted
        as a free-standing latest frame.  This prevents a delayed or fabricated
        image from becoming the before/after evidence for another action.
        """
        try:
            assert_valid(screenshot, "screenshot")
        except SchemaValidationError as exc:
            raise BridgeRequestError(400, "invalid_schema", str(exc)) from exc
        if screenshot["device_id"] != self.device_id:
            raise BridgeRequestError(403, "device_identity_mismatch", "screenshot device is not paired with this bridge")
        with self._lock:
            self._check_device_session_locked(session_id)
            if not self._paired:
                raise BridgeRequestError(409, "device_not_paired", "pair the Android app before sending screenshots")
            observation = self._observation_history.get(screenshot["observation_id"])
            if observation is None:
                raise BridgeRequestError(409, "observation_unavailable", "screenshot observation is not available")
            if (
                observation["device_id"] != self.device_id
                or observation["task_id"] != screenshot["task_id"]
                or observation["observation_version"] != screenshot["observation_version"]
            ):
                raise BridgeRequestError(409, "observation_mismatch", "screenshot does not match its observation")
            screenshot_id = screenshot["screenshot_id"]
            if screenshot_id in self._screenshot_events:
                raise BridgeRequestError(409, "screenshot_exists", "screenshot_id has already been uploaded")
            missing_reason = screenshot.get("missing_reason")
            has_png = bool(screenshot.get("png_base64"))
            if missing_reason is None and not has_png:
                raise BridgeRequestError(400, "invalid_screenshot", "a successful screenshot must contain PNG data")
            if missing_reason is not None and (not isinstance(missing_reason, str) or not missing_reason.strip()):
                raise BridgeRequestError(400, "invalid_screenshot", "missing_reason must be a non-empty string when the image is unavailable")
            if missing_reason is not None and has_png:
                raise BridgeRequestError(400, "invalid_screenshot", "an unavailable screenshot must not contain PNG data")
            available = missing_reason is None
            key = (screenshot["observation_id"], screenshot["capture_type"])
            previous = self._screenshot_by_observation.get(key)
            if previous is not None:
                raise BridgeRequestError(409, "screenshot_exists", "this observation already has a screenshot of this type")
            event = copy.deepcopy(screenshot)
            self._screenshot_events[screenshot_id] = event
            visual = copy.deepcopy(observation.get("visual") or {
                "capture_state": "NOT_REQUESTED",
                "capture_count": 0,
                "upload_count": 0,
                "missing_reason": None,
                "screenshot_id": None,
            })
            visual.update({
                "capture_state": "CAPTURED" if available else "UNAVAILABLE",
                "capture_count": screenshot["capture_count"],
                "upload_count": screenshot["upload_count"],
                "missing_reason": None if available else missing_reason,
                "screenshot_id": screenshot_id if available else None,
            })
            observation["visual"] = visual
            self._observation_history[screenshot["observation_id"]] = copy.deepcopy(observation)
            if self._latest is not None and self._latest["observation_id"] == screenshot["observation_id"]:
                self._latest["visual"] = copy.deepcopy(visual)
            if available:
                self._screenshots[screenshot_id] = event
                self._screenshot_by_observation[key] = screenshot_id
            self._condition.notify_all()
            return {
                "schema_version": SCHEMA_VERSION,
                "android_schema_version": ANDROID_SCHEMA_VERSION,
                "device_id": self.device_id,
                "accepted": True,
                "available": available,
                "screenshot_id": screenshot_id,
                "observation_id": screenshot["observation_id"],
                "observation_version": screenshot["observation_version"],
                "capture_type": screenshot["capture_type"],
                "capture_count": screenshot["capture_count"],
                "upload_count": screenshot["upload_count"],
                "missing_reason": missing_reason,
            }

    def _screenshot_for_observation_locked(self, observation_id: str, capture_type: str) -> dict[str, Any] | None:
        screenshot_id = self._screenshot_by_observation.get((observation_id, capture_type))
        return self._screenshots.get(screenshot_id) if screenshot_id is not None else None

    @staticmethod
    def _bounds_contains(bounds: dict[str, Any], x: float, y: float) -> bool:
        return (
            isinstance(bounds, dict)
            and bounds.get("left", 0) <= x <= bounds.get("right", 0)
            and bounds.get("top", 0) <= y <= bounds.get("bottom", 0)
        )

    @staticmethod
    def _coordinate_frame(observation: dict[str, Any]) -> dict[str, Any]:
        screen = observation.get("screen") or {}
        width = int(screen.get("width_px", 1))
        height = int(screen.get("height_px", 1))
        insets = screen.get("system_bar_insets") or {"left": 0, "top": 0, "right": 0, "bottom": 0}
        offset = screen.get("window_offset") or {"x": 0, "y": 0}
        active_windows = [
            window for window in observation.get("windows", [])
            if window.get("active") and window.get("focused")
        ]
        active_window = max(active_windows, key=lambda window: int(window.get("layer", 0)), default=None)
        active_bounds = copy.deepcopy(active_window.get("bounds")) if active_window is not None else None
        content_width = int(screen.get("content_width_px", width))
        content_height = int(screen.get("content_height_px", height))
        return {
            "screen_width_px": width,
            "screen_height_px": height,
            "rotation": int(screen.get("rotation", 0)),
            "model_width_px": width,
            "model_height_px": height,
            "content_width_px": content_width,
            "content_height_px": content_height,
            "system_bar_insets": {
                "left": int(insets.get("left", 0)),
                "top": int(insets.get("top", 0)),
                "right": int(insets.get("right", 0)),
                "bottom": int(insets.get("bottom", 0)),
            },
            "window_offset": {
                "x": int(offset.get("x", 0)),
                "y": int(offset.get("y", 0)),
            },
            "active_window_id": int(active_window.get("window_id", -1)) if active_window is not None else -1,
            "active_window_package": str(active_window.get("package_name", "")) if active_window is not None else "",
            "active_window_layer": int(active_window.get("layer", -1)) if active_window is not None else -1,
            "active_window_bounds": active_bounds,
            "coordinate_space": "screen",
        }

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
            if plan["kind"] in {"tap", "long_press"}:
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

    @staticmethod
    def _action_for_recovery_locked(task: dict[str, Any]) -> dict[str, Any] | None:
        """Return an action that still needs recovery accounting.

        A resumed VLM run retains its previous action for history, while the
        explicit None generation marker says it is no longer executable or
        eligible for recovery in the new run. Older checkpoints lack the
        marker and remain reconcilable until their first explicit resume.
        """
        action = task.get("action")
        if task.get("mode") == "vlm" and "action_generation" in task and task.get("action_generation") is None:
            return None
        return action

    @classmethod
    def _action_for_current_run_locked(cls, task: dict[str, Any]) -> dict[str, Any] | None:
        action = cls._action_for_recovery_locked(task)
        if action is None:
            return None
        if task.get("mode") == "vlm" and "action_generation" in task:
            if task.get("action_generation") != int(task.get("run_generation", 0)):
                return None
        return action

    def _task_status_locked(self, task: dict[str, Any], *, mark_command: bool = False) -> dict[str, Any]:
        action = self._action_for_recovery_locked(task)
        current_action = self._action_for_current_run_locked(task)
        if mark_command and task["state"] == "RUNNING" and task["receipt"] is None and current_action is not None:
            if not task.get("command_delivered"):
                task["command_delivered"] = True
                self._persist_locked()
        next_action = None
        intent = task.get("command_intent")
        intent_pending = isinstance(intent, dict) and intent.get("action_id") == (current_action or {}).get("action_id")
        if task["state"] == "RUNNING" and task["receipt"] is None and not task.get("action_result_unknown") and not intent_pending:
            next_action = copy.deepcopy(current_action)
        status = {
            "schema_version": SCHEMA_VERSION,
            "android_schema_version": ANDROID_SCHEMA_VERSION,
            "task_id": task["task_id"],
            "device_id": self.device_id,
            "goal": task["goal"],
            "state": task["state"],
            "phase": task.get("phase", "RUNNING"),
            "action": copy.deepcopy(action),
            "next_action": next_action,
            "receipt": copy.deepcopy(task.get("receipt") or task.get("last_receipt")),
            "verification": copy.deepcopy(task.get("verification")),
            "failure": copy.deepcopy(task.get("failure")),
            "control": copy.deepcopy(task.get("control")),
            "before_observation_id": task.get("before_observation_id"),
            "after_observation_id": task.get("after_observation_id"),
            "before_screenshot_id": task.get("before_screenshot_id"),
            "after_screenshot_id": task.get("after_screenshot_id"),
            "before_visual": copy.deepcopy(task.get("before_visual")),
            "after_visual": copy.deepcopy(task.get("after_visual")),
            "action_result_unknown": bool(task.get("action_result_unknown")),
            # VLM tasks keep model completion, device effect, and the known
            # independent controlled-page judge as separate values.  The
            # deterministic task path leaves these fields null/absent.
            "mode": task.get("mode", "deterministic"),
            "model": copy.deepcopy(task.get("model_info")),
            "actual_effect": copy.deepcopy(task.get("actual_effect")),
            "vlm_completion": copy.deepcopy(task.get("vlm_completion")),
            "independent_result": copy.deepcopy(task.get("independent_result")),
            "task_output": task.get("task_output"),
            "usage": self._usage_summary_locked(task) if task.get("mode") == "vlm" else copy.deepcopy(task.get("usage")),
            "trace": copy.deepcopy(task["trace"]),
        }
        return status

    def task_recovery_status(self, task_id: str, session_id: str | None = None) -> dict[str, Any]:
        with self._lock:
            self._check_device_session_locked(session_id)
            task = self._tasks.get(task_id)
            if task is None:
                raise BridgeRequestError(404, "task_not_found", "task does not exist")
            recovery = task.get("recovery") or {}
            action = self._action_for_recovery_locked(task) or {}
            return {
                "format": "jev-android-recovery-v1",
                "task_id": task_id,
                "state": task["state"],
                "phase": recovery.get("phase", "NOT_REQUIRED"),
                "reason": recovery.get("reason") or task.get("recovery_reason"),
                "eligible": bool(recovery.get("eligible")),
                "confirmed": bool(recovery.get("confirmed")),
                "resume_token": recovery.get("resume_token"),
                "observation_version": recovery.get("observation_version"),
                "action_outcome": recovery.get("action_outcome"),
                "action_id": action.get("action_id"),
                "action_kind": action.get("kind"),
                "actual_effect": copy.deepcopy(task.get("actual_effect")),
                "independent_result": copy.deepcopy(task.get("independent_result")),
                "device_session_id": self._device_session_id,
                "usage": self._usage_summary_locked(task) if task.get("mode") == "vlm" else copy.deepcopy(task.get("usage")),
            }

    def record_command_intent(self, task_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        action_id = payload.get("action_id")
        session_id = payload.get("device_session_id")
        if not isinstance(action_id, str) or not action_id or not isinstance(session_id, str) or not session_id:
            raise BridgeRequestError(400, "invalid_command_intent", "action_id and device_session_id are required")
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                raise BridgeRequestError(404, "task_not_found", "task does not exist")
            action = self._action_for_current_run_locked(task) or {}
            if session_id != self._device_session_id or task.get("device_session_id") != session_id:
                raise BridgeRequestError(409, "device_session_mismatch", "the command belongs to an older device session")
            if task["state"] != "RUNNING" or action.get("action_id") != action_id:
                raise BridgeRequestError(409, "action_mismatch", "the command is no longer current")
            task["command_intent"] = {
                "action_id": action_id,
                "device_session_id": session_id,
                "recorded_at": self.clock(),
                "state": "DEVICE_PENDING",
            }
            self._persist_locked()
            return {
                "task_id": task_id,
                "action_id": action_id,
                "device_session_id": session_id,
                "intent_recorded": True,
                "recovery_outcome": "UNKNOWN",
            }

    def reconcile_task(self, task_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        session_id = payload.get("device_session_id")
        if not isinstance(session_id, str) or not session_id:
            raise BridgeRequestError(400, "invalid_reconciliation", "device_session_id is required")
        device_record = payload.get("device_record")
        if device_record is not None and not isinstance(device_record, dict):
            raise BridgeRequestError(400, "invalid_reconciliation", "device_record must be an object or null")
        if device_record is not None:
            try:
                assert_valid(device_record, "device_record")
            except SchemaValidationError as exc:
                raise BridgeRequestError(400, "invalid_reconciliation", str(exc)) from exc
            if (device_record["phase"] == "PENDING") != (device_record["receipt"] is None):
                raise BridgeRequestError(400, "invalid_reconciliation", "device_record phase and receipt disagree")
        with self._condition:
            task = self._tasks.get(task_id)
            if task is None:
                raise BridgeRequestError(404, "task_not_found", "task does not exist")
            if session_id != self._device_session_id:
                raise BridgeRequestError(409, "device_session_mismatch", "pair the current App session before reconciliation")
            if task["state"] in {"SUCCEEDED", "FAILED", "CANCELLED"} and not task.get("recovery_required"):
                raise BridgeRequestError(409, "task_terminal", "a terminal task does not need recovery")
            if task.get("mode") == "vlm":
                task["run_generation"] = int(task.get("run_generation", 0)) + 1
            if task["state"] == "RUNNING":
                task["state"] = "PAUSED"
                task["phase"] = "PAUSED"
                task["failure"] = {
                    "code": "reconciliation_requested",
                    "message": "the task is paused while its device history is checked",
                }
            task["recovery_required"] = True
            current = self._latest if self._is_current_locked() else None
            action = self._action_for_recovery_locked(task)
            receipt = task.get("receipt") or task.get("last_receipt")
            outcome = "UNKNOWN"
            reason = "action_history_inconclusive"
            if action is None:
                outcome = "NOT_EXECUTED"
                reason = "no_device_action_was_created"
            elif isinstance(receipt, dict) and receipt.get("action_id") == action.get("action_id"):
                outcome = "EXECUTED" if receipt.get("accepted") else "NOT_EXECUTED"
                reason = "persisted_server_receipt_confirms_execution" if outcome == "EXECUTED" else "persisted_server_receipt_confirms_rejection"
            elif (
                device_record is None
                and task.get("command_intent") is None
                and task.get("recovery_capability") == self._JOURNALED_ACTION_RECOVERY_CAPABILITY
            ):
                # This claim is copied from the paired client into the durable
                # task record at creation. That client commits its local PENDING
                # journal entry before recording command intent, and executes
                # only after the server confirms that intent. With neither a
                # local action record nor server intent, the action was never
                # dispatched. Older clients and every ambiguous history stay
                # UNKNOWN.
                outcome = "NOT_EXECUTED"
                reason = "durable_client_order_proves_action_was_not_dispatched"
            elif isinstance(device_record, dict):
                recorded_action_id = device_record.get("action_id")
                recorded_receipt = device_record.get("receipt")
                if recorded_action_id != action.get("action_id"):
                    reason = "device_history_action_identity_mismatch"
                elif recorded_receipt is None:
                    reason = "device_record_is_pending_without_result"
                elif isinstance(recorded_receipt, dict):
                    try:
                        assert_valid(recorded_receipt, "receipt")
                    except SchemaValidationError as exc:
                        raise BridgeRequestError(400, "invalid_device_receipt", str(exc)) from exc
                    if (
                        recorded_receipt.get("task_id") != task_id
                        or recorded_receipt.get("action_id") != action.get("action_id")
                        or recorded_receipt.get("device_id") != self.device_id
                    ):
                        reason = "device_history_receipt_identity_mismatch"
                    else:
                        outcome = "EXECUTED" if recorded_receipt.get("accepted") else "NOT_EXECUTED"
                        reason = "durable_device_receipt_confirms_execution" if outcome == "EXECUTED" else "durable_device_receipt_confirms_rejection"
                        if task.get("receipt") is None:
                            task["receipt"] = copy.deepcopy(recorded_receipt)
                            task["last_receipt"] = copy.deepcopy(recorded_receipt)
                else:
                    raise BridgeRequestError(400, "invalid_device_receipt", "receipt must be an object or null")
            else:
                reason = "no_durable_device_history_for_pending_action"
            if outcome == "UNKNOWN" and (current is None or current.get("availability") != "AVAILABLE"):
                reason = "fresh_accessibility_observation_unavailable"
            elif current is None or current.get("availability") != "AVAILABLE":
                reason = "execution_history_known_but_fresh_observation_required"
            if current is not None:
                fingerprint = _android_scene_fingerprint(current)
                observation_id = current["observation_id"]
                observation_version = current["observation_version"]
            else:
                fingerprint = None
                observation_id = None
                observation_version = None
            usage_blocked = False
            budget_blocked = False
            if task.get("mode") == "vlm":
                requests_used, cost_used, usage_missing = self._usage_accounting_locked(task)
                usage_blocked = usage_missing
                if usage_blocked and current is not None and current.get("availability") == "AVAILABLE":
                    reason = "model_usage_unknown_after_interruption"
                limits = task.get("vlm_resume_config") or {}
                step_limit = int(limits.get("max_steps", 0))
                request_limit = int(limits.get("max_requests", 0))
                budget_limit = float(limits.get("budget_cny", 0.0))
                max_tokens = int(limits.get("max_tokens", 0))
                upper_bound = (8192 * 1.5 + max_tokens * 4.5) / 1_000_000
                budget_blocked = (
                    requests_used >= request_limit
                    or int(task.get("step_budget_used", 0)) >= step_limit
                    or budget_limit - cost_used + 1e-12 < upper_bound
                )
                if budget_blocked and current is not None and current.get("availability") == "AVAILABLE":
                    reason = "model_task_budget_exhausted"
            if self._state_path is None and current is not None and current.get("availability") == "AVAILABLE":
                reason = "durable_server_checkpoint_unavailable"
            terminal_not_executed = (
                task["state"] in {"SUCCEEDED", "FAILED", "CANCELLED"}
                and outcome == "NOT_EXECUTED"
            )
            eligible = (
                self._state_path is not None
                and
                current is not None
                and current.get("availability") == "AVAILABLE"
                and outcome in {"EXECUTED", "NOT_EXECUTED"}
                and task["state"] not in {"SUCCEEDED", "FAILED", "CANCELLED"}
                and not usage_blocked
                and not budget_blocked
            )
            task["recovery"] = {
                "phase": (
                    "NOT_REQUIRED"
                    if terminal_not_executed
                    else "READY_TO_RESUME" if eligible else "RECONCILIATION_REQUIRED"
                ),
                "reason": reason,
                "eligible": eligible,
                "confirmed": False,
                "resume_token": secrets.token_urlsafe(24) if eligible else None,
                "action_outcome": outcome,
                "observation_id": observation_id,
                "observation_version": observation_version,
                "scene_fingerprint": fingerprint,
                "device_record_state": device_record.get("phase") if isinstance(device_record, dict) else None,
            }
            task["recovery_reason"] = reason
            task["recovery_required"] = not terminal_not_executed
            task["action_result_unknown"] = outcome == "UNKNOWN" and action is not None
            if task["action_result_unknown"]:
                task["actual_effect"] = {
                    "status": "UNKNOWN",
                    "action_id": action.get("action_id"),
                    "reason": reason,
                }
            elif terminal_not_executed:
                task["actual_effect"] = {
                    "status": "NOT_EXECUTED",
                    "action_id": action.get("action_id") if action is not None else None,
                    "reason": reason,
                }
            elif (
                action is not None
                and outcome == "EXECUTED"
                and (task.get("independent_result") or {}).get("success") is not True
                and (task.get("actual_effect") or {}).get("status") not in {"UNKNOWN", "EXECUTED"}
            ):
                # A receipt settles execution history only; it does not prove
                # the user-visible effect or goal postcondition.
                task["actual_effect"] = {
                    "status": "UNKNOWN",
                    "action_id": action.get("action_id"),
                    "receipt_id": receipt.get("receipt_id") if isinstance(receipt, dict) else None,
                    "reason": "effect_requires_fresh_independent_verification",
                }
            if terminal_not_executed and self._active_task_id == task_id:
                self._active_task_id = None
            self._record_task_event_locked(task, "recovery.reconciled", task_id)
            self._persist_locked()
            return self.task_recovery_status(task_id)

    def _build_recovery_action_locked(self, task: dict[str, Any], observation: dict[str, Any]) -> dict[str, Any]:
        plan = _parse_node_goal(task["goal"])
        target: dict[str, Any] = {"node_id": ""}
        if plan["kind"] in {"tap", "set_text", "long_press"} and plan.get("target_label"):
            target = self._find_target_locked(observation, plan)
        requires_screenshot = bool(plan.get("requires_screenshot", False))
        before_screenshot = (
            self._screenshot_for_observation_locked(observation["observation_id"], "BEFORE")
            if requires_screenshot else None
        )
        if requires_screenshot and before_screenshot is None:
            raise BridgeRequestError(
                409,
                "before_screenshot_required",
                "a fresh BEFORE screenshot is required to plan a recovered visual action",
            )
        coordinate_frame = self._coordinate_frame(observation)
        parameters = copy.deepcopy(plan.get("parameters", {}))
        if plan["kind"] == "swipe":
            parameters.update({
                "x1": coordinate_frame["screen_width_px"] * float(parameters.pop("start_fraction_x", 0.25)),
                "y1": coordinate_frame["screen_height_px"] * float(parameters.pop("fraction_y", 0.5)),
                "x2": coordinate_frame["screen_width_px"] * float(parameters.pop("end_fraction_x", 0.75)),
                "y2": coordinate_frame["screen_height_px"] * float(parameters.pop("fraction_y", 0.5)),
            })
        if plan["kind"] == "long_press" and "fraction_x" in parameters:
            parameters["x"] = coordinate_frame["screen_width_px"] * float(parameters.pop("fraction_x"))
            parameters["y"] = coordinate_frame["screen_height_px"] * float(parameters.pop("fraction_y", 0.55))
        sequence = int(task.get("recovery_action_sequence", 0)) + 1
        action = {
            "schema_version": SCHEMA_VERSION,
            "android_schema_version": ANDROID_SCHEMA_VERSION,
            "task_id": task["task_id"],
            "device_id": self.device_id,
            "session_id": f"android-session-{task['task_id']}",
            "action_id": f"android-action-{task['task_id']}-recovery-{sequence}",
            "observation_id": observation["observation_id"],
            "observation_version": observation["observation_version"],
            "sequence": sequence + 1,
            "kind": plan["kind"],
            "target_node_id": target["node_id"],
            "target_node_label": plan["target_label"],
            "target_node_role": plan["target_role"],
            "expected_page_state": plan["expected_page_state"],
            "parameters": parameters if plan["kind"] != "set_text" else {"text": plan["input_text"]},
            "coordinate_frame": coordinate_frame,
            "requires_screenshot": requires_screenshot,
            "before_screenshot_id": before_screenshot.get("screenshot_id") if before_screenshot else None,
            "source": "deterministic-android-recovery-plan",
            "created_at": self.clock(),
        }
        try:
            assert_valid(action, "action")
        except SchemaValidationError as exc:
            raise BridgeRequestError(500, "bridge_contract_error", str(exc)) from exc
        task["recovery_action_sequence"] = sequence
        task["action"] = action
        task["receipt"] = None
        task["command_intent"] = None
        task["command_delivered"] = False
        task["action_result_unknown"] = False
        task["actual_effect"] = {"status": "PENDING", "action_id": action["action_id"], "reason": "awaiting_android_receipt"}
        task["before_observation_id"] = observation["observation_id"]
        task["before_screenshot_id"] = action["before_screenshot_id"]
        task["before_visual"] = copy.deepcopy(observation.get("visual")) if requires_screenshot else None
        task["after_observation_id"] = None
        task["after_screenshot_id"] = None
        task["after_visual"] = None
        task["phase"] = "WAITING_RECEIPT"
        task["post_observation_min_version"] = observation["observation_version"]
        self._record_task_event_locked(task, "action.replanned_after_recovery", action["action_id"])
        return action

    def resume_task(self, task_id: str, payload: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
        try:
            assert_valid(payload, "resume_request")
        except SchemaValidationError as exc:
            raise BridgeRequestError(400, "invalid_recovery_request", str(exc)) from exc

        model_config: dict[str, Any] | None = None
        start_generation: int | None = None
        with self._condition:
            task = self._tasks.get(task_id)
            if task is None:
                raise BridgeRequestError(404, "task_not_found", "task does not exist")
            session_id = payload["device_session_id"]
            if session_id != self._device_session_id:
                raise BridgeRequestError(409, "device_session_mismatch", "resume confirmation belongs to an older App session")
            if task["state"] != "PAUSED" or not task.get("recovery_required"):
                raise BridgeRequestError(409, "recovery_not_required", "the task is not awaiting recovery confirmation")
            recovery = task.get("recovery") or {}
            if (
                recovery.get("phase") != "READY_TO_RESUME"
                or not recovery.get("eligible")
                or recovery.get("confirmed")
                or payload["resume_token"] != recovery.get("resume_token")
                or payload["observation_version"] != recovery.get("observation_version")
            ):
                raise BridgeRequestError(409, "recovery_confirmation_stale", "reconcile the current scene before confirming recovery")
            current = self._latest if self._is_current_locked() else None
            if current is None or current.get("availability") != "AVAILABLE":
                raise BridgeRequestError(409, "fresh_accessibility_observation_unavailable", "connect and capture a current Accessibility observation before resuming")
            if current.get("task_id") != task_id or current.get("device_id") != self.device_id:
                raise BridgeRequestError(409, "observation_mismatch", "the current observation belongs to a different device task")
            if current["observation_version"] < payload["observation_version"]:
                raise BridgeRequestError(409, "observation_version_stale", "the current observation is older than the confirmed scene")
            if _android_scene_fingerprint(current) != recovery.get("scene_fingerprint"):
                recovery.update({
                    "phase": "RECONCILIATION_REQUIRED",
                    "reason": "scene_changed_since_reconciliation",
                    "eligible": False,
                    "confirmed": False,
                    "resume_token": None,
                })
                task["recovery"] = recovery
                task["recovery_reason"] = "scene_changed_since_reconciliation"
                self._persist_locked()
                raise BridgeRequestError(409, "scene_changed_since_reconciliation", "the observed scene changed; reconcile and confirm it again")

            action_outcome = recovery.get("action_outcome")
            if action_outcome not in {"EXECUTED", "NOT_EXECUTED"}:
                raise BridgeRequestError(409, "action_outcome_unknown", "the device action outcome is unknown; the task remains paused")

            independent = self._judge_controlled_observation(current, task.get("goal", ""))
            if independent.get("success") is True:
                action = task.get("action") or {}
                task["independent_result"] = independent
                if action_outcome == "NOT_EXECUTED":
                    task["actual_effect"] = {
                        "status": "NOT_EXECUTED",
                        "action_id": action.get("action_id"),
                        "reason": "goal_already_complete_without_agent_action",
                    }
                else:
                    task["actual_effect"] = {
                        "status": "EXECUTED",
                        "action_id": action.get("action_id"),
                        "receipt_id": (task.get("receipt") or task.get("last_receipt") or {}).get("receipt_id"),
                        "reason": "fresh_independent_observation_proves_goal",
                    }
                task["state"] = "SUCCEEDED"
                task["phase"] = "SUCCEEDED"
                task["verification"] = {
                    "schema_version": SCHEMA_VERSION,
                    "android_schema_version": ANDROID_SCHEMA_VERSION,
                    "verification_id": f"android-recovery-verification-{task_id}-{int(task.get('run_generation', 0))}",
                    "task_id": task_id,
                    "action_id": action.get("action_id") or f"recovery-goal-{task_id}",
                    "before_observation_id": task.get("before_observation_id"),
                    "after_observation_id": current["observation_id"],
                    "status": "SUCCESS",
                    "expected_page_state": action.get("expected_page_state", "goal_completed"),
                    "actual_page_state": "observed_goal_completed",
                    "reason": "fresh_independent_observation_proves_goal",
                    "verified_at": self.clock(),
                }
                task["recovery_required"] = False
                recovery.update({
                    "phase": "RESUMED",
                    "reason": "goal_already_confirmed_by_current_observation",
                    "eligible": False,
                    "confirmed": True,
                    "resume_token": None,
                    "observation_id": current["observation_id"],
                    "observation_version": current["observation_version"],
                    "scene_fingerprint": _android_scene_fingerprint(current),
                })
                task["recovery"] = recovery
                self._active_task_id = None
                self._record_task_event_locked(task, "recovery.confirmed_goal_already_complete", task_id)
                self._persist_locked()
                return self._task_status_locked(task), self.task_recovery_status(task_id)

            if task.get("mode") == "vlm":
                model_payload = payload.get("model")
                if not isinstance(model_payload, dict):
                    raise BridgeRequestError(422, "model_credentials_required", "provide the existing private VLM key to resume")
                candidate = self._validate_vlm_config(model_payload)
                saved = task.get("vlm_resume_config")
                if not isinstance(saved, dict):
                    raise BridgeRequestError(409, "model_history_unavailable", "the saved model limits are unavailable; cannot resume safely")
                if any(candidate[key] != saved.get(key) for key in ("provider", "endpoint", "model")):
                    raise BridgeRequestError(409, "model_configuration_changed", "resume must use the task's original provider, endpoint, and model")
                requests_used, cost_used, usage_missing = self._usage_accounting_locked(task)
                if usage_missing:
                    raise BridgeRequestError(409, "model_usage_unknown", "a model request may have spent unaccounted budget; paid resume is blocked")
                steps_used = int(task.get("step_budget_used", 0))
                remaining = {
                    "max_steps": int(saved["max_steps"]) - steps_used,
                    "max_requests": int(saved["max_requests"]) - requests_used,
                    "max_tokens": int(saved["max_tokens"]),
                    "budget_cny": float(saved["budget_cny"]) - cost_used,
                    "timeout_seconds": float(saved["timeout_seconds"]),
                }
                if remaining["max_steps"] < 1 or remaining["max_requests"] < 1 or remaining["budget_cny"] <= 0:
                    raise BridgeRequestError(409, "model_task_budget_exhausted", "no bounded model budget remains for this task")
                model_config = {
                    "provider": saved["provider"],
                    "endpoint": saved["endpoint"],
                    "model": saved["model"],
                    "api_key": candidate["api_key"],
                    **remaining,
                }
            else:
                model_payload = payload.get("model")
                if model_payload is not None:
                    raise BridgeRequestError(400, "unexpected_model_configuration", "deterministic task recovery does not accept model credentials")

            task["run_generation"] = int(task.get("run_generation", 0)) + 1
            start_generation = task["run_generation"]
            if task.get("mode") == "vlm":
                # Keep the prior action in the checkpoint for recovery
                # history, but do not expose it as executable in this run.
                task["action_generation"] = None
            task["state"] = "RUNNING"
            task["failure"] = None
            task["control"] = {"command": "resume", "reason": "user_confirmed_recovery", "requested_at": self.clock()}
            task["device_session_id"] = session_id
            task["recovery_required"] = False
            recovery.update({
                "phase": "RESUMED",
                "reason": "user_confirmed_recovery",
                "eligible": False,
                "confirmed": True,
                "resume_token": None,
                "observation_id": current["observation_id"],
                "observation_version": current["observation_version"],
                "scene_fingerprint": _android_scene_fingerprint(current),
            })
            task["recovery"] = recovery
            task["recovery_reason"] = None
            self._active_task_id = task_id
            self._record_task_event_locked(task, "recovery.confirmed", task_id)
            if task.get("mode") == "vlm":
                completion = task.get("vlm_completion") or {}
                completion["status"] = "RUNNING"
                task["vlm_completion"] = completion
                task["phase"] = "WAITING_MODEL"
            else:
                self._build_recovery_action_locked(task, current)
            self._persist_locked()
            task_response = self._task_status_locked(task)
            recovery_response = self.task_recovery_status(task_id)

        if model_config is not None and start_generation is not None:
            runner = threading.Thread(
                target=self._run_vlm_task,
                args=(task_id, task["goal"], model_config, start_generation),
                name=f"jev-vlm-{task_id}-recovery-{start_generation}",
                daemon=True,
            )
            runner.start()
        return task_response, recovery_response

    def submit_task(self, payload: dict[str, Any], session_id: str | None = None) -> dict[str, Any]:
        try:
            assert_valid(payload, "task_submit")
        except SchemaValidationError as exc:
            raise BridgeRequestError(400, "invalid_schema", str(exc)) from exc
        if payload["device_id"] != self.device_id:
            raise BridgeRequestError(403, "device_identity_mismatch", "task device is not paired with this bridge")
        with self._lock:
            self._check_device_session_locked(session_id)
            if not self._paired:
                raise BridgeRequestError(409, "device_not_paired", "pair the Android app before submitting a task")
            recovery_capability = payload.get("recovery_capability")
            if recovery_capability is not None and recovery_capability not in self._recovery_capabilities:
                raise BridgeRequestError(
                    409,
                    "recovery_capability_not_paired",
                    "the task recovery capability was not declared by the current paired App session",
                )
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
            target = {"node_id": ""}
            if plan["kind"] in {"tap", "set_text", "long_press"} and plan.get("target_label"):
                target = self._find_target_locked(observation, plan)
            requires_screenshot = bool(plan.get("requires_screenshot", False))
            before_screenshot = (
                self._screenshot_for_observation_locked(observation["observation_id"], "BEFORE")
                if requires_screenshot else None
            )
            if requires_screenshot and before_screenshot is None:
                raise BridgeRequestError(
                    409,
                    "before_screenshot_required",
                    "a real BEFORE screenshot bound to the fresh observation is required for this visual action",
                )
            task_id = payload["task_id"]
            session_id = f"android-session-{task_id}"
            coordinate_frame = self._coordinate_frame(observation)
            parameters = copy.deepcopy(plan.get("parameters", {}))
            if plan["kind"] == "swipe":
                width = coordinate_frame["screen_width_px"]
                height = coordinate_frame["screen_height_px"]
                fraction_y = float(parameters.pop("fraction_y", 0.5))
                parameters.update({
                    "x1": width * float(parameters.pop("start_fraction_x", 0.25)),
                    "y1": height * fraction_y,
                    "x2": width * float(parameters.pop("end_fraction_x", 0.75)),
                    "y2": height * fraction_y,
                })
            if plan["kind"] == "long_press" and "fraction_x" in parameters:
                width = coordinate_frame["screen_width_px"]
                height = coordinate_frame["screen_height_px"]
                parameters["x"] = width * float(parameters.pop("fraction_x"))
                parameters["y"] = height * float(parameters.pop("fraction_y", 0.55))
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
                "parameters": parameters if plan["kind"] != "set_text" else {"text": plan["input_text"]},
                "coordinate_frame": coordinate_frame,
                "requires_screenshot": requires_screenshot,
                "before_screenshot_id": before_screenshot.get("screenshot_id") if before_screenshot else None,
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
                "device_session_id": self._device_session_id,
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
                "recovery_capability": recovery_capability,
                "post_observation_min_version": None,
                "post_observation_id": None,
                "post_observation_version": None,
                "requires_screenshot": requires_screenshot,
                "before_screenshot_id": before_screenshot.get("screenshot_id") if before_screenshot else None,
                "after_screenshot_id": None,
                "before_visual": copy.deepcopy(observation.get("visual")) if requires_screenshot else None,
                "after_visual": None,
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
            self._persist_locked()
            return self._task_status_locked(task)

    @staticmethod
    def _validate_vlm_config(config: Any) -> dict[str, Any]:
        if not isinstance(config, dict):
            raise BridgeRequestError(422, "model_configuration_required", "VLM configuration is required")
        provider = config.get("provider", "")
        endpoint = config.get("endpoint")
        model = config.get("model")
        api_key = config.get("api_key")
        if not isinstance(provider, str):
            raise BridgeRequestError(422, "model_invalid_config", "VLM provider must be text")
        if not isinstance(endpoint, str) or not endpoint.strip():
            raise BridgeRequestError(422, "model_configuration_required", "VLM endpoint is required")
        parsed = urlparse(endpoint.strip())
        if parsed.scheme != "https" or not parsed.netloc:
            raise BridgeRequestError(422, "model_invalid_endpoint", "VLM endpoint must be an HTTPS URL")
        if not isinstance(model, str) or not model.strip():
            raise BridgeRequestError(422, "model_configuration_required", "VLM model is required")
        if not isinstance(api_key, str) or not api_key:
            raise BridgeRequestError(422, "model_credentials_required", "VLM API key is required")
        if provider.strip().casefold() not in {"gui-plus", "gui_plus", "gui plus"}:
            raise BridgeRequestError(
                422,
                "model_unpriced",
                "only the reviewed GUI-Plus pricing configuration can run a bounded paid task",
            )
        if model.strip() != "gui-plus-2026-02-26":
            raise BridgeRequestError(
                422,
                "model_unpriced",
                "this model has no reviewed local CNY price; choose gui-plus-2026-02-26",
            )

        def bounded_int(name: str, default: int, maximum: int) -> int:
            value = config.get(name, default)
            if isinstance(value, bool) or not isinstance(value, int) or value < 1:
                raise BridgeRequestError(422, "model_invalid_config", f"{name} must be a positive integer")
            return min(value, maximum)

        max_steps = bounded_int("max_steps", 5, 5)
        max_requests = bounded_int("max_requests", 25, 25)
        max_tokens = bounded_int("max_tokens", 1024, 1024)
        try:
            budget_cny = float(config.get("budget_cny", 1.0))
            timeout_seconds = float(config.get("timeout_seconds", 30.0))
        except (TypeError, ValueError) as exc:
            raise BridgeRequestError(422, "model_invalid_config", "VLM budget and timeout must be numbers") from exc
        if not math.isfinite(budget_cny) or budget_cny <= 0:
            raise BridgeRequestError(422, "model_invalid_config", "VLM budget must be positive")
        if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
            raise BridgeRequestError(422, "model_invalid_config", "VLM timeout must be positive")
        return {
            "provider": provider.strip(),
            "endpoint": endpoint.strip(),
            "model": model.strip(),
            "api_key": api_key,
            "max_steps": max_steps,
            "max_requests": max_requests,
            "max_tokens": max_tokens,
            "budget_cny": min(budget_cny, 1.0),
            "timeout_seconds": min(timeout_seconds, 30.0),
        }

    def submit_vlm_task(self, payload: dict[str, Any], session_id: str | None = None) -> dict[str, Any]:
        """Start the bounded extracted-role loop for the paired Android app.

        The existing deterministic task endpoint remains unchanged.  This
        endpoint creates the same guarded task record but lets a background
        role runner fill each observation-bound action after the model reply.
        The API key is copied only into that runner's in-memory configuration;
        no task/status/trace field stores it.
        """
        core = {
            key: payload.get(key)
            for key in (
                "schema_version", "android_schema_version", "task_id", "device_id", "goal", "source",
                "recovery_capability",
            )
            if key in payload
        }
        try:
            assert_valid(core, "task_submit")
        except SchemaValidationError as exc:
            raise BridgeRequestError(400, "invalid_schema", str(exc)) from exc
        if core["device_id"] != self.device_id:
            raise BridgeRequestError(403, "device_identity_mismatch", "task device is not paired with this bridge")
        model_config = self._validate_vlm_config(payload.get("model"))
        with self._condition:
            self._check_device_session_locked(session_id)
            if not self._paired:
                raise BridgeRequestError(409, "device_not_paired", "pair the Android app before submitting a task")
            recovery_capability = core.get("recovery_capability")
            if recovery_capability is not None and recovery_capability not in self._recovery_capabilities:
                raise BridgeRequestError(
                    409,
                    "recovery_capability_not_paired",
                    "the task recovery capability was not declared by the current paired App session",
                )
            if self._active_task_id is not None:
                raise BridgeRequestError(409, "task_active", "one Android task is already active")
            if core["task_id"] in self._tasks:
                raise BridgeRequestError(409, "task_exists", "task_id already exists")
            if self._latest is None or not self._is_current_locked():
                raise BridgeRequestError(409, "observation_unavailable", "a fresh Accessibility observation is required before starting a task")
            observation = self._latest
            if observation["task_id"] != core["task_id"]:
                raise BridgeRequestError(409, "task_observation_mismatch", "task_id must match the paired App observation session")
            if observation["availability"] != "AVAILABLE":
                raise BridgeRequestError(409, "permission_unavailable", "the App has no current actionable Accessibility tree")
            before_screenshot = self._screenshot_for_observation_locked(
                observation["observation_id"], "BEFORE"
            )
            if before_screenshot is None:
                raise BridgeRequestError(
                    409,
                    "before_screenshot_required",
                    "a real BEFORE screenshot bound to the fresh observation is required for a VLM task",
                )
            task_id = core["task_id"]
            task = {
                "task_id": task_id,
                "device_id": self.device_id,
                "device_session_id": self._device_session_id,
                "goal": core["goal"],
                "mode": "vlm",
                "state": "RUNNING",
                "phase": "WAITING_MODEL",
                "action": None,
                "action_generation": None,
                "receipt": None,
                "last_receipt": None,
                "verification": None,
                "failure": None,
                "control": None,
                "before_observation_id": observation["observation_id"],
                "after_observation_id": None,
                "action_result_unknown": False,
                "command_delivered": False,
                "recovery_capability": recovery_capability,
                "post_observation_min_version": observation["observation_version"],
                "post_observation_id": None,
                "post_observation_version": None,
                "requires_screenshot": True,
                "before_screenshot_id": before_screenshot["screenshot_id"],
                "after_screenshot_id": None,
                "before_visual": copy.deepcopy(observation.get("visual")),
                "after_visual": None,
                "actual_effect": {"status": "PENDING", "reason": "awaiting_model_action"},
                "vlm_completion": {
                    "status": "RUNNING",
                    "steps": 0,
                    "max_steps": model_config["max_steps"],
                },
                "independent_result": {"status": "UNKNOWN", "success": None, "reason": "not_evaluated"},
                "task_output": None,
                "usage": None,
                "model_info": {"provider": model_config["provider"], "model": model_config["model"]},
                "trace": [],
                "vlm_after_observation": None,
                "vlm_after_screenshot": None,
                "vlm_last_observation_version": None,
                "vlm_limits": {
                    "max_steps": model_config["max_steps"],
                    "max_requests": model_config["max_requests"],
                    "max_tokens": model_config["max_tokens"],
                    "budget_cny": model_config["budget_cny"],
                    "timeout_seconds": model_config["timeout_seconds"],
                },
                "run_generation": 1,
                "step_budget_used": 0,
                "vlm_resume_config": {
                    key: model_config[key]
                    for key in ("provider", "endpoint", "model", "max_steps", "max_requests", "max_tokens", "budget_cny", "timeout_seconds")
                },
                "usage_reservations": [],
            }
            self._tasks[task_id] = task
            self._active_task_id = task_id
            self._record_task_event_locked(task, "task.submitted", task_id)
            self._record_task_event_locked(task, "observation.captured", observation["observation_id"])
            self._persist_locked()
            self._condition.notify_all()
        runner = threading.Thread(
            target=self._run_vlm_task,
            args=(task_id, core["goal"], model_config, 1),
            name=f"jev-vlm-{task_id}",
            daemon=True,
        )
        runner.start()
        with self._condition:
            task = self._tasks[task_id]
            return self._task_status_locked(task)

    def _vlm_frame_locked(
            self,
            observation: dict[str, Any],
            screenshot: dict[str, Any],
    ) -> Any:
        # Import the extracted role seam lazily so deterministic bridge users
        # and the Android app do not acquire model/runtime dependencies.
        from agent_core.vlm import ObservationFrame

        return ObservationFrame.from_contract(
            copy.deepcopy(observation),
            screenshot={
                "data": screenshot["png_base64"],
                "media_type": "image/png",
            },
        )

    def _wait_vlm_frame(self, task_id: str, generation: int, minimum_version: int, timeout: float) -> Any:
        deadline = time.monotonic() + timeout
        with self._condition:
            while True:
                task = self._tasks.get(task_id)
                if task is None:
                    raise _VlmTaskStopped("task no longer exists")
                if task["state"] != "RUNNING" or int(task.get("run_generation", 0)) != generation:
                    raise _VlmTaskStopped("task is controlled by the user")
                observation = self._latest
                if observation is not None and observation["observation_version"] > minimum_version:
                    if observation["task_id"] != task_id or observation["device_id"] != self.device_id:
                        raise _VlmTaskFailure(
                            "observation_mismatch",
                            "the fresh Accessibility observation belongs to another task",
                            pause=True,
                        )
                    if observation["availability"] != "AVAILABLE":
                        raise _VlmTaskFailure(
                            "permission_unavailable",
                            "the App has no actionable Accessibility observation",
                            pause=True,
                        )
                    screenshot = self._screenshot_for_observation_locked(
                        observation["observation_id"], "BEFORE"
                    )
                    if screenshot is not None:
                        return self._vlm_frame_locked(observation, screenshot)
                    visual = observation.get("visual") or {}
                    if visual.get("capture_state") == "UNAVAILABLE":
                        raise _VlmTaskFailure(
                            "screenshot_missing",
                            "a fresh VLM observation has no usable screenshot",
                            pause=True,
                        )
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise _VlmTaskFailure(
                        "observation_timeout",
                        "timed out waiting for a fresh Accessibility observation and screenshot",
                        pause=True,
                    )
                self._condition.wait(min(remaining, 0.5))

    @staticmethod
    def _judge_controlled_observation(observation: dict[str, Any], goal: str) -> dict[str, Any]:
        if observation.get("availability") != "AVAILABLE":
            return {"status": "UNKNOWN", "success": None, "reason": "observation_unavailable"}
        values: list[str] = []
        for node in observation.get("nodes", []):
            for field in ("text", "content_description", "state_description"):
                value = node.get(field)
                if isinstance(value, str) and value:
                    values.append(value.casefold())
        joined = "\n".join(values)
        normalised_goal = _normalise_goal(goal)
        expected_text = None
        for pattern in _INPUT_GOAL_PATTERNS:
            match = pattern.fullmatch(goal or "") or pattern.fullmatch(normalised_goal)
            if match is not None:
                expected_text = match.group("text")
                break
        if expected_text is not None:
            successful = any(
                node.get("content_description") == "中文输入框"
                and node.get("text") == expected_text
                for node in observation.get("nodes", [])
            )
            return {
                "status": "SUCCESS" if successful else "UNKNOWN",
                "success": successful,
                "reason": "controlled_input_completed" if successful else "controlled_input_not_completed",
            }
        expected_tokens: tuple[str, ...] = ()
        if "长按" in normalised_goal:
            expected_tokens = ("long press completed", "long_press completed")
        elif "滑动" in normalised_goal:
            expected_tokens = ("swipe completed", "swipe_completed")
        elif "坐标" in normalised_goal:
            expected_tokens = ("coordinate tap completed", "coordinate_tap completed")
        elif "返回" in normalised_goal or normalised_goal.casefold() in {"back", "system back"}:
            expected_tokens = ("system back completed", "system_back completed")
        elif "切换受控状态" in normalised_goal or "toggle controlled state" in normalised_goal.casefold():
            expected_tokens = ("controlled action state: completed", "controlled action state completed")
        if expected_tokens and any(token in joined for token in expected_tokens):
            return {"status": "SUCCESS", "success": True, "reason": "controlled_page_completed"}
        if expected_tokens:
            return {"status": "UNKNOWN", "success": None, "reason": "controlled_page_not_completed"}
        return {"status": "UNKNOWN", "success": None, "reason": "independent_judge_not_applicable"}

    def _dispatch_vlm_action(
        self,
        task_id: str,
        generation: int,
        action_command: Any,
        before: Any,
        timeout: float,
    ) -> tuple[dict[str, Any], Any]:
        contract = copy.deepcopy(action_command.contract_action)
        if not isinstance(contract, dict):
            raise _VlmTaskFailure("unsupported_action", "the model action is outside the Android Accessibility contract")
        observation = before.as_contract()
        with self._condition:
            task = self._tasks.get(task_id)
            if task is None or task["state"] != "RUNNING" or int(task.get("run_generation", 0)) != generation:
                raise _VlmTaskStopped("task is controlled by the user")
            if observation.get("task_id") != task_id or observation.get("device_id") != self.device_id:
                raise _VlmTaskFailure("observation_mismatch", "the action observation is not bound to this task", pause=True)
            contract["task_id"] = task_id
            contract["device_id"] = self.device_id
            if contract.get("action_id") == (task.get("action") or {}).get("action_id"):
                contract["action_id"] = f"android-action-{task_id}-g{generation}-{uuid4().hex[:8]}"
            contract["session_id"] = f"android-session-{task_id}"
            # The extracted role only knows the portable ScreenFrame shape.
            # Rebuild the Android action frame from this exact observation so
            # the action carries the active window identity and geometry that
            # the Accessibility service will validate.  Do not read
            # ``self._latest`` here: a delayed capture must not rebind the
            # action or its screenshot to a different observation.
            contract["coordinate_frame"] = self._coordinate_frame(observation)
            before_screenshot = self._screenshot_for_observation_locked(
                observation["observation_id"], "BEFORE"
            )
            contract["before_screenshot_id"] = (
                before_screenshot["screenshot_id"] if before_screenshot is not None else None
            )
            if not contract["before_screenshot_id"]:
                raise _VlmTaskFailure("before_screenshot_required", "the action has no screenshot bound to its observation", pause=True)
            if contract.get("kind") == "set_text":
                editable = next(
                    (
                        node for node in observation.get("nodes", [])
                        if node.get("editable") and node.get("enabled") and node.get("visible_to_user")
                    ),
                    None,
                )
                if editable is None:
                    raise _VlmTaskFailure("target_node_not_found", "no visible editable Accessibility node is available", pause=True)
                contract["target_node_id"] = editable["node_id"]
                contract["target_node_label"] = (
                    editable.get("content_description") or editable.get("text") or ""
                )
            try:
                assert_valid(contract, "action")
            except SchemaValidationError as exc:
                raise _VlmTaskFailure("bridge_contract_error", "the model action did not satisfy the Android contract") from exc
            task["action"] = contract
            task["action_generation"] = generation
            task["receipt"] = None
            task["after_observation_id"] = None
            task["after_screenshot_id"] = None
            task["after_visual"] = None
            task["vlm_after_observation"] = None
            task["vlm_after_screenshot"] = None
            task["phase"] = "WAITING_RECEIPT"
            task["command_delivered"] = False
            task["command_intent"] = None
            task["action_result_unknown"] = False
            task["actual_effect"] = {
                "status": "PENDING",
                "action_id": contract["action_id"],
                "reason": "awaiting_android_receipt",
            }
            task["before_observation_id"] = observation["observation_id"]
            task["before_screenshot_id"] = contract["before_screenshot_id"]
            task["_vlm_action_started_monotonic"] = time.monotonic()
            self._record_task_event_locked(task, "action.dispatched", contract["action_id"])
            self._persist_locked()
            self._condition.notify_all()
            deadline = time.monotonic() + timeout
            while True:
                if task["state"] != "RUNNING" or int(task.get("run_generation", 0)) != generation:
                    raise _VlmTaskStopped("task is controlled by the user")
                receipt = task.get("receipt")
                after = task.get("vlm_after_observation")
                if receipt is not None and (not receipt.get("accepted") or after is not None):
                    return copy.deepcopy(receipt), after
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    task["state"] = "PAUSED"
                    task["phase"] = "PAUSED"
                    task["action_result_unknown"] = True
                    task["failure"] = {
                        "code": "vlm_action_timeout",
                        "message": "timed out waiting for the Android action receipt and after frame",
                    }
                    task["actual_effect"] = {
                        "status": "UNKNOWN",
                        "action_id": contract["action_id"],
                        "reason": "receipt_timeout",
                    }
                    self._record_task_event_locked(task, "task.paused", task_id)
                    self._condition.notify_all()
                    raise _VlmTaskFailure("vlm_action_timeout", "Android action receipt timed out", pause=True)
                self._condition.wait(min(remaining, 0.5))

    def _mark_vlm_failure(self, task_id: str, generation: int, code: str, message: str, *, pause: bool) -> None:
        with self._condition:
            task = self._tasks.get(task_id)
            if (
                task is None
                or task["state"] in {"SUCCEEDED", "FAILED", "CANCELLED"}
                or int(task.get("run_generation", 0)) != generation
            ):
                return
            task["state"] = "PAUSED" if pause else "FAILED"
            task["phase"] = task["state"]
            task["failure"] = {"code": code, "message": message}
            completion = task.get("vlm_completion") or {}
            completion["status"] = task["state"]
            task["vlm_completion"] = completion
            if not pause:
                self._active_task_id = None
                self._record_task_event_locked(task, "task.failed", task_id)
            else:
                self._record_task_event_locked(task, "task.paused", task_id)
            self._persist_locked()
            self._condition.notify_all()

    def _run_vlm_task(self, task_id: str, goal: str, model_config: dict[str, Any], generation: int) -> None:
        """Run extracted roles while all device I/O stays in bridge guards."""

        transport = None
        try:
            from agent_core.vlm import RoleOrchestrator
            from services.live_vlm.transport import ProductionVlmTransport, VlmTransportError

            transport = self._checkpointed_vlm_transport(
                task_id, generation, model_config, ProductionVlmTransport
            )
            orchestrator = RoleOrchestrator(transport, allow_uncontracted_actions=True)
            previous_version = 0
            last_after: Any = None
            for step_index in range(model_config["max_steps"]):
                with self._condition:
                    task = self._tasks.get(task_id)
                    if task is None or task["state"] != "RUNNING" or int(task.get("run_generation", 0)) != generation:
                        raise _VlmTaskStopped("task is controlled by the user")
                    used_steps = int(task.get("step_budget_used", 0))
                    maximum_steps = int((task.get("vlm_limits") or {}).get("max_steps", used_steps + model_config["max_steps"]))
                    if used_steps >= maximum_steps:
                        break
                    task["step_budget_used"] = used_steps + 1
                    self._persist_locked()
                before = self._wait_vlm_frame(
                    task_id, generation, previous_version, max(2.0, model_config["timeout_seconds"] * 2.0)
                )
                after_holder: list[Any] = []
                unsupported: list[_VlmTaskFailure] = []
                first_observation = True

                def observe() -> Any:
                    if first_observation:
                        return before
                    if after_holder:
                        return after_holder[0]
                    raise _VlmTaskFailure("after_observation_missing", "the Android action produced no bound after frame", pause=True)

                def execute(action_command: Any) -> Any:
                    if action_command.answer:
                        safe_text = str(action_command.parameters.get("text", ""))
                        safe_text = safe_text.replace(model_config["api_key"], "[REDACTED]")
                        with self._condition:
                            task = self._tasks.get(task_id)
                            if task is None or task["state"] != "RUNNING" or int(task.get("run_generation", 0)) != generation:
                                raise _VlmTaskStopped("task is controlled by the user")
                            task["task_output"] = safe_text
                            self._persist_locked()
                        return {"outcome": "TASK_OUTPUT"}
                    if action_command.contract_action is None:
                        failure = _VlmTaskFailure(
                            "unsupported_action",
                            "the model selected an Android action outside the supported Accessibility contract",
                        )
                        unsupported.append(failure)
                        raise failure
                    receipt, after = self._dispatch_vlm_action(
                        task_id,
                        generation,
                        action_command,
                        before,
                        max(2.0, model_config["timeout_seconds"] * 2.0),
                    )
                    if not receipt.get("accepted"):
                        raise _VlmTaskFailure("action_rejected", "the Android Accessibility service rejected the action")
                    if after is None:
                        raise _VlmTaskFailure("after_observation_missing", "the action receipt has no bound after frame", pause=True)
                    after_holder.append(self._vlm_frame_from_contract(after, task_id))
                    return receipt

                # The callback's first call and the role loop's post-action
                # call are intentionally distinct; keep one fresh frame per
                # action and never hand the previous before frame to a later
                # action.
                def role_observe() -> Any:
                    nonlocal first_observation
                    value = observe()
                    first_observation = False
                    return value

                result = orchestrator.step(goal, observe=role_observe, execute=execute)
                summary = transport.summary()
                with self._condition:
                    task = self._tasks.get(task_id)
                    if task is None or task["state"] != "RUNNING" or int(task.get("run_generation", 0)) != generation:
                        raise _VlmTaskStopped("task is controlled by the user")
                    task["usage"] = summary
                    completion = task.get("vlm_completion") or {}
                    completion.update({
                        "status": "RUNNING",
                        "steps": task.get("step_budget_used", step_index + 1),
                        "max_steps": (task.get("vlm_limits") or {}).get("max_steps", model_config["max_steps"]),
                    })
                    task["vlm_completion"] = completion
                    action_data = result.data.get("action") if isinstance(result.data, dict) else None
                    if unsupported:
                        raise unsupported[0]
                    if isinstance(action_data, dict) and action_data.get("kind") == "answer":
                        completion["status"] = "COMPLETED"
                        task["vlm_completion"] = completion
                        task["independent_result"] = self._judge_controlled_observation(
                            last_after.as_contract() if last_after is not None else before.as_contract(),
                            goal,
                        )
                        independent = task.get("independent_result") or {}
                        if independent.get("success") is True:
                            task["state"] = "SUCCEEDED"
                            task["phase"] = "SUCCEEDED"
                            self._active_task_id = None
                            self._record_task_event_locked(task, "task.completed", task_id)
                        else:
                            task["state"] = "PAUSED"
                            task["phase"] = "PAUSED"
                            task["failure"] = {
                                "code": "independent_result_unknown",
                                "message": "the answer output does not prove the device goal completed",
                            }
                            self._record_task_event_locked(task, "task.paused", task_id)
                        self._persist_locked()
                        self._condition.notify_all()
                        return
                    if after_holder:
                        last_after = after_holder[0]
                        previous_version = last_after.observation_version
                        task["independent_result"] = self._judge_controlled_observation(last_after.as_contract(), goal)
                    if result.done:
                        completion["status"] = "COMPLETED"
                        task["vlm_completion"] = completion
                        if last_after is not None:
                            task["independent_result"] = self._judge_controlled_observation(last_after.as_contract(), goal)
                        independent = task.get("independent_result") or {}
                        if independent.get("success") is True:
                            task["state"] = "SUCCEEDED"
                            task["phase"] = "SUCCEEDED"
                            self._active_task_id = None
                            self._record_task_event_locked(task, "task.completed", task_id)
                        else:
                            task["state"] = "PAUSED"
                            task["phase"] = "PAUSED"
                            task["failure"] = {
                                "code": "independent_result_unknown",
                                "message": "the VLM marked the task complete but the controlled-page judge has no success evidence",
                            }
                            self._record_task_event_locked(task, "task.paused", task_id)
                        self._persist_locked()
                        self._condition.notify_all()
                        return
                    if after_holder:
                        task["phase"] = "WAITING_MODEL"
                        self._persist_locked()
                        self._condition.notify_all()
                # Wait for a new observation only on the next loop.  The App
                # schedules that capture after it posts this action receipt.
            self._mark_vlm_failure(
                task_id, generation, "vlm_step_budget_exhausted", "the bounded VLM step limit was reached", pause=False
            )
        except _VlmTaskStopped:
            return
        except _VlmTaskFailure as exc:
            self._mark_vlm_failure(task_id, generation, exc.code, exc.message, pause=exc.pause)
        except VlmTransportError as exc:
            self._mark_vlm_failure(task_id, generation, exc.code, "the VLM provider stopped the bounded run", pause=False)
        except Exception:
            # Provider payloads and model text never enter task status or
            # trace.  Keep the user-facing failure deliberately generic.
            self._mark_vlm_failure(task_id, generation, "vlm_loop_failed", "the bounded VLM loop failed", pause=False)
        finally:
            # Preserve usage for every exit path, including cancellation,
            # provider validation, and a late exception inside a role step.
            if transport is not None:
                with self._condition:
                    task = self._tasks.get(task_id)
                    if task is not None and int(task.get("run_generation", 0)) == generation:
                        task["usage"] = transport.summary()
                        task["usage"] = self._usage_summary_locked(task)
                        self._persist_locked()
                        self._condition.notify_all()

    def _vlm_frame_from_contract(self, observation: dict[str, Any], task_id: str) -> Any:
        with self._condition:
            task = self._tasks.get(task_id)
            screenshot = task.get("vlm_after_screenshot") if task else None
            if screenshot is None:
                screenshot = self._screenshot_for_observation_locked(observation["observation_id"], "AFTER")
            if screenshot is None:
                raise _VlmTaskFailure("after_screenshot_missing", "the after observation has no screenshot", pause=True)
            return self._vlm_frame_locked(observation, screenshot)

    def _receive_vlm_receipt_locked(
            self,
            task: dict[str, Any],
            receipt: dict[str, Any],
            associated_after: dict[str, Any] | None,
            after_screenshot: dict[str, Any] | None,
            after_screenshot_missing_reason: str | None,
    ) -> dict[str, Any]:
        task_id = task["task_id"]
        if task.get("receipt") is not None:
            duplicate = copy.deepcopy(task["receipt"])
            duplicate["deduplicated"] = True
            return {**self._task_status_locked(task), "receipt": duplicate}
        task["receipt"] = copy.deepcopy(receipt)
        task["last_receipt"] = copy.deepcopy(receipt)
        task["after_screenshot_id"] = receipt.get("after_screenshot_id")
        if associated_after is not None:
            task["after_observation_id"] = associated_after["observation_id"]
            task["post_observation_id"] = associated_after["observation_id"]
            task["post_observation_version"] = associated_after["observation_version"]
            task["after_visual"] = copy.deepcopy(associated_after.get("visual"))
            task["vlm_after_observation"] = copy.deepcopy(associated_after)
        if after_screenshot is not None:
            task["vlm_after_screenshot"] = copy.deepcopy(after_screenshot)
        self._record_task_event_locked(task, "receipt.received", receipt["receipt_id"])
        independent = None
        if associated_after is not None:
            independent = self._judge_controlled_observation(associated_after, task.get("goal", ""))
            task["independent_result"] = independent
        effect_reason = receipt.get("error_code") or "action_rejected"
        effect_status = "REJECTED"
        if receipt["accepted"]:
            if associated_after is None:
                # A receipt is an execution fact, but this VLM action has no
                # post-action image to prove an effect.
                effect_status = "UNKNOWN"
                effect_reason = after_screenshot_missing_reason or "after_observation_missing"
            elif after_screenshot is None:
                # VLM actions require the real after image as evidence.  A
                # tree alone cannot turn an accepted receipt into an effect.
                effect_status = "UNKNOWN"
                effect_reason = after_screenshot_missing_reason or "after_screenshot_missing"
            elif independent is not None and independent.get("success") is True:
                effect_status = "EXECUTED"
                effect_reason = independent.get("reason") or "controlled_page_completed"
            else:
                effect_status = "UNKNOWN"
                effect_reason = (independent or {}).get("reason") or "effect_not_proven"
        task["actual_effect"] = {
            "status": effect_status,
            "action_id": receipt["action_id"],
            "receipt_id": receipt["receipt_id"],
            "reason": effect_reason,
        }
        if task["state"] in {"CANCELLED", "PAUSED"}:
            # A delayed receipt may resolve action_result_unknown, but never
            # reopens a user-controlled task or creates another action.
            task["action_result_unknown"] = False
            if task["state"] == "CANCELLED" and self._active_task_id == task_id:
                self._active_task_id = None
            self._condition.notify_all()
            self._persist_locked()
            return self._task_status_locked(task)
        if not receipt["accepted"]:
            task["state"] = "FAILED"
            task["phase"] = "FAILED"
            task["failure"] = {
                "code": receipt.get("error_code") or "action_rejected",
                "message": "the Android Accessibility service rejected the model action",
            }
            task["vlm_completion"] = {**(task.get("vlm_completion") or {}), "status": "FAILED"}
            self._active_task_id = None
            self._record_task_event_locked(task, "task.failed", task_id)
        elif after_screenshot is None:
            task["state"] = "PAUSED"
            task["phase"] = "PAUSED"
            task["action_result_unknown"] = True
            reason = after_screenshot_missing_reason or "after_screenshot_missing"
            task["failure"] = {
                "code": "screenshot_missing",
                "message": "after screenshot evidence is unavailable: " + reason,
            }
            task["vlm_completion"] = {**(task.get("vlm_completion") or {}), "status": "PAUSED"}
            self._record_task_event_locked(task, "task.paused", task_id)
        else:
            task["phase"] = "WAITING_MODEL"
        self._condition.notify_all()
        self._persist_locked()
        return self._task_status_locked(task)

    def task_status(self, task_id: str, session_id: str | None = None) -> dict[str, Any]:
        with self._lock:
            self._check_device_session_locked(session_id)
            task = self._tasks.get(task_id)
            if task is None:
                raise BridgeRequestError(404, "task_not_found", "task does not exist")
            return self._task_status_locked(task, mark_command=True)

    def control_task(
        self,
        task_id: str,
        command: str,
        reason: str | None = None,
        session_id: str | None = None,
    ) -> dict[str, Any]:
        if command not in {"pause", "cancel"}:
            raise BridgeRequestError(400, "invalid_control", "control command must be pause or cancel")
        with self._lock:
            self._check_device_session_locked(session_id)
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
            self._persist_locked()
            return self._task_status_locked(task)

    def _apply_control_locked(self, task: dict[str, Any], command: str, reason: str) -> None:
        task_id = task["task_id"]
        if command == "pause" and task["state"] == "RUNNING":
            if task.get("mode") == "vlm":
                task["run_generation"] = int(task.get("run_generation", 0)) + 1
            task["state"] = "PAUSED"
            task["phase"] = "PAUSED"
            task["control"] = {"command": command, "reason": reason, "requested_at": self.clock()}
            self._record_task_event_locked(task, "task.paused", task_id)
            if task.get("command_delivered") and task.get("receipt") is None:
                task["action_result_unknown"] = True
        elif command == "cancel" and task["state"] not in {"SUCCEEDED", "FAILED", "CANCELLED"}:
            if task.get("mode") == "vlm":
                task["run_generation"] = int(task.get("run_generation", 0)) + 1
            task["state"] = "CANCELLED"
            task["phase"] = "CANCELLED"
            task["control"] = {"command": command, "reason": reason, "requested_at": self.clock()}
            self._record_task_event_locked(task, "task.cancelled", task_id)
            if task.get("command_delivered") and task.get("receipt") is None:
                task["action_result_unknown"] = True
            elif self._active_task_id == task_id:
                self._active_task_id = None
        self._condition.notify_all()

    def receive_receipt(
        self,
        task_id: str,
        receipt: dict[str, Any],
        session_id: str | None = None,
    ) -> dict[str, Any]:
        try:
            assert_valid(receipt, "receipt")
        except SchemaValidationError as exc:
            raise BridgeRequestError(400, "invalid_schema", str(exc)) from exc
        after_id = receipt.get("after_observation_id")
        after_version = receipt.get("after_observation_version")
        after_screenshot_id = receipt.get("after_screenshot_id")
        after_screenshot_missing_reason = receipt.get("after_screenshot_missing_reason")
        if (after_id is None) != (after_version is None):
            raise BridgeRequestError(
                400,
                "invalid_schema",
                "after_observation_id and after_observation_version must be supplied together",
            )
        with self._lock:
            self._check_device_session_locked(session_id)
            task = self._tasks.get(task_id)
            if task is None:
                raise BridgeRequestError(404, "task_not_found", "task does not exist")
            if receipt["task_id"] != task_id or receipt["device_id"] != self.device_id:
                raise BridgeRequestError(403, "device_identity_mismatch", "receipt is not associated with this task and device")
            if task.get("mode") == "vlm" and task["state"] not in {"PAUSED", "CANCELLED"}:
                action = self._action_for_current_run_locked(task)
            else:
                # While awaiting reconciliation, a late receipt still belongs
                # to the interrupted action. Explicit resume clears this
                # generation marker, so it can never attach to the new run.
                action = self._action_for_recovery_locked(task)
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
            if after_screenshot_id is not None:
                if after_id is None:
                    raise BridgeRequestError(
                        400,
                        "invalid_schema",
                        "after_screenshot_id requires after_observation_id and after_observation_version",
                    )
                after_screenshot = self._screenshots.get(after_screenshot_id)
                if (
                    after_screenshot is None
                    or after_screenshot["capture_type"] != "AFTER"
                    or after_screenshot["observation_id"] != after_id
                    or after_screenshot["observation_version"] != after_version
                    or after_screenshot["task_id"] != task_id
                    or after_screenshot["device_id"] != self.device_id
                ):
                    raise BridgeRequestError(
                        409,
                        "screenshot_mismatch",
                        "the explicitly associated after screenshot does not match this task observation",
                    )
            if task.get("mode") == "vlm":
                return self._receive_vlm_receipt_locked(
                    task,
                    receipt,
                    associated_after,
                    after_screenshot if after_screenshot_id is not None else None,
                    after_screenshot_missing_reason,
                )
            if task.get("receipt") is not None:
                duplicate = copy.deepcopy(task["receipt"])
                duplicate["deduplicated"] = True
                return {**self._task_status_locked(task), "receipt": duplicate}
            task["receipt"] = copy.deepcopy(receipt)
            task["after_screenshot_id"] = after_screenshot_id
            if associated_after is not None:
                task["after_visual"] = copy.deepcopy(associated_after.get("visual"))
            self._record_task_event_locked(task, "receipt.received", receipt["receipt_id"])
            if task["state"] in {"CANCELLED", "PAUSED"}:
                if task.get("action_result_unknown"):
                    task["action_result_unknown"] = False
                if task["state"] == "CANCELLED" and self._active_task_id == task_id:
                    self._active_task_id = None
                self._persist_locked()
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
                    if not action.get("requires_screenshot") or after_screenshot_id is not None:
                        self._maybe_verify_task_locked()
                if action.get("requires_screenshot") and after_screenshot_id is None:
                    # The device may have executed the gesture, but without a
                    # real after image the visual result is not decidable.
                    # Preserve the receipt and hold the single-task boundary.
                    task["state"] = "PAUSED"
                    task["phase"] = "PAUSED"
                    task["action_result_unknown"] = True
                    reason = after_screenshot_missing_reason or "after_screenshot_missing"
                    task["failure"] = {
                        "code": "screenshot_missing",
                        "message": "after screenshot evidence is unavailable: " + reason,
                    }
                    task["verification"] = None
                    self._record_task_event_locked(task, "task.paused", task_id)
            self._persist_locked()
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
        else:
            expected = action.get("expected_page_state", "")
            aliases = {
                "long_press_completed": ("long press", "long_press"),
                "swipe_completed": ("swipe", "swipe"),
                "coordinate_tap_completed": ("coordinate tap", "coordinate_tap"),
                "system_back_completed": ("system back", "system_back"),
            }
            tokens = aliases.get(expected, (expected.replace("_", " "), expected))
            successful = any(
                any(
                    token in str(node.get(field, "")).casefold()
                    for field in ("text", "content_description", "state_description")
                    for token in tokens
                )
                and ("completed" in str(node.get("text", "")).casefold()
                     or "completed" in str(node.get("content_description", "")).casefold()
                     or expected in str(node.get("content_description", "")))
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


MAX_BODY_BYTES = 8 * 1024 * 1024


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
                    _send_json(self, 200, bridge.receive_observation(
                        _read_json(self), self.headers.get("X-JEV-Device-Session-Id")))
                    return
                if self.path == "/v1/android/screenshots":
                    _send_json(self, 200, bridge.receive_screenshot(
                        _read_json(self), self.headers.get("X-JEV-Device-Session-Id")))
                    return
                if path == "/v1/android/vlm-tasks":
                    _send_json(self, 200, bridge.submit_vlm_task(
                        _read_json(self), self.headers.get("X-JEV-Device-Session-Id")))
                    return
                segments = [part for part in path.split("/") if part]
                if len(segments) == 5 and segments[:3] == ["v1", "android", "tasks"]:
                    task_id, operation = segments[3], segments[4]
                    if operation == "command-intent":
                        _send_json(self, 200, bridge.record_command_intent(task_id, _read_json(self)))
                        return
                    if operation == "reconcile":
                        _send_json(self, 200, bridge.reconcile_task(task_id, _read_json(self)))
                        return
                    if operation == "resume":
                        task, recovery = bridge.resume_task(task_id, _read_json(self))
                        _send_json(self, 200, {"task": task, "recovery": recovery})
                        return
                task_prefix = segments[:2] in (["v1", "tasks"], ["v1", "android"])
                if task_prefix and len(segments) == 2 and segments[1] == "tasks":
                    _send_json(self, 200, bridge.submit_task(
                        _read_json(self), self.headers.get("X-JEV-Device-Session-Id")))
                    return
                if task_prefix and len(segments) == 3 and segments[1] == "android" and segments[2] == "tasks":
                    _send_json(self, 200, bridge.submit_task(
                        _read_json(self), self.headers.get("X-JEV-Device-Session-Id")))
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
                            segments[task_offset], command, payload.get("reason"),
                            self.headers.get("X-JEV-Device-Session-Id")))
                        return
                    if len(segments) == task_offset + 2 and segments[task_offset + 1] == "receipt":
                        _send_json(self, 200, bridge.receive_receipt(
                            segments[task_offset], _read_json(self),
                            self.headers.get("X-JEV-Device-Session-Id")))
                        return
                raise BridgeRequestError(404, "not_found", "Android bridge endpoint not found")
            except BridgeRequestError as exc:
                _send_json(self, exc.status, _error_payload(exc.code, exc.message))
            except Exception:
                _send_json(self, 500, _error_payload("bridge_internal_error", "Android bridge failed"))

        def do_GET(self) -> None:  # noqa: N802 - stdlib handler API
            try:
                if urlparse(self.path).path == "/healthz":
                    _send_json(self, 200, bridge.health())
                    return
                self._guard()
                parsed = urlparse(self.path)
                path = parsed.path
                query = parse_qs(parsed.query)
                segments = [part for part in path.split("/") if part]
                if len(segments) == 3 and segments[:2] == ["v1", "tasks"]:
                    _send_json(self, 200, bridge.task_status(
                        segments[2], self.headers.get("X-JEV-Device-Session-Id")))
                    return
                if len(segments) == 4 and segments[:3] == ["v1", "android", "tasks"]:
                    _send_json(self, 200, bridge.task_status(
                        segments[3], self.headers.get("X-JEV-Device-Session-Id")))
                    return
                if len(segments) == 5 and segments[:3] == ["v1", "android", "tasks"] and segments[4] == "recovery":
                    _send_json(self, 200, bridge.task_recovery_status(
                        segments[3], self.headers.get("X-JEV-Device-Session-Id")))
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
