"""Bounded live GUI-Plus compatibility probe.

The probe intentionally has one narrow job: send the same model request shape
to one configured model for the original v3.5 phone tool-call prompt and the
four original AndroidWorld roles.  The role prompts and parsers are loaded
from the checked-in upstream files at runtime; they are not copied here.
"""

from __future__ import annotations

import base64
import copy
import hashlib
import importlib
import importlib.util
import json
import os
import re
import ssl
import subprocess
import sys
import tempfile
import types
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

from services.sim_loop.device import SimulatedDevice

from .images import synthetic_probe_images


REPO_ROOT = Path(__file__).resolve().parents[2]
ROLE_SOURCE = REPO_ROOT / "Mobile-Agent-v3.5/android_world_v3.5/android_world/agents/mobile_agent_v3_agent.py"
PHONE_PROMPT_SOURCE = REPO_ROOT / "Mobile-Agent-v3.5/mobile_use/utils.py"
PHONE_PARSER_SOURCE = REPO_ROOT / "Mobile-Agent-v3.5/mobile_use/run_gui_owl_1_5_for_mobile.py"

DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
DEFAULT_MODEL = "gui-plus-2026-02-26"
DEFAULT_CREDENTIAL_ENV = "JEV_VLM_API_KEY"
DEFAULT_MAX_REQUESTS = 5
DEFAULT_MAX_TOKENS = 1024
MAX_REQUESTS = 6
MAX_TOKENS = 1024
MAX_TIMEOUT_SECONDS = 30.0
INPUT_TOKEN_BOUND = 8192
INPUT_PRICE_PER_MILLION = 1.5
OUTPUT_PRICE_PER_MILLION = 4.5
DEFAULT_BUDGET_CNY = 0.2

Clock = Callable[[], str]


@dataclass(frozen=True)
class ProbeRequest:
    name: str
    prompt: str
    messages: list[dict[str, Any]]
    parser: str
    parse_requirements: list[str]
    images: list[dict[str, Any]]
    parse_response: Callable[[str], dict[str, Any]]


class ProbeConfigurationError(ValueError):
    """Raised for invalid local probe settings before any network call."""


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _git_sha() -> str | None:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=REPO_ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    value = result.stdout.strip()
    return value or None


def _load_roles() -> types.ModuleType:
    if not ROLE_SOURCE.is_file():
        raise ProbeConfigurationError(f"upstream role source is missing: {ROLE_SOURCE}")
    android_root = str(ROLE_SOURCE.parents[2])
    if android_root not in sys.path:
        sys.path.insert(0, android_root)
    module_name = "jev_live_vlm_original_roles"
    loaded = sys.modules.get(module_name)
    if loaded is not None:
        return loaded
    spec = importlib.util.spec_from_file_location(module_name, ROLE_SOURCE)
    if spec is None or spec.loader is None:
        raise ProbeConfigurationError("could not load the original role source")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    try:
        spec.loader.exec_module(module)
    except Exception:
        sys.modules.pop(module_name, None)
        raise
    return module


def _load_phone_modules() -> tuple[types.ModuleType, types.ModuleType]:
    """Load the original mobile prompt and parser without importing ADB code."""

    mobile_root = PHONE_PROMPT_SOURCE.parent
    if not mobile_root.is_dir():
        raise ProbeConfigurationError(f"upstream phone source is missing: {mobile_root}")
    # The upstream utility imports qwen_vl_utils even though build_messages does
    # not use it.  A tiny import-only fallback keeps the probe offline and does
    # not replace any prompt or parser behavior.
    if "qwen_vl_utils" not in sys.modules:
        try:
            importlib.import_module("qwen_vl_utils")
        except ModuleNotFoundError:
            stub = types.ModuleType("qwen_vl_utils")
            stub.smart_resize = lambda height, width, **_: (height, width)
            sys.modules["qwen_vl_utils"] = stub
    sys.path.insert(0, str(mobile_root))
    previous_utils = sys.modules.get("utils")
    previous_utils_path = Path(getattr(previous_utils, "__file__", "")).resolve() if previous_utils is not None and getattr(previous_utils, "__file__", None) else None
    try:
        if previous_utils_path != PHONE_PROMPT_SOURCE.resolve():
            utility_spec = importlib.util.spec_from_file_location("utils", PHONE_PROMPT_SOURCE)
            if utility_spec is None or utility_spec.loader is None:
                raise ProbeConfigurationError("could not load the original phone prompt source")
            utility = importlib.util.module_from_spec(utility_spec)
            sys.modules["utils"] = utility
            try:
                utility_spec.loader.exec_module(utility)
            except Exception:
                if previous_utils is None:
                    sys.modules.pop("utils", None)
                else:
                    sys.modules["utils"] = previous_utils
                raise
        else:
            utility = previous_utils
        module_name = "jev_live_vlm_original_mobile_run"
        loaded = sys.modules.get(module_name)
        if loaded is None:
            spec = importlib.util.spec_from_file_location(module_name, PHONE_PARSER_SOURCE)
            if spec is None or spec.loader is None:
                raise ProbeConfigurationError("could not load the original phone parser")
            loaded = importlib.util.module_from_spec(spec)
            sys.modules[module_name] = loaded
            try:
                spec.loader.exec_module(loaded)
            except Exception:
                sys.modules.pop(module_name, None)
                raise
        return utility, loaded
    finally:
        if previous_utils_path != PHONE_PROMPT_SOURCE.resolve():
            if previous_utils is None:
                sys.modules.pop("utils", None)
            else:
                sys.modules["utils"] = previous_utils
        try:
            sys.path.remove(str(mobile_root))
        except ValueError:
            pass


def _fixture_metadata(images: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "image_id": image["image_id"],
            "media_type": image["media_type"],
            "width": image["width"],
            "height": image["height"],
            "sha256": image["sha256"],
            "valid_png": base64.b64decode(image["data"]).startswith(b"\x89PNG\r\n\x1a\n"),
            "scene": image.get("scene"),
        }
        for image in images
    ]


def _wire_images(images: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "type": "image_url",
            "image_url": {
                "url": f"data:{image['media_type']};base64,{image['data']}",
            },
        }
        for image in images
    ]


def _role_messages(prompt: str, images: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [{"role": "user", "content": [{"type": "text", "text": prompt}, *_wire_images(images)]}]


def _phone_messages(utility: types.ModuleType, images: list[dict[str, Any]]) -> list[dict[str, Any]]:
    with tempfile.TemporaryDirectory(prefix="jev-live-vlm-") as directory:
        image_path = Path(directory) / "probe-red.png"
        image_path.write_bytes(base64.b64decode(images[0]["data"]))
        original = utility.build_messages(
            str(image_path),
            "请点击截图中央的红色按钮，然后确认页面发生变化。",
            [],
            DEFAULT_MODEL,
        )
    converted: list[dict[str, Any]] = []
    for message in original:
        content: list[dict[str, Any]] = []
        for item in message.get("content", []):
            if isinstance(item, dict) and "text" in item:
                content.append({"type": "text", "text": item["text"]})
            elif isinstance(item, dict) and "image" in item:
                content.extend(_wire_images(images[:1]))
        converted.append({"role": message.get("role", "user"), "content": content})
    return converted


def _make_info_pool(module: types.ModuleType) -> Any:
    # The upstream prompt code indexes these fields before deciding whether
    # optional knowledge exists.  A single whitespace keeps that original code
    # path executable without inventing a project-specific prompt.
    return module.InfoPool(
        instruction="请点击截图中央的红色按钮，然后确认页面发生变化。",
        task_name="live_vlm_probe",
        additional_knowledge_manager=[""],
        additional_knowledge_executor=[""],
        ui_elements_list_before="",
        ui_elements_list_after="",
        action_pool=[],
        summary_history=[],
        action_history=[],
        action_outcomes=[],
        error_descriptions=[],
        last_summary="",
        last_action="",
        last_action_thought="",
        important_notes="",
        error_flag_plan=False,
        error_description_plan=False,
        plan="",
        completed_plan="No completed subgoal.",
        progress_status="",
        progress_status_history=[],
        finish_thought="",
        current_subgoal="点击截图中央的红色按钮。",
        err_to_manager_thresh=2,
        future_tasks=[],
    )


def _build_requests() -> tuple[list[ProbeRequest], dict[str, Any]]:
    images = synthetic_probe_images()
    roles = _load_roles()
    utility, phone = _load_phone_modules()
    initial_info = _make_info_pool(roles)
    executor_info = copy.deepcopy(initial_info)
    executor_info.plan = "1. 点击截图中央的红色按钮。 2. 确认页面出现完成标记。"
    reflector_info = copy.deepcopy(executor_info)
    reflector_info.last_action = {"action": "click", "coordinate": [500, 500]}
    reflector_info.last_summary = "点击截图中央的红色按钮。"
    reflector_info.completed_plan = "No completed subgoal."
    notetaker_info = copy.deepcopy(initial_info)
    role_specs = (
        ("manager", roles.Manager, initial_info, images[:1], ["### Thought", "### Plan"]),
        ("executor", roles.Executor, executor_info, images[:1], ["### Thought", "### Action", "### Description"]),
        (
            "action_reflector",
            roles.ActionReflector,
            reflector_info,
            images,
            ["### Outcome", "### Error Description"],
        ),
        ("notetaker", roles.Notetaker, notetaker_info, images[1:], ["### Important Notes"]),
    )
    requests = [
        ProbeRequest(
            name="mobile_tool_call",
            prompt=next(
                item["text"]
                for item in _phone_messages(utility, images)
                if item["role"] == "system"
                for item in item["content"]
                if item.get("type") == "text"
            ),
            messages=_phone_messages(utility, images),
            parser=f"{phone.__name__}.parse_action",
            parse_requirements=["<tool_call>\\n", "JSON name=mobile_use", "arguments.action"],
            images=images[:1],
            parse_response=phone.parse_action,
        )
    ]
    for name, role_class, role_info, role_images, requirements in role_specs:
        role = role_class()
        prompt = role.get_prompt(role_info)
        requests.append(
            ProbeRequest(
                name=name,
                prompt=prompt,
                messages=_role_messages(prompt, role_images),
                parser=f"{roles.__name__}.{role_class.__name__}.parse_response",
                parse_requirements=requirements,
                images=role_images,
                parse_response=role.parse_response,
            )
        )
    source_files = {
        "role_prompts_and_parsers": {
            "path": str(ROLE_SOURCE.relative_to(REPO_ROOT)),
            "sha256": _sha256_file(ROLE_SOURCE),
        },
        "phone_prompt": {
            "path": str(PHONE_PROMPT_SOURCE.relative_to(REPO_ROOT)),
            "sha256": _sha256_file(PHONE_PROMPT_SOURCE),
        },
        "phone_parser": {
            "path": str(PHONE_PARSER_SOURCE.relative_to(REPO_ROOT)),
            "sha256": _sha256_file(PHONE_PARSER_SOURCE),
        },
    }
    return requests, {"images": images, "source_files": source_files}


def _preview_messages(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    preview = copy.deepcopy(messages)
    for message in preview:
        for item in message.get("content", []):
            if item.get("type") == "image_url":
                item["image_url"]["url"] = "data:image/png;base64,<synthetic-fixture-omitted>"
    return preview


def _redact_value(value: Any, secrets: list[str]) -> Any:
    if isinstance(value, dict):
        return {key: _redact_value(child, secrets) for key, child in value.items()}
    if isinstance(value, list):
        return [_redact_value(child, secrets) for child in value]
    if not isinstance(value, str):
        return value
    safe = value
    for secret in secrets:
        if secret:
            safe = safe.replace(secret, "<redacted>")
    safe = re.sub(r"(?i)(bearer\s+)[^\s,;]+", r"\1<redacted>", safe)
    safe = re.sub(r"(?i)(api[_-]?key|token|secret|password)=([^&\s]+)", r"\1=<redacted>", safe)
    return safe


def _request_payload(model: str, messages: list[dict[str, Any]], max_tokens: int) -> dict[str, Any]:
    return {
        "model": model,
        "messages": messages,
        "enable_thinking": False,
        "max_tokens": max_tokens,
    }


def _request_hash(payload: dict[str, Any]) -> str:
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _ssl_context(endpoint: str) -> ssl.SSLContext | None:
    if urlparse(endpoint).scheme != "https":
        return None
    cert_file = os.environ.get("SSL_CERT_FILE")
    if cert_file:
        return ssl.create_default_context(cafile=cert_file)
    try:
        import certifi
    except ImportError:
        return ssl.create_default_context()
    return ssl.create_default_context(cafile=certifi.where())


def _decode_body(raw: bytes) -> tuple[Any, str]:
    text = raw.decode("utf-8", errors="replace")
    try:
        return json.loads(text), text
    except json.JSONDecodeError:
        return None, text


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
        context = _ssl_context(endpoint)
        kwargs = {"timeout": timeout}
        if context is not None:
            kwargs["context"] = context
        with urlopen(request, **kwargs) as response:
            raw = response.read()
            payload_value, text = _decode_body(raw)
            return response.status, payload_value, text, None
    except HTTPError as exc:
        raw = exc.read()
        payload_value, text = _decode_body(raw)
        return exc.code, payload_value, text, None
    except (URLError, TimeoutError, OSError) as exc:
        return None, None, "", type(exc).__name__


def _content(payload: Any) -> str | None:
    if not isinstance(payload, dict):
        return None
    choices = payload.get("choices")
    if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
        return None
    choice = choices[0]
    message = choice.get("message")
    value = message.get("content") if isinstance(message, dict) else choice.get("text")
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "".join(str(part.get("text", "")) for part in value if isinstance(part, dict) and part.get("type") == "text")
    return None


def _finish_reason(payload: Any) -> str | None:
    if not isinstance(payload, dict):
        return None
    choices = payload.get("choices")
    if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
        return None
    value = choices[0].get("finish_reason")
    return value if isinstance(value, str) else None


def _parse_phone(parse_response: Callable[[str], dict[str, Any]], content: str) -> dict[str, Any]:
    try:
        result = parse_response(content)
    except Exception as exc:
        return {"parse_ok": False, "parser_invoked": True, "error": f"{type(exc).__name__}: {exc}"}
    valid = (
        isinstance(result, dict)
        and result.get("name") == "mobile_use"
        and isinstance(result.get("arguments"), dict)
        and isinstance(result["arguments"].get("action"), str)
    )
    if valid:
        arguments = result["arguments"]
        action = arguments["action"]
        if action != "click":
            valid = False
            error = "live probe requires a click action for independent device confirmation"
        else:
            coordinate = arguments.get("coordinate")
            valid = isinstance(coordinate, list) and len(coordinate) == 2 and all(isinstance(value, (int, float)) for value in coordinate)
            error = None if valid else "click action requires a two-number coordinate"
    else:
        error = "missing mobile_use/action fields"
    return {"parse_ok": valid, "parsed": result, **({} if valid else {"error": error})}


def _parse_role(name: str, parse_response: Callable[[str], dict[str, Any]], content: str) -> dict[str, Any]:
    required_headers = {
        "manager": ("### Thought ###", "### Plan ###"),
        "executor": ("### Thought ###", "### Action ###", "### Description ###"),
        "action_reflector": ("### Outcome ###", "### Error Description ###"),
        "notetaker": ("### Important Notes ###",),
    }[name]
    missing_headers = [header for header in required_headers if header not in content]
    if missing_headers:
        return {
            "parse_ok": False,
            "parser_invoked": False,
            "error": f"missing original response section(s): {', '.join(missing_headers)}",
        }
    try:
        result = parse_response(content)
    except Exception as exc:
        return {"parse_ok": False, "parser_invoked": True, "error": f"{type(exc).__name__}: {exc}"}
    if not isinstance(result, dict):
        return {"parse_ok": False, "parser_invoked": True, "parsed": result, "error": "original parser did not return an object"}
    if name == "manager":
        valid = bool(result.get("thought", "").strip()) and bool(result.get("plan", "").strip())
    elif name == "executor":
        action_text = result.get("action", "")
        try:
            action = json.loads(action_text)
        except (TypeError, json.JSONDecodeError):
            action = None
        coordinate = action.get("coordinate") if isinstance(action, dict) else None
        valid = (
            bool(result.get("thought", "").strip())
            and isinstance(action, dict)
            and action.get("action") == "click"
            and isinstance(coordinate, list)
            and len(coordinate) == 2
            and all(isinstance(value, (int, float)) for value in coordinate)
            and bool(result.get("description", "").strip())
        )
        if isinstance(action, dict):
            result = {**result, "action_object": action}
    elif name == "action_reflector":
        valid = result.get("outcome", "").strip() in {"A", "B", "C"} and bool(result.get("error_description", "").strip())
    else:
        valid = bool(result.get("important_notes", "").strip())
    return {"parse_ok": valid, "parser_invoked": True, "parsed": result, **({} if valid else {"error": "required original response sections were not usable"})}


def _simulate_click(action: dict[str, Any], source: str) -> dict[str, Any]:
    """Map a model click to the existing simulated device and verify its state."""

    arguments = action.get("arguments") if source == "mobile_tool_call" else action
    if not isinstance(arguments, dict) or arguments.get("action") != "click":
        return {
            "source": source,
            "status": "unsupported_action",
            "accepted": False,
            "independent_confirmation": False,
        }
    coordinate = arguments.get("coordinate")
    if not isinstance(coordinate, list) or len(coordinate) != 2 or not all(isinstance(value, (int, float)) for value in coordinate):
        return {
            "source": source,
            "status": "invalid_coordinate",
            "accepted": False,
            "independent_confirmation": False,
        }
    x, y = coordinate
    # Both upstream prompt formats express coordinates in a 1000x1000 space.
    inside = 0 <= x <= 1000 and 0 <= y <= 1000 and 250 <= x <= 750 and 375 <= y <= 625
    if not inside:
        return {
            "source": source,
            "status": "postcondition_failed",
            "accepted": False,
            "independent_confirmation": False,
            "mapping": {"coordinate": [x, y], "target_node_id": None},
        }
    task_id = f"live-vlm-probe-{source}"
    action_id = f"{task_id}-tap-1"
    session_id = f"{task_id}-session"
    device = SimulatedDevice(
        device_id="live-vlm-probe-device",
        token="local-live-vlm-probe-token",
        behavior="apply_effect",
        clock=lambda: "2026-09-23T00:00:00Z",
    )
    before = device.observe(task_id, session_id=session_id)
    mapped_action = {
        "schema_version": "1.0",
        "task_id": task_id,
        "action_id": action_id,
        "observation_id": before["observation_id"],
        "observation_version": before["observation_version"],
        "kind": "tap",
        "target_node_id": "start-button",
        "expected_page_state": "done",
        "source": f"live-vlm:{source}",
        "created_at": "2026-09-23T00:00:00Z",
        "device_id": device.device_id,
        "session_id": session_id,
        "sequence": 1,
        "target_node_role": "button",
        "target_node_label": "Start",
        "parameters": {"coordinate_1000": [x, y]},
    }
    receipt = device.execute(mapped_action)
    after = device.observe(task_id, session_id=session_id)
    action_status = device.action_status(task_id, action_id, session_id=session_id)
    confirmed = receipt["accepted"] and receipt["outcome"] == "EXECUTED" and action_status["status"] == "EXECUTED" and after["page_state"] == "done"
    return {
        "source": source,
        "status": "confirmed" if confirmed else "postcondition_failed",
        "accepted": receipt["accepted"],
        "independent_confirmation": confirmed,
        "mapping": {"coordinate": [x, y], "target_node_id": "start-button"},
        "before_observation": before,
        "mapped_action": mapped_action,
        "receipt": receipt,
        "after_observation": after,
        "action_status": action_status,
        "postcondition": "after_observation.page_state == done and action_status.status == EXECUTED",
    }


def _tokens(usage: Any, *names: str) -> int | None:
    if not isinstance(usage, dict):
        return None
    for name in names:
        value = usage.get(name)
        if isinstance(value, int) and value >= 0:
            return value
    return None


def _usage_cost(usage: Any) -> float | None:
    input_tokens = _tokens(usage, "prompt_tokens", "input_tokens")
    output_tokens = _tokens(usage, "completion_tokens", "output_tokens")
    if input_tokens is None or output_tokens is None:
        return None
    return (input_tokens * INPUT_PRICE_PER_MILLION + output_tokens * OUTPUT_PRICE_PER_MILLION) / 1_000_000


def _validate(endpoint: str | None, model: str | None, max_requests: int, max_tokens: int, timeout: float, budget_cny: float) -> tuple[str | None, str | None, int, int, float, float]:
    endpoint_value = endpoint.strip() if isinstance(endpoint, str) else endpoint
    model_value = model.strip() if isinstance(model, str) else model
    if endpoint_value:
        parsed = urlparse(endpoint_value)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ProbeConfigurationError("endpoint must be a complete http(s) chat-completions URL")
    if not endpoint_value or not model_value:
        return endpoint_value, model_value, max_requests, max_tokens, timeout, budget_cny
    if not 1 <= int(max_requests) <= MAX_REQUESTS:
        raise ProbeConfigurationError(f"max_requests must be between 1 and {MAX_REQUESTS}")
    if not 1 <= int(max_tokens) <= MAX_TOKENS:
        raise ProbeConfigurationError(f"max_tokens must be between 1 and {MAX_TOKENS}")
    timeout_value = float(timeout)
    if timeout_value <= 0:
        raise ProbeConfigurationError("timeout must be positive")
    budget_value = float(budget_cny)
    if budget_value < 0:
        raise ProbeConfigurationError("budget_cny must not be negative")
    return endpoint_value, model_value, int(max_requests), int(max_tokens), min(timeout_value, MAX_TIMEOUT_SECONDS), budget_value


def run_probe(
    *,
    endpoint: str | None = DEFAULT_ENDPOINT,
    model: str | None = DEFAULT_MODEL,
    credential_env: str = DEFAULT_CREDENTIAL_ENV,
    timeout: float = 30.0,
    execute: bool = False,
    max_requests: int = DEFAULT_MAX_REQUESTS,
    max_tokens: int = DEFAULT_MAX_TOKENS,
    budget_cny: float = DEFAULT_BUDGET_CNY,
) -> dict[str, Any]:
    """Build the suite and optionally execute at most six single requests."""

    try:
        endpoint, model, max_requests, max_tokens, timeout, budget_cny = _validate(
            endpoint, model, max_requests, max_tokens, timeout, budget_cny
        )
    except (ProbeConfigurationError, TypeError, ValueError) as exc:
        return {"status": "invalid_configuration", "executed": False, "network_call": False, "error": str(exc)}

    base: dict[str, Any] = {
        "schema_version": "live-vlm/1",
        "status": "configuration_required" if not endpoint or not model else "ready",
        "executed": False,
        "network_call": False,
        "endpoint": _redact_value(endpoint, [os.environ.get(credential_env, "")]),
        "model": model,
        "credential_env": credential_env,
        "credentials_present": bool(os.environ.get(credential_env)),
        "max_requests": max_requests,
        "max_tokens": max_tokens,
        "timeout_seconds": timeout,
        "budget": {
            "limit_cny": budget_cny,
            "input_price_per_million": INPUT_PRICE_PER_MILLION,
            "output_price_per_million": OUTPUT_PRICE_PER_MILLION,
            "input_token_bound": INPUT_TOKEN_BOUND,
            "preflight_upper_bound_cny": max_requests * (INPUT_TOKEN_BOUND * INPUT_PRICE_PER_MILLION + max_tokens * OUTPUT_PRICE_PER_MILLION) / 1_000_000,
            "estimated_spend_cny": 0.0,
            "usage_missing": False,
        },
        "git_sha": _git_sha(),
    }
    if not endpoint or not model:
        return base
    if not execute:
        try:
            requests, metadata = _build_requests()
        except Exception as exc:
            base["status"] = "source_load_failed"
            base["error"] = f"{type(exc).__name__}: {exc}"
            return base
        base.update(
            {
                "images": _fixture_metadata(metadata["images"]),
                "source_files": metadata["source_files"],
                "checks": [
                    {
                        "name": request.name,
                        "status": "not_executed",
                        "parser": request.parser,
                        "parse_requirements": request.parse_requirements,
                        "prompt": request.prompt,
                        "messages": _preview_messages(request.messages),
                        "images": _fixture_metadata(request.images),
                        "network_call": False,
                    }
                    for request in requests[:max_requests]
                ],
            }
        )
        return base
    if not base["credentials_present"]:
        base["status"] = "credentials_required"
        return base
    try:
        requests, metadata = _build_requests()
    except Exception as exc:
        base["status"] = "source_load_failed"
        base["error"] = f"{type(exc).__name__}: {exc}"
        return base
    requests = requests[:max_requests]
    per_request_bound = (INPUT_TOKEN_BOUND * INPUT_PRICE_PER_MILLION + max_tokens * OUTPUT_PRICE_PER_MILLION) / 1_000_000
    if max_requests * per_request_bound > budget_cny:
        base["status"] = "budget_exceeded_before_execution"
        base["images"] = _fixture_metadata(metadata["images"])
        base["source_files"] = metadata["source_files"]
        base["checks"] = []
        return base

    base.update({"executed": True, "network_call": True, "images": _fixture_metadata(metadata["images"]), "source_files": metadata["source_files"], "checks": []})
    credential = os.environ[credential_env]
    reserved = 0.0
    actual_spend = 0.0
    for request in requests:
        payload = _request_payload(model, request.messages, max_tokens)
        check: dict[str, Any] = {
            "name": request.name,
            "status": "requesting",
            "parser": request.parser,
            "parse_requirements": request.parse_requirements,
            "prompt": request.prompt,
            "messages": _preview_messages(request.messages),
            "images": _fixture_metadata(request.images),
            "request": {"model": model, "max_tokens": max_tokens, "enable_thinking": False},
            "request_sha256": _request_hash(payload),
            "network_call": True,
            "retry_count": 0,
        }
        if reserved + per_request_bound > budget_cny:
            check.update({"status": "budget_exhausted_before_request", "network_call": False})
            base["checks"].append(check)
            continue
        reserved += per_request_bound
        status_code, response_payload, response_text, transport_error = _post_json(endpoint, credential, payload, timeout)
        check["http_status"] = status_code
        check["response"] = _redact_value(response_payload, [credential])
        if response_payload is None and response_text:
            check["response_text"] = _redact_value(response_text, [credential])
        if transport_error:
            check.update(
                {
                    "status": "request_failed",
                    "error": {"code": "transport_error", "type": transport_error},
                    "usage_present": False,
                    "usage": None,
                    "estimated_cost_cny": None,
                }
            )
            base["budget"]["usage_missing"] = True
            base["checks"].append(check)
            continue
        usage = response_payload.get("usage") if isinstance(response_payload, dict) else None
        check["usage_present"] = isinstance(usage, dict)
        check["usage"] = copy.deepcopy(usage) if isinstance(usage, dict) else None
        if not isinstance(usage, dict):
            base["budget"]["usage_missing"] = True
        cost = _usage_cost(usage)
        check["estimated_cost_cny"] = cost
        if cost is not None:
            actual_spend += cost
            base["budget"]["estimated_spend_cny"] = round(actual_spend, 9)
        if status_code is None or status_code < 200 or status_code >= 300:
            provider_error = response_payload.get("error") if isinstance(response_payload, dict) else None
            provider_code = provider_error.get("code") if isinstance(provider_error, dict) else None
            check.update({"status": "request_failed", "error": {"code": provider_code or f"http_{status_code}"}})
            base["checks"].append(check)
            continue
        finish_reason = _finish_reason(response_payload)
        check["finish_reason"] = finish_reason
        if finish_reason in {"length", "max_tokens"}:
            check.update(
                {
                    "status": "response_truncated",
                    "parse": {"parse_ok": False, "error": f"provider finish_reason={finish_reason}"},
                }
            )
            base["checks"].append(check)
            continue
        content = _content(response_payload)
        check["original_response_content"] = _redact_value(content, [credential])
        if content is None:
            check.update({"status": "response_invalid", "parse": {"parse_ok": False, "error": "missing choices.message.content"}})
            base["checks"].append(check)
            continue
        parsed = _parse_phone(request.parse_response, content) if request.name == "mobile_tool_call" else _parse_role(request.name, request.parse_response, content)
        check["parse"] = _redact_value(parsed, [credential])
        if parsed.get("parse_ok") and request.name == "mobile_tool_call":
            check["simulation"] = _simulate_click(parsed["parsed"], request.name)
        elif parsed.get("parse_ok") and request.name == "executor":
            check["simulation"] = _simulate_click(parsed["parsed"]["action_object"], request.name)
        simulation_ok = request.name not in {"mobile_tool_call", "executor"} or bool(check.get("simulation", {}).get("independent_confirmation"))
        check["status"] = "compatible" if parsed.get("parse_ok") and simulation_ok else "role_incompatible"
        base["checks"].append(check)
    compatible = sum(check.get("status") == "compatible" for check in base["checks"])
    failures = sum(check.get("status") in {"request_failed", "response_invalid", "role_incompatible"} for check in base["checks"])
    base["compatible_checks"] = compatible
    base["failed_checks"] = failures
    base["status"] = "success" if compatible == len(requests) and not failures else "partial"
    # Apply one final recursive pass so future report fields cannot accidentally
    # bypass the per-response redaction above.
    return _redact_value(base, [credential])
