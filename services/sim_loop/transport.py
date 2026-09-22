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
    def __init__(self, status: int, payload: Any):
        self.status = status
        self.payload = payload
        error = payload.get("error") if isinstance(payload, dict) else None
        code = error.get("code", "transport_error") if isinstance(error, dict) else "transport_error"
        super().__init__(f"device HTTP {status}: {code}")


def request_json(
    url: str,
    *,
    method: str,
    token: str,
    device_id: str,
    protocol_version: int,
    body: dict[str, Any] | None = None,
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

    def observe(self, task_id: str) -> dict[str, Any]:
        encoded_task_id = urllib.parse.quote(task_id, safe="")
        return request_json(
            f"{self.base_url}/v1/simulated/observations?task_id={encoded_task_id}",
            method="GET",
            token=self.token,
            device_id=self.device_id,
            protocol_version=self.protocol_version,
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
