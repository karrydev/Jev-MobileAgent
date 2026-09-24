"""Small production transport for the extracted VLM roles.

The reference baseline transport writes benchmark evidence and knows about the
upstream runners.  The App loop only needs the OpenAI compatible wire shape,
the same 0..1000 coordinate note, and bounded accounting, so this adapter
keeps those concerns local to the live VLM package.
"""

from __future__ import annotations

import copy
import json
import math
import os
import ssl
from collections.abc import Callable, Mapping, Sequence
from numbers import Real
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

# Keep the exact task17 ``BoundedVlmTransport._build_messages`` note at the
# production boundary.  Importing the probe module here would pull its
# simulation-only device dependency into the App service.
COORDINATE_ADAPTATION_INSTRUCTION = (
    "For coordinate and coordinate2 values, use normalized x/y values from 0 to 1000."
)


class VlmTransportError(RuntimeError):
    """A bounded provider or configuration failure."""

    def __init__(self, code: str, message: str, *, retryable: bool = False, usage: Any = None):
        self.code = code
        self.message = message
        self.retryable = retryable
        self.usage = copy.deepcopy(usage)
        super().__init__(message)


class VlmBudgetError(VlmTransportError):
    """Raised before a model request would exceed the configured bound."""


def _safe_positive(value: Any, *, name: str, maximum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, Real):
        raise VlmTransportError("invalid_model_config", f"{name} must be a finite integer")
    number = float(value)
    if not math.isfinite(number) or number != int(number) or number < 1:
        raise VlmTransportError("invalid_model_config", f"{name} must be a positive integer")
    return min(int(number), maximum)


def _safe_timeout(value: Any, maximum: float = 30.0) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError) as exc:
        raise VlmTransportError("invalid_model_config", "timeout_seconds must be a number") from exc
    if not math.isfinite(number) or number <= 0:
        raise VlmTransportError("invalid_model_config", "timeout_seconds must be positive")
    return min(number, maximum)


def _ssl_context() -> ssl.SSLContext:
    # Keep normal certificate and hostname verification.  A caller can select
    # a private CA through the standard SSL_CERT_FILE environment variable.
    cert_file = os.environ.get("SSL_CERT_FILE")
    return ssl.create_default_context(cafile=cert_file) if cert_file else ssl.create_default_context()


def _decode(raw: bytes) -> tuple[Any, str]:
    text = raw.decode("utf-8", errors="replace")
    try:
        return json.loads(text), text
    except json.JSONDecodeError:
        return None, text


def _content(payload: Any) -> str | None:
    if not isinstance(payload, Mapping):
        return None
    choices = payload.get("choices")
    if not isinstance(choices, list) or not choices or not isinstance(choices[0], Mapping):
        return None
    choice = choices[0]
    message = choice.get("message")
    value = message.get("content") if isinstance(message, Mapping) else choice.get("text")
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "".join(
            str(part.get("text", ""))
            for part in value
            if isinstance(part, Mapping) and part.get("type") == "text"
        )
    return None


def _redact(value: Any, secret: str) -> tuple[Any, bool]:
    """Copy provider data without allowing a BYOK value to escape telemetry."""

    if isinstance(value, str):
        if not secret or secret not in value:
            return value, False
        return value.replace(secret, "[REDACTED]"), True
    if isinstance(value, Mapping):
        changed = False
        result: dict[Any, Any] = {}
        for key, child in value.items():
            safe_child, child_changed = _redact(child, secret)
            result[key] = safe_child
            changed = changed or child_changed
        return result, changed
    if isinstance(value, list):
        result = []
        changed = False
        for child in value:
            safe_child, child_changed = _redact(child, secret)
            result.append(safe_child)
            changed = changed or child_changed
        return result, changed
    if isinstance(value, tuple):
        safe, changed = _redact(list(value), secret)
        return tuple(safe), changed
    return value, False


def _image_url(image: Any) -> str:
    if isinstance(image, Mapping):
        supplied = image.get("url")
        if isinstance(supplied, str) and supplied:
            return supplied
        data = image.get("data")
        media_type = image.get("media_type", "image/png")
    else:
        supplied = None
        data = image
        media_type = "image/png"
    if isinstance(supplied, str) and supplied:
        return supplied
    if not isinstance(data, str) or not data:
        raise VlmTransportError("invalid_model_request", "model image data is missing")
    if data.startswith(("data:", "http://", "https://")):
        return data
    return f"data:{media_type};base64,{data}"


def _public_usage(value: Any) -> dict[str, Any] | None:
    """Keep only finite, non-negative numeric usage values for status output."""

    if not isinstance(value, Mapping):
        return None
    result: dict[str, Any] = {}
    for key, child in value.items():
        if isinstance(child, bool):
            result[str(key)] = None
        elif isinstance(child, Real):
            number = float(child)
            result[str(key)] = (
                int(number)
                if math.isfinite(number) and number >= 0 and number == int(number)
                else None
            )
        elif child is None or isinstance(child, str):
            result[str(key)] = child
        else:
            # Provider extensions are not needed by the bounded accounting
            # path and must not introduce arbitrary numeric values.
            result[str(key)] = None
    return result


class ProductionVlmTransport:
    """Role-aware, bounded OpenAI-compatible chat-completions transport."""

    MAX_REQUESTS = 25
    MAX_TOKENS = 1024
    MAX_BUDGET_CNY = 1.0
    INPUT_PRICE_PER_MILLION = 1.5
    OUTPUT_PRICE_PER_MILLION = 4.5
    PRICED_MODEL = "gui-plus-2026-02-26"

    def __init__(
        self,
        *,
        endpoint: str,
        model: str,
        api_key: str,
        provider: str = "",
        max_requests: int = MAX_REQUESTS,
        max_tokens: int = MAX_TOKENS,
        budget_cny: float = MAX_BUDGET_CNY,
        timeout_seconds: float = 30.0,
        request_fn: Callable[[str, str, dict[str, Any], float], tuple[int | None, Any, str, str | None]] | None = None,
    ):
        self.endpoint = endpoint.strip() if isinstance(endpoint, str) else ""
        self.model = model.strip() if isinstance(model, str) else ""
        self.provider = provider.strip() if isinstance(provider, str) else ""
        self.api_key = api_key if isinstance(api_key, str) else ""
        parsed = urlparse(self.endpoint)
        if parsed.scheme != "https" or not parsed.netloc:
            raise VlmTransportError("invalid_endpoint", "VLM endpoint must be an HTTPS URL")
        if not self.model:
            raise VlmTransportError("configuration_required", "VLM model is required")
        if not self.api_key:
            raise VlmTransportError("credentials_required", "VLM API key is required")
        if self.model != self.PRICED_MODEL or (
            self.provider and self.provider.casefold() not in {"gui-plus", "gui_plus", "gui plus"}
        ):
            raise VlmTransportError(
                "model_unpriced",
                "only the reviewed GUI-Plus pricing configuration can run a bounded paid task",
            )
        self.max_requests = _safe_positive(max_requests, name="max_requests", maximum=self.MAX_REQUESTS)
        self.max_tokens = _safe_positive(max_tokens, name="max_tokens", maximum=self.MAX_TOKENS)
        try:
            budget = float(budget_cny)
        except (TypeError, ValueError) as exc:
            raise VlmTransportError("invalid_model_config", "budget_cny must be a number") from exc
        if not math.isfinite(budget) or budget <= 0:
            raise VlmTransportError("invalid_model_config", "budget_cny must be positive")
        self.budget_cny = min(budget, self.MAX_BUDGET_CNY)
        self.timeout_seconds = _safe_timeout(timeout_seconds)
        self.request_fn = request_fn or self._post_json
        self.attempts: list[dict[str, Any]] = []
        self.estimated_spend_cny = 0.0
        self.usage_missing = False
        self._halted_reason: str | None = None

    def _messages(self, prompt: str, images: Sequence[Any] | None) -> tuple[str, list[dict[str, Any]]]:
        prompt_text = str(prompt).rstrip() + "\n\n---\n### Bounded baseline coordinate convention ###\n" + COORDINATE_ADAPTATION_INSTRUCTION
        content: list[dict[str, Any]] = [{"type": "text", "text": prompt_text}]
        for image in images or []:
            content.append({"type": "image_url", "image_url": {"url": _image_url(image)}})
        return prompt_text, [{"role": "user", "content": content}]

    def _reserve(self, role: str, step: int) -> dict[str, Any]:
        if self._halted_reason is not None:
            raise VlmTransportError("usage_gate", "VLM request gate is closed")
        if len(self.attempts) >= self.max_requests:
            raise VlmBudgetError("max_requests", "VLM request limit reached")
        upper_bound = (8192 * self.INPUT_PRICE_PER_MILLION + self.max_tokens * self.OUTPUT_PRICE_PER_MILLION) / 1_000_000
        if self.estimated_spend_cny + upper_bound > self.budget_cny + 1e-12:
            raise VlmBudgetError("budget_reserved", "VLM budget limit reached")
        attempt = {
            "attempt": len(self.attempts) + 1,
            "role": role,
            "step": step,
            "usage": None,
            "usage_present": False,
            "estimated_cost_cny": None,
            "transport_error": None,
            "http_status": None,
        }
        self.attempts.append(attempt)
        return attempt

    @staticmethod
    def _usage_cost(usage: Any) -> float | None:
        if not isinstance(usage, Mapping):
            return None
        prompt = usage.get("prompt_tokens", usage.get("input_tokens"))
        completion = usage.get("completion_tokens", usage.get("output_tokens"))
        if (
            isinstance(prompt, bool)
            or isinstance(completion, bool)
            or not isinstance(prompt, Real)
            or not isinstance(completion, Real)
        ):
            return None
        prompt_value = float(prompt)
        completion_value = float(completion)
        if (
            not math.isfinite(prompt_value)
            or not math.isfinite(completion_value)
            or prompt_value < 0
            or completion_value < 0
            or prompt_value != int(prompt_value)
            or completion_value != int(completion_value)
        ):
            return None
        return (prompt_value * ProductionVlmTransport.INPUT_PRICE_PER_MILLION + completion_value * ProductionVlmTransport.OUTPUT_PRICE_PER_MILLION) / 1_000_000

    @staticmethod
    def _post_json(endpoint: str, credential: str, payload: dict[str, Any], timeout: float) -> tuple[int | None, Any, str, str | None]:
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = Request(
            endpoint,
            data=encoded,
            method="POST",
            headers={
                "Accept": "application/json",
                "Content-Type": "application/json",
                "Authorization": f"Bearer {credential}",
            },
        )
        try:
            with urlopen(request, timeout=timeout, context=_ssl_context()) as response:
                raw = response.read()
                value, text = _decode(raw)
                return response.status, value, text, None
        except HTTPError as exc:
            value, text = _decode(exc.read())
            return exc.code, value, text, None
        except (URLError, TimeoutError, OSError) as exc:
            return None, None, "", type(exc).__name__

    def predict(self, *, role: str, prompt: str, images: Sequence[Any], step: int) -> tuple[str, list[dict[str, Any]], dict[str, Any]]:
        attempt = self._reserve(role, step)
        prompt_text, messages = self._messages(prompt, images)
        payload = {
            "model": self.model,
            "messages": messages,
            "enable_thinking": False,
            "max_tokens": self.max_tokens,
        }
        status, response, response_text, transport_error = self.request_fn(
            self.endpoint, self.api_key, payload, self.timeout_seconds
        )
        safe_response, response_redacted = _redact(response, self.api_key)
        safe_response_text, text_redacted = _redact(response_text, self.api_key)
        del safe_response_text
        attempt["http_status"] = status
        safe_transport_error, transport_error_redacted = _redact(transport_error, self.api_key)
        attempt["transport_error"] = safe_transport_error if not transport_error_redacted else "provider_error_redacted"
        response = safe_response
        raw_usage = response.get("usage") if isinstance(response, Mapping) else None
        usage = _public_usage(raw_usage)
        attempt["usage"] = copy.deepcopy(usage)
        attempt["usage_present"] = isinstance(raw_usage, Mapping)
        cost = self._usage_cost(usage)
        attempt["estimated_cost_cny"] = cost
        if cost is None:
            self.usage_missing = True
            self._halted_reason = "usage_missing_or_invalid"
        else:
            if self.estimated_spend_cny + cost > self.budget_cny + 1e-12:
                self._halted_reason = "budget_exceeded"
                raise VlmBudgetError("budget_exceeded", "VLM budget limit reached", usage=usage)
            self.estimated_spend_cny += cost
        if response_redacted or text_redacted or transport_error_redacted:
            self._halted_reason = "provider_response_redacted"
            raise VlmTransportError("provider_response_redacted", "provider response contained protected credentials", usage=usage)
        if transport_error or status is None or status < 200 or status >= 300:
            raise VlmTransportError("provider_request_failed", "VLM provider request failed", retryable=bool(status and status >= 500), usage=usage)
        if cost is None:
            raise VlmTransportError("usage_missing", "provider usage is missing or invalid", usage=usage)
        choices = response.get("choices") if isinstance(response, Mapping) else None
        finish_reason = None
        if isinstance(choices, list) and choices and isinstance(choices[0], Mapping):
            finish_reason = choices[0].get("finish_reason")
        if finish_reason in {"length", "max_tokens"}:
            self._halted_reason = "response_truncated"
            raise VlmTransportError("response_truncated", "provider response reached the output token limit", usage=usage)
        content = _content(response)
        if not content:
            raise VlmTransportError("provider_response_invalid", "VLM provider returned no text response", usage=usage)
        return content, messages, copy.deepcopy(response) if isinstance(response, Mapping) else {}

    def predict_mm(self, prompt: str, images: Sequence[Any] | None = None) -> tuple[str, list[dict[str, Any]], dict[str, Any]]:
        return self.predict(role="unknown", prompt=prompt, images=list(images or []), step=0)

    def summary(self) -> dict[str, Any]:
        return {
            "requests": len(self.attempts),
            "max_requests": self.max_requests,
            "max_tokens": self.max_tokens,
            "estimated_cost_cny": round(self.estimated_spend_cny, 9),
            "usage_missing": self.usage_missing,
            "halted_reason": self._halted_reason,
            "attempts": [
                {
                    "attempt": item["attempt"],
                    "role": item["role"],
                    "step": item["step"],
                    "http_status": item["http_status"],
                    "usage_present": item["usage_present"],
                    "usage": copy.deepcopy(item["usage"]),
                    "estimated_cost_cny": item["estimated_cost_cny"],
                    "transport_error": item["transport_error"],
                }
                for item in self.attempts
            ],
        }


__all__ = ["ProductionVlmTransport", "VlmBudgetError", "VlmTransportError"]
