"""A bounded Jev contract probe.

The module deliberately has no provider SDK dependency.  It builds one small
Chinese ``choice`` request, sends it only when ``live=True`` is explicit, and
keeps the report to request metadata, usage, timing and classified errors.
Raw responses and credentials are never written to the report.
"""

from __future__ import annotations

import copy
import hashlib
import json
import os
import platform
import re
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


DEFAULT_ENDPOINT = "https://api.typesafe.ai/v1/systemone"
DEFAULT_MODEL = "jev-1.13.0"
DEFAULT_CREDENTIAL_ENV = "JEV_API_KEY"
REPORT_VERSION = "jev-probe/report-v1"
DEFAULT_TIMEOUT_SECONDS = 20.0
MAX_TIMEOUT_SECONDS = 60.0

DEFAULT_STATE = (
    "这是一个中文手机操作选择测试。当前页面有“继续”和“取消”两个按钮，"
    "用户希望继续。请从候选动作中选择下一步。"
)
DEFAULT_QUESTION_ID = "next_action"
DEFAULT_INSTRUCTIONS = "选择应执行的下一步。"
DEFAULT_CANDIDATES = {
    "click_continue": "点击“继续”按钮",
    "click_cancel": "点击“取消”按钮",
}


class ProbeConfigurationError(ValueError):
    """Raised when local probe configuration cannot be used safely."""


@dataclass(frozen=True)
class _Exchange:
    status: int | None
    body: bytes
    elapsed_ms: float
    transport_error: str | None = None


def default_config() -> dict[str, Any]:
    """Return a copy of the one-question, one-choice local example."""

    return {
        "endpoint": DEFAULT_ENDPOINT,
        "model": DEFAULT_MODEL,
        "state": DEFAULT_STATE,
        "questions": {
            DEFAULT_QUESTION_ID: {
                "type": "choice",
                "instructions": DEFAULT_INSTRUCTIONS,
                "criteria": copy.deepcopy(DEFAULT_CANDIDATES),
            }
        },
        "expected_choice": "click_continue",
    }


def load_config(path: str | Path | None = None) -> dict[str, Any]:
    """Load and validate a local JSON config without reading any secret file."""

    if path is None:
        config = default_config()
    else:
        config_path = Path(path)
        try:
            config = json.loads(config_path.read_text(encoding="utf-8"))
        except OSError as exc:
            raise ProbeConfigurationError(f"无法读取配置文件: {config_path}") from exc
        except json.JSONDecodeError as exc:
            raise ProbeConfigurationError(f"配置文件不是有效 JSON: {config_path}") from exc
    if not isinstance(config, dict):
        raise ProbeConfigurationError("配置根节点必须是 JSON object")
    return _validate_config(config)


def _validate_config(config: Mapping[str, Any]) -> dict[str, Any]:
    merged = default_config()
    merged.update(config)

    endpoint = merged.get("endpoint")
    if not isinstance(endpoint, str) or not endpoint:
        raise ProbeConfigurationError("endpoint 必须是非空字符串")
    _validate_endpoint(endpoint)

    model = merged.get("model")
    if not isinstance(model, str) or not model:
        raise ProbeConfigurationError("model 必须是非空字符串")

    state = merged.get("state")
    if not isinstance(state, (str, dict)):
        raise ProbeConfigurationError("state 必须是 string 或 JSON object")

    questions = merged.get("questions")
    if not isinstance(questions, dict) or len(questions) != 1:
        raise ProbeConfigurationError("探针只允许一个 choice question")
    question_id, question = next(iter(questions.items()))
    if not isinstance(question_id, str) or not question_id:
        raise ProbeConfigurationError("question id 必须是非空字符串")
    if not isinstance(question, dict) or question.get("type") != "choice":
        raise ProbeConfigurationError("question.type 必须为 choice")
    instructions = question.get("instructions")
    if not isinstance(instructions, str) or not instructions.strip():
        raise ProbeConfigurationError("choice question 必须有 instructions")
    criteria = question.get("criteria")
    if not isinstance(criteria, dict) or not criteria:
        raise ProbeConfigurationError("choice question.criteria 不能为空")
    for candidate_id, description in criteria.items():
        if not isinstance(candidate_id, str) or not candidate_id:
            raise ProbeConfigurationError("candidate id 必须是非空字符串")
        if not isinstance(description, str) or not description.strip():
            raise ProbeConfigurationError(f"候选 {candidate_id!r} 必须有描述")

    expected_choice = merged.get("expected_choice", "click_continue")
    if not isinstance(expected_choice, str) or expected_choice not in criteria:
        raise ProbeConfigurationError("expected_choice 必须是候选 id")

    credential_env = merged.get("credential_env", DEFAULT_CREDENTIAL_ENV)
    if credential_env != DEFAULT_CREDENTIAL_ENV:
        raise ProbeConfigurationError("凭据环境变量固定为 JEV_API_KEY")

    return {
        "endpoint": endpoint,
        "model": model,
        "state": state,
        "questions": {
            question_id: {
                "type": "choice",
                "instructions": instructions,
                "criteria": dict(criteria),
            }
        },
        "expected_choice": expected_choice,
        "credential_env": DEFAULT_CREDENTIAL_ENV,
    }


def _validate_endpoint(endpoint: str) -> None:
    parsed = urlparse(endpoint)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ProbeConfigurationError("endpoint 必须是 http(s) URL")
    if parsed.username or parsed.password:
        raise ProbeConfigurationError("endpoint 不得包含 URL 内嵌凭据")


def _payload(config: Mapping[str, Any], *, model: str) -> dict[str, Any]:
    return {
        "model": model,
        "state": config["state"],
        "questions": copy.deepcopy(config["questions"]),
    }


def _invalid_probe_payload(config: Mapping[str, Any], *, model: str) -> dict[str, Any]:
    """Make one intentionally invalid request for the opt-in error probe."""

    return {
        "model": model,
        "state": config["state"],
        "questions": {
            "invalid_probe": {
                "type": "unsupported_probe_type",
            }
        },
    }


def _request_summary(payload: Mapping[str, Any]) -> dict[str, Any]:
    questions = payload.get("questions")
    summary: dict[str, Any] = {
        "state_type": type(payload.get("state")).__name__,
        "question_ids": list(questions.keys()) if isinstance(questions, dict) else [],
    }
    candidate_ids: dict[str, list[str]] = {}
    if isinstance(questions, dict):
        for question_id, question in questions.items():
            if isinstance(question, dict) and isinstance(question.get("criteria"), dict):
                candidate_ids[str(question_id)] = [str(item) for item in question["criteria"]]
    summary["candidate_ids"] = candidate_ids
    return summary


def _now_utc() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _elapsed_ms(start: float) -> float:
    return round(max(0.0, (time.monotonic() - start) * 1000), 3)


def _safe_text(value: Any) -> str:
    text = str(value)
    text = re.sub(r"(?i)bearer\s+[^\s,;]+", "Bearer [REDACTED]", text)
    text = re.sub(r"(?i)(api[_ -]?key|authorization|token)\s*[:=]\s*[^\s,;]+", r"\1=[REDACTED]", text)
    return text[:240]


def _body_summary(body: bytes) -> dict[str, Any]:
    summary: dict[str, Any] = {
        "bytes": len(body),
        "sha256": hashlib.sha256(body).hexdigest(),
        "json": False,
    }
    if not body:
        return summary
    try:
        parsed = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        summary["encoding"] = "non_json"
        return summary
    summary["json"] = True
    if isinstance(parsed, dict):
        summary["top_level_keys"] = sorted(str(key) for key in parsed.keys())[:32]
        error_value = parsed.get("error")
        if isinstance(error_value, dict):
            code = error_value.get("code")
            if isinstance(code, (str, int, float)) and not isinstance(code, bool):
                summary["provider_error_code"] = _safe_text(code)
        elif isinstance(error_value, (str, int, float)) and not isinstance(error_value, bool):
            summary["provider_error_code"] = _safe_text(error_value)
    else:
        summary["top_level_type"] = type(parsed).__name__
    return summary


def _read_response(response: Any) -> tuple[int | None, bytes]:
    if hasattr(response, "__enter__"):
        with response as entered:
            return getattr(entered, "status", None), entered.read()
    return getattr(response, "status", None), response.read()


def _post_json(
    endpoint: str,
    payload: Mapping[str, Any],
    api_key: str,
    timeout: float,
    opener: Callable[..., Any] | None = None,
) -> _Exchange:
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    request = Request(
        endpoint,
        data=encoded,
        method="POST",
        headers={
            "Accept": "application/json",
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
    )
    start = time.monotonic()
    open_request = opener or urlopen
    try:
        status, body = _read_response(open_request(request, timeout=timeout))
        return _Exchange(status=status, body=body, elapsed_ms=_elapsed_ms(start))
    except HTTPError as exc:
        try:
            body = exc.read()
        except OSError:
            body = b""
        return _Exchange(status=exc.code, body=body, elapsed_ms=_elapsed_ms(start))
    except (URLError, TimeoutError, OSError) as exc:
        return _Exchange(
            status=None,
            body=b"",
            elapsed_ms=_elapsed_ms(start),
            transport_error=_safe_text(exc),
        )


def _usage(value: Any) -> tuple[dict[str, Any], str | None]:
    if not isinstance(value, dict):
        return {"status": "missing", "input_tokens": None, "output_tokens": None}, "usage_missing"
    values: dict[str, Any] = {}
    for name in ("input_tokens", "output_tokens"):
        token_count = value.get(name)
        if isinstance(token_count, bool) or not isinstance(token_count, int) or token_count < 0:
            return {
                "status": "invalid",
                "input_tokens": value.get("input_tokens"),
                "output_tokens": value.get("output_tokens"),
            }, "usage_invalid"
        values[name] = token_count
    return {"status": "known", **values}, None


def _error(error_class: str, code: str, message: str, *, body: bytes = b"") -> dict[str, Any]:
    result: dict[str, Any] = {
        "class": error_class,
        "code": code,
        "message": _safe_text(message),
    }
    if body:
        result["provider_body"] = _body_summary(body)
    return result


def _record_base(
    *,
    index: int,
    kind: str,
    endpoint: str,
    payload: Mapping[str, Any],
) -> dict[str, Any]:
    return {
        "index": index,
        "kind": kind,
        "method": "POST",
        "endpoint": endpoint,
        "request": _request_summary(payload),
        "sent": False,
        "http_status": None,
        "elapsed_ms": 0.0,
        "usage": {"status": "not_requested", "input_tokens": None, "output_tokens": None},
        "protocol_status": "not_evaluated",
        "semantic_status": "not_evaluated",
        "error": None,
        "retry_scheduled": False,
    }


def _configuration_report(
    *,
    model: str,
    mode: str,
    config: Mapping[str, Any] | None,
    error: str,
) -> dict[str, Any]:
    report = {
        "report_version": REPORT_VERSION,
        "created_at_utc": _now_utc(),
        "mode": mode,
        "environment": {"python": platform.python_version()},
        "config": {
            # A rejected endpoint is untrusted input and must not be reflected
            # into the report, where it could contain URL userinfo credentials.
            "endpoint": None,
            "model": model,
            "credential_env": DEFAULT_CREDENTIAL_ENV,
        },
        "network": {"enabled": mode == "live", "calls": 0, "error_probe_requested": False},
        "requests": [],
        "selection": {
            "question_id": None,
            "expected_choice": None,
            "choice": None,
            "protocol_status": "not_run",
            "semantic_status": "not_run",
        },
        "usage": {"status": "unknown", "reason": "no HTTP request was made"},
        "cost": {
            "status": "unknown",
            "currency": "USD",
            "amount_usd": None,
            "reason": "实际费用来源未在本探针中验证",
        },
        "run": {
            "status": "configuration_error",
            "stop_reason": "configuration_error",
            "real_api_verified": False,
        },
        "integrity": {"raw_response_recorded": False, "api_key_recorded": False},
        "error": _error("configuration", "invalid_configuration", error),
    }
    if config:
        question_id = next(iter(config.get("questions", {})), None)
        report["selection"]["question_id"] = question_id
        report["selection"]["expected_choice"] = config.get("expected_choice")
    return report


def _redact(value: Any, secret: str | None) -> Any:
    if not secret:
        return value
    if isinstance(value, str):
        return value.replace(secret, "[REDACTED]")
    if isinstance(value, dict):
        return {key: _redact(item, secret) for key, item in value.items()}
    if isinstance(value, list):
        return [_redact(item, secret) for item in value]
    return value


def _validate_success_response(
    response: Any,
    payload: Mapping[str, Any],
) -> tuple[dict[str, Any] | None, dict[str, Any], str | None]:
    usage, usage_error = _usage(response.get("usage") if isinstance(response, dict) else None)
    if usage_error:
        return None, usage, usage_error
    if not isinstance(response, dict):
        return None, usage, "response_not_object"
    if not isinstance(response.get("model"), str):
        return None, usage, "model_missing"
    questions = payload.get("questions")
    answers = response.get("answers")
    if not isinstance(questions, dict) or not isinstance(answers, dict):
        return None, usage, "answers_missing"
    question_id = next(iter(questions))
    answer = answers.get(question_id)
    if not isinstance(answer, dict) or answer.get("type") != "choice":
        return None, usage, "choice_answer_invalid"
    choice = answer.get("choice")
    criteria = questions[question_id].get("criteria") if isinstance(questions[question_id], dict) else None
    if not isinstance(choice, str) or not isinstance(criteria, dict) or choice not in criteria:
        return None, usage, "choice_unknown"
    probabilities = answer.get("probabilities")
    if not isinstance(probabilities, dict):
        return None, usage, "probabilities_missing"
    for candidate_id in criteria:
        probability = probabilities.get(candidate_id)
        if isinstance(probability, bool) or not isinstance(probability, (int, float)) or not 0 <= probability <= 1:
            return None, usage, "probabilities_invalid"
    confidence = answer.get("confidence")
    if isinstance(confidence, bool) or not isinstance(confidence, (int, float)) or not 0 <= confidence <= 1:
        return None, usage, "confidence_invalid"
    return {"choice": choice, "confidence": confidence}, usage, None


def _response_json(exchange: _Exchange) -> tuple[Any | None, dict[str, Any] | None]:
    try:
        return json.loads(exchange.body.decode("utf-8")), None
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None, _error(
            "protocol_error",
            "response_not_json",
            f"HTTP {exchange.status} response is not valid JSON",
            body=exchange.body,
        )


def run_probe(
    *,
    config_path: str | Path | None = None,
    endpoint: str | None = None,
    model: str | None = None,
    live: bool = False,
    error_probe: bool = False,
    timeout: float = DEFAULT_TIMEOUT_SECONDS,
    opener: Callable[..., Any] | None = None,
) -> dict[str, Any]:
    """Run a dry-run or one bounded live Jev probe.

    ``opener`` is intentionally injectable so tests can exercise the HTTP
    contract without reaching the network.  Production callers should leave
    it unset.
    """

    source_config = load_config(config_path)
    effective_endpoint = endpoint or source_config["endpoint"]
    effective_model = model or source_config["model"]
    _validate_endpoint(effective_endpoint)
    if not isinstance(effective_model, str) or not effective_model:
        raise ProbeConfigurationError("model 必须是非空字符串")
    if timeout <= 0 or timeout > MAX_TIMEOUT_SECONDS:
        raise ProbeConfigurationError(f"timeout 必须在 0 和 {MAX_TIMEOUT_SECONDS:g} 秒之间")

    mode = "live" if live else "dry-run"
    request_payload = _payload(source_config, model=effective_model)
    invalid_payload = _invalid_probe_payload(source_config, model=effective_model)
    question_id = next(iter(source_config["questions"]))
    report: dict[str, Any] = {
        "report_version": REPORT_VERSION,
        "created_at_utc": _now_utc(),
        "mode": mode,
        "environment": {"python": platform.python_version()},
        "config": {
            "endpoint": effective_endpoint,
            "model": effective_model,
            "credential_env": DEFAULT_CREDENTIAL_ENV,
            "question_id": question_id,
            "candidate_ids": list(source_config["questions"][question_id]["criteria"]),
            "expected_choice": source_config["expected_choice"],
        },
        "network": {
            "enabled": live,
            "calls": 0,
            "error_probe_requested": error_probe,
        },
        "requests": [],
        "selection": {
            "question_id": question_id,
            "expected_choice": source_config["expected_choice"],
            "choice": None,
            "confidence": None,
            "protocol_status": "not_run",
            "semantic_status": "not_run",
        },
        "usage": {"status": "unknown", "input_tokens": None, "output_tokens": None},
        "cost": {
            "status": "unknown",
            "currency": "USD",
            "amount_usd": None,
            "reason": "实际费用来源未在本探针中验证",
        },
        "run": {
            "status": "dry_run" if not live else "not_started",
            "stop_reason": "dry_run" if not live else None,
            "real_api_verified": False,
        },
        "integrity": {
            "raw_response_recorded": False,
            "api_key_recorded": False,
            "retry_scheduled": False,
        },
    }

    if not live:
        report["requests"].append(_record_base(index=1, kind="choice", endpoint=effective_endpoint, payload=request_payload))
        if error_probe:
            report["requests"].append(
                _record_base(index=2, kind="error_probe", endpoint=effective_endpoint, payload=invalid_payload)
            )
        report["run"]["stop_reason"] = "dry_run_no_network"
        return report

    api_key = os.environ.get(DEFAULT_CREDENTIAL_ENV, "")
    if not api_key.strip():
        record = _record_base(index=1, kind="choice", endpoint=effective_endpoint, payload=request_payload)
        record["error"] = _error("configuration", "missing_credential", "JEV_API_KEY 未设置")
        report["requests"].append(record)
        report["run"].update(status="configuration_error", stop_reason="missing_credential")
        report["selection"]["protocol_status"] = "not_run"
        return report

    def send(index: int, kind: str, payload: Mapping[str, Any]) -> tuple[dict[str, Any], _Exchange]:
        exchange = _post_json(effective_endpoint, payload, api_key, timeout, opener=opener)
        record = _record_base(index=index, kind=kind, endpoint=effective_endpoint, payload=payload)
        record.update(
            sent=True,
            http_status=exchange.status,
            elapsed_ms=exchange.elapsed_ms,
        )
        report["network"]["calls"] += 1
        report["requests"].append(record)
        return record, exchange

    record, exchange = send(1, "choice", request_payload)
    if exchange.transport_error:
        record["usage"] = {"status": "unknown", "input_tokens": None, "output_tokens": None, "reason": "transport_error"}
        record["error"] = _error("transport_error", "request_failed", exchange.transport_error)
        report["usage"] = record["usage"]
        report["selection"]["protocol_status"] = "not_evaluated"
        report["run"].update(status="transport_error", stop_reason="transport_error")
        return _redact(report, api_key)

    if exchange.status is None or not 200 <= exchange.status < 300:
        record["usage"] = {"status": "unknown", "input_tokens": None, "output_tokens": None, "reason": "http_error"}
        record["error"] = _error(
            "http_error",
            f"http_{exchange.status or 'unknown'}",
            f"HTTP {exchange.status or 'unknown'} response",
            body=exchange.body,
        )
        report["usage"] = record["usage"]
        report["selection"]["protocol_status"] = "not_evaluated"
        report["run"].update(status="http_error", stop_reason="http_error")
        return _redact(report, api_key)

    decoded, decode_error = _response_json(exchange)
    if decode_error:
        record["usage"] = {"status": "unknown", "input_tokens": None, "output_tokens": None, "reason": "response_not_json"}
        record["error"] = decode_error
        report["usage"] = record["usage"]
        report["selection"]["protocol_status"] = "protocol_error"
        report["run"].update(status="protocol_error", stop_reason="response_not_json")
        return _redact(report, api_key)

    answer, usage, response_error = _validate_success_response(decoded, request_payload)
    record["usage"] = usage
    report["usage"] = usage
    if response_error:
        record["error"] = _error("protocol_error", response_error, f"Jev response schema error: {response_error}")
        report["selection"]["protocol_status"] = "protocol_error"
        report["run"].update(status="protocol_error", stop_reason=response_error)
        return _redact(report, api_key)

    assert answer is not None
    choice = answer["choice"]
    record["protocol_status"] = "valid"
    report["selection"].update(
        choice=choice,
        confidence=answer["confidence"],
        protocol_status="valid",
        semantic_status="expected" if choice == source_config["expected_choice"] else "semantic_error",
    )
    if choice == source_config["expected_choice"]:
        report["run"].update(status="ok", stop_reason="choice_completed", real_api_verified=True)
    else:
        record["semantic_status"] = "semantic_error"
        record["error"] = _error(
            "semantic_error",
            "unexpected_choice",
            f"Jev selected {choice!r}; expected {source_config['expected_choice']!r}",
        )
        report["run"].update(status="semantic_error", stop_reason="unexpected_choice", real_api_verified=True)

    if error_probe:
        error_record, error_exchange = send(2, "error_probe", invalid_payload)
        if error_exchange.transport_error:
            error_record["usage"] = {"status": "unknown", "input_tokens": None, "output_tokens": None, "reason": "transport_error"}
            error_record["error"] = _error("transport_error", "error_probe_failed", error_exchange.transport_error)
            report["run"].update(status="transport_error", stop_reason="error_probe_failed")
        elif error_exchange.status in {400, 422}:
            error_record["protocol_status"] = "invalid_request_rejected"
            error_record["semantic_status"] = "not_applicable"
            error_record["usage"] = {"status": "not_expected", "input_tokens": None, "output_tokens": None}
            error_record["error"] = _error(
                "http_error",
                f"invalid_request_http_{error_exchange.status}",
                f"invalid request was rejected with HTTP {error_exchange.status}",
                body=error_exchange.body,
            )
            report["error_probe"] = {"status": "invalid_request_rejected", "http_status": error_exchange.status}
        elif error_exchange.status == 401:
            error_record["usage"] = {
                "status": "unknown",
                "input_tokens": None,
                "output_tokens": None,
                "reason": "authentication_error",
            }
            error_record["error"] = _error(
                "authentication_error",
                "authentication_failed",
                "error probe was rejected because authentication failed",
                body=error_exchange.body,
            )
            report["error_probe"] = {"status": "authentication_error", "http_status": 401}
            report["run"].update(status="authentication_error", stop_reason="error_probe_authentication_error")
        elif error_exchange.status == 429:
            error_record["usage"] = {
                "status": "unknown",
                "input_tokens": None,
                "output_tokens": None,
                "reason": "rate_limit_error",
            }
            error_record["error"] = _error(
                "rate_limit_error",
                "rate_limited",
                "error probe was rejected because provider rate limits apply",
                body=error_exchange.body,
            )
            report["error_probe"] = {"status": "rate_limit_error", "http_status": 429}
            report["run"].update(status="rate_limit_error", stop_reason="error_probe_rate_limit_error")
        elif error_exchange.status is not None and 200 <= error_exchange.status < 300:
            error_record["protocol_status"] = "invalid_request_accepted"
            error_record["semantic_status"] = "not_applicable"
            error_record["usage"] = {"status": "unknown", "input_tokens": None, "output_tokens": None, "reason": "invalid_request_accepted"}
            error_record["error"] = _error(
                "protocol_error",
                "invalid_request_accepted",
                "provider accepted the intentionally invalid request",
                body=error_exchange.body,
            )
            report["error_probe"] = {"status": "invalid_request_accepted", "http_status": error_exchange.status}
            report["run"].update(status="protocol_error", stop_reason="invalid_request_accepted")
        elif error_exchange.status is not None:
            error_record["usage"] = {
                "status": "unknown",
                "input_tokens": None,
                "output_tokens": None,
                "reason": "http_error",
            }
            error_record["error"] = _error(
                "http_error",
                f"http_{error_exchange.status}",
                f"error probe failed with HTTP {error_exchange.status}",
                body=error_exchange.body,
            )
            report["error_probe"] = {"status": "http_error", "http_status": error_exchange.status}
            report["run"].update(status="http_error", stop_reason="error_probe_http_error")
        else:
            error_record["usage"] = {"status": "unknown", "input_tokens": None, "output_tokens": None, "reason": "error_probe_no_http_status"}
            error_record["error"] = _error("transport_error", "error_probe_no_http_status", "error probe returned no HTTP status")
            report["run"].update(status="transport_error", stop_reason="error_probe_no_http_status")
        if "error_probe" not in report:
            report["error_probe"] = {"status": "error", "http_status": error_exchange.status}

    return _redact(report, api_key)
