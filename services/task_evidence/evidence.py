"""Validate, evaluate, and redact one runtime task result.

This module is deliberately an adapter.  It does not execute a model, talk to
an Android device, or change ``services.sim_loop`` or ``eval.engine``.  The
input is the result returned by the existing public task endpoint.  A small
independent truth document is built for the offline simulated device and is
fed through the task-02 evaluator in a temporary, local-only directory.
"""

from __future__ import annotations

import copy
import hashlib
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any, Iterable, Mapping

from services.sim_loop.schema import SchemaValidationError, assert_valid

from eval.engine import EvaluationInputError, evaluate_files


EVIDENCE_INPUT_VERSION = "jev-task-evidence/runtime-result-v1"
EVIDENCE_REPORT_VERSION = "jev-task-evidence/report-v1"
PUBLIC_REPORT_VERSION = "jev-task-evidence/public-report-v1"
PRICE_BOOK_FORMAT = "jev-task-evidence/pricing-v1"
PUBLIC_REDACTION_VERSION = "jev-task-evidence/public-whitelist-v1"
RUNTIME_SCHEMA_VERSION = "1.0"

_STATES = frozenset({"RUNNING", "PAUSED", "SUCCEEDED", "FAILED", "CANCELLED"})
_TRACE_KINDS = frozenset(
    {
        "task.submitted",
        "observation.captured",
        "action.dispatched",
        "receipt.received",
        "verification.recorded",
        "task.completed",
        "task.failed",
        "task.paused",
        "task.cancelled",
        "model.requested",
        "model.responded",
        "model.failed",
        "action.deduplicated",
    }
)
_TERMINAL_EVENT_BY_STATE = {
    "SUCCEEDED": "task.completed",
    "FAILED": "task.failed",
    "CANCELLED": "task.cancelled",
}
_FALLBACK_SOURCES = frozenset(
    {
        "simulated-planner",
        "rule-planner",
        "visual-fallback",
        "synthetic-fallback",
    }
)
_EVENT_ENTITY_KINDS = {
    "task.submitted": "task",
    "observation.captured": "observation",
    "action.dispatched": "action",
    "receipt.received": "receipt",
    "verification.recorded": "verification",
    "task.completed": "task",
    "task.failed": "task",
    "task.paused": "task",
    "task.cancelled": "task",
    "model.requested": "model_request",
    "model.responded": "model_attempt",
    "model.failed": "model_attempt",
    "action.deduplicated": "action",
}
_EVENT_REQUIRED_LINKS = {
    "task.submitted": frozenset({"task_id"}),
    "observation.captured": frozenset({"task_id", "observation_id"}),
    "action.dispatched": frozenset({"task_id", "action_id", "observation_id"}),
    "receipt.received": frozenset({"task_id", "action_id", "receipt_id"}),
    "verification.recorded": frozenset({"task_id", "action_id", "verification_id"}),
    "task.completed": frozenset({"task_id"}),
    "task.failed": frozenset({"task_id"}),
    "task.paused": frozenset({"task_id"}),
    "task.cancelled": frozenset({"task_id"}),
    "model.requested": frozenset({"task_id"}),
    "model.responded": frozenset({"task_id"}),
    "model.failed": frozenset({"task_id"}),
    "action.deduplicated": frozenset({"task_id", "action_id"}),
}


class EvidenceError(ValueError):
    """Base error for malformed evidence or an unusable report input."""


class EvidenceIntegrityError(EvidenceError):
    """Raised when a runtime result cannot be trusted as a complete trace."""


def _fail(message: str) -> None:
    raise EvidenceIntegrityError(message)


def _non_empty_string(value: Any, path: str) -> str:
    if not isinstance(value, str) or not value:
        _fail(f"{path} must be a non-empty string")
    return value


def _mapping(value: Any, path: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        _fail(f"{path} must be an object")
    return value


def _list(value: Any, path: str) -> list[Any]:
    if not isinstance(value, list):
        _fail(f"{path} must be an array")
    return value


def _iso_timestamp(value: Any, path: str) -> datetime:
    text = _non_empty_string(value, path)
    normalized = text[:-1] + "+00:00" if text.endswith("Z") else text
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as exc:
        _fail(f"{path} is not an ISO-8601 timestamp")
        raise AssertionError from exc
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def _assert_contract(value: Mapping[str, Any], kind: str, path: str) -> None:
    try:
        assert_valid(dict(value), kind)
    except (SchemaValidationError, ValueError) as exc:
        _fail(f"{path} is not a valid {kind}: {exc}")


def _event_reference_ids(trace: Iterable[Mapping[str, Any]], kind: str) -> set[str]:
    return {str(event["entity_id"]) for event in trace if event.get("kind") == kind}


def _required_event_kinds_for_success(trace: list[Mapping[str, Any]]) -> set[str]:
    """Return the event kinds that a successful runtime must have emitted."""

    required = {
        "task.submitted",
        "observation.captured",
        "action.dispatched",
        "receipt.received",
        "verification.recorded",
        "task.completed",
    }
    # A model-backed action must have a request and response/failure event.  A
    # default action is explicitly represented as a fallback and has no model
    # request, so it is not silently described as a model call.
    if any(event.get("kind") == "model.requested" for event in trace):
        required.update({"model.requested", "model.responded"})
    return required


def validate_runtime_result(runtime_result: Mapping[str, Any]) -> None:
    """Validate IDs, contracts, event ordering, and state/trace consistency.

    A malformed or truncated trace raises ``EvidenceIntegrityError``.  This
    is intentional: a missing event is never converted into a successful
    report.  Legitimate failed, cancelled, and paused results remain readable
    when their own emitted event chain is intact.
    """

    result = _mapping(runtime_result, "runtime_result")
    task_id = _non_empty_string(result.get("task_id"), "runtime_result.task_id")
    state = _non_empty_string(result.get("state"), "runtime_result.state")
    if state not in _STATES:
        _fail(f"runtime_result.state must be one of {sorted(_STATES)}; got {state!r}")
    if result.get("schema_version", RUNTIME_SCHEMA_VERSION) != RUNTIME_SCHEMA_VERSION:
        _fail("runtime_result.schema_version is not the supported runtime version")

    trace = _list(result.get("trace"), "runtime_result.trace")
    if not trace:
        _fail("runtime_result.trace must contain task.submitted and evidence events")

    event_ids: set[str] = set()
    previous_event_id: str | None = None
    for expected_sequence, raw_event in enumerate(trace):
        event = _mapping(raw_event, f"runtime_result.trace[{expected_sequence}]")
        _assert_contract(event, "trace_event", f"runtime_result.trace[{expected_sequence}]")
        event_id = _non_empty_string(event.get("event_id"), "trace event id")
        if event_id in event_ids:
            _fail(f"duplicate trace event_id {event_id!r}")
        event_ids.add(event_id)
        if event.get("task_id") != task_id:
            _fail(f"trace event {event_id!r} belongs to another task")
        if event.get("sequence") != expected_sequence:
            _fail(f"trace event {event_id!r} has a non-contiguous sequence")
        if event.get("previous_event_id") != previous_event_id:
            _fail(f"trace event {event_id!r} has a broken previous_event_id chain")
        if event.get("kind") not in _TRACE_KINDS:
            _fail(f"trace event {event_id!r} has an unsupported kind")
        links = _mapping(event.get("links"), f"trace event {event_id}.links")
        if links.get("task_id") != task_id:
            _fail(f"trace event {event_id!r} has a broken task link")
        previous_event_id = event_id
        _iso_timestamp(event.get("recorded_at"), f"trace event {event_id}.recorded_at")

    # A unique event_id and a valid sequence chain do not make a fact unique:
    # an attacker could insert a second receipt (or terminal fact) with a new
    # event_id and make the position map silently point at whichever copy was
    # seen last.  Every kind/entity pair is one runtime fact, so reject a
    # duplicate before using positions for any causal check.
    event_positions: dict[tuple[str, str], int] = {}
    for index, event in enumerate(trace):
        fact_key = (str(event.get("kind")), str(event.get("entity_id")))
        if fact_key in event_positions:
            _fail(
                "duplicate trace fact "
                f"kind={fact_key[0]!r}, entity_id={fact_key[1]!r}"
            )
        event_positions[fact_key] = index

    observations = _list(result.get("observations"), "runtime_result.observations")
    observation_ids: set[str] = set()
    for index, raw_observation in enumerate(observations):
        observation = _mapping(raw_observation, f"runtime_result.observations[{index}]")
        _assert_contract(observation, "observation", f"runtime_result.observations[{index}]")
        observation_id = _non_empty_string(observation.get("observation_id"), "observation_id")
        if observation_id in observation_ids:
            _fail(f"duplicate observation_id {observation_id!r}")
        observation_ids.add(observation_id)
        if observation.get("task_id") != task_id:
            _fail(f"observation {observation_id!r} belongs to another task")
        _iso_timestamp(observation.get("captured_at"), f"observation {observation_id}.captured_at")

    observation_event_ids = {
        str(event.get("entity_id"))
        for event in trace
        if event.get("kind") == "observation.captured"
    }
    if observation_event_ids != observation_ids:
        _fail(
            "observation events and result observations differ: "
            f"events={sorted(observation_event_ids)}, result={sorted(observation_ids)}"
        )

    action = result.get("action")
    action_map: Mapping[str, Any] | None = None
    action_id: str | None = None
    if action is not None:
        action_map = _mapping(action, "runtime_result.action")
        _assert_contract(action_map, "action", "runtime_result.action")
        action_id = _non_empty_string(action_map.get("action_id"), "runtime_result.action.action_id")
        if action_map.get("task_id") != task_id:
            _fail("runtime_result.action belongs to another task")
        if action_map.get("observation_id") not in observation_ids:
            _fail("runtime_result.action references an unknown observation")

    receipt = result.get("receipt")
    receipt_map: Mapping[str, Any] | None = None
    receipt_id: str | None = None
    if receipt is not None:
        receipt_map = _mapping(receipt, "runtime_result.receipt")
        _assert_contract(receipt_map, "receipt", "runtime_result.receipt")
        receipt_id = _non_empty_string(receipt_map.get("receipt_id"), "runtime_result.receipt.receipt_id")
        if receipt_map.get("task_id") != task_id:
            _fail("runtime_result.receipt belongs to another task")
        if action_id is None or receipt_map.get("action_id") != action_id:
            _fail("runtime_result.receipt is not linked to runtime_result.action")

    verification = result.get("verification")
    verification_map: Mapping[str, Any] | None = None
    verification_id: str | None = None
    if verification is not None:
        verification_map = _mapping(verification, "runtime_result.verification")
        _assert_contract(verification_map, "verification", "runtime_result.verification")
        verification_id = _non_empty_string(
            verification_map.get("verification_id"), "runtime_result.verification.verification_id"
        )
        if verification_map.get("task_id") != task_id:
            _fail("runtime_result.verification belongs to another task")
        if action_id is None or verification_map.get("action_id") != action_id:
            _fail("runtime_result.verification is not linked to runtime_result.action")
        before_observation_id = _non_empty_string(
            verification_map.get("before_observation_id"),
            "runtime_result.verification.before_observation_id",
        )
        after_observation_id = verification_map.get("after_observation_id")
        if not isinstance(after_observation_id, str):
            _fail("runtime_result.verification.after_observation_id must be a string")
        if before_observation_id not in observation_ids:
            _fail("runtime_result.verification must reference a captured before observation")
        if after_observation_id and after_observation_id not in observation_ids:
            _fail("runtime_result.verification must reference a captured after observation")
        if action_map is None or action_map.get("observation_id") != before_observation_id:
            _fail("runtime_result.verification.before_observation_id is not linked to the action observation")
        if after_observation_id and before_observation_id == after_observation_id:
            _fail("runtime_result.verification before and after observations must differ")
        if not after_observation_id and verification_map.get("status") != "UNKNOWN":
            _fail("non-UNKNOWN verification must include an after observation")

    attempts = _list(result.get("attempts"), "runtime_result.attempts")
    attempt_ids: set[str] = set()
    for index, raw_attempt in enumerate(attempts):
        attempt = _mapping(raw_attempt, f"runtime_result.attempts[{index}]")
        attempt_id = _non_empty_string(attempt.get("attempt_id"), f"attempt[{index}].attempt_id")
        if attempt_id in attempt_ids:
            _fail(f"duplicate model attempt_id {attempt_id!r}")
        attempt_ids.add(attempt_id)
        number = attempt.get("attempt")
        if not isinstance(number, int) or isinstance(number, bool) or number < 1:
            _fail(f"attempt {attempt_id!r} has an invalid attempt number")
        _non_empty_string(attempt.get("role"), f"attempt {attempt_id}.role")
        request = _mapping(attempt.get("request"), f"attempt {attempt_id}.request")
        _assert_contract(request, "model_request", f"attempt {attempt_id}.request")
        if request.get("task_id") != task_id or request.get("attempt_id") != attempt_id:
            _fail(f"attempt {attempt_id!r} request is not linked to the task")
        outcome = _non_empty_string(attempt.get("outcome"), f"attempt {attempt_id}.outcome")
        if outcome not in {"PENDING", "ACTION", "ERROR", "MALFORMED"}:
            _fail(f"attempt {attempt_id!r} has unsupported outcome {outcome!r}")
        _iso_timestamp(attempt.get("started_at"), f"attempt {attempt_id}.started_at")
        completed_at = attempt.get("completed_at")
        if completed_at is not None:
            _iso_timestamp(completed_at, f"attempt {attempt_id}.completed_at")

    # Every entity in the trace must be a result entity, and every result entity
    # that claims to have happened must have a corresponding event.
    for raw_event in trace:
        event = _mapping(raw_event, "trace event")
        kind = event["kind"]
        entity_id = event["entity_id"]
        expected_entity_kind = _EVENT_ENTITY_KINDS[kind]
        if event.get("entity_kind") != expected_entity_kind:
            _fail(f"trace event {kind} has entity_kind {event.get('entity_kind')!r}, expected {expected_entity_kind!r}")
        links = _mapping(event.get("links"), f"trace event {event['event_id']}.links")
        missing_links = _EVENT_REQUIRED_LINKS[kind] - set(links)
        if missing_links:
            _fail(f"trace event {kind} is missing required link(s): {sorted(missing_links)}")
        if links.get("task_id") != task_id:
            _fail(f"trace event {event['event_id']!r} has a broken task link")

        if kind in {"task.submitted", "task.completed", "task.failed", "task.paused", "task.cancelled"}:
            if entity_id != task_id:
                _fail(f"task event {kind} has a mismatched entity id")
            if kind in {"task.completed", "task.failed"} and "verification_id" in links:
                if verification_id is None or links["verification_id"] != verification_id:
                    _fail(f"task event {kind} has a mismatched verification link")
        elif kind == "observation.captured":
            if entity_id not in observation_ids or links.get("observation_id") != entity_id:
                _fail(f"trace references missing or mismatched observation {entity_id!r}")
        elif kind in {"action.dispatched", "action.deduplicated"}:
            if action_id is None or entity_id != action_id or links.get("action_id") != action_id:
                _fail(f"trace references missing or mismatched action {entity_id!r}")
            if kind == "action.dispatched" and links.get("observation_id") != action_map.get("observation_id"):
                _fail(f"action.dispatched has a mismatched observation link")
        elif kind == "receipt.received":
            if receipt_id is None or entity_id != receipt_id or links.get("receipt_id") != receipt_id:
                _fail(f"trace references missing or mismatched receipt {entity_id!r}")
            if action_id is None or links.get("action_id") != action_id:
                _fail("receipt.received has a mismatched action link")
        elif kind == "verification.recorded":
            if verification_id is None or entity_id != verification_id or links.get("verification_id") != verification_id:
                _fail(f"trace references missing or mismatched verification {entity_id!r}")
            if action_id is None or links.get("action_id") != action_id:
                _fail("verification.recorded has a mismatched action link")
        elif kind in {"model.requested", "model.responded", "model.failed"}:
            if entity_id not in attempt_ids:
                _fail(f"trace references missing model attempt {entity_id!r}")

    if verification_id is not None and state in {"SUCCEEDED", "FAILED"}:
        terminal_kind = _TERMINAL_EVENT_BY_STATE[state]
        terminal_events = [event for event in trace if event.get("kind") == terminal_kind]
        if not terminal_events or terminal_events[-1].get("links", {}).get("verification_id") != verification_id:
            _fail(f"{terminal_kind} must link the runtime verification")

    if action_id is not None and action_map is not None:
        action_position = event_positions.get(("action.dispatched", action_id))
        before_position = event_positions.get(("observation.captured", str(action_map.get("observation_id"))))
        if action_position is None or before_position is None or before_position >= action_position:
            _fail("action.dispatched must follow its bound before observation")
        if receipt_id is not None:
            receipt_position = event_positions.get(("receipt.received", receipt_id))
            if receipt_position is None or action_position >= receipt_position:
                _fail("receipt.received must follow action.dispatched")
        if verification_map is not None and verification_id is not None:
            verification_position = event_positions.get(("verification.recorded", verification_id))
            if verification_position is None:
                _fail("verification.recorded is missing for runtime verification")
            after_observation_id = verification_map.get("after_observation_id")
            if after_observation_id:
                after_position = event_positions.get(("observation.captured", str(after_observation_id)))
                if after_position is None or (receipt_id is not None and after_position <= event_positions[("receipt.received", receipt_id)]):
                    _fail("verification must follow its after observation")
                if verification_position <= after_position:
                    _fail("verification.recorded must follow its after observation")
            elif verification_map.get("status") != "UNKNOWN":
                _fail("non-UNKNOWN verification must include an after observation")

    model_event_ids = {
        str(event.get("entity_id"))
        for event in trace
        if event.get("kind") in {"model.requested", "model.responded", "model.failed"}
    }
    if model_event_ids != attempt_ids:
        _fail(
            "model attempts and model trace events differ: "
            f"events={sorted(model_event_ids)}, attempts={sorted(attempt_ids)}"
        )
    if action_id is not None and isinstance(action, Mapping):
        action_source = action.get("source")
        if action_source not in _FALLBACK_SOURCES and not attempt_ids:
            _fail("model-backed action is missing model attempt events")
    if action_id is not None and not _event_reference_ids(trace, "action.dispatched"):
        _fail("runtime_result.action is missing action.dispatched")
    if receipt_id is not None and not _event_reference_ids(trace, "receipt.received"):
        _fail("runtime_result.receipt is missing receipt.received")
    if verification_id is not None and not _event_reference_ids(trace, "verification.recorded"):
        _fail("runtime_result.verification is missing verification.recorded")

    first_kind = trace[0].get("kind")
    if first_kind != "task.submitted":
        _fail("runtime_result.trace must begin with task.submitted")
    last_kind = trace[-1].get("kind")
    if state in _TERMINAL_EVENT_BY_STATE:
        terminal_kind = _TERMINAL_EVENT_BY_STATE[state]
        terminal_positions = [index for index, event in enumerate(trace) if event.get("kind") == terminal_kind]
        # A cancel request is intentionally allowed to race with an already
        # waiting model/device call.  The control event can therefore appear
        # before a late model.responded or verification.recorded event.  The
        # late event is retained for replay but may not dispatch a new action.
        if state == "CANCELLED":
            if not terminal_positions:
                _fail("cancelled runtime result is missing task.cancelled")
            cancel_index = terminal_positions[-1]
            # An already in-flight action may still yield a receipt and an
            # UNKNOWN verification after cancellation; those facts explain
            # what happened.  A new dispatch or terminal success/failure
            # event after cancel would be a protocol violation.
            forbidden_after_cancel = {"action.dispatched", "task.completed", "task.failed"}
            if any(event.get("kind") in forbidden_after_cancel for event in trace[cancel_index + 1 :]):
                _fail("cancelled runtime result contains a side-effect event after task.cancelled")
        elif last_kind != terminal_kind:
            _fail(
                f"runtime state {state} requires {terminal_kind} as the final trace event; "
                f"got {last_kind}"
            )
    if state == "SUCCEEDED":
        present = {str(event.get("kind")) for event in trace}
        missing = _required_event_kinds_for_success(trace) - present
        if missing:
            _fail(f"successful runtime result is missing required events: {sorted(missing)}")
        if action is None or receipt is None or verification is None:
            _fail("successful runtime result must include action, receipt, and verification")
        if verification.get("status") != "SUCCESS":
            _fail("runtime state SUCCEEDED conflicts with verification status")
    if state == "FAILED" and "task.failed" not in {str(event.get("kind")) for event in trace}:
        _fail("failed runtime result is missing task.failed")
    if state == "CANCELLED" and "task.cancelled" not in {str(event.get("kind")) for event in trace}:
        _fail("cancelled runtime result is missing task.cancelled")


def _runtime_hash(runtime_result: Mapping[str, Any]) -> str:
    encoded = json.dumps(runtime_result, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _runtime_core_hash(runtime_result: Mapping[str, Any]) -> str:
    core = copy.deepcopy(dict(runtime_result))
    core.pop("evidence_context", None)
    return _runtime_hash(core)


def _attached_truth(runtime_result: Mapping[str, Any]) -> Mapping[str, Any] | None:
    context = runtime_result.get("evidence_context")
    if not isinstance(context, Mapping):
        return None
    sidecar = context.get("truth_sidecar")
    if sidecar is None and "truth_sha256" not in context and "runtime_result_sha256" not in context:
        return None
    if not isinstance(sidecar, Mapping):
        _fail("runtime_result.evidence_context.truth_sidecar must be an object")
    expected_runtime_hash = _non_empty_string(
        context.get("runtime_result_sha256"),
        "runtime_result.evidence_context.runtime_result_sha256",
    )
    if _runtime_core_hash(runtime_result) != expected_runtime_hash:
        _fail("runtime result does not match its independent truth sidecar")
    expected_truth_hash = _non_empty_string(
        context.get("truth_sha256"),
        "runtime_result.evidence_context.truth_sha256",
    )
    if _runtime_hash(sidecar) != expected_truth_hash:
        _fail("independent truth sidecar hash does not match")
    return copy.deepcopy(dict(sidecar))


def _safe_split(split: str) -> str:
    if split not in {"dev", "holdout"}:
        raise EvidenceError("split must be dev or holdout")
    return split


def _runtime_step(runtime_result: Mapping[str, Any]) -> dict[str, Any] | None:
    action = runtime_result.get("action")
    if action is None:
        return None
    observations = runtime_result.get("observations") or []
    before = observations[0] if observations else {"page_state": "unknown"}
    after = observations[1] if len(observations) > 1 else None
    receipt = runtime_result.get("receipt")
    # The evaluator only needs a structural receipt object.  A missing receipt
    # is represented as evidence absence, never as an accepted receipt.
    receipt_payload = copy.deepcopy(receipt) if isinstance(receipt, Mapping) else {"present": False, "status": "UNKNOWN"}
    step: dict[str, Any] = {
        "step_id": "step-01",
        "observation_before": _trace_observation(before),
        "action": copy.deepcopy(action),
        "receipt": receipt_payload,
    }
    if after is not None:
        step["observation_after"] = _trace_observation(after)
    return step


def _trace_observation(observation: Mapping[str, Any]) -> dict[str, Any]:
    """Keep runtime observation facts while satisfying task-02 trace schema.

    Task-02 reserves the literal ``label`` key as an evaluator-only field in
    agent traces.  The simulated runtime uses that key for a visible node
    label, so the adapter keeps the node identity/role/enabled facts and
    removes only the reserved label.  The public decision payload still uses
    the visible label list because it is explicitly allow-listed there.
    """

    result: dict[str, Any] = {}
    for key in (
        "schema_version",
        "task_id",
        "observation_id",
        "observation_version",
        "device_id",
        "captured_at",
        "page_state",
        "capabilities",
        "session_id",
    ):
        if key in observation:
            result[key] = copy.deepcopy(observation[key])
    nodes = observation.get("nodes", [])
    if isinstance(nodes, list):
        result["nodes"] = [
            {
                field: copy.deepcopy(node[field])
                for field in ("node_id", "role", "enabled")
                if isinstance(node, Mapping) and field in node
            }
            for node in nodes
            if isinstance(node, Mapping)
        ]
    return result


def _truth_record_from_argument(truth: Mapping[str, Any] | None, runtime_result: Mapping[str, Any], split: str) -> dict[str, Any]:
    attached_truth = _attached_truth(runtime_result)
    if truth is None:
        if attached_truth is None:
            raise EvidenceError("independent truth sidecar is required; pass an explicit --truth document")
        truth = attached_truth
    if not isinstance(truth, Mapping):
        raise EvidenceError("truth must be an object")
    value = truth.get("records") if isinstance(truth, Mapping) else None
    if isinstance(value, list):
        matches = [record for record in value if isinstance(record, Mapping) and record.get("task_id") == runtime_result.get("task_id")]
        if len(matches) != 1:
            raise EvidenceError("truth document must contain exactly one matching runtime task")
        record = copy.deepcopy(matches[0])
    elif isinstance(truth, Mapping) and truth.get("task_id") == runtime_result.get("task_id"):
        record = copy.deepcopy(dict(truth))
    else:
        raise EvidenceError("truth must be a record or a records document matching runtime task_id")
    if attached_truth is not None and _runtime_hash(record) != _runtime_hash(attached_truth):
        _fail("provided independent truth does not match the runtime sidecar")
    record["_split"] = split
    return record


def build_evaluation_documents(
    runtime_result: Mapping[str, Any],
    *,
    truth: Mapping[str, Any] | None = None,
    split: str = "dev",
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    """Adapt one validated runtime trace to task-02 evaluator documents."""

    validate_runtime_result(runtime_result)
    split = _safe_split(split)
    task_id = str(runtime_result["task_id"])
    trajectory_id = f"trajectory-{task_id}"
    observations = runtime_result.get("observations") or []
    initial_observation = observations[0] if observations else {"page_state": "unknown", "nodes": []}
    task = {
        "task_id": task_id,
        "trajectory_id": trajectory_id,
        "split": split,
        "initial_state": {"page_state": initial_observation.get("page_state", "unknown")},
        "input": {"mode": runtime_result.get("mode", "simulated"), "scenario": runtime_result.get("scenario", "unknown")},
        "goal": {"page_state": "done"},
        "decision_payload": {
            "instruction": "Complete the simulated task.",
            "initial_observation": {
                "page": str(initial_observation.get("page_state", "unknown")),
                "visible_controls": [str(node.get("label")) for node in initial_observation.get("nodes", []) if isinstance(node, Mapping) and node.get("label")],
            },
            "available_actions": ["tap:start-button"],
        },
    }
    step = _runtime_step(runtime_result)
    trace = {
        "trajectory_id": trajectory_id,
        "task_id": task_id,
        "steps": [step] if step is not None else [],
    }
    truth_record = _truth_record_from_argument(truth, runtime_result, split)
    truth_record["trajectory_id"] = trajectory_id
    truth_record["task_id"] = task_id
    truth_record.pop("_split", None)
    tasks_document = {"format": "jev-independent-evaluation/tasks-v1", "dataset_id": "jev-task-evidence-runtime-v1", "tasks": [task]}
    traces_document = {"format": "jev-independent-evaluation/traces-v1", "dataset_id": "jev-task-evidence-runtime-v1", "trajectories": [trace]}
    truth_document = {"format": "jev-independent-evaluation/truth-v1", "dataset_id": "jev-task-evidence-runtime-v1", "records": [truth_record]}
    return tasks_document, traces_document, truth_document


def evaluate_runtime(
    runtime_result: Mapping[str, Any],
    *,
    truth: Mapping[str, Any] | None = None,
    split: str = "dev",
) -> dict[str, Any]:
    """Run task-02's independent evaluator against an adapted runtime trace."""

    tasks, traces, truth_document = build_evaluation_documents(runtime_result, truth=truth, split=split)
    try:
        with TemporaryDirectory(prefix="jev-task-evidence-") as directory:
            root = Path(directory)
            tasks_path = root / "tasks.json"
            traces_path = root / "traces.json"
            truth_path = root / "truth.json"
            tasks_path.write_text(json.dumps(tasks, ensure_ascii=False, sort_keys=True), encoding="utf-8")
            traces_path.write_text(json.dumps(traces, ensure_ascii=False, sort_keys=True), encoding="utf-8")
            truth_path.write_text(json.dumps(truth_document, ensure_ascii=False, sort_keys=True), encoding="utf-8")
            return evaluate_files(tasks_path, traces_path, truth_path, split=split)
    except (OSError, EvaluationInputError) as exc:
        raise EvidenceError(f"independent evaluator rejected runtime evidence: {exc}") from exc


def _usage_summary(usage: Any) -> dict[str, Any]:
    fields = {"input_tokens": None, "output_tokens": None, "total_tokens": None, "cached_input_tokens": None}
    if not isinstance(usage, Mapping):
        return {"status": "unknown", "reason": "usage_missing", **fields}
    for key in fields:
        value = usage.get(key)
        if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(float(value)) and value >= 0:
            fields[key] = value
    if fields["total_tokens"] is None and fields["input_tokens"] is not None and fields["output_tokens"] is not None:
        fields["total_tokens"] = fields["input_tokens"] + fields["output_tokens"]
        derived = True
    else:
        derived = False
    known = [value is not None for value in fields.values()]
    # ``cached_input_tokens`` is optional for providers; its absence should not
    # turn otherwise complete input/output usage into an unknown bill.  A
    # single token field is still explicitly partial and cannot be priced.
    if not any(known):
        status = "unknown"
        reason = "usage_missing"
    elif fields["input_tokens"] is not None and fields["output_tokens"] is not None:
        status = "known"
        reason = "provider_usage"
    else:
        status = "partial"
        reason = "usage_partial"
    return {"status": status, "reason": reason, "derived_total_tokens": derived, **fields}


class PriceBook:
    """Versioned local price configuration; it never fetches current prices."""

    def __init__(self, document: Mapping[str, Any]):
        value = dict(document)
        if value.get("format") != PRICE_BOOK_FORMAT:
            raise EvidenceError(f"price book format must be {PRICE_BOOK_FORMAT!r}")
        version = value.get("price_book_version")
        if not isinstance(version, str) or not version:
            raise EvidenceError("price book requires a non-empty price_book_version")
        if value.get("currency") != "USD":
            raise EvidenceError("only explicitly versioned USD price books are supported")
        models = value.get("models", {})
        if not isinstance(models, Mapping):
            raise EvidenceError("price book models must be an object")
        self.document = copy.deepcopy(value)
        self.version = version
        self.currency = "USD"
        self.models = copy.deepcopy(dict(models))

    @classmethod
    def from_path(cls, path: str | Path) -> "PriceBook":
        try:
            document = json.loads(Path(path).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise EvidenceError(f"cannot read price book {path}: {exc}") from exc
        if not isinstance(document, Mapping):
            raise EvidenceError("price book must contain a JSON object")
        return cls(document)

    def _rate(self, model: str) -> Mapping[str, Any] | None:
        candidate = self.models.get(model)
        return candidate if isinstance(candidate, Mapping) else None

    def cost(self, model: str, usage: Mapping[str, Any]) -> dict[str, Any]:
        rate = self._rate(model)
        if rate is None:
            return {"status": "unknown", "reason": "no_local_price_for_model", "currency": self.currency, "price_book_version": self.version, "amount": None}
        input_tokens = usage.get("input_tokens")
        output_tokens = usage.get("output_tokens")
        if not isinstance(input_tokens, (int, float)) or not isinstance(output_tokens, (int, float)):
            return {"status": "unknown", "reason": "usage_missing_for_price", "currency": self.currency, "price_book_version": self.version, "amount": None}
        input_rate = rate.get("input_usd_per_million")
        output_rate = rate.get("output_usd_per_million")
        if not isinstance(input_rate, (int, float)) or not isinstance(output_rate, (int, float)):
            return {"status": "unknown", "reason": "local_price_incomplete", "currency": self.currency, "price_book_version": self.version, "amount": None}
        amount = (float(input_tokens) * float(input_rate) + float(output_tokens) * float(output_rate)) / 1_000_000
        return {
            "status": "known",
            "reason": "local_versioned_price",
            "currency": self.currency,
            "price_book_version": self.version,
            "amount": round(amount, 12),
        }


def load_price_book(path: str | Path | None = None) -> PriceBook:
    path = Path(path) if path is not None else Path(__file__).with_name("pricing.json")
    return PriceBook.from_path(path)


def _latency_ms(start: Any, end: Any) -> float | None:
    if not isinstance(start, str) or not isinstance(end, str):
        return None
    try:
        first = _iso_timestamp(start, "start")
        last = _iso_timestamp(end, "end")
    except EvidenceIntegrityError:
        return None
    value = (last - first).total_seconds() * 1000
    return round(max(0.0, value), 3)


def _model_context(runtime_result: Mapping[str, Any], model_name: str | None, role: str | None) -> tuple[str, str]:
    context = runtime_result.get("evidence_context")
    context_map = context if isinstance(context, Mapping) else {}
    model = model_name or context_map.get("model") or "unknown"
    role_value = role or context_map.get("role") or "planner"
    return str(model), str(role_value)


def _public_attempt_records(runtime_result: Mapping[str, Any], *, model_name: str | None, role: str | None, price_book: PriceBook) -> list[dict[str, Any]]:
    model, default_role = _model_context(runtime_result, model_name, role)
    records: list[dict[str, Any]] = []
    action = runtime_result.get("action")
    source = action.get("source") if isinstance(action, Mapping) else None
    visual_fallback = source in _FALLBACK_SOURCES
    for raw_attempt in runtime_result.get("attempts", []):
        attempt = _mapping(raw_attempt, "attempt")
        usage = _usage_summary(attempt.get("usage"))
        outcome = str(attempt.get("outcome", "UNKNOWN"))
        cost = price_book.cost(model, usage)
        record = {
            "attempt_id": str(attempt.get("attempt_id")),
            "attempt_number": attempt.get("attempt"),
            "kind": "model",
            "role": str(attempt.get("role", default_role)),
            "model": model,
            "outcome": outcome,
            "status": "success" if outcome == "ACTION" else "failure" if outcome in {"ERROR", "MALFORMED"} else "unknown",
            "retry": bool(attempt.get("attempt", 1) > 1),
            "call_made": True,
            "prompt_present": bool((_mapping(attempt.get("request"), "attempt.request")).get("prompt")),
            "image_count": len((_mapping(attempt.get("request"), "attempt.request")).get("images", [])),
            "usage": usage,
            "latency_ms": _latency_ms(attempt.get("started_at"), attempt.get("completed_at")),
            "cost": cost,
        }
        error = attempt.get("error")
        if isinstance(error, Mapping):
            # Error codes are operational evidence; the provider message may
            # contain prompts, URLs, or credential-adjacent details and stays
            # in the controlled raw result.
            if isinstance(error.get("code"), str) and error["code"]:
                record["error_code"] = error["code"]
            if isinstance(error.get("retryable"), bool):
                record["retryable"] = error["retryable"]
        records.append(record)

    if visual_fallback and records:
        # A fallback may itself be model-backed (for example a visual fallback
        # after a closed-set selector failed).  Keep the model call and mark
        # only the terminal fallback attempt so it is counted once.
        records[-1]["fallback"] = True
        records[-1]["fallback_reason"] = "runtime_action_source"
    if not records and source in _FALLBACK_SOURCES:
        unknown_usage = _usage_summary(None)
        records.append(
            {
                "attempt_id": f"fallback-{runtime_result['task_id']}",
                "attempt_number": 1,
                "kind": "fallback",
                "role": default_role,
                "model": "none",
                "outcome": "FALLBACK",
                "status": "success" if runtime_result.get("state") == "SUCCEEDED" else "failure",
                "retry": False,
                "call_made": False,
                "synthetic": True,
                "reason": "existing_runtime_default_action",
                "prompt_present": False,
                "image_count": 0,
                "usage": unknown_usage,
                "latency_ms": None,
                "cost": {"status": "unknown", "reason": "no_model_call", "currency": price_book.currency, "price_book_version": price_book.version, "amount": None},
            }
        )
    if runtime_result.get("state") == "CANCELLED":
        records.append(
            {
                "attempt_id": f"control-cancel-{runtime_result['task_id']}",
                "attempt_number": None,
                "kind": "control",
                "control": "cancel",
                "role": "system",
                "model": "none",
                "outcome": "CANCELLED",
                "status": "cancelled",
                "retry": False,
                "call_made": False,
                "usage": _usage_summary(None),
                "latency_ms": None,
                "cost": {"status": "unknown", "reason": "control_event", "currency": price_book.currency, "price_book_version": price_book.version, "amount": None},
            }
        )
    if runtime_result.get("state") == "FAILED" and not records:
        failure = runtime_result.get("failure") if isinstance(runtime_result.get("failure"), Mapping) else {}
        records.append(
            {
                "attempt_id": f"failure-{runtime_result['task_id']}",
                "attempt_number": None,
                "kind": "terminal",
                "role": "system",
                "model": "none",
                "outcome": "FAILURE",
                "status": "failure",
                "retry": False,
                "call_made": False,
                "reason": str(failure.get("code", "task_failed")),
                "usage": _usage_summary(None),
                "latency_ms": None,
                "cost": {"status": "unknown", "reason": "no_model_call", "currency": price_book.currency, "price_book_version": price_book.version, "amount": None},
            }
        )
    if runtime_result.get("state") == "PAUSED" and not records:
        records.append(
            {
                "attempt_id": f"control-pause-{runtime_result['task_id']}",
                "attempt_number": None,
                "kind": "control",
                "control": "pause",
                "role": "system",
                "model": "none",
                "outcome": "PAUSED",
                "status": "unknown",
                "retry": False,
                "call_made": False,
                "usage": _usage_summary(None),
                "latency_ms": None,
                "cost": {"status": "unknown", "reason": "control_event", "currency": price_book.currency, "price_book_version": price_book.version, "amount": None},
            }
        )
    return records


def _sum_numeric(records: Iterable[Mapping[str, Any]], key: str) -> float | int | None:
    values = [record.get("usage", {}).get(key) for record in records if isinstance(record.get("usage"), Mapping) and record.get("usage", {}).get(key) is not None]
    if not values:
        return None
    total = sum(values)
    return int(total) if all(isinstance(value, int) for value in values) else total


def _attempt_report(records: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "total": len(records),
        "model_calls": sum(record["kind"] == "model" for record in records),
        "fallbacks": sum(record["kind"] == "fallback" or bool(record.get("fallback")) for record in records),
        "controls": sum(record["kind"] == "control" for record in records),
        "terminal_failures": sum(record["kind"] == "terminal" for record in records),
        "retries": sum(bool(record.get("retry")) for record in records),
        "successful": sum(record.get("status") == "success" for record in records),
        "failed": sum(record.get("status") == "failure" for record in records),
        "cancelled": sum(record.get("status") == "cancelled" for record in records),
        "records": records,
    }


def _usage_report(records: list[dict[str, Any]], price_book: PriceBook) -> dict[str, Any]:
    model_records = [record for record in records if record.get("kind") == "model"]
    grouped: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for record in model_records:
        grouped.setdefault((str(record["role"]), str(record["model"])), []).append(record)
    by_role_model: list[dict[str, Any]] = []
    for (role, model), items in sorted(grouped.items()):
        usage_values = {
            key: _sum_numeric(items, key)
            for key in ("input_tokens", "output_tokens", "total_tokens", "cached_input_tokens")
        }
        item_statuses = [str(item.get("usage", {}).get("status", "unknown")) for item in items]
        if all(status == "known" for status in item_statuses):
            usage_status = "known"
        elif all(status == "unknown" for status in item_statuses):
            usage_status = "unknown"
        else:
            usage_status = "partial"
        costs = [item["cost"] for item in items]
        known_costs = [float(cost["amount"]) for cost in costs if cost.get("status") == "known" and cost.get("amount") is not None]
        cost = {
            "status": "known" if len(known_costs) == len(costs) and costs else "unknown",
            "reason": "local_versioned_price" if len(known_costs) == len(costs) and costs else "one_or_more_attempts_unknown",
            "currency": price_book.currency,
            "price_book_version": price_book.version,
            "amount": round(sum(known_costs), 12) if len(known_costs) == len(costs) and costs else None,
        }
        by_role_model.append({"role": role, "model": model, "attempts": len(items), "usage_status": usage_status, "unknown_usage_attempts": sum(status != "known" for status in item_statuses), **usage_values, "cost": cost})
    all_statuses = [str(record.get("usage", {}).get("status", "unknown")) for record in model_records]
    if not all_statuses or all(status == "unknown" for status in all_statuses):
        overall_status = "unknown"
    elif all(status == "known" for status in all_statuses):
        overall_status = "known"
    else:
        overall_status = "partial"
    return {
        "by_role_model": by_role_model,
        "status": overall_status,
        "input_tokens": _sum_numeric(model_records, "input_tokens"),
        "output_tokens": _sum_numeric(model_records, "output_tokens"),
        "total_tokens": _sum_numeric(model_records, "total_tokens"),
        "cached_input_tokens": _sum_numeric(model_records, "cached_input_tokens"),
        "price_book_version": price_book.version,
    }


def _cost_report(records: list[dict[str, Any]], price_book: PriceBook) -> dict[str, Any]:
    known = [record["cost"]["amount"] for record in records if record.get("cost", {}).get("status") == "known" and record["cost"].get("amount") is not None]
    has_unknown = any(record.get("cost", {}).get("status") != "known" for record in records)
    return {
        "status": "unknown" if has_unknown else "known",
        "reason": "one_or_more_attempts_unknown" if has_unknown else "local_versioned_price",
        "currency": price_book.currency,
        "price_book_version": price_book.version,
        "known_amount": round(sum(float(value) for value in known), 12) if known else 0.0,
        "amount": None if has_unknown else round(sum(float(value) for value in known), 12),
    }


def _latency_report(runtime_result: Mapping[str, Any], records: list[dict[str, Any]]) -> dict[str, Any]:
    trace = runtime_result.get("trace", [])
    overall = _latency_ms(trace[0].get("recorded_at"), trace[-1].get("recorded_at")) if trace else None
    by_role: dict[str, dict[str, Any]] = {}
    by_role_model: dict[tuple[str, str], dict[str, Any]] = {}
    for record in records:
        role = str(record.get("role", "unknown"))
        item = by_role.setdefault(role, {"known_ms": 0.0, "known_count": 0, "unknown_count": 0})
        role_model = by_role_model.setdefault(
            (role, str(record.get("model", "unknown"))),
            {"role": role, "model": str(record.get("model", "unknown")), "known_ms": 0.0, "known_count": 0, "unknown_count": 0},
        )
        latency = record.get("latency_ms")
        if latency is None:
            item["unknown_count"] += 1
            role_model["unknown_count"] += 1
        else:
            item["known_ms"] = round(item["known_ms"] + float(latency), 3)
            item["known_count"] += 1
            role_model["known_ms"] = round(role_model["known_ms"] + float(latency), 3)
            role_model["known_count"] += 1
    return {
        "overall_ms": overall,
        "overall_status": "known" if overall is not None else "unknown",
        "by_role": by_role,
        "by_role_model": [by_role_model[key] for key in sorted(by_role_model)],
    }


def _safe_evaluation(evaluation: Mapping[str, Any]) -> dict[str, Any]:
    safe_results: list[dict[str, Any]] = []
    for raw in evaluation.get("results", []):
        result = _mapping(raw, "evaluation result")
        steps = []
        for raw_step in result.get("steps", []):
            step = _mapping(raw_step, "evaluation step")
            outcome = _mapping(step.get("action_verification"), "evaluation outcome")
            steps.append(
                {
                    "step_id": step.get("step_id"),
                    "status": outcome.get("status"),
                    "reason": outcome.get("reason"),
                    "failure_class": outcome.get("failure_class"),
                    "trace_present": outcome.get("trace_present"),
                }
            )
        completion = _mapping(result.get("task_completion"), "evaluation completion")
        safe_results.append(
            {
                "task_id": result.get("task_id"),
                "trajectory_id": result.get("trajectory_id"),
                "split": result.get("split"),
                "trace_step_count": result.get("trace_step_count"),
                "steps": steps,
                "task_completion": {
                    "status": completion.get("status"),
                    "reason": completion.get("reason"),
                    "failure_class": completion.get("failure_class"),
                },
                "environment_status": (result.get("environment") or {}).get("status"),
            }
        )
    return {
        "report_version": evaluation.get("report_version"),
        "selected_split": evaluation.get("selected_split"),
        "counts": copy.deepcopy(evaluation.get("counts", {})),
        "integrity": copy.deepcopy(evaluation.get("integrity", {})),
        "results": safe_results,
    }


def _safe_trace(runtime_result: Mapping[str, Any]) -> list[dict[str, Any]]:
    entity_aliases: dict[tuple[str, str], str] = {}

    def anonymous_entity(entity_kind: str, entity_id: str) -> str:
        key = (entity_kind, entity_id)
        if key not in entity_aliases:
            prefix = {
                "task": "task",
                "observation": "observation",
                "action": "action",
                "receipt": "receipt",
                "verification": "verification",
                "model_request": "model-request",
                "model_attempt": "model-attempt",
            }[entity_kind]
            entity_aliases[key] = f"{prefix}-{sum(kind == entity_kind for kind, _ in entity_aliases) + 1}"
        return entity_aliases[key]

    safe: list[dict[str, Any]] = []
    for index, event in enumerate(runtime_result.get("trace", []), start=1):
        entity_kind = str(event["entity_kind"])
        safe.append(
            {
                "event_id": f"event-{index}",
                "sequence": index - 1,
                "kind": event["kind"],
                "entity_id": anonymous_entity(entity_kind, str(event["entity_id"])),
                "entity_kind": entity_kind,
                "previous_event_id": None if index == 1 else f"event-{index - 1}",
                "recorded_at": event["recorded_at"],
            }
        )
    return safe


def build_report(
    runtime_result: Mapping[str, Any],
    *,
    truth: Mapping[str, Any] | None = None,
    split: str = "dev",
    price_book: PriceBook | None = None,
    model_name: str | None = None,
    role: str | None = None,
) -> dict[str, Any]:
    """Return a deterministic, public-safe report for one runtime result."""

    validate_runtime_result(runtime_result)
    price_book = price_book or load_price_book()
    evaluation = evaluate_runtime(runtime_result, truth=truth, split=split)
    records = _public_attempt_records(runtime_result, model_name=model_name, role=role, price_book=price_book)
    evaluation_result = evaluation.get("results", [{}])[0]
    completion = evaluation_result.get("task_completion", {})
    report = {
        "report_version": EVIDENCE_REPORT_VERSION,
        "public_report_version": PUBLIC_REPORT_VERSION,
        "versions": {
            "evidence_input": EVIDENCE_INPUT_VERSION,
            "runtime_contract": RUNTIME_SCHEMA_VERSION,
            "independent_evaluation": evaluation.get("report_version"),
            "price_book": price_book.version,
            "redaction": PUBLIC_REDACTION_VERSION,
        },
        "run": {
            "task_id": runtime_result["task_id"],
            "state": runtime_result["state"],
            "mode": runtime_result.get("mode"),
            "scenario": runtime_result.get("scenario"),
            "independent_status": completion.get("status"),
            "independent_reason": completion.get("reason"),
            "runtime_result_sha256": _runtime_hash(runtime_result),
        },
        "attempts": _attempt_report(records),
        "usage": _usage_report(records, price_book),
        "cost": _cost_report(records, price_book),
        "latency": _latency_report(runtime_result, records),
        "independent_evaluation": _safe_evaluation(evaluation),
        "trace": _safe_trace(runtime_result),
        "integrity": {
            "runtime_trace_validated": True,
            "missing_key_events_reject_success": True,
            "independent_evaluation_used": True,
            "public_whitelist_applied": True,
            "raw_payload_included": False,
        },
        "environment": {
            "execution": "simulated",
            "python": f"{sys.version_info.major}.{sys.version_info.minor}",
            "evidence": "offline_localhost_only",
        },
    }
    return report


def render_report(report: Mapping[str, Any]) -> str:
    """Render stable JSON suitable for a checked-in or attached public report."""

    return json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
