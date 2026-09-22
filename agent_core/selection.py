"""Closed-set candidate selection and deterministic offline replay.

The selection pipeline intentionally has three boundaries:

* :func:`build_candidates` sees only the current observation and known user or
  template parameters.  It cannot see an evaluator truth document or a future
  observation.
* :class:`ReplaySelector` can select an existing candidate by ID, but it cannot
  create an action or text that was not already present in that candidate set.
* :class:`DeterministicEffectSimulator` applies explicit fixture rules.  It is
  an offline device stand-in and is never installed into ``services.sim_loop``.

The dataset runner adapts its generated trace and external truth to the
already-accepted ``eval.engine`` format in memory.  ``eval/`` remains the
single independent report engine and is not modified by this package.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping

from .common import (
    OfflineInputError,
    assert_decision_truth_free,
    deep_copy,
    evaluate_independent_documents,
    get_path,
    load_json,
    render_json,
    require_bool,
    require_enum,
    require_integer,
    require_list,
    require_mapping,
    require_string,
    set_path,
    sha256_json,
    stable_unique,
)


SELECTION_CASES_FORMAT = "jev-offline-selection/cases-v1"
SELECTION_REPLAY_FORMAT = "jev-offline-selection/replay-v1"
SELECTION_TRUTH_FORMAT = "jev-offline-selection/truth-v1"
SELECTION_REPORT_VERSION = "jev-offline-selection/report-v1"
SELECTION_POLICY_VERSION = "jev-offline-selection-policy-v1"
SELECTION_FREEZE_MANIFEST_FORMAT = "jev-offline-freeze-manifest-v1"
SELECTION_POLICY_ARTIFACT_ID = "jev-offline-selection-policy-v1"
SELECTION_REPLAY_ARTIFACT_ID = "jev-offline-selection-replay-v1"
SELECTION_OFFLINE_SCOPE = "offline-fixture-only"
# This compact, source-controlled policy artifact is what the freeze manifest
# identifies.  The implementation must validate this identity instead of
# treating the policy version string as proof that the policy was frozen.
SELECTION_POLICY_ARTIFACT = {
    "artifact_id": SELECTION_POLICY_ARTIFACT_ID,
    "policy_version": SELECTION_POLICY_VERSION,
    "candidate_source": "current_observation_and_known_parameters",
    "supported_action_kinds": ["back", "input_text", "long_press", "scroll", "tap"],
    "observation_clock": "required_for_expiry",
}
# Filled from the canonical replay fixture after the fixture is updated.  It
# is intentionally a code-level value as well as a document manifest value:
# changing a replay label requires an explicit re-freeze in source control.
SELECTION_FROZEN_REPLAY_SHA256 = "bed96e0a22d7eae282228d269ea8051c183f10e165ac8e020a3ce70268c95496"
SELECTION_SPLITS = frozenset({"dev", "holdout"})
ACTION_STATUSES = frozenset({"SUCCESS", "FAILURE", "PENDING", "UNKNOWN"})
FAILURE_CLASSES = frozenset({"agent", "environment"})

SelectionInputError = OfflineInputError


@dataclass(frozen=True)
class CandidateBuild:
    """Result of constructing complete candidates from one observation."""

    observation_id: str
    candidates: tuple[dict[str, Any], ...]
    fallback: dict[str, Any] | None
    warnings: tuple[dict[str, Any], ...] = ()

    @property
    def complete(self) -> bool:
        return bool(self.candidates)


@dataclass(frozen=True)
class SelectionDecision:
    """A replay selection or an explicit safe fallback."""

    status: str
    candidate: dict[str, Any] | None
    fallback: dict[str, Any] | None
    reason: str


def _language_for_label(label: str) -> str:
    if any("\u4e00" <= char <= "\u9fff" for char in label):
        return "zh-CN"
    return "en"


def _canonical_candidate_seed(kind: str, target_node_id: str, parameters: Mapping[str, Any]) -> str:
    encoded = json.dumps(
        {"kind": kind, "target_node_id": target_node_id, "parameters": parameters},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()[:12]


def _fallback(reason: str, observation_id: str, **details: Any) -> dict[str, Any]:
    result: dict[str, Any] = {
        "kind": "VISUAL_FALLBACK",
        "reason": reason,
        "observation_id": observation_id,
        "requires_generated_text": False,
    }
    result.update(details)
    return result


def _validate_observation(
    observation: Mapping[str, Any], now: int | None
) -> tuple[str, int, str | None]:
    observation_id = require_string(observation.get("observation_id"), "observation.observation_id")
    version = require_integer(observation.get("version"), "observation.version")
    if version < 1:
        raise SelectionInputError("observation.version must be positive")
    captured_at = require_integer(observation.get("captured_at"), "observation.captured_at")
    expires_at = require_integer(observation.get("expires_at"), "observation.expires_at")
    if expires_at < captured_at:
        raise SelectionInputError("observation.expires_at must be >= captured_at")
    require_string(observation.get("page"), "observation.page")
    nodes = require_list(observation.get("nodes"), "observation.nodes")
    for index, raw_node in enumerate(nodes):
        node = require_mapping(raw_node, f"observation.nodes[{index}]")
        require_string(node.get("node_id"), f"observation.nodes[{index}].node_id")
        require_string(node.get("role"), f"observation.nodes[{index}].role")
        require_string(node.get("label"), f"observation.nodes[{index}].label")
        require_bool(node.get("enabled"), f"observation.nodes[{index}].enabled")
    if now is not None:
        now = require_integer(now, "now")
    validity_error = "observation_clock_unavailable" if now is None else None
    if now is not None and now > expires_at:
        validity_error = "observation_expired"
    elif now is not None and now < captured_at:
        validity_error = "observation_from_future"
    return observation_id, version, validity_error


def _normalize_action_specs(node: Mapping[str, Any], path: str) -> list[dict[str, Any]]:
    raw_specs = node.get("actions", node.get("action_kinds"))
    if raw_specs is None:
        role = str(node.get("role", ""))
        if role in {"button", "checkbox", "switch", "link", "icon_button"}:
            raw_specs = ["tap"]
        elif role in {"text_field", "edit_text", "search_field"}:
            raw_specs = [{"kind": "input_text", "parameter": node.get("parameter", "text")}]
        else:
            raw_specs = []
    if not isinstance(raw_specs, list):
        raise SelectionInputError(f"{path}.actions must be an array")
    specs: list[dict[str, Any]] = []
    for index, raw_spec in enumerate(raw_specs):
        if isinstance(raw_spec, str):
            specs.append({"kind": require_string(raw_spec, f"{path}.actions[{index}]")})
        else:
            spec = require_mapping(raw_spec, f"{path}.actions[{index}]")
            kind = require_string(spec.get("kind"), f"{path}.actions[{index}].kind")
            specs.append(dict(spec, kind=kind))
    return specs


def _parameter_for_spec(
    kind: str, spec: Mapping[str, Any], node: Mapping[str, Any], known_parameters: Mapping[str, Any]
) -> tuple[dict[str, Any] | None, str | None]:
    """Return action parameters, or the missing known parameter name."""

    if kind in {"tap", "back"}:
        return {}, None
    parameter_name = spec.get("parameter", node.get("parameter"))
    if kind == "input_text":
        parameter_name = parameter_name or "text"
        if parameter_name not in known_parameters:
            return None, str(parameter_name)
        value = known_parameters[parameter_name]
        if not isinstance(value, str) or not value:
            return None, str(parameter_name)
        return {"text": value}, None
    if kind == "long_press":
        parameter_name = parameter_name or "duration_ms"
        if parameter_name not in known_parameters and "duration_ms" not in spec:
            return None, str(parameter_name)
        duration = spec.get("duration_ms", known_parameters.get(parameter_name))
        if isinstance(duration, bool) or not isinstance(duration, int) or duration <= 0:
            return None, str(parameter_name)
        return {"duration_ms": duration}, None
    if kind == "scroll":
        parameter_name = parameter_name or "direction"
        if parameter_name not in known_parameters and "direction" not in spec:
            return None, str(parameter_name)
        direction = spec.get("direction", known_parameters.get(parameter_name))
        if not isinstance(direction, str) or direction not in {"up", "down", "left", "right"}:
            return None, str(parameter_name)
        return {"direction": direction}, None
    return None, None


def build_candidates(
    observation: Mapping[str, Any],
    known_parameters: Mapping[str, Any],
    *,
    now: int | None = None,
) -> CandidateBuild:
    """Build complete, executable candidates from one current observation.

    ``known_parameters`` is intentionally a mapping supplied by the user or a
    predeclared template.  The function never derives free text from a
    candidate ID and never reads a future/reference field.
    """

    observation = require_mapping(observation, "observation")
    known_parameters = require_mapping(known_parameters, "known_parameters")
    assert_decision_truth_free(observation, "observation")
    assert_decision_truth_free(known_parameters, "known_parameters")
    observation_id, version, validity_error = _validate_observation(observation, now)
    if validity_error is not None:
        return CandidateBuild(
            observation_id=observation_id,
            candidates=(),
            fallback=_fallback(validity_error, observation_id),
        )

    nodes = require_list(observation.get("nodes"), "observation.nodes")
    candidates: list[dict[str, Any]] = []
    warnings: list[dict[str, Any]] = []
    missing_parameters: list[str] = []
    used_ids: set[str] = set()
    supported = {"tap", "input_text", "long_press", "scroll", "back"}

    for node_index, raw_node in enumerate(nodes):
        node = require_mapping(raw_node, f"observation.nodes[{node_index}]")
        if not node["enabled"]:
            continue
        specs = _normalize_action_specs(node, f"observation.nodes[{node_index}]")
        for spec_index, spec in enumerate(specs):
            kind = spec["kind"]
            if kind not in supported:
                warnings.append(
                    {
                        "node_id": node["node_id"],
                        "kind": kind,
                        "reason": "unsupported_action_kind",
                    }
                )
                continue
            parameters, missing = _parameter_for_spec(kind, spec, node, known_parameters)
            if parameters is None:
                if missing is not None:
                    missing_parameters.append(str(missing))
                    warnings.append(
                        {
                            "node_id": node["node_id"],
                            "kind": kind,
                            "reason": "missing_required_parameter",
                            "parameter": str(missing),
                        }
                    )
                continue

            explicit_id = spec.get("candidate_id", node.get("candidate_id"))
            if explicit_id is not None:
                candidate_id = require_string(
                    explicit_id,
                    f"observation.nodes[{node_index}].actions[{spec_index}].candidate_id",
                )
            else:
                seed = _canonical_candidate_seed(kind, node["node_id"], parameters)
                candidate_id = f"candidate-{kind}-{node['node_id']}-{seed}"
            if candidate_id in used_ids:
                suffix = 2
                original = candidate_id
                while candidate_id in used_ids:
                    candidate_id = f"{original}-{suffix}"
                    suffix += 1
            used_ids.add(candidate_id)

            label = node["label"]
            accepted_labels = spec.get("accepted_labels", node.get("accepted_labels", [label]))
            accepted_labels = require_list(
                accepted_labels,
                f"observation.nodes[{node_index}].accepted_labels",
            )
            normalized_labels = [
                require_string(item, f"observation.nodes[{node_index}].accepted_labels[{index}]")
                for index, item in enumerate(accepted_labels)
            ]
            normalized_labels = stable_unique(normalized_labels)
            if label not in normalized_labels:
                normalized_labels.insert(0, label)
            languages = spec.get("languages", node.get("languages"))
            if languages is None:
                languages = stable_unique(_language_for_label(item) for item in normalized_labels)
            else:
                languages = [
                    require_string(item, f"observation.nodes[{node_index}].languages[{index}]")
                    for index, item in enumerate(require_list(languages, "candidate.languages"))
                ]
                languages = stable_unique(languages)
            group = spec.get(
                "equivalence_group",
                node.get("equivalence_group", f"node:{node['node_id']}"),
            )
            group = require_string(group, "candidate.accepted_action_set.group")
            action = {
                "schema_version": "offline-action-v1",
                "candidate_id": candidate_id,
                "observation_id": observation_id,
                "observation_version": version,
                "kind": kind,
                "target_node_id": node["node_id"],
                "parameters": deep_copy(parameters),
                "source": "known-template",
            }
            candidate = {
                "candidate_id": candidate_id,
                "action": action,
                "accepted_action_set": {
                    "group": group,
                    "equivalent": len(normalized_labels) > 1,
                    "labels": normalized_labels,
                    "languages": languages,
                },
            }
            candidates.append(candidate)

    fallback = None
    if not candidates:
        if missing_parameters:
            reason = "missing_required_parameter"
        elif warnings and all(item["reason"] == "unsupported_action_kind" for item in warnings):
            reason = "unsupported_action_kind"
        else:
            reason = "empty_candidates"
        fallback = _fallback(
            reason,
            observation_id,
            missing_parameters=stable_unique(missing_parameters),
        )
    return CandidateBuild(
        observation_id=observation_id,
        candidates=tuple(candidates),
        fallback=fallback,
        warnings=tuple(warnings),
    )


class ReplaySelector:
    """Select by a pre-recorded candidate ID without inventing an action."""

    def __init__(self, selections: Mapping[str, Any] | None = None):
        self._selections = dict(selections or {})

    @classmethod
    def from_document(cls, document: Mapping[str, Any]) -> "ReplaySelector":
        document = require_mapping(document, "replay")
        if document.get("format") != SELECTION_REPLAY_FORMAT:
            raise SelectionInputError(f"replay must use format {SELECTION_REPLAY_FORMAT!r}")
        if document.get("scope") != SELECTION_OFFLINE_SCOPE:
            raise SelectionInputError(f"replay.scope must be {SELECTION_OFFLINE_SCOPE!r}")
        if document.get("policy_version", SELECTION_POLICY_VERSION) != SELECTION_POLICY_VERSION:
            raise SelectionInputError("replay policy_version does not match the frozen selection policy")
        if document.get("artifact_id") != SELECTION_REPLAY_ARTIFACT_ID:
            raise SelectionInputError("replay artifact_id does not match the frozen selection replay")
        if SELECTION_FROZEN_REPLAY_SHA256 == "__selection_replay_hash_pending__":
            raise SelectionInputError("selection replay freeze hash is not configured")
        actual_hash = sha256_json(document)
        if actual_hash != SELECTION_FROZEN_REPLAY_SHA256:
            raise SelectionInputError("replay artifact hash does not match the frozen selection replay")
        records = require_list(document.get("selections"), "replay.selections")
        selections: dict[str, Any] = {}
        for index, raw_record in enumerate(records):
            record = require_mapping(raw_record, f"replay.selections[{index}]")
            case_id = require_string(record.get("case_id"), f"replay.selections[{index}].case_id")
            candidate_id = record.get("candidate_id")
            if candidate_id is not None and not isinstance(candidate_id, str):
                raise SelectionInputError(
                    f"replay.selections[{index}].candidate_id must be a string or null"
                )
            if case_id in selections:
                raise SelectionInputError(f"duplicate replay case_id {case_id!r}")
            selections[case_id] = candidate_id
        return cls(selections)

    def select(
        self,
        case_id: str,
        build: CandidateBuild,
    ) -> SelectionDecision:
        requested = self._selections.get(case_id, None)
        if build.fallback is not None:
            return SelectionDecision("FALLBACK", None, deep_copy(build.fallback), build.fallback["reason"])
        if case_id not in self._selections:
            fallback = _fallback("replay_selection_missing", build.observation_id)
            return SelectionDecision("FALLBACK", None, fallback, "replay_selection_missing")
        if requested is None or not isinstance(requested, str) or not requested:
            fallback = _fallback("invalid_candidate_id", build.observation_id, candidate_id=requested)
            return SelectionDecision("FALLBACK", None, fallback, "invalid_candidate_id")
        by_id = {candidate["candidate_id"]: candidate for candidate in build.candidates}
        candidate = by_id.get(requested)
        if candidate is None:
            fallback = _fallback(
                "invalid_candidate_id",
                build.observation_id,
                candidate_id=requested,
                available_candidate_ids=sorted(by_id),
            )
            return SelectionDecision("FALLBACK", None, fallback, "invalid_candidate_id")
        return SelectionDecision("SELECTED", deep_copy(candidate), None, "candidate_selected")


@dataclass(frozen=True)
class SimulationResult:
    before: dict[str, Any]
    after: dict[str, Any]
    receipt: dict[str, Any]
    changed: bool


class DeterministicEffectSimulator:
    """Apply explicit fixture rules to a JSON-like state."""

    def apply(
        self,
        initial_state: Mapping[str, Any],
        action: Mapping[str, Any],
        effect_rules: Iterable[Mapping[str, Any]],
    ) -> SimulationResult:
        before = deep_copy(dict(initial_state))
        after = deep_copy(before)
        kind = require_string(action.get("kind"), "action.kind")
        target = require_string(action.get("target_node_id"), "action.target_node_id")
        action_id = require_string(action.get("candidate_id"), "action.candidate_id")
        matched: Mapping[str, Any] | None = None
        for index, raw_rule in enumerate(effect_rules):
            rule = require_mapping(raw_rule, f"effect_rules[{index}]")
            if rule.get("kind") == kind and rule.get("target_node_id") == target:
                matched = rule
                break
        if matched is None:
            receipt = {
                "schema_version": "offline-receipt-v1",
                "action_id": action_id,
                "accepted": True,
                "outcome": "EXECUTED",
                "effect": "NO_EFFECT",
            }
            return SimulationResult(before, after, receipt, False)

        outcome = matched.get("outcome", "EXECUTED")
        if outcome not in {"EXECUTED", "REJECTED", "ERROR"}:
            raise SelectionInputError("effect_rules.outcome must be EXECUTED, REJECTED, or ERROR")
        accepted = outcome == "EXECUTED"
        if accepted:
            patch = matched.get("patch", {})
            patch = require_mapping(patch, "effect_rules.patch")
            for path, value in patch.items():
                if not isinstance(path, str) or not path:
                    raise SelectionInputError("effect_rules.patch keys must be non-empty strings")
                if isinstance(value, Mapping) and "$parameter" in value:
                    parameter_name = require_string(value["$parameter"], "effect_rules.$parameter")
                    parameters = require_mapping(action.get("parameters"), "action.parameters")
                    if parameter_name not in parameters:
                        raise SelectionInputError(
                            f"action.parameters is missing simulator parameter {parameter_name!r}"
                        )
                    value = parameters[parameter_name]
                set_path(after, path, value)
            if matched.get("set_parameter") is not None:
                parameter_name = require_string(matched["set_parameter"], "effect_rules.set_parameter")
                destination = require_string(matched.get("state_path"), "effect_rules.state_path")
                parameters = require_mapping(action.get("parameters"), "action.parameters")
                if parameter_name not in parameters:
                    raise SelectionInputError(
                        f"action.parameters is missing simulator parameter {parameter_name!r}"
                    )
                set_path(after, destination, parameters[parameter_name])
        receipt = {
            "schema_version": "offline-receipt-v1",
            "action_id": action_id,
            "accepted": accepted,
            "outcome": outcome,
            "effect": "APPLIED" if accepted else "NO_EFFECT",
        }
        return SimulationResult(before, after, receipt, before != after)


def _selection_document(value: str | Path | Mapping[str, Any], name: str) -> dict[str, Any]:
    if isinstance(value, (str, Path)):
        return load_json(value)
    return require_mapping(value, name)


def _validate_freeze_manifest(
    cases_document: Mapping[str, Any], replay_document: Mapping[str, Any]
) -> dict[str, Any]:
    manifest = require_mapping(cases_document.get("freeze_manifest"), "cases.freeze_manifest")
    if manifest.get("format") != SELECTION_FREEZE_MANIFEST_FORMAT:
        raise SelectionInputError(
            f"cases.freeze_manifest must use format {SELECTION_FREEZE_MANIFEST_FORMAT!r}"
        )
    if manifest.get("scope") != SELECTION_OFFLINE_SCOPE:
        raise SelectionInputError(
            f"cases.freeze_manifest.scope must be {SELECTION_OFFLINE_SCOPE!r}"
        )
    policy = require_mapping(manifest.get("policy"), "cases.freeze_manifest.policy")
    replay = require_mapping(manifest.get("replay"), "cases.freeze_manifest.replay")
    policy_id = require_string(policy.get("artifact_id"), "cases.freeze_manifest.policy.artifact_id")
    replay_id = require_string(replay.get("artifact_id"), "cases.freeze_manifest.replay.artifact_id")
    policy_hash = require_string(policy.get("sha256"), "cases.freeze_manifest.policy.sha256")
    replay_hash = require_string(replay.get("sha256"), "cases.freeze_manifest.replay.sha256")
    if policy_id != SELECTION_POLICY_ARTIFACT_ID:
        raise SelectionInputError("selection policy artifact_id does not match the frozen policy")
    if replay_id != SELECTION_REPLAY_ARTIFACT_ID:
        raise SelectionInputError("selection replay artifact_id does not match the frozen replay")
    actual_policy_hash = sha256_json(SELECTION_POLICY_ARTIFACT)
    if policy_hash != actual_policy_hash:
        raise SelectionInputError("selection policy hash does not match the frozen policy artifact")
    actual_replay_hash = sha256_json(replay_document)
    if replay_hash != actual_replay_hash or replay_hash != SELECTION_FROZEN_REPLAY_SHA256:
        raise SelectionInputError("selection replay hash does not match the frozen selection replay artifact")
    if replay_document.get("artifact_id") != replay_id:
        raise SelectionInputError("replay artifact_id does not match cases.freeze_manifest")
    return {
        "format": SELECTION_FREEZE_MANIFEST_FORMAT,
        "scope": SELECTION_OFFLINE_SCOPE,
        "policy": {
            "artifact_id": policy_id,
            "sha256": actual_policy_hash,
            "verified": True,
        },
        "replay": {
            "artifact_id": replay_id,
            "sha256": actual_replay_hash,
            "verified": True,
        },
    }


def _validate_split_identities(records: Iterable[Mapping[str, Any]], source: str) -> bool:
    identities: dict[str, dict[str, set[str]]] = {
        "task_id": {},
        "trajectory_id": {},
    }
    for index, record in enumerate(records):
        split = require_enum(record.get("split"), SELECTION_SPLITS, f"{source}[{index}].split")
        for field in identities:
            value = require_string(record.get(field), f"{source}[{index}].{field}")
            splits = identities[field].setdefault(value, set())
            splits.add(split)
            if len(splits) > 1:
                raise SelectionInputError(
                    f"{source} {field} {value!r} crosses dev/holdout splits: {sorted(splits)}"
                )
    return True


def _validate_cases(document: Mapping[str, Any]) -> tuple[dict[str, Any], ...]:
    if document.get("format") != SELECTION_CASES_FORMAT:
        raise SelectionInputError(f"cases must use format {SELECTION_CASES_FORMAT!r}")
    if document.get("scope") != SELECTION_OFFLINE_SCOPE:
        raise SelectionInputError(f"cases.scope must be {SELECTION_OFFLINE_SCOPE!r}")
    cases = require_list(document.get("cases"), "cases.cases")
    normalized: list[dict[str, Any]] = []
    seen: set[str] = set()
    for index, raw_case in enumerate(cases):
        path = f"cases.cases[{index}]"
        case = require_mapping(raw_case, path)
        case_id = require_string(case.get("case_id"), f"{path}.case_id")
        if case_id in seen:
            raise SelectionInputError(f"duplicate case_id {case_id!r}")
        seen.add(case_id)
        require_string(case.get("task_id"), f"{path}.task_id")
        require_string(case.get("trajectory_id"), f"{path}.trajectory_id")
        require_enum(case.get("split"), SELECTION_SPLITS, f"{path}.split")
        require_string(case.get("instruction"), f"{path}.instruction")
        observation = require_mapping(case.get("observation"), f"{path}.observation")
        known_parameters = require_mapping(case.get("known_parameters"), f"{path}.known_parameters")
        execution = require_mapping(case.get("execution"), f"{path}.execution")
        require_mapping(execution.get("initial_state"), f"{path}.execution.initial_state")
        require_list(execution.get("effect_rules"), f"{path}.execution.effect_rules")
        assert_decision_truth_free(observation, f"{path}.observation")
        assert_decision_truth_free(known_parameters, f"{path}.known_parameters")
        normalized.append(case)
    _validate_split_identities(normalized, "cases.cases")
    return tuple(normalized)


def _validate_truth(document: Mapping[str, Any], case_by_id: Mapping[str, Mapping[str, Any]]) -> dict[str, dict[str, Any]]:
    if document.get("format") != SELECTION_TRUTH_FORMAT:
        raise SelectionInputError(f"truth must use format {SELECTION_TRUTH_FORMAT!r}")
    if document.get("scope") != SELECTION_OFFLINE_SCOPE:
        raise SelectionInputError(f"truth.scope must be {SELECTION_OFFLINE_SCOPE!r}")
    records = require_list(document.get("records"), "truth.records")
    result: dict[str, dict[str, Any]] = {}
    for index, raw_record in enumerate(records):
        path = f"truth.records[{index}]"
        record = require_mapping(raw_record, path)
        case_id = require_string(record.get("case_id"), f"{path}.case_id")
        if case_id not in case_by_id:
            raise SelectionInputError(f"truth refers to unknown case_id {case_id!r}")
        if case_id in result:
            raise SelectionInputError(f"duplicate truth case_id {case_id!r}")
        task_id = require_string(record.get("task_id"), f"{path}.task_id")
        trajectory_id = require_string(record.get("trajectory_id"), f"{path}.trajectory_id")
        split = require_enum(record.get("split"), SELECTION_SPLITS, f"{path}.split")
        case = case_by_id[case_id]
        if task_id != case["task_id"] or trajectory_id != case["trajectory_id"]:
            raise SelectionInputError(f"truth links for {case_id!r} do not match cases")
        if split != case["split"]:
            raise SelectionInputError(f"truth split for {case_id!r} does not match cases")
        require_string(record.get("source"), f"{path}.source")
        environment = require_mapping(record.get("environment"), f"{path}.environment")
        require_enum(
            environment.get("status"),
            {"READY", "LOADING", "UNAVAILABLE", "EVIDENCE_MISSING"},
            f"{path}.environment.status",
        )
        require_string(environment.get("reason"), f"{path}.environment.reason")
        initial_state = require_mapping(record.get("initial_state"), f"{path}.initial_state")
        execution = require_mapping(case["execution"], f"case {case_id}.execution")
        if initial_state != execution["initial_state"]:
            raise SelectionInputError(f"truth initial_state for {case_id!r} does not match execution")
        completion = _validate_outcome(record.get("task_completion"), f"{path}.task_completion")
        action = record.get("action_postcondition")
        if action is not None:
            action = _validate_outcome(action, f"{path}.action_postcondition")
        result[case_id] = dict(record, initial_state=initial_state, task_completion=completion, action_postcondition=action)
    _validate_split_identities(result.values(), "truth.records")
    missing = set(case_by_id) - set(result)
    if missing:
        raise SelectionInputError(f"truth is missing cases: {sorted(missing)}")
    return result


def _validate_outcome(value: Any, path: str) -> dict[str, Any]:
    outcome = require_mapping(value, path)
    require_enum(outcome.get("status"), ACTION_STATUSES, f"{path}.status")
    require_string(outcome.get("reason"), f"{path}.reason")
    require_mapping(outcome.get("evidence"), f"{path}.evidence")
    failure_class = outcome.get("failure_class")
    if failure_class is not None:
        require_enum(failure_class, FAILURE_CLASSES, f"{path}.failure_class")
    if outcome["status"] == "FAILURE" and failure_class is None:
        raise SelectionInputError(f"{path}.failure_class is required for FAILURE")
    if outcome["status"] == "SUCCESS" and failure_class is not None:
        raise SelectionInputError(f"{path}.failure_class must be null for SUCCESS")
    return outcome


def _visible_controls(observation: Mapping[str, Any]) -> list[str]:
    controls: list[str] = []
    for raw_node in observation.get("nodes", []):
        node = require_mapping(raw_node, "observation.nodes[]")
        if node.get("enabled"):
            controls.append(str(node.get("label") or node.get("node_id")))
    return controls


def _decision_payload(case: Mapping[str, Any], build: CandidateBuild) -> dict[str, Any]:
    observation = require_mapping(case["observation"], "case.observation")
    available_actions = [
        f"{candidate['action']['kind']}:{candidate['action']['target_node_id']}"
        for candidate in build.candidates
    ]
    initial_observation: dict[str, Any] = {
        "page": observation["page"],
        "visible_controls": _visible_controls(observation),
    }
    if isinstance(observation.get("query"), str):
        initial_observation["query"] = observation["query"]
    return {
        "instruction": case["instruction"],
        "initial_observation": initial_observation,
        "available_actions": available_actions,
    }


def _trace_observation(observation: Mapping[str, Any]) -> dict[str, Any]:
    # Keep trace evidence truth-free and only expose the current frame.
    nodes = []
    for raw_node in observation.get("nodes", []):
        node = require_mapping(raw_node, "observation.nodes[]")
        nodes.append(
            {
                "node_id": node["node_id"],
                "role": node["role"],
                "enabled": node["enabled"],
            }
        )
    return {
        "observation_id": observation["observation_id"],
        "version": observation["version"],
        "page": observation["page"],
        "nodes": nodes,
    }


def run_selection_dataset(
    cases: str | Path | Mapping[str, Any],
    replay: str | Path | Mapping[str, Any],
    truth: str | Path | Mapping[str, Any],
    *,
    split: str = "all",
    now: int | None = None,
) -> dict[str, Any]:
    """Run selection, deterministic effects, and an independent report.

    The same frozen policy is used for ``dev`` and ``holdout``.  A truth
    document is consumed only after candidates and replay actions have been
    produced; it is never passed into :func:`build_candidates`.
    """

    if split != "all" and split not in SELECTION_SPLITS:
        raise SelectionInputError("split must be all, dev, or holdout")
    cases_document = _selection_document(cases, "cases")
    replay_document = _selection_document(replay, "replay")
    truth_document = _selection_document(truth, "truth")
    case_values = _validate_cases(cases_document)
    case_by_id = {case["case_id"]: case for case in case_values}
    truth_by_id = _validate_truth(truth_document, case_by_id)
    split_separation_verified = _validate_split_identities(case_values, "cases.cases") and _validate_split_identities(
        truth_by_id.values(), "truth.records"
    )
    freeze_manifest = _validate_freeze_manifest(cases_document, replay_document)
    selector = ReplaySelector.from_document(replay_document)
    if now is None:
        document_now = cases_document.get("now")
        if document_now is not None:
            now = require_integer(document_now, "cases.now")
    simulator = DeterministicEffectSimulator()
    selected_cases = [case for case in case_values if split == "all" or case["split"] == split]
    selected_cases.sort(key=lambda item: (item["case_id"], item["trajectory_id"]))

    results: list[dict[str, Any]] = []
    eval_tasks: list[dict[str, Any]] = []
    eval_traces: list[dict[str, Any]] = []
    eval_truth: list[dict[str, Any]] = []
    selection_counts: dict[str, int] = {}
    fallback_counts: dict[str, int] = {}
    for case in selected_cases:
        build = build_candidates(case["observation"], case["known_parameters"], now=now)
        decision = selector.select(case["case_id"], build)
        simulation: SimulationResult | None = None
        execution = require_mapping(case["execution"], f"case {case['case_id']}.execution")
        if decision.candidate is not None:
            simulation = simulator.apply(
                execution["initial_state"],
                decision.candidate["action"],
                execution["effect_rules"],
            )
        status_count = selection_counts.get(decision.status, 0)
        selection_counts[decision.status] = status_count + 1
        if decision.fallback is not None:
            reason = decision.fallback["reason"]
            fallback_counts[reason] = fallback_counts.get(reason, 0) + 1
        result: dict[str, Any] = {
            "case_id": case["case_id"],
            "task_id": case["task_id"],
            "trajectory_id": case["trajectory_id"],
            "split": case["split"],
            "policy_version": SELECTION_POLICY_VERSION,
            "candidates": list(build.candidates),
            "candidate_warnings": list(build.warnings),
            "selection": {
                "status": decision.status,
                "reason": decision.reason,
                "candidate": decision.candidate,
                "action": deep_copy(decision.candidate["action"])
                if decision.candidate is not None
                else None,
                "fallback": decision.fallback,
            },
            "simulation": None,
        }
        if simulation is not None:
            result["simulation"] = {
                "before": simulation.before,
                "after": simulation.after,
                "receipt": simulation.receipt,
                "changed": simulation.changed,
            }
        results.append(result)

        task = {
            "task_id": case["task_id"],
            "trajectory_id": case["trajectory_id"],
            "split": case["split"],
            "initial_state": deep_copy(execution["initial_state"]),
            "input": {
                "instruction": case["instruction"],
                "known_parameters": deep_copy(case["known_parameters"]),
            },
            "goal": deep_copy(execution.get("goal", {"description": case["instruction"], "completion_rule": "external_truth"})),
            "decision_payload": _decision_payload(case, build),
        }
        eval_tasks.append(task)
        trace_steps: list[dict[str, Any]] = []
        if simulation is not None:
            trace_steps.append(
                {
                    "step_id": "01-action",
                    "observation_before": _trace_observation(case["observation"]),
                    "action": deep_copy(decision.candidate["action"]),
                    "receipt": deep_copy(simulation.receipt),
                    "observation_after": deep_copy(simulation.after),
                }
            )
        eval_traces.append(
            {
                "trajectory_id": case["trajectory_id"],
                "task_id": case["task_id"],
                "steps": trace_steps,
            }
        )

        external_truth = truth_by_id[case["case_id"]]
        action_truth = external_truth.get("action_postcondition")
        if (simulation is not None) != (action_truth is not None):
            raise SelectionInputError(
                f"truth action presence for {case['case_id']!r} does not match replay execution"
            )
        truth_steps = []
        if action_truth is not None:
            truth_steps.append({"step_id": "01-action", "action_postcondition": deep_copy(action_truth)})
        eval_truth.append(
            {
                "task_id": case["task_id"],
                "trajectory_id": case["trajectory_id"],
                "source": external_truth["source"],
                "environment": deep_copy(external_truth["environment"]),
                "initial_state": deep_copy(external_truth["initial_state"]),
                "steps": truth_steps,
                "task_completion": deep_copy(external_truth["task_completion"]),
            }
        )

    independent_report = evaluate_independent_documents(
        tuple(eval_tasks), tuple(eval_traces), tuple(eval_truth), split=split
    )
    return {
        "report_version": SELECTION_REPORT_VERSION,
        "policy_version": SELECTION_POLICY_VERSION,
        "selected_split": split,
        "counts": {
            "selection": {key: selection_counts[key] for key in sorted(selection_counts)},
            "fallback_reason": {key: fallback_counts[key] for key in sorted(fallback_counts)},
        },
        "integrity": {
            "jev_key_required": False,
            "decision_input_truth_free": True,
            "reference_and_holdout_separated": split_separation_verified,
            "policy_frozen_before_holdout": freeze_manifest["policy"]["verified"]
            and freeze_manifest["replay"]["verified"],
            "freeze_manifest": freeze_manifest,
            "candidate_actions_complete": all(
                bool(result["candidates"]) or result["selection"]["status"] == "FALLBACK"
                for result in results
            ),
        },
        "results": results,
        "independent_evaluation": independent_report,
    }


def render_selection_report(report: Mapping[str, Any]) -> str:
    return render_json(report)


__all__ = [
    "CandidateBuild",
    "DeterministicEffectSimulator",
    "ReplaySelector",
    "SelectionDecision",
    "SelectionInputError",
    "SimulationResult",
    "build_candidates",
    "render_selection_report",
    "run_selection_dataset",
]
