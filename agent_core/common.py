"""Small validation and JSON helpers shared by the offline pipelines."""

from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
from typing import Any, Iterable, Mapping


FORBIDDEN_DECISION_KEYS = frozenset(
    {
        "answer",
        "backend_truth",
        "expected_state",
        "evaluator",
        "future",
        "future_frame",
        "future_state",
        "ground_truth",
        "next_frame",
        "next_observation",
        "oracle",
        "postcondition_truth",
        "reference",
        "reference_answer",
        "task_completion",
        "truth",
    }
)


class OfflineInputError(ValueError):
    """Raised when a public offline input document is malformed."""


def load_json(path: str | Path) -> dict[str, Any]:
    source = Path(path)
    try:
        value = json.loads(source.read_text(encoding="utf-8"))
    except OSError as exc:
        raise OfflineInputError(f"cannot read {source}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise OfflineInputError(f"invalid JSON in {source}: {exc}") from exc
    if not isinstance(value, dict):
        raise OfflineInputError(f"{source} must contain a JSON object")
    return value


def render_json(value: Any) -> str:
    """Render stable UTF-8 JSON suitable for CLI output and golden files."""

    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def canonical_json(value: Any) -> str:
    """Return the stable JSON representation used for offline artifact hashes."""

    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256_json(value: Any) -> str:
    """Hash a JSON-like artifact without depending on object insertion order."""

    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def require_mapping(value: Any, path: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise OfflineInputError(f"{path} must be an object")
    return value


def require_list(value: Any, path: str) -> list[Any]:
    if not isinstance(value, list):
        raise OfflineInputError(f"{path} must be an array")
    return value


def require_string(value: Any, path: str) -> str:
    if not isinstance(value, str) or not value:
        raise OfflineInputError(f"{path} must be a non-empty string")
    return value


def require_bool(value: Any, path: str) -> bool:
    if not isinstance(value, bool):
        raise OfflineInputError(f"{path} must be a boolean")
    return value


def require_integer(value: Any, path: str) -> int:
    # bool is an int subclass, but is never a meaningful observation clock.
    if isinstance(value, bool) or not isinstance(value, int):
        raise OfflineInputError(f"{path} must be an integer")
    return value


def require_enum(value: Any, allowed: Iterable[str], path: str) -> str:
    result = require_string(value, path)
    if result not in allowed:
        options = ", ".join(sorted(allowed))
        raise OfflineInputError(f"{path} must be one of {options}; got {result}")
    return result


def walk_keys(value: Any, path: str = "value") -> Iterable[tuple[str, str]]:
    if isinstance(value, dict):
        for key, child in value.items():
            if isinstance(key, str):
                yield path, key
                yield from walk_keys(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from walk_keys(child, f"{path}[{index}]")


def assert_decision_truth_free(value: Any, path: str = "decision") -> None:
    """Reject evaluator-only keys before building candidates or classifications."""

    for key_path, key in walk_keys(value, path):
        if key in FORBIDDEN_DECISION_KEYS:
            raise OfflineInputError(
                f"{key_path} contains evaluator-only key {key!r}; "
                "decision input must not include reference or future evidence"
            )


def deep_copy(value: Any) -> Any:
    return copy.deepcopy(value)


def sanitize_trace(value: Any) -> Any:
    """Drop evaluator-only key names from an adapter trace snapshot."""

    if isinstance(value, dict):
        return {
            key: sanitize_trace(child)
            for key, child in value.items()
            if key not in FORBIDDEN_DECISION_KEYS
        }
    if isinstance(value, list):
        return [sanitize_trace(child) for child in value]
    return deep_copy(value)


def stable_unique(values: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        if value not in seen:
            seen.add(value)
            result.append(value)
    return result


def get_path(value: Mapping[str, Any], path: str) -> tuple[bool, Any]:
    """Read a dotted path from nested mappings, returning (present, value)."""

    current: Any = value
    for component in path.split("."):
        if not isinstance(current, Mapping) or component not in current:
            return False, None
        current = current[component]
    return True, current


def set_path(value: dict[str, Any], path: str, replacement: Any) -> None:
    """Set a dotted path, creating intermediate objects for the simulator."""

    parts = path.split(".")
    current = value
    for component in parts[:-1]:
        child = current.get(component)
        if not isinstance(child, dict):
            child = {}
            current[component] = child
        current = child
    current[parts[-1]] = deep_copy(replacement)


def evaluate_independent_documents(
    tasks: tuple[dict[str, Any], ...],
    traces: tuple[dict[str, Any], ...],
    truth: tuple[dict[str, Any], ...],
    *,
    split: str,
) -> dict[str, Any]:
    """Validate and evaluate in-memory documents with the task-02 engine.

    The accepted evaluator exposes file loading as its public validation entry
    point.  The offline adapters already keep their documents in memory, so we
    use the engine's documented validation stages directly rather than writing
    temporary files or bypassing integrity checks.
    """

    try:
        from eval import engine
    except ImportError as exc:  # pragma: no cover - outside repository installs
        raise OfflineInputError("independent evaluator eval.engine is unavailable") from exc
    try:
        validated_tasks = engine._validate_tasks({"format": engine.TASKS_FORMAT, "tasks": list(tasks)})
        validated_traces = engine._validate_traces(
            {"format": engine.TRACES_FORMAT, "trajectories": list(traces)}
        )
        validated_truth = engine._validate_truth({"format": engine.TRUTH_FORMAT, "records": list(truth)})
        engine._validate_cross_document_links(validated_tasks, validated_traces, validated_truth)
        return engine.evaluate_documents(validated_tasks, validated_traces, validated_truth, split=split)
    except engine.EvaluationInputError as exc:
        raise OfflineInputError(f"independent evaluator input invalid: {exc}") from exc
