"""Local HTTP simulated device used by the first task loop."""

from __future__ import annotations

import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable
from urllib.parse import parse_qs, urlparse

from .http_common import (
    RequestRejected,
    error_payload,
    read_json,
    require_auth,
    require_device_identity,
    require_protocol,
    send_json,
)
from .schema import PROTOCOL_VERSION, SCHEMA_VERSION, SchemaValidationError, assert_valid

Clock = Callable[[], str]


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class DeviceRequestError(ValueError):
    def __init__(self, code: str, message: str, status: int = 422):
        self.code = code
        self.message = message
        self.status = status
        super().__init__(message)


class SimulatedDevice:
    """A deterministic two-page device with one executable action.

    ``apply_effect`` changes the known page state after accepting the action.
    ``receipt_without_effect`` deliberately returns the same successful
    execution receipt while leaving the page on ``landing``.  The service must
    therefore use the next observation for independent verification.
    """

    BEHAVIORS = {"apply_effect", "receipt_without_effect"}

    def __init__(
        self,
        *,
        device_id: str,
        token: str,
        behavior: str = "apply_effect",
        clock: Clock = utc_now,
        action_delay: float = 0.0,
    ):
        if not token:
            raise ValueError("device token must be supplied explicitly")
        if behavior not in self.BEHAVIORS:
            raise ValueError(f"unsupported simulation behavior: {behavior}")
        self.device_id = device_id
        self.token = token
        self.behavior = behavior
        self.clock = clock
        self.action_delay = action_delay
        self.page_state = "landing"
        self._lock = threading.Lock()
        self._action_in_flight = False
        self._observation_counts: dict[str, int] = {}
        self._latest_observation_id: dict[str, str] = {}
        self._receipts: dict[tuple[str, str], dict[str, Any]] = {}
        self._actions: dict[tuple[str, str], dict[str, Any]] = {}
        self._session_by_task: dict[str, str] = {}
        self._next_sequence: dict[tuple[str, str | None], int] = {}
        self.action_count = 0

    def bind_session(self, task_id: str, session_id: str) -> None:
        if not isinstance(session_id, str) or not session_id:
            raise DeviceRequestError("invalid_session", "a non-empty session id is required", status=400)
        with self._lock:
            known_session = self._session_by_task.get(task_id)
            if known_session is not None and session_id != known_session:
                raise DeviceRequestError("stale_session", "session belongs to an obsolete device session", status=409)
            if known_session is None:
                self._session_by_task[task_id] = session_id

    def has_bound_session(self, task_id: str) -> bool:
        with self._lock:
            return task_id in self._session_by_task

    def observe(self, task_id: str, *, session_id: str | None = None) -> dict[str, Any]:
        if session_id is not None:
            self.bind_session(task_id, session_id)
        with self._lock:
            known_session = self._session_by_task.get(task_id)
            count = self._observation_counts.get(task_id, 0)
            self._observation_counts[task_id] = count + 1
            suffix = "before" if count == 0 else "after" if count == 1 else f"after-{count}"
            observation = {
                "schema_version": SCHEMA_VERSION,
                "task_id": task_id,
                "observation_id": f"obs-{task_id}-{suffix}",
                "observation_version": f"obs-{task_id}-{suffix}",
                "device_id": self.device_id,
                "captured_at": self.clock(),
                "page_state": self.page_state,
                "nodes": [
                    {
                        "node_id": "start-button",
                        "role": "button",
                        "label": "Start",
                        "enabled": self.page_state == "landing",
                    }
                ],
                "capabilities": ["observe", "tap"],
            }
            if known_session is not None:
                observation["session_id"] = known_session
            self._latest_observation_id[task_id] = observation["observation_id"]
        assert_valid(observation, "observation")
        return observation

    def execute(self, action: dict[str, Any]) -> dict[str, Any]:
        try:
            assert_valid(action, "action")
        except SchemaValidationError as exc:
            raise DeviceRequestError("invalid_action", str(exc)) from exc
        task_id = action["task_id"]
        action_id = action["action_id"]
        session_id = action.get("session_id")
        sequence = action.get("sequence")
        if action.get("device_id") is not None and action["device_id"] != self.device_id:
            raise DeviceRequestError("device_identity_mismatch", "action device identity is not recognized", status=403)
        with self._lock:
            known_session = self._session_by_task.get(task_id)
            if known_session is not None and session_id != known_session:
                raise DeviceRequestError("stale_session", "action belongs to an obsolete device session", status=409)
            prior = self._receipts.get((task_id, action_id))
            if prior is not None:
                original = self._actions[(task_id, action_id)]
                for field in ("task_id", "observation_id", "kind", "target_node_id", "expected_page_state", "session_id", "sequence"):
                    if action.get(field) != original.get(field):
                        raise DeviceRequestError("action_id_conflict", "action_id was reused with different action fields", status=409)
                duplicate = dict(prior)
                duplicate["deduplicated"] = True
                return duplicate
            if self._action_in_flight:
                raise DeviceRequestError("action_in_flight", "the device already has an action in flight", status=409)
            sequence_key = (task_id, session_id)
            expected_sequence = self._next_sequence.get(sequence_key, 1)
            if sequence is not None and sequence != expected_sequence:
                raise DeviceRequestError(
                    "action_out_of_order",
                    f"expected action sequence {expected_sequence}, received {sequence}",
                    status=409,
                )
            self._action_in_flight = True
        try:
            if self.action_delay:
                time.sleep(self.action_delay)
            with self._lock:
                current_observation_count = self._observation_counts.get(task_id, 0)
                if current_observation_count < 1:
                    raise DeviceRequestError("observation_required", "an observation is required before an action")
                expected_observation_id = self._latest_observation_id.get(task_id)
                if action["observation_id"] != expected_observation_id:
                    raise DeviceRequestError("stale_observation", "action is bound to an obsolete observation", status=409)
                if action.get("observation_version") not in (None, expected_observation_id):
                    raise DeviceRequestError("stale_observation", "action observation version is obsolete", status=409)
                if action["kind"] != "tap":
                    raise DeviceRequestError("unsupported_action", "the simulated device does not support this action", status=422)
                if action["target_node_id"] != "start-button":
                    raise DeviceRequestError("target_node_not_found", "the action target is not present", status=409)
                if action.get("target_node_role") not in (None, "button"):
                    raise DeviceRequestError("target_node_mismatch", "the action target role does not match the observation", status=409)
                if action.get("target_node_label") not in (None, "Start"):
                    raise DeviceRequestError("target_node_mismatch", "the action target label does not match the observation", status=409)
                if self.page_state != "landing":
                    raise DeviceRequestError(
                        "action_not_applicable",
                        "the simulated start button is not enabled on the current page",
                        status=409,
                    )
                if session_id is not None:
                    self._session_by_task[task_id] = session_id
                self.action_count += 1
                if self.behavior == "apply_effect":
                    self.page_state = "done"
                receipt = {
                    "schema_version": SCHEMA_VERSION,
                    "task_id": task_id,
                    "receipt_id": f"receipt-{task_id}",
                    "action_id": action["action_id"],
                    "device_id": self.device_id,
                    "accepted": True,
                    "outcome": "EXECUTED",
                    "received_at": self.clock(),
                    "deduplicated": False,
                }
                if session_id is not None:
                    receipt["session_id"] = session_id
                if sequence is not None:
                    receipt["sequence"] = sequence
                self._actions[(task_id, action_id)] = dict(action)
                self._receipts[(task_id, action_id)] = dict(receipt)
                if sequence is not None:
                    self._next_sequence[(task_id, session_id)] = sequence + 1
            assert_valid(receipt, "receipt")
            return receipt
        finally:
            with self._lock:
                self._action_in_flight = False


def create_device_server(device: SimulatedDevice, host: str = "127.0.0.1", port: int = 0) -> ThreadingHTTPServer:
    class DeviceHandler(BaseHTTPRequestHandler):
        server_version = "JevSimDevice/1"

        def _guard(self) -> None:
            require_auth(self, device.token)
            require_protocol(self, PROTOCOL_VERSION)
            require_device_identity(self, device.device_id)

        def do_GET(self) -> None:  # noqa: N802 - stdlib handler API
            try:
                self._guard()
                parsed = urlparse(self.path)
                if parsed.path != "/v1/simulated/observations":
                    raise RequestRejected(404, "not_found", "device endpoint not found")
                query = parse_qs(parsed.query)
                task_ids = query.get("task_id", [])
                if len(task_ids) != 1 or not task_ids[0]:
                    raise RequestRejected(400, "invalid_task_id", "task_id query parameter is required")
                session_id = self.headers.get("X-JEV-Session-Id")
                if session_id:
                    device.bind_session(task_ids[0], session_id)
                elif device.has_bound_session(task_ids[0]):
                    raise DeviceRequestError(
                        "stale_session",
                        "an established task session must be declared for every observation",
                        status=409,
                    )
                send_json(self, 200, device.observe(task_ids[0]))
            except RequestRejected as exc:
                send_json(self, exc.status, error_payload(exc.code, exc.message))
            except DeviceRequestError as exc:
                send_json(self, exc.status, error_payload(exc.code, exc.message))
            except Exception:
                send_json(self, 500, error_payload("device_internal_error", "simulated device failed"))

        def do_POST(self) -> None:  # noqa: N802 - stdlib handler API
            try:
                self._guard()
                if self.path != "/v1/simulated/actions":
                    raise RequestRejected(404, "not_found", "device endpoint not found")
                action = read_json(self)
                receipt = device.execute(action)
                send_json(self, 200, receipt)
            except RequestRejected as exc:
                send_json(self, exc.status, error_payload(exc.code, exc.message))
            except DeviceRequestError as exc:
                send_json(self, exc.status, error_payload(exc.code, exc.message))
            except Exception:
                send_json(self, 500, error_payload("device_internal_error", "simulated device failed"))

        def log_message(self, format: str, *args: Any) -> None:
            return

    return ThreadingHTTPServer((host, port), DeviceHandler)
