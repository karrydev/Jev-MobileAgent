"""HTTP helpers shared by the public service and simulated device."""

from __future__ import annotations

import json
import secrets
from http import HTTPStatus
from typing import Any

from .schema import PROTOCOL_VERSION, SCHEMA_VERSION

MAX_BODY_BYTES = 128 * 1024


class RequestRejected(Exception):
    def __init__(self, status: int, code: str, message: str):
        self.status = status
        self.code = code
        self.message = message
        super().__init__(message)


def require_auth(handler: Any, token: str) -> None:
    supplied = handler.headers.get("Authorization", "")
    expected = f"Bearer {token}"
    if not token or not secrets.compare_digest(supplied, expected):
        raise RequestRejected(HTTPStatus.UNAUTHORIZED, "unauthorized", "valid bearer authentication is required")


def require_protocol(handler: Any, expected: int = PROTOCOL_VERSION) -> None:
    supplied = handler.headers.get("X-JEV-Protocol-Version")
    try:
        version = int(supplied) if supplied is not None else None
    except ValueError:
        version = None
    if version != expected:
        raise RequestRejected(
            HTTPStatus.UPGRADE_REQUIRED,
            "unsupported_protocol_version",
            f"X-JEV-Protocol-Version must be {expected}",
        )


def require_device_identity(handler: Any, expected_device_id: str) -> None:
    supplied = handler.headers.get("X-JEV-Device-Id")
    if supplied != expected_device_id:
        raise RequestRejected(HTTPStatus.FORBIDDEN, "device_identity_mismatch", "device identity is not recognized")


def read_json(handler: Any) -> dict[str, Any]:
    content_type = handler.headers.get("Content-Type", "")
    if not content_type.lower().split(";", 1)[0].strip() == "application/json":
        raise RequestRejected(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "application/json is required")
    try:
        content_length = int(handler.headers.get("Content-Length", "-1"))
    except ValueError:
        content_length = -1
    if content_length < 0 or content_length > MAX_BODY_BYTES:
        raise RequestRejected(HTTPStatus.BAD_REQUEST, "invalid_content_length", "request body length is invalid")
    try:
        value = json.loads(handler.rfile.read(content_length).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RequestRejected(HTTPStatus.BAD_REQUEST, "malformed_json", "request body is not valid JSON") from exc
    if not isinstance(value, dict):
        raise RequestRejected(HTTPStatus.BAD_REQUEST, "invalid_json_shape", "request body must be a JSON object")
    return value


def send_json(handler: Any, status: int, payload: dict[str, Any]) -> None:
    body = json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def error_payload(code: str, message: str) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "error": {
            "code": code,
            "message": message,
        },
    }
