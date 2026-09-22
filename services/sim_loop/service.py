"""Public task service and its localhost HTTP entry point."""

from __future__ import annotations

import copy
import threading
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
    """Runs one deterministic task through a device HTTP transport."""

    def __init__(
        self,
        *,
        device_base_url: str,
        token: str,
        device_id: str,
        behavior: str = "apply_effect",
        clock: Clock = utc_now,
        device_timeout: float = 2.0,
    ):
        if not token:
            raise ValueError("service token must be supplied explicitly")
        self.token = token
        self.device_id = device_id
        self.behavior = behavior
        self.clock = clock
        self.device_client = DeviceClient(
            device_base_url,
            token=token,
            device_id=device_id,
            protocol_version=PROTOCOL_VERSION,
            timeout=device_timeout,
        )
        self._lock = threading.Lock()
        self._tasks: dict[str, dict[str, Any]] = {}
        self._active_task_id: str | None = None

    @property
    def active_task_id(self) -> str | None:
        with self._lock:
            return self._active_task_id

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
        with self._lock:
            if self._active_task_id is not None:
                raise ServiceRequestError(409, "task_active", "one task is already active")
            if task_id in self._tasks:
                raise ServiceRequestError(409, "task_exists", "task_id already exists")
            task: dict[str, Any] = {
                "schema_version": SCHEMA_VERSION,
                "task_id": task_id,
                "device_id": self.device_id,
                "mode": payload["mode"],
                "scenario": payload["scenario"],
                "goal": payload["goal"],
                "state": "RUNNING",
                "observations": [],
                "action": None,
                "receipt": None,
                "verification": None,
                "trace": [],
            }
            self._tasks[task_id] = task
            self._active_task_id = task_id
        try:
            self._record(task, "task.submitted", "task", task_id, {})
            before = self.device_client.observe(task_id)
            self._validate_observation(before, task_id, phase="before")
            task["observations"].append(before)
            self._record(task, "observation.captured", "observation", before["observation_id"], {"observation_id": before["observation_id"]})

            self._ensure_action_applicable(before)

            action = {
                "schema_version": SCHEMA_VERSION,
                "task_id": task_id,
                "action_id": f"action-{task_id}",
                "observation_id": before["observation_id"],
                "kind": "tap",
                "target_node_id": "start-button",
                "expected_page_state": "done",
                "source": "simulated-planner",
                "created_at": self.clock(),
            }
            assert_valid(action, "action")
            task["action"] = action
            self._record(task, "action.dispatched", "action", action["action_id"], {"observation_id": before["observation_id"], "action_id": action["action_id"]})

            receipt = self.device_client.execute(action)
            assert_valid(receipt, "receipt")
            self._validate_receipt(receipt, task_id, action)
            task["receipt"] = receipt
            self._record(task, "receipt.received", "receipt", receipt["receipt_id"], {"action_id": action["action_id"], "receipt_id": receipt["receipt_id"]})

            after = self.device_client.observe(task_id)
            self._validate_observation(after, task_id, phase="after", previous=before)
            task["observations"].append(after)
            self._record(task, "observation.captured", "observation", after["observation_id"], {"observation_id": after["observation_id"]})

            verification = self._verify(task_id, action, before, after)
            task["verification"] = verification
            self._record(task, "verification.recorded", "verification", verification["verification_id"], {"action_id": action["action_id"], "verification_id": verification["verification_id"]})
            if verification["status"] == "SUCCESS":
                task["state"] = "SUCCEEDED"
                self._record(task, "task.completed", "task", task_id, {"verification_id": verification["verification_id"]})
            else:
                task["state"] = "FAILED"
                self._record(task, "task.failed", "task", task_id, {"verification_id": verification["verification_id"]})
        except (TransportError, SchemaValidationError, ServiceRequestError) as exc:
            task["state"] = "FAILED"
            if isinstance(exc, TransportError):
                failure = {"code": "device_transport_error", "message": str(exc)}
            elif isinstance(exc, SchemaValidationError):
                failure = {"code": "contract_error", "message": str(exc)}
            else:
                failure = {"code": exc.code, "message": exc.message}
            task["failure"] = failure
            self._record(task, "task.failed", "task", task_id, {})
        finally:
            with self._lock:
                if self._active_task_id == task_id:
                    self._active_task_id = None
        return copy.deepcopy(task)

    def _validate_observation(
        self,
        observation: dict[str, Any],
        task_id: str,
        *,
        phase: str,
        previous: dict[str, Any] | None = None,
    ) -> None:
        assert_valid(observation, "observation")
        if observation["task_id"] != task_id or observation["device_id"] != self.device_id:
            raise ServiceRequestError(
                502,
                "device_response_mismatch",
                f"{phase} observation is not associated with the requested task and device",
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

    def status(self, task_id: str) -> dict[str, Any] | None:
        with self._lock:
            task = self._tasks.get(task_id)
            return copy.deepcopy(task) if task is not None else None

    def _record(self, task: dict[str, Any], kind: str, entity_kind: str, entity_id: str, links: dict[str, str]) -> None:
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


def create_service_server(service: SimulationService, host: str = "127.0.0.1", port: int = 0) -> ThreadingHTTPServer:
    class ServiceHandler(BaseHTTPRequestHandler):
        server_version = "JevSimService/1"

        def _guard(self) -> None:
            require_auth(self, service.token)
            require_protocol(self, PROTOCOL_VERSION)

        def do_POST(self) -> None:  # noqa: N802 - stdlib handler API
            try:
                self._guard()
                if self.path != "/v1/tasks":
                    raise RequestRejected(404, "not_found", "task endpoint not found")
                payload = read_json(self)
                result = service.submit(payload)
                send_json(self, 200, result)
            except RequestRejected as exc:
                send_json(self, exc.status, error_payload(exc.code, exc.message))
            except ServiceRequestError as exc:
                send_json(self, exc.status, error_payload(exc.code, exc.message))
            except Exception:
                send_json(self, 500, error_payload("service_internal_error", "task service failed"))

        def do_GET(self) -> None:  # noqa: N802 - stdlib handler API
            try:
                self._guard()
                prefix = "/v1/tasks/"
                if not self.path.startswith(prefix):
                    raise RequestRejected(404, "not_found", "task endpoint not found")
                task_id = unquote(urlparse(self.path).path[len(prefix) :])
                if not task_id:
                    raise RequestRejected(400, "invalid_task_id", "task id is required")
                result = service.status(task_id)
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
