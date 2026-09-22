"""HTTP transport used by the service to reach the simulated device."""

from __future__ import annotations

import http.client
import json
import socket
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


class TransportError(RuntimeError):
    DEVICE_REJECTION_CODES = frozenset(
        {
            "invalid_action",
            "device_identity_mismatch",
            "stale_session",
            "action_id_conflict",
            "action_in_flight",
            "action_out_of_order",
            "observation_required",
            "stale_observation",
            "unsupported_action",
            "target_node_not_found",
            "target_node_mismatch",
            "action_not_applicable",
        }
    )

    def __init__(self, status: int, payload: Any):
        self.status = status
        self.payload = payload
        error = payload.get("error") if isinstance(payload, dict) else None
        self.error_code = error.get("code", "transport_error") if isinstance(error, dict) else "transport_error"
        self.error_message = error.get("message", self.error_code) if isinstance(error, dict) else "device transport failed"
        super().__init__(f"device HTTP {status}: {self.error_code}")

    @property
    def is_explicit_device_rejection(self) -> bool:
        """Whether a trusted action endpoint explicitly refused this action.

        Only known action-rejection codes in the device's 4xx range qualify.
        Proxy 5xx responses, malformed bodies, and unknown status/code pairs
        remain transport-uncertain because they do not prove non-execution.
        """

        return self.status in {400, 403, 409, 422} and self.error_code in self.DEVICE_REJECTION_CODES


def request_json(
    url: str,
    *,
    method: str,
    token: str,
    device_id: str,
    protocol_version: int,
    body: dict[str, Any] | None = None,
    session_id: str | None = None,
    timeout: float = 2.0,
) -> dict[str, Any]:
    headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {token}",
        "X-JEV-Device-Id": device_id,
        "X-JEV-Protocol-Version": str(protocol_version),
    }
    data: bytes | None = None
    if body is not None:
        data = json.dumps(body, sort_keys=True).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if session_id is not None:
        headers["X-JEV-Session-Id"] = session_id
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read()
    except urllib.error.HTTPError as exc:
        try:
            payload = json.loads(exc.read().decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError, OSError, http.client.HTTPException):
            payload = {"error": {"code": "invalid_device_response", "message": "device returned non-JSON error"}}
        raise TransportError(exc.code, payload) from exc
    except (urllib.error.URLError, TimeoutError, socket.timeout, OSError, http.client.HTTPException) as exc:
        reason = getattr(exc, "reason", exc)
        timed_out = isinstance(reason, (TimeoutError, socket.timeout)) or isinstance(exc, (TimeoutError, socket.timeout))
        code = "device_timeout" if timed_out else "device_unreachable"
        message = "device request timed out" if timed_out else "device request could not be completed"
        raise TransportError(503, {"error": {"code": code, "message": message}}) from exc

    if not raw:
        raise TransportError(
            502,
            {"error": {"code": "invalid_device_response", "message": "device returned an empty response"}},
        )
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise TransportError(
            502,
            {"error": {"code": "invalid_device_response", "message": "device returned malformed JSON"}},
        ) from exc
    if not isinstance(payload, dict):
        raise TransportError(
            502,
            {"error": {"code": "invalid_device_response", "message": "device response must be a JSON object"}},
        )
    return payload


class DeviceClient:
    def __init__(self, base_url: str, *, token: str, device_id: str, protocol_version: int = 1, timeout: float = 2.0):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.device_id = device_id
        self.protocol_version = protocol_version
        self.timeout = timeout

    def observe(self, task_id: str, *, session_id: str | None = None) -> dict[str, Any]:
        encoded_task_id = urllib.parse.quote(task_id, safe="")
        return request_json(
            f"{self.base_url}/v1/simulated/observations?task_id={encoded_task_id}",
            method="GET",
            token=self.token,
            device_id=self.device_id,
            protocol_version=self.protocol_version,
            session_id=session_id,
            timeout=self.timeout,
        )

    def execute(self, action: dict[str, Any]) -> dict[str, Any]:
        return request_json(
            f"{self.base_url}/v1/simulated/actions",
            method="POST",
            token=self.token,
            device_id=self.device_id,
            protocol_version=self.protocol_version,
            body=action,
            timeout=self.timeout,
        )

    def action_status(
        self,
        task_id: str,
        action_id: str,
        *,
        session_id: str | None = None,
    ) -> dict[str, Any]:
        encoded_task_id = urllib.parse.quote(task_id, safe="")
        encoded_action_id = urllib.parse.quote(action_id, safe="")
        return request_json(
            f"{self.base_url}/v1/simulated/action-status?task_id={encoded_task_id}&action_id={encoded_action_id}",
            method="GET",
            token=self.token,
            device_id=self.device_id,
            protocol_version=self.protocol_version,
            session_id=session_id,
            timeout=self.timeout,
        )
