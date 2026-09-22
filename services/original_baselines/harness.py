"""Bounded adapters around the original MobileAgent v3.5 entry points.

This module is intentionally small.  The phone entry is run by calling the
original ``main`` after replacing only its model, ADB safety boundary and CLI
arguments.  The AndroidWorld entry calls the original ``MobileAgentV3_M3A``
through its original ``reset``/``step`` episode runner.  Neither loop is
copied here.

Raw requests and responses can contain screenshots, user instructions and
provider output.  They are written only below the caller supplied local
evidence directory.  The returned report contains hashes and redacted
metadata, so it is safe to use as a public handoff summary.
"""

from __future__ import annotations

import base64
import copy
import datetime as dt
import hashlib
import importlib.util
import io
import inspect
import json
import math
import os
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from numbers import Real
from pathlib import Path
from types import ModuleType, SimpleNamespace
from typing import Any, Callable, Iterable, Mapping, Sequence
from PIL import Image

from services.live_vlm.probe import (
    DEFAULT_CREDENTIAL_ENV,
    DEFAULT_ENDPOINT,
    DEFAULT_MODEL,
    INPUT_TOKEN_BOUND,
    INPUT_PRICE_PER_MILLION,
    OUTPUT_PRICE_PER_MILLION,
    _post_json,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
PHONE_RUN_SOURCE = REPO_ROOT / "Mobile-Agent-v3.5/mobile_use/run_gui_owl_1_5_for_mobile.py"
PHONE_UTILS_SOURCE = REPO_ROOT / "Mobile-Agent-v3.5/mobile_use/utils.py"
ANDROID_AGENT_SOURCE = REPO_ROOT / "Mobile-Agent-v3.5/android_world_v3.5/android_world/agents/mobile_agent_v3.py"
ANDROID_ROLE_SOURCE = REPO_ROOT / "Mobile-Agent-v3.5/android_world_v3.5/android_world/agents/mobile_agent_v3_agent.py"
ANDROID_INFER_SOURCE = REPO_ROOT / "Mobile-Agent-v3.5/android_world_v3.5/android_world/agents/infer_ma3.py"
ANDROID_TASK_SOURCE = REPO_ROOT / "Mobile-Agent-v3.5/android_world_v3.5/android_world/task_evals/single/system.py"
ANDROID_EPISODE_RUNNER_SOURCE = REPO_ROOT / "Mobile-Agent-v3.5/android_world_v3.5/android_world/episode_runner.py"
ANDROID_ENV_LAUNCHER_SOURCE = REPO_ROOT / "Mobile-Agent-v3.5/android_world_v3.5/android_world/env/env_launcher.py"
EXTRACTED_ROLE_SOURCE = REPO_ROOT / "agent_core/vlm/roles.py"
EXTRACTED_ORCHESTRATION_SOURCE = REPO_ROOT / "agent_core/vlm/orchestration.py"
EXTRACTED_REFERENCE_ADAPTER_SOURCE = REPO_ROOT / "services/original_baselines/extracted_androidworld.py"

MAX_STEPS = 5
MAX_REQUESTS_PER_RUN = 25
MAX_TOKENS = 1024
ENTRY_BUDGET_CNY = 1.0
DEFAULT_TIMEOUT_SECONDS = 30.0
DEFAULT_ANDROID_CONSOLE_PORT = 5556
DEFAULT_ANDROID_GRPC_PORT = 8554
DEFAULT_ANDROID_DEVICE_ID = "android-world-pixel6-api33"
DEFAULT_PHONE_PACKAGE = "com.jev.mobileagent"
CONTROLLED_COMPLETED_TEXT = "Controlled action state: completed"
CONTROLLED_READY_TEXT = "Controlled action state: ready"
LIVE_OBSERVATION_MAX_AGE_SECONDS = 120.0


def _bounded_integer(value: Any, *, name: str, maximum: int) -> int:
    """Validate and cap a positive finite integer configuration value."""

    if isinstance(value, bool) or not isinstance(value, Real):
        raise ValueError(f"{name}_must_be_finite_integer")
    numeric = float(value)
    if not math.isfinite(numeric) or numeric != int(numeric):
        raise ValueError(f"{name}_must_be_finite_integer")
    integer = int(numeric)
    if integer < 1:
        raise ValueError(f"{name}_must_be_positive")
    return min(integer, maximum)


def _bounded_float(value: Any, *, name: str, maximum: float) -> float:
    """Validate and cap a positive finite float configuration value."""

    if isinstance(value, bool) or not isinstance(value, Real):
        raise ValueError(f"{name}_must_be_finite")
    numeric = float(value)
    if not math.isfinite(numeric):
        raise ValueError(f"{name}_must_be_finite")
    if numeric <= 0:
        raise ValueError(f"{name}_must_be_positive")
    return min(numeric, maximum)


class BaselineStop(RuntimeError):
    """Stops an upstream loop at a safe bounded boundary."""

    def __init__(self, reason: str):
        self.reason = reason
        super().__init__(reason)


class UnsafePhoneAction(BaselineStop):
    """Raised when the original phone model asks for an out-of-scope action."""


class EvidenceWriter:
    """Persist redacted per-attempt evidence in a caller-controlled folder.

    Raw provider payloads are useful for debugging, but they must never become
    a second credential sink.  Redaction happens at this write boundary so a
    caller cannot accidentally bypass it by writing an error or response
    object directly.
    """

    def __init__(self, directory: str | Path | None = None, *, secrets: Sequence[str] = ()):
        if directory is None:
            directory = tempfile.mkdtemp(prefix="jev-original-baselines-")
        self.directory = Path(directory).expanduser().resolve()
        self.raw_directory = self.directory / "raw"
        self.raw_directory.mkdir(parents=True, exist_ok=True)
        self.secrets = tuple(secret for secret in secrets if isinstance(secret, str) and secret)

    def _safe(self, payload: Any) -> Any:
        return _redact(payload, self.secrets)

    def write_raw(self, attempt_no: int, payload: Mapping[str, Any]) -> str:
        path = self.raw_directory / f"attempt-{attempt_no:03d}.json"
        path.write_text(
            json.dumps(self._safe(payload), ensure_ascii=False, sort_keys=True, indent=2, default=str)
            + "\n",
            encoding="utf-8",
        )
        return str(path)

    def write_report(self, report: Mapping[str, Any]) -> str:
        path = self.directory / "report.json"
        path.write_text(
            json.dumps(self._safe(report), ensure_ascii=False, sort_keys=True, indent=2, default=str)
            + "\n",
            encoding="utf-8",
        )
        return str(path)


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha256_text(value: str) -> str:
    return _sha256_bytes(value.encode("utf-8"))


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _git_sha() -> str | None:
    try:
        completed = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=REPO_ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    return completed.stdout.strip() or None


def _source_hashes(paths: Iterable[Path]) -> dict[str, dict[str, str]]:
    result: dict[str, dict[str, str]] = {}
    for path in paths:
        if not path.is_file():
            continue
        result[str(path.relative_to(REPO_ROOT))] = {"sha256": _sha256_file(path)}
    return result


def _redact(value: Any, secrets: Sequence[str]) -> Any:
    if isinstance(value, dict):
        safe: dict[Any, Any] = {}
        for key, child in value.items():
            if isinstance(key, str) and re.fullmatch(
                r"(?i)(?:api[_-]?key|authorization|proxy-authorization|bearer|access[_-]?token|refresh[_-]?token|id[_-]?token|client[_-]?secret|secret|password|credential|private[_-]?key)",
                key,
            ):
                safe[key] = "<redacted>"
            else:
                safe[key] = _redact(child, secrets)
        return safe
    if isinstance(value, list):
        return [_redact(child, secrets) for child in value]
    if not isinstance(value, str):
        return value
    safe = value
    for secret in secrets:
        if secret:
            safe = safe.replace(secret, "<redacted>")
    safe = re.sub(r"(?i)(bearer\s+)[^\s,;]+", r"\1<redacted>", safe)
    safe = re.sub(
        r"(?i)((?:api[_-]?key|authorization|proxy-authorization|bearer|access[_-]?token|refresh[_-]?token|id[_-]?token|client[_-]?secret|secret|password|credential|private[_-]?key)\s*[:=]\s*)([\"']?)([^\"'\s,;}]+)",
        r"\1\2<redacted>",
        safe,
    )
    # Common provider key prefixes are unsafe even when a caller forgot to
    # pass the credential literal to this writer.
    safe = re.sub(r"(?i)\b(?:sk|key|token)[_-][A-Za-z0-9_-]{8,}\b", "<redacted>", safe)
    return safe


def _tokens(usage: Any, *names: str) -> int | None:
    if not isinstance(usage, Mapping):
        return None
    for name in names:
        value = usage.get(name)
        if isinstance(value, (int, float)) and value >= 0:
            return int(value)
    return None


def usage_cost_cny(usage: Any) -> float | None:
    input_tokens = _tokens(usage, "prompt_tokens", "input_tokens")
    output_tokens = _tokens(usage, "completion_tokens", "output_tokens")
    if input_tokens is None or output_tokens is None:
        return None
    return (input_tokens * INPUT_PRICE_PER_MILLION + output_tokens * OUTPUT_PRICE_PER_MILLION) / 1_000_000


def _content(payload: Any) -> str | None:
    if not isinstance(payload, Mapping):
        return None
    choices = payload.get("choices")
    if not isinstance(choices, list) or not choices or not isinstance(choices[0], Mapping):
        return None
    choice = choices[0]
    message = choice.get("message")
    content = message.get("content") if isinstance(message, Mapping) else choice.get("text")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(
            str(item.get("text", ""))
            for item in content
            if isinstance(item, Mapping) and item.get("type") == "text"
        )
    return None


def _finish_reason(payload: Any) -> str | None:
    if not isinstance(payload, Mapping):
        return None
    choices = payload.get("choices")
    if not isinstance(choices, list) or not choices or not isinstance(choices[0], Mapping):
        return None
    reason = choices[0].get("finish_reason")
    return reason if isinstance(reason, str) else None


def _request_payload(model: str, messages: list[dict[str, Any]], max_tokens: int) -> dict[str, Any]:
    return {
        "model": model,
        "messages": messages,
        "enable_thinking": False,
        "max_tokens": max_tokens,
    }


def _request_hash(payload: Mapping[str, Any]) -> str:
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return _sha256_bytes(encoded)


def _image_data_url(image: Any) -> str:
    if isinstance(image, str):
        if image.startswith("data:"):
            return image
        path = image[7:] if image.startswith("file://") else image
        raw = Path(path).read_bytes()
        media_type = "image/png"
        if Path(path).suffix.lower() in {".jpg", ".jpeg"}:
            media_type = "image/jpeg"
        return f"data:{media_type};base64,{base64.b64encode(raw).decode('ascii')}"
    if isinstance(image, Image.Image):
        output = io.BytesIO()
        image.save(output, format="PNG")
        return f"data:image/png;base64,{base64.b64encode(output.getvalue()).decode('ascii')}"
    # AndroidWorld supplies file names in the real path.  The fallback below
    # keeps injected numpy-like image fixtures useful without importing numpy.
    if hasattr(image, "save"):
        output = io.BytesIO()
        image.save(output, format="PNG")
        return f"data:image/png;base64,{base64.b64encode(output.getvalue()).decode('ascii')}"
    raise TypeError(f"unsupported image value: {type(image).__name__}")


def _normalise_messages(messages: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    converted: list[dict[str, Any]] = []
    for message in messages:
        content: list[dict[str, Any]] = []
        for item in message.get("content", []):
            if not isinstance(item, Mapping):
                continue
            if "text" in item:
                content.append({"type": "text", "text": str(item["text"])})
            elif "image" in item:
                content.append({"type": "image_url", "image_url": {"url": _image_data_url(item["image"])}})
            elif item.get("type") == "image_url":
                image_url = item.get("image_url")
                if isinstance(image_url, Mapping) and isinstance(image_url.get("url"), str):
                    url = image_url["url"]
                    if url.startswith("file://"):
                        url = _image_data_url(url)
                    content.append({"type": "image_url", "image_url": {"url": url}})
            elif item.get("type") == "text":
                content.append({"type": "text", "text": str(item.get("text", ""))})
        converted.append({"role": str(message.get("role", "user")), "content": content})
    return converted


@dataclass
class BudgetLedger:
    """Hard request and cost bounds shared by both original entries."""

    max_requests: int = MAX_REQUESTS_PER_RUN
    max_tokens: int = MAX_TOKENS
    budget_cny: float = ENTRY_BUDGET_CNY
    input_token_bound: int = INPUT_TOKEN_BOUND
    attempts: list[dict[str, Any]] = field(default_factory=list)
    reserved_cny: float = 0.0
    estimated_spend_cny: float = 0.0
    usage_missing: bool = False
    stop_reason: str | None = None

    def __post_init__(self) -> None:
        # Keep the direct transport seam bounded as well as the two runners.
        # This prevents an injected ledger from widening the real request
        # limits behind the runner's configuration clamp.
        self.max_requests = _bounded_integer(
            self.max_requests, name="max_requests", maximum=MAX_REQUESTS_PER_RUN
        )
        self.max_tokens = _bounded_integer(
            self.max_tokens, name="max_tokens", maximum=MAX_TOKENS
        )
        self.budget_cny = _bounded_float(
            self.budget_cny, name="budget_cny", maximum=ENTRY_BUDGET_CNY
        )

    @property
    def per_request_upper_bound_cny(self) -> float:
        return (self.input_token_bound * INPUT_PRICE_PER_MILLION + self.max_tokens * OUTPUT_PRICE_PER_MILLION) / 1_000_000

    def reserve(self) -> int:
        if self.stop_reason:
            raise BaselineStop(self.stop_reason)
        if len(self.attempts) >= self.max_requests:
            self.stop_reason = "max_requests"
            raise BaselineStop(self.stop_reason)
        if self.reserved_cny + self.per_request_upper_bound_cny > self.budget_cny + 1e-12:
            self.stop_reason = "budget_reserved"
            raise BaselineStop(self.stop_reason)
        self.reserved_cny += self.per_request_upper_bound_cny
        self.attempts.append({"attempt": len(self.attempts) + 1, "reserved_cny": self.per_request_upper_bound_cny})
        return len(self.attempts) - 1

    def finish(self, index: int, *, http_status: int | None, response: Any, response_text: str, transport_error: str | None) -> dict[str, Any]:
        usage = response.get("usage") if isinstance(response, Mapping) else None
        cost = usage_cost_cny(usage)
        attempt = self.attempts[index]
        attempt.update({
            "http_status": http_status,
            "usage_present": isinstance(usage, Mapping),
            "usage": copy.deepcopy(usage) if isinstance(usage, Mapping) else None,
            "estimated_cost_cny": cost,
            "transport_error": transport_error,
            "finish_reason": _finish_reason(response),
        })
        if cost is not None:
            self.estimated_spend_cny += cost
        if cost is None:
            self.usage_missing = True
            self.stop_reason = self.stop_reason or "usage_missing"
        return attempt


class BoundedVlmTransport:
    """OpenAI-compatible transport with one request per call and no retries."""

    def __init__(
        self,
        *,
        endpoint: str = DEFAULT_ENDPOINT,
        model: str = DEFAULT_MODEL,
        credential: str | None = None,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        ledger: BudgetLedger | None = None,
        evidence: EvidenceWriter | None = None,
        request_fn: Callable[[str, str, dict[str, Any], float], tuple[int | None, Any, str, str | None]] | None = None,
        coordinate_adaptation: bool = False,
    ):
        self.endpoint = endpoint
        self.model = model
        self.credential = credential
        self.timeout = timeout
        self.ledger = ledger or BudgetLedger()
        self.evidence = evidence or EvidenceWriter()
        if credential and credential not in self.evidence.secrets:
            self.evidence.secrets = (*self.evidence.secrets, credential)
        self.request_fn = request_fn or _post_json
        self.coordinate_adaptation = coordinate_adaptation
        self.prompts: list[dict[str, Any]] = []

    def _build_messages(self, prompt: Any, images: Sequence[Any] | None, messages: Sequence[Mapping[str, Any]] | None) -> tuple[str, list[dict[str, Any]]]:
        if messages is not None:
            normalised = _normalise_messages(messages)
            prompt_text = "\n".join(
                str(item.get("text", ""))
                for message in normalised
                for item in message.get("content", [])
                if item.get("type") == "text"
            )
            return prompt_text, normalised
        if isinstance(prompt, list) and all(isinstance(item, Mapping) for item in prompt):
            normalised = _normalise_messages(prompt)
            prompt_text = "\n".join(
                str(item.get("text", ""))
                for message in normalised
                for item in message.get("content", [])
                if item.get("type") == "text"
            )
            return prompt_text, normalised
        prompt_text = str(prompt)
        if self.coordinate_adaptation:
            prompt_text = (
                prompt_text.rstrip()
                + "\n\n---\n"
                + "### Bounded baseline coordinate convention ###\n"
                + "For coordinate and coordinate2 values, use normalized x/y values from 0 to 1000."
            )
        content: list[dict[str, Any]] = [{"type": "text", "text": prompt_text}]
        for image in images or []:
            content.append({"type": "image_url", "image_url": {"url": _image_data_url(image)}})
        return prompt_text, [{"role": "user", "content": content}]

    def request(
        self,
        prompt: Any,
        images: Sequence[Any] | None = None,
        *,
        messages: Sequence[Mapping[str, Any]] | None = None,
        role: str | None = None,
        step: int | None = None,
    ) -> tuple[str, list[dict[str, Any]], dict[str, Any]]:
        prompt_text, wire_messages = self._build_messages(prompt, images, messages)
        payload = _request_payload(self.model, wire_messages, self.ledger.max_tokens)
        index = self.ledger.reserve()
        attempt = self.ledger.attempts[index]
        attempt.update({
            "role": role,
            "step": step,
            "prompt_sha256": _sha256_text(prompt_text),
            "request_sha256": _request_hash(payload),
        })
        status_code: int | None = None
        response_payload: Any = None
        response_text = ""
        transport_error: str | None = None
        try:
            if not self.credential:
                raise BaselineStop("credentials_required")
            status_code, response_payload, response_text, transport_error = self.request_fn(
                self.endpoint, self.credential, payload, self.timeout
            )
        except BaselineStop:
            # There was no provider attempt when the credential guard failed;
            # preserve the reserved record as an explicit local error.
            attempt.update({
                "http_status": None,
                "usage_present": False,
                "usage": None,
                "estimated_cost_cny": None,
                "transport_error": "credentials_required",
            })
            self.ledger.usage_missing = True
            self.ledger.stop_reason = "credentials_required"
            raw_path = self.evidence.write_raw(
                attempt["attempt"],
                {"request": payload, "response": None, "response_text": "", "error": "credentials_required"},
            )
            attempt["raw_evidence_path"] = raw_path
            raise
        except Exception as exc:  # transport adapter failures are evidence too
            transport_error = type(exc).__name__
        # Treat provider data as untrusted input.  The response and its text
        # representation can both contain echoed authorization material; use
        # the same redacted values for accounting, evidence, parsing, and the
        # upstream loop so a key cannot leak through stdout later.
        safe_response_payload = self.evidence._safe(response_payload)
        safe_response_text = self.evidence._safe(response_text)
        self.ledger.finish(
            index,
            http_status=status_code,
            response=safe_response_payload,
            response_text=safe_response_text,
            transport_error=transport_error,
        )
        raw_path = self.evidence.write_raw(
            attempt["attempt"],
            {
                "request": payload,
                "response": safe_response_payload,
                "response_text": safe_response_text,
                "http_status": status_code,
                "transport_error": transport_error,
            },
        )
        attempt["raw_evidence_path"] = raw_path
        content = _content(safe_response_payload)
        if transport_error or status_code is None or status_code < 200 or status_code >= 300:
            raise BaselineStop("provider_request_failed")
        if content is None:
            raise BaselineStop("provider_response_invalid")
        if _finish_reason(safe_response_payload) in {"length", "max_tokens"}:
            raise BaselineStop("provider_response_truncated")
        self.prompts.append({"role": role, "step": step, "prompt_sha256": _sha256_text(prompt_text)})
        # Missing or incomplete usage deliberately stops the next call.  The
        # current content remains available to the upstream parser only when
        # usage is complete, so cost accounting cannot silently drift.
        if self.ledger.stop_reason == "usage_missing":
            raise BaselineStop("usage_missing")
        return content, wire_messages, safe_response_payload

    def predict_mm(
        self,
        text_prompt: Any,
        images: Sequence[Any] | None = None,
        messages: Sequence[Mapping[str, Any]] | None = None,
    ) -> tuple[str, list[dict[str, Any]], dict[str, Any]]:
        return self.request(text_prompt, images, messages=messages)


class PhoneModelAdapter:
    """Adapter with the exact ``predict_mm(messages)`` shape used upstream."""

    def __init__(self, transport: BoundedVlmTransport):
        self.transport = transport

    def predict_mm(self, messages: Sequence[Mapping[str, Any]]) -> tuple[str, list[dict[str, Any]], dict[str, Any]]:
        return self.transport.request(messages, messages=messages, role="mobile_tool_call")


def _load_module(path: Path, name: str) -> ModuleType:
    if not path.is_file():
        raise FileNotFoundError(path)
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ImportError(f"could not load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def evaluate_controlled_observation(observation: Any) -> dict[str, Any]:
    """Independently judge the controlled-page text after the phone run."""

    if isinstance(observation, Mapping) and isinstance(observation.get("observation"), Mapping):
        observation = observation["observation"]
    nodes = observation.get("nodes") if isinstance(observation, Mapping) else None
    if not isinstance(nodes, list):
        return {"status": "unknown", "success": None, "reason": "observation_nodes_missing"}
    matching = [node for node in nodes if isinstance(node, Mapping) and node.get("text") == CONTROLLED_COMPLETED_TEXT]
    return {
        "status": "success" if matching else "failure",
        "success": bool(matching),
        "reason": "exact_state_text" if matching else "exact_state_text_not_found",
        "matched_nodes": len(matching),
        "expected_text": CONTROLLED_COMPLETED_TEXT,
    }


def _unwrap_observation(value: Any) -> Mapping[str, Any] | None:
    if isinstance(value, Mapping) and isinstance(value.get("observation"), Mapping):
        return value["observation"]
    return value if isinstance(value, Mapping) else None


def _observation_state(observation: Mapping[str, Any], expected_text: str) -> bool:
    nodes = observation.get("nodes")
    return isinstance(nodes, list) and any(
        isinstance(node, Mapping) and node.get("text") == expected_text for node in nodes
    )


def _parse_observation_time(observation: Mapping[str, Any]) -> dt.datetime | None:
    captured_at = observation.get("captured_at")
    if not isinstance(captured_at, str) or not captured_at:
        return None
    try:
        parsed = dt.datetime.fromisoformat(captured_at.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc)


def evaluate_controlled_observations(
    before_value: Any,
    after_value: Any,
    *,
    action_at: dt.datetime | None = None,
    checked_at: dt.datetime | None = None,
    before_checked_at: dt.datetime | None = None,
    after_checked_at: dt.datetime | None = None,
) -> dict[str, Any]:
    """Judge a live before/after pair without trusting model or file state.

    A single observation, a stale file, or a pair from different devices is
    evidence only; none can certify a physical baseline success.
    """

    before = _unwrap_observation(before_value)
    after = _unwrap_observation(after_value)
    if before is None or after is None:
        return {"status": "unknown", "success": None, "reason": "live_observations_missing"}
    before_id = before.get("observation_id")
    after_id = after.get("observation_id")
    before_device = before.get("device_id")
    after_device = after.get("device_id")
    before_time = _parse_observation_time(before)
    after_time = _parse_observation_time(after)
    now = checked_at or dt.datetime.now(dt.timezone.utc)
    before_now = before_checked_at or now
    after_now = after_checked_at or now
    if before_time is None or after_time is None:
        return {"status": "unknown", "success": None, "reason": "observation_captured_at_missing_or_invalid"}
    if not isinstance(before_id, str) or not before_id or not isinstance(after_id, str) or not after_id:
        return {"status": "unknown", "success": None, "reason": "observation_id_missing"}
    if before_id == after_id:
        return {"status": "unknown", "success": None, "reason": "before_after_observation_id_same"}
    if not isinstance(before_device, str) or not before_device or before_device != after_device:
        return {"status": "unknown", "success": None, "reason": "before_after_device_mismatch"}
    if before.get("task_id") != after.get("task_id"):
        return {"status": "unknown", "success": None, "reason": "before_after_task_mismatch"}
    if any(
        observation.get("availability") not in (None, "AVAILABLE")
        for observation in (before, after)
    ):
        return {"status": "unknown", "success": None, "reason": "observation_unavailable"}
    if abs((before_now - before_time).total_seconds()) > LIVE_OBSERVATION_MAX_AGE_SECONDS:
        return {"status": "unknown", "success": None, "reason": "before_observation_stale"}
    if abs((after_now - after_time).total_seconds()) > LIVE_OBSERVATION_MAX_AGE_SECONDS:
        return {"status": "unknown", "success": None, "reason": "after_observation_stale"}
    if after_time <= before_time:
        return {"status": "unknown", "success": None, "reason": "after_observation_not_newer"}
    if action_at is not None and after_time < action_at:
        return {"status": "unknown", "success": None, "reason": "after_observation_before_action"}
    if not _observation_state(before, CONTROLLED_READY_TEXT):
        return {"status": "failure", "success": False, "reason": "initial_ready_state_not_found"}
    if not _observation_state(after, CONTROLLED_COMPLETED_TEXT):
        return {"status": "failure", "success": False, "reason": "exact_state_text_not_found"}
    return {
        "status": "success",
        "success": True,
        "reason": "live_before_after_exact_state",
        "device_id": before_device,
        "before_observation_id": before_id,
        "after_observation_id": after_id,
        "before_captured_at": before_time.isoformat().replace("+00:00", "Z"),
        "after_captured_at": after_time.isoformat().replace("+00:00", "Z"),
        "initial_state": CONTROLLED_READY_TEXT,
        "expected_text": CONTROLLED_COMPLETED_TEXT,
    }


def _call_observation_provider(provider: Callable[..., Any], phase: str) -> Any:
    """Call a coordinator-owned live observer with a phase when supported."""

    try:
        signature = inspect.signature(provider)
    except (TypeError, ValueError):
        signature = None
    if signature is None:
        return provider(phase)
    positional = [
        parameter
        for parameter in signature.parameters.values()
        if parameter.kind in (parameter.POSITIONAL_ONLY, parameter.POSITIONAL_OR_KEYWORD)
    ]
    has_varargs = any(
        parameter.kind == parameter.VAR_POSITIONAL
        for parameter in signature.parameters.values()
    )
    if not has_varargs and not positional and "phase" in signature.parameters:
        return provider(phase=phase)
    if has_varargs or positional:
        return provider(phase)
    return provider()


def _current_package(adb_path: str, serial: str | None) -> str | None:
    command = [adb_path]
    if serial:
        command += ["-s", serial]
    command += ["shell", "dumpsys", "window"]
    try:
        completed = subprocess.run(command, check=False, capture_output=True, text=True, timeout=5)
    except (OSError, subprocess.SubprocessError):
        return None
    text = completed.stdout + "\n" + completed.stderr
    # Some Android 14 window-manager implementations omit focus fields from
    # ``dumpsys window windows``.  The full dump has the authoritative current
    # window; mFocusedApp may still point at the project activity while a
    # system window (for example NotificationShade) is covering it.
    match = re.search(r"(?m)^\s*mCurrentFocus=.*?\bu\d+\s+([A-Za-z0-9_.]+)/", text)
    return match.group(1) if match else None


def _validated_tap_bounds(value: Any) -> tuple[int, int, int, int] | None:
    if not isinstance(value, (tuple, list)) or len(value) != 4:
        return None
    if any(isinstance(item, bool) or not isinstance(item, Real) for item in value):
        return None
    numbers = tuple(int(item) for item in value)
    if any(float(item) != number for item, number in zip(value, numbers)):
        return None
    left, top, right, bottom = numbers
    if left < 0 or top < 0 or left >= right or top >= bottom:
        return None
    return numbers


class GuardedPhoneAdb:
    """Thin safety boundary delegating real operations to upstream ``AdbTools``."""

    def __init__(
        self,
        upstream: Any,
        *,
        adb_path: str,
        serial: str | None,
        allowed_package: str = DEFAULT_PHONE_PACKAGE,
        package_checker: Callable[[], str | None] | None = None,
        allowed_tap_bounds: tuple[int, int, int, int] | None = None,
    ):
        self.upstream = upstream
        self.adb_path = adb_path
        self.device = serial
        self.allowed_package = allowed_package
        self.package_checker = package_checker or (lambda: _current_package(adb_path, serial))
        self.allowed_tap_bounds = allowed_tap_bounds
        self.image_info: tuple[int, int] | None = None
        self.actions: list[dict[str, Any]] = []
        self.click_count = 0
        self.screenshot_paths: list[str] = []
        self.last_project_action_at: dt.datetime | None = None
        if _validated_tap_bounds(allowed_tap_bounds) is None:
            raise UnsafePhoneAction("valid_tap_bounds_required_before_action")

    def ensure_project_app(self) -> None:
        package = self.package_checker()
        if package != self.allowed_package:
            raise UnsafePhoneAction(f"active_package_not_allowed:{package or 'unknown'}")

    def get_screenshot(self, image_path: str, retry_times: int = 3) -> bool:
        self.ensure_project_app()
        # Keep the original screenshot implementation, but do not let its
        # three-attempt recovery silently turn into an unbounded baseline.
        ok = self.upstream.get_screenshot(image_path, retry_times=1)
        if ok:
            self.image_info = getattr(self.upstream, "image_info", None)
            if self.image_info is None:
                self.image_info = Image.open(image_path).size
            self.screenshot_paths.append(str(Path(image_path).resolve()))
        return ok

    def click(self, x: int, y: int) -> None:
        self.ensure_project_app()
        if self.click_count >= 1:
            raise UnsafePhoneAction("more_than_one_project_tap")
        if self.image_info is None or not (0 <= int(x) < self.image_info[0] and 0 <= int(y) < self.image_info[1]):
            raise UnsafePhoneAction("tap_outside_current_screenshot")
        bounds = _validated_tap_bounds(self.allowed_tap_bounds)
        if bounds is None:
            raise UnsafePhoneAction("valid_tap_bounds_required_before_action")
        left, top, right, bottom = bounds
        if right > self.image_info[0] or bottom > self.image_info[1]:
            raise UnsafePhoneAction("tap_bounds_outside_current_screenshot")
        if not (left <= int(x) <= right and top <= int(y) <= bottom):
            raise UnsafePhoneAction("tap_outside_allowed_controlled_button")
        self.click_count += 1
        self.last_project_action_at = dt.datetime.now(dt.timezone.utc)
        self.actions.append({"kind": "click", "x": int(x), "y": int(y), "package": self.allowed_package})
        self.upstream.click(x, y)

    def back(self) -> None:
        self.actions.append({"kind": "system_back"})
        self.upstream.back()

    def home(self) -> None:
        self.actions.append({"kind": "system_home"})
        self.upstream.home()

    def __getattr__(self, name: str) -> Any:
        if name in {"long_press", "slide", "type", "open_app", "get_package_name"}:
            raise UnsafePhoneAction(f"action_not_allowed:{name}")
        raise AttributeError(name)


def _phone_report_base(*, config: Mapping[str, Any], evidence: EvidenceWriter) -> dict[str, Any]:
    return {
        "schema_version": "original-baselines/1",
        "entrypoint": "original_mobile_use",
        "baseline_variant": "original_loop_with_bounded_transport_and_phone_safety_guard",
        "git_sha": _git_sha(),
        "config": dict(config),
        "source_files": _source_hashes([PHONE_RUN_SOURCE, PHONE_UTILS_SOURCE]),
        "pricing": {
            "input_cny_per_million": INPUT_PRICE_PER_MILLION,
            "output_cny_per_million": OUTPUT_PRICE_PER_MILLION,
            "input_token_bound": INPUT_TOKEN_BOUND,
        },
        "evidence_directory": str(evidence.directory),
    }


def run_phone_baseline(
    *,
    adb_path: str,
    serial: str | None,
    endpoint: str = DEFAULT_ENDPOINT,
    model: str = DEFAULT_MODEL,
    credential_env: str = DEFAULT_CREDENTIAL_ENV,
    instruction: str = "点击 Toggle controlled state，使状态变为 completed",
    max_steps: int = MAX_STEPS,
    max_requests: int = MAX_REQUESTS_PER_RUN,
    max_tokens: int = MAX_TOKENS,
    budget_cny: float = ENTRY_BUDGET_CNY,
    timeout: float = DEFAULT_TIMEOUT_SECONDS,
    evidence_dir: str | Path | None = None,
    observation_provider: Callable[..., Any] | None = None,
    allowed_package: str = DEFAULT_PHONE_PACKAGE,
    allowed_tap_bounds: tuple[int, int, int, int] | None = None,
    package_checker: Callable[[], str | None] | None = None,
    adb_factory: Callable[..., Any] | None = None,
    request_fn: Callable[[str, str, dict[str, Any], float], tuple[int | None, Any, str, str | None]] | None = None,
) -> dict[str, Any]:
    """Run the original phone loop with strict task16 bounds.

    ``observation_provider`` is a coordinator-owned live callback.  It is
    called once with ``"before"`` and once with ``"after"`` when its signature
    accepts a phase (a no-argument callback is called at both phases for
    compatibility).  A physical success requires the resulting observations
    to be a fresh, same-device pair surrounding a real project tap.
    """

    credential = os.environ.get(credential_env)
    requested_max_steps = max_steps
    requested_max_requests = max_requests
    requested_max_tokens = max_tokens
    requested_budget_cny = budget_cny
    requested_timeout = timeout
    try:
        max_steps = _bounded_integer(max_steps, name="max_steps", maximum=MAX_STEPS)
        max_requests = _bounded_integer(max_requests, name="max_requests", maximum=MAX_REQUESTS_PER_RUN)
        max_tokens = _bounded_integer(max_tokens, name="max_tokens", maximum=MAX_TOKENS)
        budget_cny = _bounded_float(budget_cny, name="budget_cny", maximum=ENTRY_BUDGET_CNY)
        timeout = _bounded_float(timeout, name="timeout", maximum=3600.0)
    except ValueError as exc:
        evidence = EvidenceWriter(evidence_dir, secrets=[credential or ""])
        report = _phone_report_base(
            config={
                "endpoint": endpoint,
                "model": model,
                "credential_env": credential_env,
                "credentials_present": bool(credential),
                "adb_path": adb_path,
                "serial": serial,
                "allowed_package": allowed_package,
                "allowed_tap_bounds": list(allowed_tap_bounds) if allowed_tap_bounds is not None else None,
                "instruction": instruction,
                "max_steps": requested_max_steps,
                "max_requests": requested_max_requests,
                "max_tokens": requested_max_tokens,
                "budget_cny": requested_budget_cny,
                "timeout_seconds": requested_timeout,
            },
            evidence=evidence,
        )
        report.update({"status": "invalid_configuration", "success": None, "reason": str(exc)})
        report["evidence_report"] = evidence.write_report(report)
        return _redact(report, [credential or ""])
    evidence = EvidenceWriter(evidence_dir, secrets=[credential or ""])
    config = {
        "endpoint": endpoint,
        "model": model,
        "credential_env": credential_env,
        "credentials_present": bool(credential),
        "adb_path": adb_path,
        "serial": serial,
        "allowed_package": allowed_package,
        "allowed_tap_bounds": list(allowed_tap_bounds) if allowed_tap_bounds is not None else None,
        "instruction": instruction,
        "max_steps": max_steps,
        "max_requests": max_requests,
        "max_tokens": max_tokens,
        "budget_cny": budget_cny,
        "timeout_seconds": timeout,
        "coordinate_adaptation": "original_phone_prompt_unchanged",
    }
    report = _phone_report_base(config=config, evidence=evidence)
    ledger = BudgetLedger(max_requests=max_requests, max_tokens=max_tokens, budget_cny=budget_cny)
    validated_bounds = _validated_tap_bounds(allowed_tap_bounds)
    if validated_bounds is None:
        report.update({
            "status": "invalid_configuration",
            "success": None,
            "reason": "allowed_tap_bounds_required_from_independent_observation",
        })
        report["budget"] = _budget_report(ledger)
        report["evidence_report"] = evidence.write_report(_redact(report, [credential or ""]))
        return _redact(report, [credential or ""])
    allowed_tap_bounds = validated_bounds
    transport = BoundedVlmTransport(
        endpoint=endpoint,
        model=model,
        credential=credential,
        timeout=timeout,
        ledger=ledger,
        evidence=evidence,
        request_fn=request_fn,
        coordinate_adaptation=False,
    )
    if not credential:
        report.update({"status": "credentials_required", "success": None, "reason": "credentials_required"})
        report["budget"] = _budget_report(ledger)
        report["evidence_report"] = evidence.write_report(_redact(report, [credential or ""]))
        return report

    before_observation: Any = None
    after_observation: Any = None
    before_checked_at: dt.datetime | None = None
    after_checked_at: dt.datetime | None = None
    observer_error: BaseException | None = None
    pair_returned = False
    if observation_provider is not None:
        try:
            before_checked_at = dt.datetime.now(dt.timezone.utc)
            before_observation = _call_observation_provider(observation_provider, "before")
            if (
                isinstance(before_observation, Mapping)
                and isinstance(before_observation.get("before"), Mapping)
                and isinstance(before_observation.get("after"), Mapping)
            ):
                after_observation = before_observation["after"]
                before_observation = before_observation["before"]
                pair_returned = True
        except BaseException as exc:
            observer_error = exc
    if observer_error is not None:
        report.update({
            "status": "invalid_observation",
            "success": None,
            "reason": f"observer_before_error:{type(observer_error).__name__}",
        })
        report["budget"] = _budget_report(ledger)
        report["evidence_report"] = evidence.write_report(_redact(report, [credential]))
        return _redact(report, [credential])

    # The upstream script uses directory-local imports (``packages`` and
    # ``utils``).  Load its actual utility module under that import name and
    # leave the source file itself unchanged.
    mobile_root = str(PHONE_RUN_SOURCE.parent)
    if mobile_root not in sys.path:
        sys.path.insert(0, mobile_root)
    if "qwen_vl_utils" not in sys.modules:
        try:
            import qwen_vl_utils  # type: ignore  # noqa: F401
        except ModuleNotFoundError:
            stub = ModuleType("qwen_vl_utils")
            stub.smart_resize = lambda height, width, **_: (height, width)
            sys.modules["qwen_vl_utils"] = stub
    original_utils = _load_module(PHONE_UTILS_SOURCE, "utils")
    original_run = _load_module(PHONE_RUN_SOURCE, "jev_original_baselines_phone_run")
    base_adb_factory = adb_factory or original_utils.AdbTools
    holder: dict[str, Any] = {}

    def guarded_adb_factory(*args: Any, **kwargs: Any) -> GuardedPhoneAdb:
        upstream = base_adb_factory(*args, **kwargs)
        guard = GuardedPhoneAdb(
            upstream,
            adb_path=kwargs.get("adb_path", args[0] if args else adb_path),
            serial=kwargs.get("device", serial),
            allowed_package=allowed_package,
            package_checker=package_checker,
            allowed_tap_bounds=allowed_tap_bounds,
        )
        holder["adb"] = guard
        guard.ensure_project_app()
        return guard

    class _BoundedPhoneModel(PhoneModelAdapter):
        def __init__(self, _api_key: str, _base_url: str, _model: str, *args: Any, **kwargs: Any):
            del _api_key, _base_url, _model, args, kwargs
            super().__init__(transport)

    args = SimpleNamespace(
        adb_path=adb_path,
        device=serial,
        api_key=credential,
        base_url=endpoint,
        model=model,
        instruction=instruction,
        add_info="",
        max_steps=max_steps,
        app_resolver_api_key=None,
        app_resolver_base_url=None,
        app_resolver_model="qwen-plus",
    )
    work_dir = evidence.directory / "phone-loop"
    work_dir.mkdir(parents=True, exist_ok=True)
    previous_cwd = Path.cwd()
    caught: BaseException | None = None
    try:
        os.chdir(work_dir)
        original_run.parse_args = lambda: args
        original_run.AdbTools = guarded_adb_factory
        original_run.GUIOwlWrapper = _BoundedPhoneModel
        original_parse_action = original_run.parse_action

        def bounded_parse_action(output_text: str) -> dict[str, Any]:
            parsed = original_parse_action(output_text)
            arguments = parsed.get("arguments") if isinstance(parsed, Mapping) else None
            action_type = arguments.get("action") if isinstance(arguments, Mapping) else None
            if action_type == "click":
                return parsed
            if action_type == "system_button" and isinstance(arguments, Mapping) and arguments.get("button") in {"Back", "Home"}:
                return parsed
            if action_type in {"terminate", "answer"}:
                return parsed
            raise UnsafePhoneAction(f"action_not_allowed:{action_type or 'missing'}")

        # Keep the original parser as the first operation, then apply the
        # project-task safety policy before the upstream dispatcher can call
        # wait/open/type/swipe or any unknown action.
        original_run.parse_action = bounded_parse_action
        # Never allow the upstream resolver to create an unbounded second model
        # call.  ``open`` is out of scope for this controlled-page baseline.
        original_run.handle_open_action = lambda *a, **k: (_ for _ in ()).throw(UnsafePhoneAction("open_action_not_allowed"))
        original_run.main()
    except BaseException as exc:  # report the boundary; do not hide a source failure
        caught = exc
    finally:
        os.chdir(previous_cwd)
        if observation_provider is not None and not pair_returned:
            try:
                after_checked_at = dt.datetime.now(dt.timezone.utc)
                after_observation = _call_observation_provider(observation_provider, "after")
            except BaseException as exc:
                observer_error = exc
    guard = holder.get("adb")
    report["device"] = {
        "serial": serial,
        "allowed_package": allowed_package,
        "allowed_tap_bounds": list(allowed_tap_bounds) if allowed_tap_bounds is not None else None,
        "actions": getattr(guard, "actions", []),
        "screenshot_paths": getattr(guard, "screenshot_paths", []),
        "click_count": getattr(guard, "click_count", 0),
    }
    report["attempts"] = _public_attempts(ledger, credential)
    report["budget"] = _budget_report(ledger)
    report["model_self_report"] = "not_used_for_success"
    if observation_provider is not None and observer_error is not None:
        report["independent_judge"] = {
            "status": "unknown",
            "success": None,
            "reason": f"observer_after_error:{type(observer_error).__name__}",
        }
    elif observation_provider is not None:
        report["independent_judge"] = evaluate_controlled_observations(
            before_observation,
            after_observation,
            action_at=getattr(guard, "last_project_action_at", None),
            before_checked_at=before_checked_at,
            after_checked_at=after_checked_at,
        )
        if getattr(guard, "click_count", 0) < 1:
            report["independent_judge"] = {
                "status": "failure",
                "success": False,
                "reason": "project_action_required_before_after_judge",
            }
    else:
        report["independent_judge"] = {"status": "unknown", "success": None, "reason": "observation_provider_required"}
    if caught is not None:
        report["error"] = {"type": type(caught).__name__, "message": str(caught)}
    # A policy stop (for example an out-of-scope action after a valid tap)
    # remains a failed run even if a later observer happens to see the target
    # text.  Independent state cannot erase an unauthorized action boundary.
    if caught is not None:
        report["status"] = "stopped"
        report["success"] = False
    elif report["independent_judge"].get("success") is True:
        report["status"] = "success"
        report["success"] = True
    else:
        report["status"] = "completed_without_independent_judge"
        report["success"] = None
    report["evidence_report"] = evidence.write_report(_redact(report, [credential]))
    return _redact(report, [credential])


def _budget_report(ledger: BudgetLedger) -> dict[str, Any]:
    return {
        "max_requests": ledger.max_requests,
        "requests_attempted": len(ledger.attempts),
        "reserved_cny": ledger.reserved_cny,
        "budget_cny": ledger.budget_cny,
        "estimated_spend_cny": ledger.estimated_spend_cny,
        "usage_missing": ledger.usage_missing,
        "stop_reason": ledger.stop_reason,
        "per_request_upper_bound_cny": ledger.per_request_upper_bound_cny,
    }


def _public_attempts(ledger: BudgetLedger, credential: str | None) -> list[dict[str, Any]]:
    return [_redact(attempt, [credential or ""]) for attempt in ledger.attempts]


def _android_report_base(*, config: Mapping[str, Any], evidence: EvidenceWriter) -> dict[str, Any]:
    return {
        "schema_version": "original-baselines/1",
        "entrypoint": "original_androidworld_mobile_agent_v3_m3a",
        "baseline_variant": "original_reset_step_with_bounded_transport_and_coordinate_convention",
        "git_sha": _git_sha(),
        "config": dict(config),
        "source_files": _source_hashes([
            ANDROID_AGENT_SOURCE,
            ANDROID_ROLE_SOURCE,
            ANDROID_INFER_SOURCE,
            ANDROID_TASK_SOURCE,
            ANDROID_EPISODE_RUNNER_SOURCE,
            ANDROID_ENV_LAUNCHER_SOURCE,
        ]),
        "pricing": {
            "input_cny_per_million": INPUT_PRICE_PER_MILLION,
            "output_cny_per_million": OUTPUT_PRICE_PER_MILLION,
            "input_token_bound": INPUT_TOKEN_BOUND,
        },
        "evidence_directory": str(evidence.directory),
    }


def run_androidworld_baseline(
    *,
    adb_path: str,
    console_port: int = DEFAULT_ANDROID_CONSOLE_PORT,
    grpc_port: int = DEFAULT_ANDROID_GRPC_PORT,
    endpoint: str = DEFAULT_ENDPOINT,
    model: str = DEFAULT_MODEL,
    credential_env: str = DEFAULT_CREDENTIAL_ENV,
    max_steps: int = MAX_STEPS,
    max_requests: int = MAX_REQUESTS_PER_RUN,
    max_tokens: int = MAX_TOKENS,
    budget_cny: float = ENTRY_BUDGET_CNY,
    timeout: float = DEFAULT_TIMEOUT_SECONDS,
    evidence_dir: str | Path | None = None,
    request_fn: Callable[[str, str, dict[str, Any], float], tuple[int | None, Any, str, str | None]] | None = None,
    env_factory: Callable[[], Any] | None = None,
    task_factory: Callable[[], Any] | None = None,
    agent_factory: Callable[[Any, Any, str], Any] | None = None,
    episode_runner: Callable[..., Any] | None = None,
    coordinate_adaptation: bool = True,
    freeze_datetime: bool = False,
) -> dict[str, Any]:
    """Run ``SystemBrightnessMax`` with original M3A reset/step semantics."""

    credential = os.environ.get(credential_env)
    requested_max_steps = max_steps
    requested_max_requests = max_requests
    requested_max_tokens = max_tokens
    requested_budget_cny = budget_cny
    requested_timeout = timeout
    try:
        max_steps = _bounded_integer(max_steps, name="max_steps", maximum=MAX_STEPS)
        max_requests = _bounded_integer(max_requests, name="max_requests", maximum=MAX_REQUESTS_PER_RUN)
        max_tokens = _bounded_integer(max_tokens, name="max_tokens", maximum=MAX_TOKENS)
        budget_cny = _bounded_float(budget_cny, name="budget_cny", maximum=ENTRY_BUDGET_CNY)
        timeout = _bounded_float(timeout, name="timeout", maximum=3600.0)
    except ValueError as exc:
        evidence = EvidenceWriter(evidence_dir, secrets=[credential or ""])
        report = _android_report_base(
            config={
                "endpoint": endpoint,
                "model": model,
                "credential_env": credential_env,
                "credentials_present": bool(credential),
                "adb_path": adb_path,
                "console_port": console_port,
                "grpc_port": grpc_port,
                "device_id": DEFAULT_ANDROID_DEVICE_ID,
                "task": "SystemBrightnessMax",
                "goal": "Turn brightness to the max value.",
                "max_steps": requested_max_steps,
                "max_requests": requested_max_requests,
                "max_tokens": requested_max_tokens,
                "budget_cny": requested_budget_cny,
                "timeout_seconds": requested_timeout,
                "coordinate_adaptation": coordinate_adaptation,
                "freeze_datetime": freeze_datetime,
            },
            evidence=evidence,
        )
        report.update({"status": "invalid_configuration", "success": None, "reason": str(exc)})
        report["evidence_report"] = evidence.write_report(report)
        return _redact(report, [credential or ""])
    evidence = EvidenceWriter(evidence_dir, secrets=[credential or ""])
    config = {
        "endpoint": endpoint,
        "model": model,
        "credential_env": credential_env,
        "credentials_present": bool(credential),
        "adb_path": adb_path,
        "console_port": console_port,
        "grpc_port": grpc_port,
        "device_id": DEFAULT_ANDROID_DEVICE_ID,
        "task": "SystemBrightnessMax",
        "goal": "Turn brightness to the max value.",
        "max_steps": max_steps,
        "max_requests": max_requests,
        "max_tokens": max_tokens,
        "budget_cny": budget_cny,
        "timeout_seconds": timeout,
        "coordinate_adaptation": coordinate_adaptation,
        "freeze_datetime": freeze_datetime,
        "execution_mode": "injected_offline"
        if any(value is not None for value in (request_fn, env_factory, task_factory, agent_factory, episode_runner))
        else "real_environment",
    }
    report = _android_report_base(config=config, evidence=evidence)
    injected_offline = config["execution_mode"] == "injected_offline"
    report["execution_mode"] = config["execution_mode"]
    report["baseline_eligible"] = not injected_offline
    ledger = BudgetLedger(max_requests=max_requests, max_tokens=max_tokens, budget_cny=budget_cny)
    if not credential:
        report.update({"status": "credentials_required", "success": None, "reason": "credentials_required", "budget": _budget_report(ledger)})
        report["evidence_report"] = evidence.write_report(report)
        return report
    try:
        if env_factory is None or task_factory is None or agent_factory is None or episode_runner is None:
            android_root = REPO_ROOT / "Mobile-Agent-v3.5/android_world_v3.5"
            if str(android_root) not in sys.path:
                sys.path.insert(0, str(android_root))
            from android_world.agents import mobile_agent_v3
            from android_world.env import env_launcher
            from android_world.task_evals.single import system
            from android_world import episode_runner as original_episode_runner

            env_factory = env_factory or (lambda: env_launcher.load_and_setup_env(
                console_port=console_port,
                emulator_setup=False,
                freeze_datetime=freeze_datetime,
                adb_path=adb_path,
                grpc_port=grpc_port,
            ))
            task_factory = task_factory or (lambda: system.SystemBrightnessMax({"max_or_min": "max"}))
            agent_factory = agent_factory or (lambda env, wrapper, output_path: mobile_agent_v3.MobileAgentV3_M3A(
                env, wrapper, wait_after_action_seconds=0.0, output_path=output_path
            ))
            episode_runner = episode_runner or original_episode_runner.run_episode
        env = env_factory()
        task = task_factory()
        transport = BoundedVlmTransport(
            endpoint=endpoint,
            model=model,
            credential=credential,
            timeout=timeout,
            ledger=ledger,
            evidence=evidence,
            request_fn=request_fn,
            coordinate_adaptation=coordinate_adaptation,
        )
        output_path = str(evidence.directory / "androidworld-traces")
        Path(output_path).mkdir(parents=True, exist_ok=True)
        env.reset(go_home=True)
        task_initialized = bool(getattr(task, "initialized", False))
        if not task_initialized:
            task.initialize_task(env)
            task_initialized = True
        initial_score: float | None
        try:
            initial_score = float(task.is_successful(env))
        except Exception as exc:
            initial_score = None
            report["initial_judge_error"] = {"type": type(exc).__name__, "message": str(exc)}
        report["task"] = {
            "name": type(task).__name__,
            "goal": getattr(task, "goal", "Turn brightness to the max value."),
            "judge": "SystemBrightnessMax.is_successful",
            "initial_score": initial_score,
            "initial_expected_score": 0.0,
            "initial_state": "initialized_low",
            "initial_success": initial_score == 0.0,
        }
        episode = None
        caught: BaseException | None = None
        agent = None
        if initial_score != 0.0:
            caught = BaselineStop("task_initial_state_not_low")
        else:
            # Construct the original agent only after the concrete task
            # initialization and score=0 precondition have been recorded.
            agent = agent_factory(env, transport, output_path)
            try:
                episode = episode_runner(
                    task.goal,
                    agent,
                    max_n_steps=max_steps,
                    start_on_home_screen=False,
                )
            except BaseException as exc:
                caught = exc
        try:
            independent_score = float(task.is_successful(env))
        except Exception as exc:
            independent_score = None
            report["judge_error"] = {"type": type(exc).__name__, "message": str(exc)}
        report["task"].update({
            "final_score": independent_score,
            "system_brightness_max_score": independent_score,
            "independent_score": independent_score,
            "independent_success": independent_score == 1.0,
            "final_expected_score": 1.0,
        })
        report["episode"] = {
            "done": bool(getattr(episode, "done", False)) if episode is not None else False,
            "step_count": len(getattr(episode, "step_data", {}).get("step_number", [])) if episode is not None and isinstance(getattr(episode, "step_data", None), Mapping) else None,
        }
        if caught is not None:
            report["error"] = {"type": type(caught).__name__, "message": str(caught)}
        report["attempts"] = _public_attempts(ledger, credential)
        report["budget"] = _budget_report(ledger)
        report["model_self_report"] = "not_used_for_success"
        success = bool(
            report["episode"]["done"]
            and report["task"]["initial_success"]
            and report["task"]["independent_success"]
        )
        report["success"] = success
        report["status"] = "success" if success else ("stopped" if caught is not None else "completed")
        if injected_offline:
            report["evidence_mode"] = "injected_offline"
            report["baseline_result"] = "offline_injected_only"
        else:
            report["evidence_mode"] = "real_environment"
            report["baseline_result"] = "real_candidate"
        try:
            if hasattr(task, "tear_down"):
                try:
                    task.tear_down(env)
                except Exception as exc:
                    report["teardown_error"] = {"type": type(exc).__name__, "message": str(exc)}
        finally:
            if hasattr(env, "close"):
                try:
                    env.close()
                except Exception as exc:
                    report["close_error"] = {"type": type(exc).__name__, "message": str(exc)}
    except BaseException as exc:
        report.update({
            "status": "environment_error",
            "success": False,
            "error": {"type": type(exc).__name__, "message": str(exc)},
            "attempts": _public_attempts(ledger, credential),
            "budget": _budget_report(ledger),
        })
    report["evidence_report"] = evidence.write_report(_redact(report, [credential]))
    return _redact(report, [credential])


def run_androidworld_extracted_baseline(
    *,
    adb_path: str,
    console_port: int = DEFAULT_ANDROID_CONSOLE_PORT,
    grpc_port: int = DEFAULT_ANDROID_GRPC_PORT,
    endpoint: str = DEFAULT_ENDPOINT,
    model: str = DEFAULT_MODEL,
    credential_env: str = DEFAULT_CREDENTIAL_ENV,
    max_steps: int = MAX_STEPS,
    max_requests: int = MAX_REQUESTS_PER_RUN,
    max_tokens: int = MAX_TOKENS,
    budget_cny: float = ENTRY_BUDGET_CNY,
    timeout: float = DEFAULT_TIMEOUT_SECONDS,
    evidence_dir: str | Path | None = None,
    request_fn: Callable[[str, str, dict[str, Any], float], tuple[int | None, Any, str, str | None]] | None = None,
    env_factory: Callable[[], Any] | None = None,
    task_factory: Callable[[], Any] | None = None,
    episode_runner: Callable[..., Any] | None = None,
    coordinate_adaptation: bool = True,
    freeze_datetime: bool = False,
) -> dict[str, Any]:
    """Run the extracted roles through the same bounded AndroidWorld lifecycle.

    The existing task-16 function remains the original reference entry.  This
    thin wrapper changes only the agent factory and annotates the report with
    the extracted source hashes and execution mode; transport, budget,
    evidence, task initialization, independent judge, and episode runner are
    shared with the original baseline.
    """

    from services.original_baselines.extracted_androidworld import ExtractedAndroidWorldAgent

    credential = os.environ.get(credential_env)
    injected = any(value is not None for value in (request_fn, env_factory, task_factory, episode_runner))

    def extracted_factory(env: Any, transport: Any, output_path: str) -> Any:
        return ExtractedAndroidWorldAgent(
            env,
            transport,
            output_path,
            device_id=DEFAULT_ANDROID_DEVICE_ID,
        )

    report = run_androidworld_baseline(
        adb_path=adb_path,
        console_port=console_port,
        grpc_port=grpc_port,
        endpoint=endpoint,
        model=model,
        credential_env=credential_env,
        max_steps=max_steps,
        max_requests=max_requests,
        max_tokens=max_tokens,
        budget_cny=budget_cny,
        timeout=timeout,
        evidence_dir=evidence_dir,
        request_fn=request_fn,
        env_factory=env_factory,
        task_factory=task_factory,
        agent_factory=extracted_factory,
        episode_runner=episode_runner,
        coordinate_adaptation=coordinate_adaptation,
        freeze_datetime=freeze_datetime,
    )

    # ``run_androidworld_baseline`` treats any injected factory as offline so
    # its original entry cannot accidentally be called a real baseline.  The
    # extracted wrapper injects only the agent factory for a real run, so
    # restore the explicit mode here while retaining the same safety rule for
    # test fixtures that inject an environment or episode runner.
    report["entrypoint"] = "extracted_roles_androidworld_reference"
    report["baseline_variant"] = "extracted_roles_with_contract_observation_adapter"
    report["config"]["role_implementation_mode"] = "extracted_roles_reference_adapter"
    report["config"]["role_sources"] = {
        "roles": str(EXTRACTED_ROLE_SOURCE.relative_to(REPO_ROOT)),
        "orchestration": str(EXTRACTED_ORCHESTRATION_SOURCE.relative_to(REPO_ROOT)),
        "reference_adapter": str(EXTRACTED_REFERENCE_ADAPTER_SOURCE.relative_to(REPO_ROOT)),
    }
    report.setdefault("source_files", {}).update(
        _source_hashes(
            [EXTRACTED_ROLE_SOURCE, EXTRACTED_ORCHESTRATION_SOURCE, EXTRACTED_REFERENCE_ADAPTER_SOURCE]
        )
    )
    real_candidate = not injected and report.get("status") not in {
        "credentials_required",
        "invalid_configuration",
    }
    report["execution_mode"] = "injected_offline" if injected else "real_environment"
    report["baseline_eligible"] = real_candidate
    report["evidence_mode"] = "injected_offline" if injected else "real_environment"
    report["baseline_result"] = (
        "offline_injected_only" if injected else ("real_candidate" if real_candidate else "not_run")
    )
    report["config"]["execution_mode"] = report["execution_mode"]
    report["config"]["source_hash_mode"] = "explicit_extracted_and_original"

    evidence_report = report.get("evidence_report")
    if isinstance(evidence_report, str):
        Path(evidence_report).write_text(
            json.dumps(_redact(report, [credential or ""]), ensure_ascii=False, sort_keys=True, indent=2) + "\n",
            encoding="utf-8",
        )
    return _redact(report, [credential or ""])
