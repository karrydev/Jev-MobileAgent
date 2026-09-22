"""Small model boundary used by the simulated loop.

The replay adapter deliberately models the provider boundary without making a
network request.  It still receives the same task, prompt and image payload
that a live adapter would receive and returns an attempt-shaped response so
the task trace can account for malformed responses, retries and usage.
"""

from __future__ import annotations

import copy
import http.client
import json
import os
import socket
import time
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

from .schema import SCHEMA_VERSION, SchemaValidationError, assert_valid

Clock = Callable[[], str]


class ModelError(RuntimeError):
    """A model boundary failure with an explicit retry policy."""

    def __init__(
        self,
        code: str,
        message: str,
        *,
        retryable: bool = False,
        status: int = 502,
        usage: Any = None,
    ):
        self.code = code
        self.message = message
        self.retryable = retryable
        self.status = status
        self.usage = copy.deepcopy(usage)
        super().__init__(message)


def build_model_request(
    *,
    task_id: str,
    attempt_id: str,
    prompt: str,
    images: list[Any],
    clock: Clock,
) -> dict[str, Any]:
    request = {
        "schema_version": SCHEMA_VERSION,
        "task_id": task_id,
        "attempt_id": attempt_id,
        "role": "planner",
        "prompt": prompt,
        "images": copy.deepcopy(images),
        "created_at": clock(),
    }
    try:
        assert_valid(request, "model_request")
    except SchemaValidationError as exc:
        raise ModelError("invalid_model_request", str(exc)) from exc
    return request


class ReplayModel:
    """Return a bounded, deterministic sequence of synthetic responses."""

    def __init__(
        self,
        responses: list[dict[str, Any]] | None,
        *,
        max_attempts: int = 3,
        retry_limit: int | None = None,
        delay_ms: int = 0,
    ):
        self.responses = copy.deepcopy(responses) if responses is not None else None
        self.max_attempts = max(1, min(int(max_attempts), 8))
        self.retry_limit = max(0, self.max_attempts - 1 if retry_limit is None else min(int(retry_limit), self.max_attempts - 1))
        self.delay_ms = max(0, min(int(delay_ms), 30_000))
        self._index = 0

    def complete(self, request: dict[str, Any]) -> dict[str, Any]:
        if self.delay_ms:
            time.sleep(self.delay_ms / 1000)
        if self.responses is None:
            response: dict[str, Any] = {
                "kind": "action",
                "action": {
                    "kind": "tap",
                    "target_node_id": "start-button",
                    "expected_page_state": "done",
                },
            }
        elif self._index >= len(self.responses):
            raise ModelError("replay_exhausted", "replay responses are exhausted", retryable=False, status=422)
        else:
            response = copy.deepcopy(self.responses[self._index])
        self._index += 1
        if not isinstance(response, dict):
            return self._malformed(request, response)

        kind = response.get("kind", response.get("type"))
        if kind == "error":
            error = response.get("error")
            if isinstance(error, dict):
                code = str(error.get("code", "model_error"))
                message = str(error.get("message", code))
                retryable = bool(error.get("retryable", response.get("retryable", False)))
            else:
                code = str(response.get("code", "model_error"))
                message = str(response.get("message", code))
                retryable = bool(response.get("retryable", code in {"timeout", "rate_limited", "temporarily_unavailable"}))
            raise ModelError(
                code,
                message,
                retryable=retryable,
                status=int(response.get("status", 502)),
                usage=response.get("usage"),
            )
        if kind == "malformed" or kind is None:
            return self._malformed(request, response)
        if kind != "action" or not isinstance(response.get("action"), dict):
            return self._malformed(request, response)

        normalized = {
            "schema_version": SCHEMA_VERSION,
            "task_id": request["task_id"],
            "attempt_id": request["attempt_id"],
            "kind": "action",
            "action": copy.deepcopy(response["action"]),
            "usage": copy.deepcopy(response.get("usage")) if response.get("usage") is not None else None,
        }
        try:
            assert_valid(normalized, "model_response")
        except SchemaValidationError as exc:
            raise ModelError("invalid_model_response", str(exc), status=422) from exc
        return normalized

    @staticmethod
    def _malformed(request: dict[str, Any], response: Any) -> dict[str, Any]:
        return {
            "schema_version": SCHEMA_VERSION,
            "task_id": request["task_id"],
            "attempt_id": request["attempt_id"],
            "kind": "malformed",
            "raw": copy.deepcopy(response),
            "usage": copy.deepcopy(response.get("usage")) if isinstance(response, dict) else None,
        }


class LiveModel:
    """Small OpenAI-compatible chat-completions adapter.

    The adapter deliberately owns only the wire-format conversion needed by
    the simulated loop.  It does not identify or certify any particular
    provider.  A caller must provide a complete chat-completions endpoint,
    model name, and credential environment variable explicitly.
    """

    MAX_TIMEOUT_SECONDS = 30.0

    def __init__(
        self,
        endpoint: str | None = None,
        model: str | None = None,
        credential_env: str = "JEV_VLM_API_KEY",
        timeout: float = 10.0,
    ):
        self.endpoint = endpoint.strip() if isinstance(endpoint, str) else endpoint
        self.model = model.strip() if isinstance(model, str) else model
        self.credential_env = credential_env
        try:
            configured_timeout = float(timeout)
        except (TypeError, ValueError) as exc:
            raise ModelError("invalid_model_config", "live model timeout must be a number", status=422) from exc
        if configured_timeout <= 0:
            raise ModelError("invalid_model_config", "live model timeout must be greater than zero", status=422)
        self.timeout = min(configured_timeout, self.MAX_TIMEOUT_SECONDS)

    def _require_configuration(self) -> str:
        if not self.endpoint or not self.model:
            raise ModelError(
                "configuration_required",
                "live model mode requires explicit endpoint and model configuration",
                retryable=False,
                status=424,
            )
        parsed = urlparse(self.endpoint)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ModelError(
                "invalid_endpoint",
                "live model endpoint must be a complete http(s) URL",
                retryable=False,
                status=424,
            )
        return self.endpoint

    @staticmethod
    def _image_url(image: Any) -> str:
        if not isinstance(image, dict):
            raise ModelError("invalid_model_request", "each model image must be an object", status=422)
        supplied_url = image.get("url")
        if isinstance(supplied_url, str) and supplied_url:
            return supplied_url
        data = image.get("data")
        if not isinstance(data, str) or not data:
            raise ModelError("invalid_model_request", "each model image requires non-empty data or url", status=422)
        if data.startswith("data:") or data.startswith("http://") or data.startswith("https://"):
            return data
        media_type = image.get("media_type", "image/png")
        if not isinstance(media_type, str) or not media_type:
            raise ModelError("invalid_model_request", "model image media_type must be a non-empty string", status=422)
        return f"data:{media_type};base64,{data}"

    def _wire_payload(self, request: dict[str, Any]) -> dict[str, Any]:
        content: list[dict[str, Any]] = [{"type": "text", "text": request["prompt"]}]
        for image in request["images"]:
            content.append({"type": "image_url", "image_url": {"url": self._image_url(image)}})
        return {
            "model": self.model,
            "messages": [{"role": "user", "content": content}],
        }

    @staticmethod
    def _decode_json(raw: bytes) -> dict[str, Any] | None:
        if not raw:
            return None
        try:
            decoded = json.loads(raw.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return None
        return decoded if isinstance(decoded, dict) else None

    @staticmethod
    def _error_details(payload: dict[str, Any] | None, status: int) -> tuple[str, str, Any]:
        error = payload.get("error") if isinstance(payload, dict) else None
        if isinstance(error, dict):
            code = str(error.get("code") or error.get("type") or f"http_{status}")
            message = str(error.get("message") or code)
        else:
            code = f"http_{status}"
            message = "model endpoint returned an error"
        usage = payload.get("usage") if isinstance(payload, dict) else None
        return code, message, copy.deepcopy(usage)

    @staticmethod
    def _content_as_json(content: Any) -> dict[str, Any] | None:
        if isinstance(content, dict):
            return content
        if isinstance(content, list):
            content = "".join(
                str(part.get("text", "")) for part in content if isinstance(part, dict) and part.get("type") == "text"
            )
        if not isinstance(content, str):
            return None
        text = content.strip()
        if text.startswith("```"):
            lines = text.splitlines()
            if lines and lines[0].strip().startswith("```"):
                lines = lines[1:]
            if lines and lines[-1].strip() == "```":
                lines = lines[:-1]
            text = "\n".join(lines).strip()
        try:
            decoded = json.loads(text)
        except (json.JSONDecodeError, TypeError):
            return None
        return decoded if isinstance(decoded, dict) else None

    def _normalize_response(self, request: dict[str, Any], payload: dict[str, Any]) -> dict[str, Any]:
        usage = copy.deepcopy(payload.get("usage"))
        choices = payload.get("choices")
        if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
            code, message, error_usage = self._error_details(payload, 502)
            if "error" in payload:
                raise ModelError(code, message, status=502, usage=error_usage)
            raise ModelError("invalid_model_response", "model response did not contain choices", status=502, usage=usage)
        choice = choices[0]
        message = choice.get("message")
        content = message.get("content") if isinstance(message, dict) else choice.get("text")
        decoded = self._content_as_json(content)
        if decoded is None:
            raise ModelError(
                "invalid_model_response",
                "model response content was not a JSON action",
                status=422,
                usage=usage,
            )
        if decoded.get("kind") == "action" and isinstance(decoded.get("action"), dict):
            action = decoded["action"]
        elif isinstance(decoded.get("action"), dict):
            action = decoded["action"]
        elif decoded.get("kind") and decoded.get("target_node_id"):
            action = decoded
        else:
            raise ModelError(
                "invalid_model_response",
                "model response JSON did not contain an action",
                status=422,
                usage=usage,
            )
        normalized = {
            "schema_version": SCHEMA_VERSION,
            "task_id": request["task_id"],
            "attempt_id": request["attempt_id"],
            "kind": "action",
            "action": copy.deepcopy(action),
            "usage": usage,
        }
        try:
            assert_valid(normalized, "model_response")
        except SchemaValidationError as exc:
            raise ModelError("invalid_model_response", str(exc), status=422, usage=usage) from exc
        return normalized

    def complete(self, request: dict[str, Any]) -> dict[str, Any]:
        if not os.environ.get(self.credential_env):
            raise ModelError(
                "credentials_required",
                f"live model mode requires {self.credential_env}; no credential was provided",
                retryable=False,
                status=424,
            )
        endpoint = self._require_configuration()
        wire_payload = self._wire_payload(request)
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "Authorization": f"Bearer {os.environ[self.credential_env]}",
        }
        encoded = json.dumps(wire_payload, ensure_ascii=False).encode("utf-8")
        try:
            with urlopen(Request(endpoint, data=encoded, headers=headers, method="POST"), timeout=self.timeout) as response:
                raw = response.read()
        except HTTPError as exc:
            payload = self._decode_json(exc.read())
            code, message, usage = self._error_details(payload, exc.code)
            raise ModelError(
                code,
                message,
                retryable=exc.code == 408 or exc.code == 429 or exc.code >= 500,
                status=exc.code,
                usage=usage,
            ) from exc
        except (URLError, TimeoutError, socket.timeout, OSError, http.client.HTTPException) as exc:
            reason = getattr(exc, "reason", exc)
            timed_out = isinstance(reason, (TimeoutError, socket.timeout)) or isinstance(exc, (TimeoutError, socket.timeout))
            if timed_out:
                raise ModelError("timeout", "model endpoint request timed out", retryable=True, status=504) from exc
            raise ModelError("endpoint_unreachable", "model endpoint request could not be completed", retryable=True, status=502) from exc

        payload = self._decode_json(raw)
        if payload is None:
            raise ModelError("invalid_model_response", "model endpoint returned malformed JSON", status=502)
        return self._normalize_response(request, payload)


def minimal_probe(
    *,
    credential_env: str = "JEV_VLM_API_KEY",
    endpoint: str | None = None,
    model: str | None = None,
    timeout: float = 10.0,
    execute: bool = False,
    clock: Clock,
) -> dict[str, Any]:
    """Describe, or explicitly execute, a local OpenAI-compatible probe.

    Configuration discovery never performs I/O.  A request is sent only when
    ``execute`` is true and all required configuration is present.
    """

    request = build_model_request(
        task_id="probe-task",
        attempt_id="probe-attempt-1",
        prompt="请返回一个结构化 tap action。",
        images=[
            {"image_id": "probe-image-1", "media_type": "image/png", "data": "fixture-image-1"},
            {"image_id": "probe-image-2", "media_type": "image/png", "data": "fixture-image-2"},
        ],
        clock=clock,
    )
    configured = bool(os.environ.get(credential_env))
    result: dict[str, Any] = {
        "mode": "live",
        "ready": False,
        "endpoint": endpoint,
        "model": model,
        "credential_env": credential_env,
        "credentials_present": configured,
        "status": "credentials_required" if not configured else "configuration_required",
        "request": request,
        "network_call": False,
        "executed": False,
    }
    if not configured or not endpoint or not model:
        return result

    try:
        live_model = LiveModel(
            endpoint=endpoint,
            model=model,
            credential_env=credential_env,
            timeout=timeout,
        )
        live_model._require_configuration()
    except ModelError as exc:
        result["status"] = "configuration_required"
        result["error"] = {"code": exc.code, "message": exc.message, "retryable": exc.retryable}
        return result

    result["ready"] = True
    result["status"] = "ready"
    if not execute:
        return result

    result["network_call"] = True
    result["executed"] = True
    try:
        response = live_model.complete(request)
    except ModelError as exc:
        result["ready"] = False
        result["status"] = "request_failed"
        result["error"] = {"code": exc.code, "message": exc.message, "retryable": exc.retryable}
        result["usage"] = copy.deepcopy(exc.usage)
        return result
    result["status"] = "success"
    result["response"] = response
    result["usage"] = copy.deepcopy(response.get("usage"))
    return result
