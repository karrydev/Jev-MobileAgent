"""Public task service and its localhost HTTP entry point."""

from __future__ import annotations

import copy
import threading
import time
from datetime import datetime, timezone
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
    ):
        if not token:
            raise ValueError("service token must be supplied explicitly")
        self.token = token
        self.device_id = device_id
        self.behavior = behavior
        self.clock = clock
        self.dispatch_delay = max(0.0, float(dispatch_delay))
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
            }
            self._tasks[task_id] = task
            self._active_task_id = task_id
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
        return self.status(task_id) or copy.deepcopy(task)

    def _run_task(self, task: dict[str, Any], payload: dict[str, Any]) -> None:
        task_id = task["task_id"]
        epoch = 0
        before: dict[str, Any] | None = None
        action: dict[str, Any] | None = None
        try:
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

            self._mark_action_dispatched(task, action, before, epoch)
            try:
                receipt = self.device_client.execute(action)
            except TransportError as exc:
                self._handle_action_transport_error(task, action, before, epoch, exc)
                return
            assert_valid(receipt, "receipt")
            self._validate_receipt(receipt, task_id, action)
            with self._lock:
                task["receipt"] = receipt
                task["in_flight_action_id"] = None
            self._record(
                task,
                "receipt.received",
                "receipt",
                receipt["receipt_id"],
                {"action_id": action["action_id"], "receipt_id": receipt["receipt_id"]},
            )

            if not self._can_continue(task, epoch):
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
            action.setdefault("action_id", f"action-{task['task_id']}")
            action.setdefault("observation_id", before["observation_id"])
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
            "action_id": f"action-{task['task_id']}",
            "observation_id": before["observation_id"],
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
                    self._record(task, "task.paused", "task", task_id, {})
            elif command == "cancel":
                if state not in self.TERMINAL_STATES:
                    task["control_epoch"] += 1
                    task["state"] = "CANCELLED"
                    task["control"] = {"command": "cancel", "reason": reason, "requested_at": self.clock()}
                    self._record(task, "task.cancelled", "task", task_id, {})
                    if (
                        task.get("in_flight_action_id") is None
                        and not task.get("action_result_unknown", False)
                        and self._active_task_id == task_id
                    ):
                        self._active_task_id = None
            else:
                raise ServiceRequestError(400, "invalid_control", "control command is not supported")
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
