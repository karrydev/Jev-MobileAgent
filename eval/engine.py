"""Load and evaluate the task-02 synthetic evaluation format.

The format deliberately keeps the decision payload, agent trajectory, and
external ground truth in separate documents.  This module never asks an
agent to produce the labels it later reports.  The decision payload uses an
explicit allowlist and typed fields; this is a structural boundary, not a
semantic proof that arbitrary string values contain no answer-like content.
"""

from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
from typing import Any, Iterable


STATUS_VALUES = frozenset({"SUCCESS", "FAILURE", "PENDING", "UNKNOWN"})
SPLIT_VALUES = frozenset({"dev", "holdout"})
ENVIRONMENT_VALUES = frozenset({"READY", "LOADING", "UNAVAILABLE", "EVIDENCE_MISSING"})
FAILURE_CLASSES = frozenset({"agent", "environment"})

TASKS_FORMAT = "jev-independent-evaluation/tasks-v1"
TRACES_FORMAT = "jev-independent-evaluation/traces-v1"
TRUTH_FORMAT = "jev-independent-evaluation/truth-v1"
REPORT_VERSION = "jev-independent-evaluation/report-v1"
DECISION_PAYLOAD_SCHEMA = "jev-independent-evaluation/decision-payload-v1"

DECISION_PAYLOAD_KEYS = frozenset(
    {"instruction", "initial_observation", "available_actions"}
)
INITIAL_OBSERVATION_KEYS = frozenset({"page", "query", "visible_controls"})

# These fields describe evaluator-only information.  The agent trace retains a
# small defensive blacklist; the decision payload has its own explicit schema
# below so unknown aliases cannot pass by omission from this set.
FORBIDDEN_DECISION_KEYS = frozenset(
    {
        "ground_truth",
        "truth",
        "answer",
        "label",
        "expected",
        "expected_state",
        "future",
        "future_frame",
        "future_state",
        "reference_answer",
        "oracle",
        "task_completion",
        "postcondition",
        "action_postcondition",
        "failure_class",
        "evaluator",
        "private_state",
        "backend_truth",
    }
)


class EvaluationInputError(ValueError):
    """Raised when one of the independent evaluation documents is invalid."""


def _load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise EvaluationInputError(f"cannot read {path}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise EvaluationInputError(f"invalid JSON in {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise EvaluationInputError(f"{path} must contain a JSON object")
    return value


def _require_mapping(value: Any, path: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise EvaluationInputError(f"{path} must be an object")
    return value


def _require_list(value: Any, path: str) -> list[Any]:
    if not isinstance(value, list):
        raise EvaluationInputError(f"{path} must be an array")
    return value


def _require_string(value: Any, path: str) -> str:
    if not isinstance(value, str) or not value:
        raise EvaluationInputError(f"{path} must be a non-empty string")
    return value


def _require_enum(value: Any, allowed: Iterable[str], path: str) -> str:
    value = _require_string(value, path)
    if value not in allowed:
        allowed_text = ", ".join(sorted(allowed))
        raise EvaluationInputError(f"{path} must be one of {allowed_text}; got {value}")
    return value


def _walk_keys(value: Any, path: str = "value") -> Iterable[tuple[str, str]]:
    if isinstance(value, dict):
        for key, child in value.items():
            if isinstance(key, str):
                yield path, key
                yield from _walk_keys(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from _walk_keys(child, f"{path}[{index}]")


def _assert_truth_free(value: Any, path: str) -> None:
    for key_path, key in _walk_keys(value, path):
        if key in FORBIDDEN_DECISION_KEYS:
            raise EvaluationInputError(
                f"{key_path} contains evaluator-only key {key!r}; "
                "agent traces must not carry evaluator-only fields"
            )


def _assert_unique(values: Iterable[str], path: str) -> None:
    seen: set[str] = set()
    for value in values:
        if value in seen:
            raise EvaluationInputError(f"duplicate id {value!r} in {path}")
        seen.add(value)


def _assert_allowed_keys(
    value: dict[str, Any], allowed: Iterable[str], path: str
) -> None:
    allowed_keys = frozenset(allowed)
    unknown = sorted((key for key in value if key not in allowed_keys), key=str)
    if unknown:
        unknown_text = ", ".join(repr(key) for key in unknown)
        allowed_text = ", ".join(sorted(allowed_keys))
        raise EvaluationInputError(
            f"{path} contains unsupported field(s): {unknown_text}; "
            f"allowed fields are: {allowed_text}"
        )


def _require_string_array(value: Any, path: str) -> list[str]:
    values = _require_list(value, path)
    normalized: list[str] = []
    for index, item in enumerate(values):
        normalized.append(_require_string(item, f"{path}[{index}]"))
    return normalized


def _validate_decision_payload(payload: dict[str, Any], path: str) -> None:
    """Validate the bounded input currently exposed to the decision maker.

    The schema intentionally has no observation-after, expected, reference,
    backend, or evaluator fields.  Free-text strings remain opaque and are
    only checked for type and non-emptiness.
    """

    _assert_allowed_keys(payload, DECISION_PAYLOAD_KEYS, path)
    _require_string(payload.get("instruction"), f"{path}.instruction")

    observation_path = f"{path}.initial_observation"
    observation = _require_mapping(payload.get("initial_observation"), observation_path)
    _assert_allowed_keys(observation, INITIAL_OBSERVATION_KEYS, observation_path)
    _require_string(observation.get("page"), f"{observation_path}.page")
    _require_string_array(
        observation.get("visible_controls"),
        f"{observation_path}.visible_controls",
    )
    if "query" in observation:
        _require_string(observation["query"], f"{observation_path}.query")

    _require_string_array(
        payload.get("available_actions"), f"{path}.available_actions"
    )


def _validate_tasks(document: dict[str, Any]) -> tuple[dict[str, Any], ...]:
    if document.get("format") != TASKS_FORMAT:
        raise EvaluationInputError(f"tasks document must use format {TASKS_FORMAT!r}")
    tasks = _require_list(document.get("tasks"), "tasks.tasks")
    normalized: list[dict[str, Any]] = []
    for index, raw_task in enumerate(tasks):
        path = f"tasks.tasks[{index}]"
        task = _require_mapping(raw_task, path)
        task_id = _require_string(task.get("task_id"), f"{path}.task_id")
        trajectory_id = _require_string(task.get("trajectory_id"), f"{path}.trajectory_id")
        _require_enum(task.get("split"), SPLIT_VALUES, f"{path}.split")
        _require_mapping(task.get("initial_state"), f"{path}.initial_state")
        _require_mapping(task.get("input"), f"{path}.input")
        _require_mapping(task.get("goal"), f"{path}.goal")
        payload = _require_mapping(task.get("decision_payload"), f"{path}.decision_payload")
        _validate_decision_payload(payload, f"{path}.decision_payload")
        normalized.append(task)

    _assert_unique((task["task_id"] for task in normalized), "tasks.tasks.task_id")
    _assert_unique(
        (task["trajectory_id"] for task in normalized), "tasks.tasks.trajectory_id"
    )
    return tuple(normalized)


def _validate_trace_steps(steps: list[Any], path: str) -> None:
    step_ids: list[str] = []
    for index, raw_step in enumerate(steps):
        step_path = f"{path}[{index}]"
        step = _require_mapping(raw_step, step_path)
        step_ids.append(_require_string(step.get("step_id"), f"{step_path}.step_id"))
        _require_mapping(step.get("observation_before"), f"{step_path}.observation_before")
        _require_mapping(step.get("action"), f"{step_path}.action")
        _require_mapping(step.get("receipt"), f"{step_path}.receipt")
        if "observation_after" in step:
            _require_mapping(step["observation_after"], f"{step_path}.observation_after")
    _assert_unique(step_ids, f"{path}.step_id")


def _validate_traces(document: dict[str, Any]) -> tuple[dict[str, Any], ...]:
    if document.get("format") != TRACES_FORMAT:
        raise EvaluationInputError(f"traces document must use format {TRACES_FORMAT!r}")
    traces = _require_list(document.get("trajectories"), "traces.trajectories")
    normalized: list[dict[str, Any]] = []
    for index, raw_trace in enumerate(traces):
        path = f"traces.trajectories[{index}]"
        trace = _require_mapping(raw_trace, path)
        _require_string(trace.get("trajectory_id"), f"{path}.trajectory_id")
        _require_string(trace.get("task_id"), f"{path}.task_id")
        steps = _require_list(trace.get("steps"), f"{path}.steps")
        _assert_truth_free(trace, path)
        _validate_trace_steps(steps, f"{path}.steps")
        normalized.append(trace)
    _assert_unique((trace["trajectory_id"] for trace in normalized), "traces.trajectory_id")
    _assert_unique((trace["task_id"] for trace in normalized), "traces.task_id")
    return tuple(normalized)


def _validate_truth_outcome(value: Any, path: str) -> dict[str, Any]:
    outcome = _require_mapping(value, path)
    status = _require_enum(outcome.get("status"), STATUS_VALUES, f"{path}.status")
    _require_string(outcome.get("reason"), f"{path}.reason")
    _require_mapping(outcome.get("evidence"), f"{path}.evidence")
    failure_class = outcome.get("failure_class")
    if failure_class is not None:
        _require_enum(failure_class, FAILURE_CLASSES, f"{path}.failure_class")
    if status == "FAILURE" and failure_class is None:
        raise EvaluationInputError(f"{path}.failure_class is required for FAILURE")
    if status == "SUCCESS" and failure_class is not None:
        raise EvaluationInputError(f"{path}.failure_class must be null for SUCCESS")
    return outcome


def _validate_truth(document: dict[str, Any]) -> tuple[dict[str, Any], ...]:
    if document.get("format") != TRUTH_FORMAT:
        raise EvaluationInputError(f"truth document must use format {TRUTH_FORMAT!r}")
    records = _require_list(document.get("records"), "truth.records")
    normalized: list[dict[str, Any]] = []
    for index, raw_record in enumerate(records):
        path = f"truth.records[{index}]"
        record = _require_mapping(raw_record, path)
        _require_string(record.get("task_id"), f"{path}.task_id")
        _require_string(record.get("trajectory_id"), f"{path}.trajectory_id")
        _require_string(record.get("source"), f"{path}.source")
        environment = _require_mapping(record.get("environment"), f"{path}.environment")
        _require_enum(environment.get("status"), ENVIRONMENT_VALUES, f"{path}.environment.status")
        _require_string(environment.get("reason"), f"{path}.environment.reason")
        _require_mapping(record.get("initial_state"), f"{path}.initial_state")
        steps = _require_list(record.get("steps"), f"{path}.steps")
        step_ids: list[str] = []
        for step_index, raw_step in enumerate(steps):
            step_path = f"{path}.steps[{step_index}]"
            step = _require_mapping(raw_step, step_path)
            step_ids.append(_require_string(step.get("step_id"), f"{step_path}.step_id"))
            _validate_truth_outcome(
                step.get("action_postcondition"), f"{step_path}.action_postcondition"
            )
        _assert_unique(step_ids, f"{path}.steps.step_id")
        _validate_truth_outcome(record.get("task_completion"), f"{path}.task_completion")
        normalized.append(record)
    _assert_unique((record["task_id"] for record in normalized), "truth.records.task_id")
    _assert_unique(
        (record["trajectory_id"] for record in normalized),
        "truth.records.trajectory_id",
    )
    return tuple(normalized)


def _validate_cross_document_links(
    tasks: tuple[dict[str, Any], ...],
    traces: tuple[dict[str, Any], ...],
    truth: tuple[dict[str, Any], ...],
) -> None:
    task_by_id = {task["task_id"]: task for task in tasks}
    trace_by_trajectory = {trace["trajectory_id"]: trace for trace in traces}
    truth_by_task = {record["task_id"]: record for record in truth}
    if set(task_by_id) != set(truth_by_task):
        raise EvaluationInputError("tasks and truth must contain the same task IDs")

    expected_trajectories = {task["trajectory_id"] for task in tasks}
    actual_trajectories = set(trace_by_trajectory)
    if actual_trajectories != expected_trajectories:
        missing = sorted(expected_trajectories - actual_trajectories)
        extra = sorted(actual_trajectories - expected_trajectories)
        raise EvaluationInputError(
            "traces must contain exactly the task trajectories; "
            f"missing={missing}, extra={extra}"
        )

    truth_by_trajectory = {record["trajectory_id"]: record for record in truth}
    actual_truth_trajectories = set(truth_by_trajectory)
    if actual_truth_trajectories != expected_trajectories:
        missing = sorted(expected_trajectories - actual_truth_trajectories)
        extra = sorted(actual_truth_trajectories - expected_trajectories)
        raise EvaluationInputError(
            "truth must contain exactly the task trajectories; "
            f"missing={missing}, extra={extra}"
        )

    for task in tasks:
        task_id = task["task_id"]
        trajectory_id = task["trajectory_id"]
        trace = trace_by_trajectory.get(trajectory_id)
        if trace is None:
            raise EvaluationInputError(f"missing trace for trajectory {trajectory_id!r}")
        if trace["task_id"] != task_id:
            raise EvaluationInputError(
                f"trajectory {trajectory_id!r} links to task {trace['task_id']!r}, "
                f"expected {task_id!r}"
            )
        record = truth_by_task[task_id]
        if record["trajectory_id"] != trajectory_id:
            raise EvaluationInputError(
                f"truth for task {task_id!r} links to trajectory "
                f"{record['trajectory_id']!r}, expected {trajectory_id!r}"
            )
        if record["initial_state"] != task["initial_state"]:
            raise EvaluationInputError(
                f"truth initial_state for task {task_id!r} does not match task manifest"
            )

        trace_step_ids = {step["step_id"] for step in trace["steps"]}
        truth_step_ids = {step["step_id"] for step in record["steps"]}
        if trace_step_ids != truth_step_ids:
            missing = sorted(truth_step_ids - trace_step_ids)
            extra = sorted(trace_step_ids - truth_step_ids)
            raise EvaluationInputError(
                f"trace/truth step IDs must match for trajectory {trajectory_id!r}; "
                f"missing_trace_steps={missing}, extra_trace_steps={extra}"
            )

    split_by_task = {task["task_id"]: task["split"] for task in tasks}
    split_by_trajectory = {task["trajectory_id"]: task["split"] for task in tasks}
    if len(split_by_task) != len(set(split_by_task)) or len(split_by_trajectory) != len(
        set(split_by_trajectory)
    ):
        raise EvaluationInputError("each task and trajectory must belong to one split")


def load_inputs(
    tasks_path: str | Path, traces_path: str | Path, truth_path: str | Path
) -> tuple[tuple[dict[str, Any], ...], tuple[dict[str, Any], ...], tuple[dict[str, Any], ...]]:
    """Load, validate, and link the three independent evaluation documents."""

    tasks = _validate_tasks(_load_json(Path(tasks_path)))
    traces = _validate_traces(_load_json(Path(traces_path)))
    truth = _validate_truth(_load_json(Path(truth_path)))
    _validate_cross_document_links(tasks, traces, truth)
    return tasks, traces, truth


def _trace_steps_by_id(trace: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {step["step_id"]: step for step in trace["steps"]}


def _outcome_for_report(outcome: dict[str, Any], *, trace_present: bool | None = None) -> dict[str, Any]:
    result: dict[str, Any] = {
        "status": outcome["status"],
        "reason": outcome["reason"],
        "failure_class": outcome.get("failure_class"),
        "evidence": outcome["evidence"],
    }
    if trace_present is not None:
        result["trace_present"] = trace_present
    return result


def _stable_counts(values: Iterable[str]) -> dict[str, int]:
    counter = Counter(values)
    return {key: counter[key] for key in sorted(counter)}


def evaluate_documents(
    tasks: tuple[dict[str, Any], ...],
    traces: tuple[dict[str, Any], ...],
    truth: tuple[dict[str, Any], ...],
    split: str = "all",
) -> dict[str, Any]:
    """Produce a JSON-serializable deterministic report from validated inputs."""

    if split != "all" and split not in SPLIT_VALUES:
        raise EvaluationInputError(f"split must be all, dev, or holdout; got {split!r}")

    trace_by_trajectory = {trace["trajectory_id"]: trace for trace in traces}
    truth_by_task = {record["task_id"]: record for record in truth}
    selected_tasks = [task for task in tasks if split == "all" or task["split"] == split]
    selected_tasks.sort(key=lambda task: (task["task_id"], task["trajectory_id"]))

    results: list[dict[str, Any]] = []
    action_statuses: list[str] = []
    task_statuses: list[str] = []
    failure_classes: list[str] = []
    for task in selected_tasks:
        task_id = task["task_id"]
        truth_record = truth_by_task[task_id]
        trace = trace_by_trajectory[task["trajectory_id"]]
        trace_steps = _trace_steps_by_id(trace)
        step_results: list[dict[str, Any]] = []
        for truth_step in sorted(truth_record["steps"], key=lambda step: step["step_id"]):
            step_id = truth_step["step_id"]
            outcome = truth_step["action_postcondition"]
            action_statuses.append(outcome["status"])
            agent_step = trace_steps.get(step_id)
            step_results.append(
                {
                    "step_id": step_id,
                    "action": agent_step.get("action") if agent_step else None,
                    "action_verification": _outcome_for_report(
                        outcome, trace_present=agent_step is not None
                    ),
                }
            )

        task_outcome = truth_record["task_completion"]
        task_statuses.append(task_outcome["status"])
        if task_outcome.get("failure_class") is not None:
            failure_classes.append(task_outcome["failure_class"])
        results.append(
            {
                "task_id": task_id,
                "trajectory_id": task["trajectory_id"],
                "split": task["split"],
                "initial_state": task["initial_state"],
                "input": task["input"],
                "goal": task["goal"],
                "steps": step_results,
                "task_completion": _outcome_for_report(task_outcome),
                "environment": truth_record["environment"],
                "truth_source": truth_record["source"],
                "trace_step_count": len(trace["steps"]),
            }
        )

    result_task_ids = {result["task_id"] for result in results}
    selected_trajectories = {
        task["trajectory_id"] for task in selected_tasks
    }
    split_isolation = len(result_task_ids) == len(results) and len(selected_trajectories) == len(
        results
    )
    report = {
        "report_version": REPORT_VERSION,
        "selected_split": split,
        "counts": {
            "tasks": len(results),
            "task_completion": _stable_counts(task_statuses),
            "action_verification": _stable_counts(action_statuses),
            "failure_class": _stable_counts(failure_classes),
        },
        "integrity": {
            "decision_payload_schema": DECISION_PAYLOAD_SCHEMA,
            "decision_payload_schema_validated": True,
            "trace_truth_step_sets_match": True,
            "split_isolation": split_isolation,
            "ground_truth_source_is_separate": True,
        },
        "results": results,
    }
    return report


def evaluate_files(
    tasks_path: str | Path,
    traces_path: str | Path,
    truth_path: str | Path,
    split: str = "all",
) -> dict[str, Any]:
    """Convenience API used by the CLI and external offline checks."""

    return evaluate_documents(*load_inputs(tasks_path, traces_path, truth_path), split=split)


def render_report(report: dict[str, Any]) -> str:
    """Render a report with stable key and result ordering."""

    return json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
