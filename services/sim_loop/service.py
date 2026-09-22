"""Public task service and its localhost HTTP entry point."""

from __future__ import annotations

import copy
import secrets
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable
from urllib.parse import unquote, urlparse

from .http_common import (
    RequestRejected,
    error_payload,
    read_json,
    require_auth,
    require_protocol,
    send_json,
)
from .model import LiveModel, ModelError, ReplayModel, build_model_request
from .persistence import load_json, save_json, scene_fingerprint
from .schema import PROTOCOL_VERSION, SCHEMA_VERSION, SchemaValidationError, assert_valid
from .transport import DeviceClient, TransportError

Clock = Callable[[], str]


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class ServiceRequestError(ValueError):
    def __init__(self, status: int, code: str, message: str):
        self.status = status
        self.code = code
        self.message = message
        super().__init__(message)


class SimulationService:
    """Runs one task through a device HTTP transport.

    The original task entry point remains synchronous: callers that submit a
    normal simulation receive the final result in the same response.  The
    task is nevertheless guarded at every side-effect boundary, so a caller
    can send a pause or cancel request from another HTTP connection while the
    submit request is waiting on a model or device response.
    """

    TERMINAL_STATES = {"SUCCEEDED", "FAILED", "CANCELLED"}

    def __init__(
        self,
        *,
        device_base_url: str,
        token: str,
        device_id: str,
        behavior: str = "apply_effect",
        clock: Clock = utc_now,
        device_timeout: float = 2.0,
        dispatch_delay: float = 0.0,
        state_path: str | Path | None = None,
        persistence_path: str | Path | None = None,
    ):
        if not token:
            raise ValueError("service token must be supplied explicitly")
        self.token = token
        self.device_id = device_id
        self.behavior = behavior
        self.clock = clock
        self.dispatch_delay = max(0.0, float(dispatch_delay))
        if state_path is not None and persistence_path is not None and Path(state_path) != Path(persistence_path):
            raise ValueError("state_path and persistence_path must identify the same file")
        self.state_path = Path(state_path if state_path is not None else persistence_path) if (state_path is not None or persistence_path is not None) else None
        self.device_client = DeviceClient(
            device_base_url,
            token=token,
            device_id=device_id,
            protocol_version=PROTOCOL_VERSION,
            timeout=device_timeout,
        )
        self._lock = threading.RLock()
        self._tasks: dict[str, dict[str, Any]] = {}
        self._active_task_id: str | None = None
        self._load_state()

    def _load_state(self) -> None:
        """Load task checkpoints and fence unfinished work after a restart."""

        if self.state_path is None:
            return
        state = load_json(self.state_path)
        if state is None:
            return
        if state.get("device_id") not in (None, self.device_id):
            raise ValueError("persistent service state belongs to another device")
        tasks = state.get("tasks", {})
        if isinstance(tasks, list):
            tasks = {str(task["task_id"]): task for task in tasks if isinstance(task, dict) and task.get("task_id")}
        if not isinstance(tasks, dict):
            raise ValueError("persistent service state tasks must be an object")
        self._tasks = {str(task_id): copy.deepcopy(task) for task_id, task in tasks.items() if isinstance(task, dict)}
        persisted_active = state.get("active_task_id")
        self._active_task_id = persisted_active if isinstance(persisted_active, str) else None
        # A process restart revokes the old execution lease.  Every unfinished
        # task must pass through reconnect -> reconcile -> explicit resume.
        for task in self._tasks.values():
            if task.get("state") in {"RUNNING", "PAUSED"}:
                task.setdefault("trace", [])
                task.setdefault("control_epoch", 0)
                task["control_epoch"] = int(task["control_epoch"]) + 1
                task["state"] = "PAUSED"
                task["connection"] = {
                    **(task.get("connection") or {}),
                    "state": "DISCONNECTED",
                    "generation": int((task.get("connection") or {}).get("generation", 0)) + 1,
                }
                recovery = task.setdefault("recovery", self._new_recovery())
                task["restart_recovery"] = bool(task.get("action") is not None and task.get("receipt") is None)
                recovery.update(
                    {
                        "required": True,
                        "phase": "RECONNECT_REQUIRED",
                        "reason": "process_restarted",
                        "eligible": False,
                        "confirmed": False,
                        "resume_token": None,
                    }
                )
                task.pop("reconciliation", None)
                task["failure"] = {
                    "code": "recovery_required",
                    "message": "the service restarted; reconnect and reconcile before resuming",
                }
                self._append_recovery_event(task, "process_restarted")
        self._active_task_id = self._derive_active_task_id(self._active_task_id)
        self._persist()

    def _derive_active_task_id(self, preferred: str | None = None) -> str | None:
        if preferred and preferred in self._tasks:
            task = self._tasks[preferred]
            if task.get("state") not in self.TERMINAL_STATES or task.get("action_result_unknown"):
                return preferred
        for task_id, task in self._tasks.items():
            if task.get("state") not in self.TERMINAL_STATES or task.get("action_result_unknown"):
                return task_id
        return None

    def _persist(self) -> None:
        if self.state_path is None:
            return
        with self._lock:
            save_json(
                self.state_path,
                {
                    "format_version": 1,
                    "schema_version": SCHEMA_VERSION,
                    "device_id": self.device_id,
                    "tasks": copy.deepcopy(self._tasks),
                    "active_task_id": self._active_task_id,
                },
            )

    @staticmethod
    def _new_recovery() -> dict[str, Any]:
        return {
            "required": False,
            "phase": "NONE",
            "reason": None,
            "fault_point": None,
            "eligible": False,
            "confirmed": False,
            "revision": 0,
            "reconnect_count": 0,
            "observation_id": None,
            "observation_version": None,
            "scene_fingerprint": None,
            "action_outcome": None,
            "resume_token": None,
        }

    def _append_recovery_event(self, task: dict[str, Any], reason: str) -> None:
        # This helper is used while loading before any request is served.
        # Keep the event shape identical to normal _record events.
        self._record(task, "recovery.required", "task", task["task_id"], {})

    @property
    def active_task_id(self) -> str | None:
        with self._lock:
            return self._active_task_id

    @property
    def active_action_id(self) -> str | None:
        with self._lock:
            if self._active_task_id is None:
                return None
            task = self._tasks.get(self._active_task_id)
            return task.get("in_flight_action_id") if task else None

    def validate_submission(self, payload: dict[str, Any]) -> None:
        try:
            assert_valid(payload, "task_submit")
        except SchemaValidationError as exc:
            raise ServiceRequestError(400, "invalid_schema", str(exc)) from exc
        if payload["device_id"] != self.device_id:
            raise ServiceRequestError(403, "device_identity_mismatch", "task device is not paired with this service")
        if payload["mode"] != "simulated":
            raise ServiceRequestError(422, "simulation_mode_required", "task mode must explicitly be simulated")
        if payload["scenario"] != self.behavior:
            raise ServiceRequestError(409, "simulation_behavior_mismatch", "task scenario does not match the configured device")

    @staticmethod
    def _normalized_fault_injection(payload: dict[str, Any]) -> dict[str, Any]:
        configured = payload.get("fault_injection")
        point = configured.get("point") if isinstance(configured, dict) else None
        return {
            "point": point,
            "triggered": False,
        }

    @staticmethod
    def _fault_point(task: dict[str, Any]) -> str | None:
        fault = task.get("fault_injection") or {}
        if fault.get("triggered"):
            return None
        point = fault.get("point")
        return str(point) if point else None

    @staticmethod
    def _action_id(task: dict[str, Any]) -> str:
        generation = int(task.get("resume_generation", 0))
        suffix = "" if generation == 0 else f"-resume-{generation}"
        return f"action-{task['task_id']}{suffix}"

    def _trigger_fault(self, task: dict[str, Any], point: str) -> None:
        with self._lock:
            fault = task.setdefault("fault_injection", {})
            if fault.get("point") != point or fault.get("triggered"):
                return
            fault["triggered"] = True
            self._persist()

    def _mark_recovery_required(
        self,
        task: dict[str, Any],
        *,
        reason: str,
        phase: str,
        fault_point: str | None = None,
    ) -> None:
        with self._lock:
            task["state"] = "PAUSED"
            task["control_epoch"] = int(task.get("control_epoch", 0)) + 1
            task["connection"] = {
                **(task.get("connection") or {}),
                "state": "DISCONNECTED",
            }
            recovery = task.setdefault("recovery", self._new_recovery())
            recovery.update(
                {
                    "required": True,
                    "phase": phase,
                    "reason": reason,
                    "fault_point": fault_point,
                    "eligible": False,
                    "confirmed": False,
                    "resume_token": None,
                }
            )
            task["failure"] = {"code": "recovery_required", "message": reason}
            self._record(task, "recovery.required", "task", task["task_id"], {})
            self._persist()

    def _interrupt_for_recovery(
        self,
        task: dict[str, Any],
        *,
        reason: str,
        phase: str,
        fault_point: str | None = None,
    ) -> None:
        if fault_point is not None:
            self._trigger_fault(task, fault_point)
        self._mark_recovery_required(task, reason=reason, phase=phase, fault_point=fault_point)

    def submit(self, payload: dict[str, Any]) -> dict[str, Any]:
        self.validate_submission(payload)
        task_id = payload["task_id"]
        session_id = payload.get("session_id") or f"session-{task_id}"
        with self._lock:
            if self._active_task_id is not None:
                raise ServiceRequestError(409, "task_active", "one task is already active")
            if task_id in self._tasks:
                raise ServiceRequestError(409, "task_exists", "task_id already exists")
            task: dict[str, Any] = {
                "schema_version": SCHEMA_VERSION,
                "task_id": task_id,
                "device_id": self.device_id,
                "session_id": session_id,
                "mode": payload["mode"],
                "scenario": payload["scenario"],
                "goal": payload["goal"],
                "state": "RUNNING",
                "observations": [],
                "action": None,
                "receipt": None,
                "verification": None,
                "attempts": [],
                "usage": None,
                "trace": [],
                "control_epoch": 0,
                "control": None,
                "in_flight_action_id": None,
                "action_result_unknown": False,
                "submission": copy.deepcopy(payload),
                "fault_injection": self._normalized_fault_injection(payload),
                "connection": {"state": "CONNECTED", "generation": 0},
                "recovery": self._new_recovery(),
                "reconciliation": None,
                "resume_generation": 0,
                "restart_recovery": False,
            }
            self._tasks[task_id] = task
            self._active_task_id = task_id
            self._persist()
        try:
            self._run_task(task, payload)
        finally:
            with self._lock:
                if (
                    self._active_task_id == task_id
                    and task["state"] != "PAUSED"
                    and not task["action_result_unknown"]
                ):
                    self._active_task_id = None
                if not task["action_result_unknown"]:
                    task["in_flight_action_id"] = None
                self._persist()
        return self.status(task_id) or copy.deepcopy(task)

    def _run_task(self, task: dict[str, Any], payload: dict[str, Any]) -> None:
        task_id = task["task_id"]
        epoch = 0
        before: dict[str, Any] | None = None
        action: dict[str, Any] | None = None
        try:
            if not any(event.get("kind") == "task.submitted" for event in task.get("trace", [])):
                self._record(task, "task.submitted", "task", task_id, {})
            before = self.device_client.observe(task_id, session_id=task["session_id"])
            self._validate_observation(before, task_id, phase="before", session_id=task["session_id"])
            with self._lock:
                task["observations"].append(before)
            self._record(
                task,
                "observation.captured",
                "observation",
                before["observation_id"],
                {"observation_id": before["observation_id"]},
            )

            self._ensure_action_applicable(before)
            epoch = self._control_epoch(task)
            if not self._can_continue(task, epoch):
                return

            action = self._action_from_model(task, payload, before, epoch)
            if action is None or not self._can_continue(task, epoch):
                return
            self._validate_action_binding(task, action, before)
            if self.dispatch_delay:
                time.sleep(self.dispatch_delay)
            if not self._can_continue(task, epoch):
                return

            if self._fault_point(task) == "before_dispatch":
                self._interrupt_for_recovery(
                    task,
                    reason="simulated disconnect before action dispatch",
                    phase="BEFORE_DISPATCH",
                    fault_point="before_dispatch",
                )
                return

            self._mark_action_dispatched(task, action, before, epoch)
            try:
                receipt = self.device_client.execute(action)
            except TransportError as exc:
                self._handle_action_transport_error(task, action, before, epoch, exc)
                return
            if self._fault_point(task) == "after_execute_before_receipt":
                with self._lock:
                    task["action_result_unknown"] = True
                self._interrupt_for_recovery(
                    task,
                    reason="simulated disconnect after device execution before receipt delivery",
                    phase="AFTER_EXECUTE_BEFORE_RECEIPT",
                    fault_point="after_execute_before_receipt",
                )
                with self._lock:
                    task["failure"] = {
                        "code": "action_result_unknown",
                        "message": "device execution completed without a delivered receipt",
                    }
                    self._persist()
                self._record_unknown(task, action, before, "receipt_not_delivered")
                return
            assert_valid(receipt, "receipt")
            self._validate_receipt(receipt, task_id, action)
            with self._lock:
                # A response from an execution generation that has already
                # been reconciled is stale.  It must not overwrite a terminal
                # result or bypass the confirmation fence of a resumed run.
                if task["state"] in {"SUCCEEDED", "FAILED"}:
                    return
                if task["state"] == "PAUSED" and task.get("recovery", {}).get("revision", 0):
                    return
                if task["state"] == "RUNNING" and task["control_epoch"] != epoch:
                    return
                task["receipt"] = receipt
                task["in_flight_action_id"] = None
            self._record(
                task,
                "receipt.received",
                "receipt",
                receipt["receipt_id"],
                {"action_id": action["action_id"], "receipt_id": receipt["receipt_id"]},
            )

            if self._fault_point(task) == "after_receipt_before_verification":
                self._interrupt_for_recovery(
                    task,
                    reason="simulated disconnect after receipt before verification",
                    phase="AFTER_RECEIPT_BEFORE_VERIFICATION",
                    fault_point="after_receipt_before_verification",
                )
                return

            if not self._can_continue(task, epoch):
                with self._lock:
                    if task["state"] in {"SUCCEEDED", "FAILED"} or task.get("recovery", {}).get("revision", 0):
                        return
                self._record_unknown(task, action, before, self._control_reason(task))
                return

            after = self.device_client.observe(task_id, session_id=task["session_id"])
            self._validate_observation(
                after,
                task_id,
                phase="after",
                previous=before,
                session_id=task["session_id"],
            )
            with self._lock:
                task["observations"].append(after)
            self._record(
                task,
                "observation.captured",
                "observation",
                after["observation_id"],
                {"observation_id": after["observation_id"]},
            )

            verification = self._verify(task_id, action, before, after)
            with self._lock:
                if task["state"] in {"SUCCEEDED", "FAILED"}:
                    return
                if task["state"] != "RUNNING" or task["control_epoch"] != epoch:
                    self._record_unknown(task, action, before, self._control_reason(task))
                    return
                task["verification"] = verification
            self._record(
                task,
                "verification.recorded",
                "verification",
                verification["verification_id"],
                {"action_id": action["action_id"], "verification_id": verification["verification_id"]},
            )
            with self._lock:
                if task["state"] != "RUNNING" or task["control_epoch"] != epoch:
                    return
                if verification["status"] == "SUCCESS":
                    task["state"] = "SUCCEEDED"
                else:
                    task["state"] = "FAILED"
            self._record(
                task,
                "task.completed" if verification["status"] == "SUCCESS" else "task.failed",
                "task",
                task_id,
                {"verification_id": verification["verification_id"]},
            )
        except (TransportError, SchemaValidationError, ServiceRequestError, ModelError) as exc:
            self._finish_error(task, exc, action=action, before=before, epoch=epoch)

    def _action_from_model(
        self,
        task: dict[str, Any],
        payload: dict[str, Any],
        before: dict[str, Any],
        epoch: int,
    ) -> dict[str, Any] | None:
        config = payload.get("model")
        if config is None:
            return self._default_action(task, before)

        mode = config["mode"]
        if mode == "replay":
            model: ReplayModel | LiveModel = ReplayModel(
                config.get("responses"),
                max_attempts=config.get("max_attempts", 3),
                retry_limit=config.get("retry_limit"),
                delay_ms=config.get("delay_ms", 0),
            )
        else:
            model = LiveModel(
                endpoint=config.get("endpoint"),
                model=config.get("model"),
                credential_env=config.get("credential_env", "JEV_VLM_API_KEY"),
                timeout=config.get("timeout", 10),
            )

        prompt = config.get("prompt", "请点击开始按钮并完成当前任务。")
        images = config.get("images", [])
        max_attempts = model.max_attempts if isinstance(model, ReplayModel) else 1
        retry_limit = model.retry_limit if isinstance(model, ReplayModel) else 0
        retries = 0
        for attempt_number in range(1, max_attempts + 1):
            if not self._can_continue(task, epoch):
                return None
            attempt_id = f"attempt-{task['task_id']}-{attempt_number}"
            request = build_model_request(
                task_id=task["task_id"],
                attempt_id=attempt_id,
                prompt=prompt,
                images=images,
                clock=self.clock,
            )
            self._record(task, "model.requested", "model_request", attempt_id, {})
            attempt: dict[str, Any] = {
                "attempt_id": attempt_id,
                "attempt": attempt_number,
                "role": "planner",
                "request": request,
                "response": None,
                "error": None,
                "usage": None,
                "outcome": "PENDING",
                "started_at": request["created_at"],
                "completed_at": None,
            }
            try:
                response = model.complete(request)
            except ModelError as exc:
                attempt["error"] = {"code": exc.code, "message": exc.message, "retryable": exc.retryable}
                attempt["usage"] = copy.deepcopy(exc.usage)
                attempt["outcome"] = "ERROR"
                attempt["completed_at"] = self.clock()
                with self._lock:
                    task["attempts"].append(attempt)
                    self._merge_usage(task, attempt["usage"])
                self._record(task, "model.failed", "model_attempt", attempt_id, {})
                if not self._can_continue(task, epoch):
                    return None
                if exc.retryable and retries < retry_limit and attempt_number < max_attempts:
                    retries += 1
                    continue
                raise exc

            attempt["response"] = copy.deepcopy(response)
            attempt["usage"] = copy.deepcopy(response.get("usage"))
            attempt["completed_at"] = self.clock()
            if response.get("kind") != "action" or not isinstance(response.get("action"), dict):
                attempt["outcome"] = "MALFORMED"
                with self._lock:
                    task["attempts"].append(attempt)
                    self._merge_usage(task, attempt["usage"])
                self._record(task, "model.responded", "model_attempt", attempt_id, {})
                raise ModelError("invalid_model_response", "replay response did not contain a valid action", status=422)
            attempt["outcome"] = "ACTION"
            with self._lock:
                task["attempts"].append(attempt)
                self._merge_usage(task, attempt["usage"])
            self._record(task, "model.responded", "model_attempt", attempt_id, {})
            if not self._can_continue(task, epoch):
                return None
            action = dict(response["action"])
            missing = [field for field in ("kind", "target_node_id", "expected_page_state") if not action.get(field)]
            if missing:
                raise ModelError(
                    "invalid_model_response",
                    "replay action is missing required field(s): " + ", ".join(missing),
                    status=422,
                )
            action.setdefault("schema_version", SCHEMA_VERSION)
            action.setdefault("task_id", task["task_id"])
            action.setdefault("action_id", self._action_id(task))
            if int(task.get("resume_generation", 0)) and action.get("action_id") == f"action-{task['task_id']}":
                action["action_id"] = self._action_id(task)
            action.setdefault("observation_id", before["observation_id"])
            action.setdefault("observation_version", before.get("observation_version", before["observation_id"]))
            action.setdefault("kind", "tap")
            action.setdefault("target_node_id", "start-button")
            action.setdefault("expected_page_state", "done")
            action.setdefault("source", "replay-model" if mode == "replay" else "live-model")
            action.setdefault("created_at", self.clock())
            action.setdefault("device_id", self.device_id)
            action.setdefault("session_id", task["session_id"])
            action.setdefault("sequence", 1)
            assert_valid(action, "action")
            return action
        raise ModelError("model_retry_exhausted", "model retry budget was exhausted", status=429)

    def _default_action(self, task: dict[str, Any], before: dict[str, Any]) -> dict[str, Any]:
        action = {
            "schema_version": SCHEMA_VERSION,
            "task_id": task["task_id"],
            "action_id": self._action_id(task),
            "observation_id": before["observation_id"],
            "observation_version": before.get("observation_version", before["observation_id"]),
            "kind": "tap",
            "target_node_id": "start-button",
            "expected_page_state": "done",
            "source": "simulated-planner",
            "created_at": self.clock(),
            "device_id": self.device_id,
            "session_id": task["session_id"],
            "sequence": 1,
        }
        assert_valid(action, "action")
        return action

    def _validate_action_binding(
        self,
        task: dict[str, Any],
        action: dict[str, Any],
        observation: dict[str, Any],
    ) -> None:
        """Reject a candidate before it can reach the device transport."""

        if action["task_id"] != task["task_id"]:
            raise ServiceRequestError(409, "action_task_mismatch", "action is bound to another task")
        if action.get("device_id") not in (None, self.device_id):
            raise ServiceRequestError(403, "device_identity_mismatch", "action is bound to another device")
        if action.get("session_id") not in (None, task["session_id"]):
            raise ServiceRequestError(409, "stale_session", "action belongs to an obsolete task session")
        if action["observation_id"] != observation["observation_id"]:
            raise ServiceRequestError(409, "stale_observation", "action is bound to an obsolete observation")
        if action.get("observation_version") not in (None, observation.get("observation_version", observation["observation_id"])):
            raise ServiceRequestError(409, "stale_observation", "action observation version is obsolete")
        if action.get("sequence") not in (None, 1):
            raise ServiceRequestError(409, "action_out_of_order", "the first task action must have sequence 1")
        if action["kind"] not in observation["capabilities"]:
            raise ServiceRequestError(422, "unsupported_action", "the device does not advertise this action capability")
        node = next((candidate for candidate in observation["nodes"] if candidate["node_id"] == action["target_node_id"]), None)
        if node is None:
            raise ServiceRequestError(409, "target_node_not_found", "the action target is not present in the observation")
        if not node["enabled"]:
            raise ServiceRequestError(409, "target_node_disabled", "the action target is disabled in the observation")
        if action.get("target_node_role") not in (None, node["role"]):
            raise ServiceRequestError(409, "target_node_mismatch", "the action target role does not match the observation")
        if action.get("target_node_label") not in (None, node["label"]):
            raise ServiceRequestError(409, "target_node_mismatch", "the action target label does not match the observation")

    def _mark_action_dispatched(
        self,
        task: dict[str, Any],
        action: dict[str, Any],
        before: dict[str, Any],
        epoch: int,
    ) -> None:
        del before
        with self._lock:
            if task["state"] != "RUNNING" or task["control_epoch"] != epoch:
                raise ServiceRequestError(409, "task_controlled", "task was paused or cancelled before action delivery")
            task["action"] = copy.deepcopy(action)
            task["in_flight_action_id"] = action["action_id"]
        self._record(
            task,
            "action.dispatched",
            "action",
            action["action_id"],
            {"observation_id": action["observation_id"], "action_id": action["action_id"]},
        )

    def _handle_action_transport_error(
        self,
        task: dict[str, Any],
        action: dict[str, Any],
        before: dict[str, Any],
        epoch: int,
        exc: TransportError,
    ) -> None:
        if exc.is_explicit_device_rejection:
            with self._lock:
                task["action_result_unknown"] = False
                task["in_flight_action_id"] = None
                task["failure"] = {
                    "code": exc.error_code,
                    "message": exc.error_message,
                }
                transitioned_to_failed = task["state"] in {"RUNNING", "PAUSED"} and task["state"] != "CANCELLED"
                if transitioned_to_failed:
                    task["state"] = "FAILED"
                if self._active_task_id == task["task_id"]:
                    self._active_task_id = None
            if transitioned_to_failed:
                self._record(task, "task.failed", "task", task["task_id"], {})
            else:
                self._persist()
            return
        with self._lock:
            # A timeout after dispatch does not prove that the device stopped
            # processing the command. Keep the action associated with the
            # task until a later reconciliation boundary can close it.
            task["action_result_unknown"] = True
            task["failure"] = {
                "code": "action_result_unknown",
                "message": f"action delivery ended without a reliable receipt: {exc}",
            }
            if task["state"] == "RUNNING" and task["control_epoch"] == epoch:
                task["state"] = "PAUSED"
        self._record_unknown(task, action, before, "action_result_unknown")

    def _record_unknown(
        self,
        task: dict[str, Any],
        action: dict[str, Any],
        before: dict[str, Any],
        reason: str | None,
    ) -> None:
        verification = self._unknown_verification(task["task_id"], action, before, reason or "evidence_unavailable")
        with self._lock:
            if task["state"] in {"SUCCEEDED", "FAILED"}:
                return
            task["verification"] = verification
        self._record(
            task,
            "verification.recorded",
            "verification",
            verification["verification_id"],
            {"action_id": action["action_id"], "verification_id": verification["verification_id"]},
        )

    def _unknown_verification(
        self,
        task_id: str,
        action: dict[str, Any],
        before: dict[str, Any],
        reason: str,
    ) -> dict[str, Any]:
        verification = {
            "schema_version": SCHEMA_VERSION,
            "task_id": task_id,
            "verification_id": f"verification-{task_id}",
            "action_id": action["action_id"],
            "before_observation_id": before["observation_id"],
            "after_observation_id": "",
            "status": "UNKNOWN",
            "expected_page_state": action["expected_page_state"],
            "actual_page_state": "unknown",
            "reason": reason,
            "verified_at": self.clock(),
        }
        assert_valid(verification, "verification")
        return verification

    def _finish_error(
        self,
        task: dict[str, Any],
        exc: Exception,
        *,
        action: dict[str, Any] | None,
        before: dict[str, Any] | None,
        epoch: int,
    ) -> None:
        if isinstance(exc, TransportError):
            code = "device_transport_error"
            message = str(exc)
        elif isinstance(exc, SchemaValidationError):
            code = "contract_error"
            message = str(exc)
        elif isinstance(exc, ModelError):
            code = {
                "credentials_required": "model_credentials_required",
                "configuration_required": "model_configuration_required",
                "invalid_endpoint": "model_invalid_endpoint",
                "invalid_model_config": "model_invalid_config",
                "endpoint_unreachable": "model_endpoint_unreachable",
                "invalid_model_request": "model_invalid_request",
                "invalid_model_response": "model_invalid_response",
                "replay_exhausted": "model_replay_exhausted",
                "timeout": "model_timeout",
                "rate_limited": "model_rate_limited",
            }.get(exc.code, "model_error")
            message = exc.message
        else:
            assert isinstance(exc, ServiceRequestError)
            code = exc.code
            message = exc.message
        with self._lock:
            task["in_flight_action_id"] = None
            if task["state"] != "RUNNING" or task["control_epoch"] != epoch:
                return
            task["state"] = "FAILED"
            task["failure"] = {"code": code, "message": message}
        if action is not None and before is not None and code == "action_result_unknown":
            self._record_unknown(task, action, before, code)
        self._record(task, "task.failed", "task", task["task_id"], {})

    def _validate_observation(
        self,
        observation: dict[str, Any],
        task_id: str,
        *,
        phase: str,
        previous: dict[str, Any] | None = None,
        session_id: str | None = None,
    ) -> None:
        assert_valid(observation, "observation")
        if observation["task_id"] != task_id or observation["device_id"] != self.device_id:
            raise ServiceRequestError(
                502,
                "device_response_mismatch",
                f"{phase} observation is not associated with the requested task and device",
            )
        if session_id is not None and observation.get("session_id") != session_id:
            raise ServiceRequestError(
                502,
                "device_response_mismatch",
                f"{phase} observation is not associated with the requested task session",
            )
        if previous is not None and observation["observation_id"] == previous["observation_id"]:
            raise ServiceRequestError(
                502,
                "device_response_mismatch",
                "after observation must be distinct from the before observation",
            )

    def _validate_receipt(self, receipt: dict[str, Any], task_id: str, action: dict[str, Any]) -> None:
        if (
            receipt["task_id"] != task_id
            or receipt["device_id"] != self.device_id
            or receipt["action_id"] != action["action_id"]
        ):
            raise ServiceRequestError(
                502,
                "device_response_mismatch",
                "device receipt is not associated with the requested task, device, and action",
            )
        if receipt.get("session_id") not in (None, action.get("session_id")):
            raise ServiceRequestError(502, "device_response_mismatch", "device receipt belongs to another session")

    def _ensure_action_applicable(self, observation: dict[str, Any]) -> None:
        if observation["page_state"] != "landing":
            raise ServiceRequestError(
                409,
                "action_not_applicable",
                "the simulated start action is not applicable to the current page state",
            )
        start_node = next((node for node in observation["nodes"] if node["node_id"] == "start-button"), None)
        if start_node is None or not start_node["enabled"]:
            raise ServiceRequestError(
                409,
                "action_not_applicable",
                "the simulated start button is not enabled in the current observation",
            )

    def _verify(self, task_id: str, action: dict[str, Any], before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
        successful = after["page_state"] == action["expected_page_state"]
        verification = {
            "schema_version": SCHEMA_VERSION,
            "task_id": task_id,
            "verification_id": f"verification-{task_id}",
            "action_id": action["action_id"],
            "before_observation_id": before["observation_id"],
            "after_observation_id": after["observation_id"],
            "status": "SUCCESS" if successful else "FAILURE",
            "expected_page_state": action["expected_page_state"],
            "actual_page_state": after["page_state"],
            "reason": "postcondition_met" if successful else "postcondition_not_met",
            "verified_at": self.clock(),
        }
        assert_valid(verification, "verification")
        return verification

    def _control_epoch(self, task: dict[str, Any]) -> int:
        with self._lock:
            return int(task["control_epoch"])

    def _can_continue(self, task: dict[str, Any], epoch: int) -> bool:
        with self._lock:
            return task["state"] == "RUNNING" and task["control_epoch"] == epoch

    def _control_reason(self, task: dict[str, Any]) -> str:
        with self._lock:
            control = task.get("control") or {}
            return str(control.get("reason") or "task_controlled")

    def _merge_usage(self, task: dict[str, Any], usage: Any) -> None:
        if not isinstance(usage, dict):
            return
        current = task.get("usage")
        if not isinstance(current, dict):
            current = {}
            task["usage"] = current
        for key, value in usage.items():
            if isinstance(value, (int, float)) and isinstance(current.get(key), (int, float)):
                current[key] += value
            elif key not in current:
                current[key] = value

    def pause(self, task_id: str, *, reason: str = "user_pause") -> dict[str, Any]:
        return self._control(task_id, "pause", reason)

    def cancel(self, task_id: str, *, reason: str = "user_cancel") -> dict[str, Any]:
        return self._control(task_id, "cancel", reason)

    def control(self, task_id: str, command: str, *, reason: str | None = None) -> dict[str, Any]:
        if reason is None:
            reason = "user_" + command
        return self._control(task_id, command, reason)

    def reconnect(self, task_id: str) -> dict[str, Any]:
        """Restore the logical connection while deliberately remaining paused."""

        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                raise ServiceRequestError(404, "task_not_found", "task does not exist")
            if task["state"] in self.TERMINAL_STATES:
                return copy.deepcopy(task)
            transitioned = task["state"] == "RUNNING"
            if transitioned:
                task["control_epoch"] += 1
                task["state"] = "PAUSED"
                self._record(task, "task.paused", "task", task_id, {})
            task["connection"] = {
                **(task.get("connection") or {}),
                "state": "CONNECTED",
                "generation": int((task.get("connection") or {}).get("generation", 0)) + 1,
            }
            recovery = task.setdefault("recovery", self._new_recovery())
            recovery.update(
                {
                    "required": True,
                    "phase": "RECONCILIATION_REQUIRED",
                    "reason": "connection_reestablished_requires_reconciliation",
                    "eligible": False,
                    "confirmed": False,
                    "resume_token": None,
                }
            )
            recovery["reconnect_count"] = int(recovery.get("reconnect_count", 0)) + 1
            task["reconciliation"] = None
            self._persist()
            return copy.deepcopy(task)

    def reconcile(self, task_id: str) -> dict[str, Any]:
        """Observe the device and classify the pending command.

        A device history lookup that cannot be completed is intentionally
        surfaced as UNKNOWN.  Reconciliation never dispatches an action.
        """

        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                raise ServiceRequestError(404, "task_not_found", "task does not exist")
            if task["state"] in {"SUCCEEDED", "FAILED"} and not task.get("action_result_unknown"):
                return copy.deepcopy(task)
            task.setdefault("recovery", self._new_recovery())
            if task["state"] == "RUNNING":
                task["control_epoch"] += 1
                task["state"] = "PAUSED"
                task["recovery"].update(
                    {
                        "required": True,
                        "phase": "RECONCILIATION_REQUIRED",
                        "reason": "reconcile_requested_while_running",
                        "eligible": False,
                        "resume_token": None,
                    }
                )
                self._record(task, "task.paused", "task", task_id, {})
            session_id = task.get("session_id")
            action = copy.deepcopy(task.get("action"))
            previous_fingerprint = task["recovery"].get("scene_fingerprint")

        try:
            observation = self.device_client.observe(task_id, session_id=session_id)
            self._validate_observation(observation, task_id, phase="reconciliation", session_id=session_id)
        except (TransportError, ServiceRequestError, SchemaValidationError) as exc:
            reason = self._reconciliation_error_reason(exc)
            with self._lock:
                task = self._tasks[task_id]
                recovery = task.setdefault("recovery", self._new_recovery())
                recovery.update(
                    {
                        "required": True,
                        "phase": "RECONCILIATION_REQUIRED",
                        "reason": reason,
                        "eligible": False,
                        "confirmed": False,
                        "action_outcome": "UNKNOWN",
                        "resume_token": None,
                    }
                )
                if action is not None:
                    task["action_result_unknown"] = True
                task["reconciliation"] = {
                    "reconciliation_id": f"reconciliation-{task_id}-{int(recovery.get('revision', 0)) + 1}",
                    "status": "UNKNOWN",
                    "action_outcome": "UNKNOWN",
                    "action_id": action.get("action_id") if action else None,
                    "observation_id": None,
                    "observation_version": None,
                    "reason": reason,
                    "receipt": None,
                }
                self._record(task, "recovery.reconciled", "task", task_id, {})
                self._persist()
                return copy.deepcopy(task)

        with self._lock:
            task = self._tasks[task_id]
            task.setdefault("observations", []).append(copy.deepcopy(observation))
            self._record(
                task,
                "observation.captured",
                "observation",
                observation["observation_id"],
                {"observation_id": observation["observation_id"]},
            )

        outcome, receipt, outcome_reason = self._classify_reconciliation(task_id, action, observation, session_id)
        fingerprint = scene_fingerprint(observation)
        with self._lock:
            task = self._tasks[task_id]
            recovery = task.setdefault("recovery", self._new_recovery())
            recovery["revision"] = int(recovery.get("revision", 0)) + 1
            scene_changed = previous_fingerprint is not None and previous_fingerprint != fingerprint
            if scene_changed:
                outcome_reason = "observation_changed_since_previous_reconciliation"
                outcome_for_confirmation = "UNKNOWN"
            else:
                outcome_for_confirmation = outcome
            if receipt is not None and task.get("receipt") is None:
                task["receipt"] = copy.deepcopy(receipt)
            if outcome_for_confirmation in {"EXECUTED", "NOT_EXECUTED"}:
                task["action_result_unknown"] = False
                task["in_flight_action_id"] = None
                task["restart_recovery"] = False
            elif action is not None:
                task["action_result_unknown"] = True
            task["reconciliation"] = {
                "reconciliation_id": f"reconciliation-{task_id}-{recovery['revision']}",
                "status": outcome_for_confirmation,
                "action_outcome": outcome_for_confirmation,
                "action_id": action.get("action_id") if action else None,
                "observation_id": observation["observation_id"],
                "observation_version": observation.get("observation_version"),
                "scene_fingerprint": fingerprint,
                "reason": outcome_reason,
                "receipt": copy.deepcopy(receipt),
            }
            recovery.update(
                {
                    "required": True,
                    "phase": "READY_TO_RESUME" if outcome_for_confirmation in {"EXECUTED", "NOT_EXECUTED"} else "RECONCILIATION_REQUIRED",
                    "reason": outcome_reason,
                    "eligible": outcome_for_confirmation in {"EXECUTED", "NOT_EXECUTED"} and task["state"] != "CANCELLED",
                    "confirmed": False,
                    "observation_id": observation["observation_id"],
                    "observation_version": observation.get("observation_version"),
                    "scene_fingerprint": fingerprint,
                    "action_outcome": outcome_for_confirmation,
                    "resume_token": secrets.token_urlsafe(24) if outcome_for_confirmation in {"EXECUTED", "NOT_EXECUTED"} and task["state"] != "CANCELLED" else None,
                }
            )
            if task["state"] == "CANCELLED":
                recovery["eligible"] = False
                recovery["phase"] = "CANCELLED_RECONCILED" if outcome_for_confirmation != "UNKNOWN" else "CANCELLED_RECONCILIATION_REQUIRED"
                if outcome_for_confirmation != "UNKNOWN":
                    self._active_task_id = None
            self._record(task, "recovery.reconciled", "task", task_id, {})
            self._persist()
            return copy.deepcopy(task)

    @staticmethod
    def _reconciliation_error_reason(exc: Exception) -> str:
        if isinstance(exc, TransportError):
            return "reconciliation_observation_unavailable"
        if isinstance(exc, SchemaValidationError):
            return "reconciliation_observation_invalid"
        return "reconciliation_observation_mismatch"

    def _classify_reconciliation(
        self,
        task_id: str,
        action: dict[str, Any] | None,
        observation: dict[str, Any],
        session_id: str | None,
    ) -> tuple[str, dict[str, Any] | None, str]:
        if action is None:
            if observation["page_state"] == "landing":
                return "NOT_EXECUTED", None, "no_action_was_dispatched"
            return "UNKNOWN", None, "scene_changed_without_action_history"
        try:
            history = self.device_client.action_status(
                task_id,
                action["action_id"],
                session_id=session_id,
            )
        except TransportError:
            if self.status(task_id) and self.status(task_id).get("receipt") is not None:
                return "EXECUTED", self.status(task_id).get("receipt"), "persisted_receipt_proves_execution"
            return "UNKNOWN", None, "action_history_unavailable"
        if history.get("task_id") != task_id or history.get("action_id") != action["action_id"]:
            return "UNKNOWN", None, "action_history_identity_mismatch"
        outcome = history.get("status")
        receipt = history.get("receipt") if isinstance(history.get("receipt"), dict) else None
        if outcome == "EXECUTED":
            return "EXECUTED", receipt, "device_history_confirms_execution"
        if outcome == "NOT_EXECUTED":
            task = self.status(task_id)
            if task:
                persisted_receipt = task.get("receipt")
                if isinstance(persisted_receipt, dict):
                    if history.get("durable_history", False):
                        return "UNKNOWN", None, "device_history_conflicts_with_persisted_receipt"
                    return "EXECUTED", persisted_receipt, "persisted_receipt_proves_execution"
                if history.get("durable_history") is not True:
                    if task.get("action_result_unknown"):
                        return "UNKNOWN", None, "non_durable_action_history_inconclusive"
                    if task.get("restart_recovery"):
                        return "UNKNOWN", None, "restart_without_durable_action_history"
            if observation["page_state"] == action.get("expected_page_state"):
                return "UNKNOWN", None, "expected_scene_without_action_history"
            return "NOT_EXECUTED", None, "device_history_has_no_execution"
        return "UNKNOWN", None, "device_history_is_inconclusive"

    def resume(self, task_id: str, confirmation: dict[str, Any] | None = None) -> dict[str, Any]:
        if confirmation is None:
            confirmation = {}
        explicit = isinstance(confirmation, dict) and confirmation.get("confirmed") is True
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                raise ServiceRequestError(404, "task_not_found", "task does not exist")
            if task["state"] in self.TERMINAL_STATES:
                raise ServiceRequestError(409, "task_terminal", "a terminal task cannot be resumed")
            recovery = task.get("recovery") or {}
            if task["state"] != "PAUSED":
                raise ServiceRequestError(409, "task_not_paused", "task must remain paused until reconciliation")
            if not isinstance(confirmation, dict):
                raise ServiceRequestError(400, "invalid_resume_confirmation", "resume confirmation must be an object")
            if not explicit:
                raise ServiceRequestError(400, "explicit_confirmation_required", "resume requires confirmed: true")
            if not recovery.get("eligible") or recovery.get("action_outcome") not in {"EXECUTED", "NOT_EXECUTED"}:
                raise ServiceRequestError(409, "recovery_not_eligible", "reconciliation did not establish a safe resume point")
            if set(confirmation) != {"confirmed", "resume_token", "observation_version"}:
                raise ServiceRequestError(
                    400,
                    "invalid_resume_confirmation",
                    "resume confirmation must contain only confirmed, resume_token, and observation_version",
                )
            token = confirmation["resume_token"]
            version = confirmation["observation_version"]
            if not isinstance(token, str) or not token or not isinstance(version, str) or not version:
                raise ServiceRequestError(
                    400,
                    "invalid_resume_confirmation",
                    "resume_token and observation_version must be non-empty strings",
                )
            if token != recovery.get("resume_token") or version != recovery.get("observation_version"):
                raise ServiceRequestError(409, "resume_confirmation_mismatch", "resume confirmation is bound to another observation")
            resume_epoch = int(task.get("control_epoch", 0))
            session_id = task.get("session_id")
            expected_fingerprint = recovery.get("scene_fingerprint")

        try:
            current = self.device_client.observe(task_id, session_id=session_id)
            self._validate_observation(current, task_id, phase="resume", session_id=session_id)
        except (TransportError, ServiceRequestError, SchemaValidationError) as exc:
            raise ServiceRequestError(409, "resume_observation_unavailable", self._reconciliation_error_reason(exc)) from exc
        current_fingerprint = scene_fingerprint(current)
        with self._lock:
            task = self._tasks[task_id]
            recovery = task.setdefault("recovery", self._new_recovery())
            if task["state"] != "PAUSED" or int(task.get("control_epoch", 0)) != resume_epoch:
                raise ServiceRequestError(409, "task_controlled", "task changed while resume was being confirmed")
            if current.get("observation_version") != recovery.get("observation_version") or current_fingerprint != expected_fingerprint:
                recovery.update(
                    {
                        "eligible": False,
                        "phase": "RECONCILIATION_REQUIRED",
                        "reason": "observation_changed_before_resume",
                        "resume_token": None,
                        "confirmed": False,
                    }
                )
                self._persist()
                raise ServiceRequestError(409, "recovery_observation_stale", "the confirmed device scene changed; reconcile again")
            task.setdefault("observations", []).append(copy.deepcopy(current))
            self._record(
                task,
                "observation.captured",
                "observation",
                current["observation_id"],
                {"observation_id": current["observation_id"]},
            )
            outcome = recovery.get("action_outcome")
            recovery.update(
                {
                    "required": False,
                    "phase": "RESUMING",
                    "eligible": False,
                    "confirmed": True,
                    "resume_token": None,
                }
            )
            task["connection"] = {**(task.get("connection") or {}), "state": "CONNECTED"}
            task["failure"] = None
            task["control_epoch"] += 1
            self._record(task, "task.resumed", "task", task_id, {})
            if outcome == "EXECUTED":
                action = task.get("action")
                before = next(
                    (
                        candidate
                        for candidate in reversed(task.get("observations") or [])
                        if isinstance(candidate, dict) and isinstance(action, dict) and candidate.get("observation_id") == action.get("observation_id")
                    ),
                    None,
                )
                if before is None:
                    before = (task.get("observations") or [None])[0]
                if not isinstance(action, dict) or not isinstance(before, dict):
                    raise ServiceRequestError(409, "recovery_evidence_missing", "executed action evidence is incomplete")
                verification = self._verify(task_id, action, before, current)
                task["verification"] = verification
                task["action_result_unknown"] = False
                task["in_flight_action_id"] = None
                task["state"] = "SUCCEEDED" if verification["status"] == "SUCCESS" else "FAILED"
                self._record(
                    task,
                    "verification.recorded",
                    "verification",
                    verification["verification_id"],
                    {"action_id": action["action_id"], "verification_id": verification["verification_id"]},
                )
                self._record(
                    task,
                    "task.completed" if verification["status"] == "SUCCESS" else "task.failed",
                    "task",
                    task_id,
                    {"verification_id": verification["verification_id"]},
                )
                self._active_task_id = None
                return copy.deepcopy(task)
            task["resume_generation"] = int(task.get("resume_generation", 0)) + 1
            task["state"] = "RUNNING"
            self._persist()
        payload = copy.deepcopy(task.get("submission") or {})
        self._run_task(task, payload)
        with self._lock:
            if self._active_task_id == task_id and task["state"] != "PAUSED" and not task.get("action_result_unknown", False):
                self._active_task_id = None
            if not task.get("action_result_unknown", False):
                task["in_flight_action_id"] = None
            self._persist()
        return self.status(task_id) or copy.deepcopy(task)

    def _control(self, task_id: str, command: str, reason: str) -> dict[str, Any]:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                raise ServiceRequestError(404, "task_not_found", "task does not exist")
            state = task["state"]
            if command == "pause":
                if state == "RUNNING":
                    task["control_epoch"] += 1
                    task["state"] = "PAUSED"
                    task["control"] = {"command": "pause", "reason": reason, "requested_at": self.clock()}
                    recovery = task.setdefault("recovery", self._new_recovery())
                    recovery.update(
                        {
                            "required": True,
                            "phase": "RECONCILIATION_REQUIRED",
                            "reason": reason,
                            "eligible": False,
                            "confirmed": False,
                            "resume_token": None,
                        }
                    )
                    self._record(task, "task.paused", "task", task_id, {})
                    self._record(task, "recovery.required", "task", task_id, {})
            elif command == "cancel":
                if state not in self.TERMINAL_STATES:
                    task["control_epoch"] += 1
                    task["state"] = "CANCELLED"
                    task["control"] = {"command": "cancel", "reason": reason, "requested_at": self.clock()}
                    if task.get("in_flight_action_id") is not None or task.get("action_result_unknown", False):
                        recovery = task.setdefault("recovery", self._new_recovery())
                        recovery.update(
                            {
                                "required": True,
                                "phase": "CANCELLED_RECONCILIATION_REQUIRED",
                                "reason": reason,
                                "eligible": False,
                                "confirmed": False,
                                "resume_token": None,
                            }
                        )
                    self._record(task, "task.cancelled", "task", task_id, {})
                    if (
                        task.get("in_flight_action_id") is None
                        and not task.get("action_result_unknown", False)
                        and self._active_task_id == task_id
                    ):
                        self._active_task_id = None
            else:
                raise ServiceRequestError(400, "invalid_control", "control command is not supported")
            self._persist()
            return copy.deepcopy(task)

    def status(self, task_id: str) -> dict[str, Any] | None:
        with self._lock:
            task = self._tasks.get(task_id)
            return copy.deepcopy(task) if task is not None else None

    def _record(self, task: dict[str, Any], kind: str, entity_kind: str, entity_id: str, links: dict[str, str]) -> None:
        with self._lock:
            sequence = len(task["trace"])
            event = {
                "schema_version": SCHEMA_VERSION,
                "event_id": f"event-{task['task_id']}-{sequence}",
                "task_id": task["task_id"],
                "sequence": sequence,
                "kind": kind,
                "entity_id": entity_id,
                "entity_kind": entity_kind,
                "previous_event_id": task["trace"][-1]["event_id"] if task["trace"] else None,
                "links": {"task_id": task["task_id"], **links},
                "recorded_at": self.clock(),
            }
            assert_valid(event, "trace_event")
            task["trace"].append(event)
            self._persist()


def create_service_server(service: SimulationService, host: str = "127.0.0.1", port: int = 0) -> ThreadingHTTPServer:
    class ServiceHandler(BaseHTTPRequestHandler):
        server_version = "JevSimService/1"

        def _guard(self) -> None:
            require_auth(self, service.token)
            require_protocol(self, PROTOCOL_VERSION)

        @staticmethod
        def _segments(path: str) -> list[str]:
            return [unquote(part) for part in urlparse(path).path.split("/") if part]

        def do_POST(self) -> None:  # noqa: N802 - stdlib handler API
            try:
                self._guard()
                segments = self._segments(self.path)
                if segments == ["v1", "tasks"]:
                    result = service.submit(read_json(self))
                    send_json(self, 200, result)
                    return
                if len(segments) == 4 and segments[:2] == ["v1", "tasks"] and segments[3] in {"pause", "cancel", "control"}:
                    payload = self._optional_json()
                    command = payload.get("command", segments[3])
                    if segments[3] == "control" and command not in {"pause", "cancel"}:
                        raise RequestRejected(400, "invalid_control", "control command must be pause or cancel")
                    reason = payload.get("reason", "user_" + command)
                    result = service.control(segments[2], command, reason=reason)
                    send_json(self, 200, result)
                    return
                if len(segments) == 4 and segments[:2] == ["v1", "tasks"] and segments[3] == "reconnect":
                    send_json(self, 200, service.reconnect(segments[2]))
                    return
                if len(segments) == 4 and segments[:2] == ["v1", "tasks"] and segments[3] == "reconcile":
                    send_json(self, 200, service.reconcile(segments[2]))
                    return
                if len(segments) == 4 and segments[:2] == ["v1", "tasks"] and segments[3] == "resume":
                    send_json(self, 200, service.resume(segments[2], self._optional_json()))
                    return
                raise RequestRejected(404, "not_found", "task endpoint not found")
            except RequestRejected as exc:
                send_json(self, exc.status, error_payload(exc.code, exc.message))
            except ServiceRequestError as exc:
                send_json(self, exc.status, error_payload(exc.code, exc.message))
            except Exception:
                send_json(self, 500, error_payload("service_internal_error", "task service failed"))

        def _optional_json(self) -> dict[str, Any]:
            if self.headers.get("Content-Length", "") in {"", "0"}:
                return {}
            return read_json(self)

        def do_GET(self) -> None:  # noqa: N802 - stdlib handler API
            try:
                self._guard()
                segments = self._segments(self.path)
                if len(segments) != 3 or segments[:2] != ["v1", "tasks"] or not segments[2]:
                    raise RequestRejected(404, "not_found", "task endpoint not found")
                result = service.status(segments[2])
                if result is None:
                    raise RequestRejected(404, "task_not_found", "task does not exist")
                send_json(self, 200, result)
            except RequestRejected as exc:
                send_json(self, exc.status, error_payload(exc.code, exc.message))
            except Exception:
                send_json(self, 500, error_payload("service_internal_error", "task service failed"))

        def log_message(self, format: str, *args: Any) -> None:
            return

    return ThreadingHTTPServer((host, port), ServiceHandler)
