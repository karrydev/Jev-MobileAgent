"""Authenticated localhost HTTP receiver for Android accessibility observations."""

from __future__ import annotations

import copy
import http.client
import json
import secrets
import threading
import time
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


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class BridgeRequestError(ValueError):
    def __init__(self, status: int, code: str, message: str):
        self.status = status
        self.code = code
        self.message = message
        super().__init__(message)


class AndroidBridge:
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
        self._lock = threading.Lock()
        self._paired = False
        self._client_name: str | None = None
        self._last_seen_at: str | None = None
        self._latest: dict[str, Any] | None = None
        self._received_at: str | None = None
        self._last_observation_monotonic: float | None = None

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
            self._paired = True
            self._client_name = payload["client_name"]
            self._last_seen_at = self.clock()
            # Pairing starts a new observation session.  Do not expose a tree
            # captured by an earlier session while the app is still waiting
            # for its first fresh observation.
            self._latest = None
            self._received_at = None
            self._last_observation_monotonic = None
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
            self._received_at = received_at
            self._last_seen_at = received_at
            self._last_observation_monotonic = time.monotonic()
            return {
                "schema_version": SCHEMA_VERSION,
                "android_schema_version": ANDROID_SCHEMA_VERSION,
                "device_id": self.device_id,
                "observation_id": observation["observation_id"],
                "observation_version": observation["observation_version"],
                "accepted": True,
                "received_at": received_at,
            }

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
                if self.path == "/v1/android/pair":
                    _send_json(self, 200, bridge.pair(_read_json(self)))
                    return
                if self.path == "/v1/android/observations":
                    _send_json(self, 200, bridge.receive_observation(_read_json(self)))
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
                query = parse_qs(parsed.query)
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
