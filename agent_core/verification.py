"""Offline four-state action verification and bounded control decisions."""

from __future__ import annotations

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
    sanitize_trace,
    sha256_json,
)


VERIFICATION_CASES_FORMAT = "jev-offline-verification/cases-v1"
VERIFICATION_REPLAY_FORMAT = "jev-offline-verification/replay-v1"
VERIFICATION_TRUTH_FORMAT = "jev-offline-verification/truth-v1"
VERIFICATION_REPORT_VERSION = "jev-offline-verification/report-v1"
VERIFICATION_RULE_VERSION = "jev-offline-verification-rules-v1"
VERIFICATION_CLASSIFIER_VERSION = "jev-offline-verification-replay-v1"
VERIFICATION_FREEZE_MANIFEST_FORMAT = "jev-offline-freeze-manifest-v1"
VERIFICATION_POLICY_ARTIFACT_ID = "jev-offline-verification-policy-v1"
VERIFICATION_REPLAY_ARTIFACT_ID = "jev-offline-verification-replay-v1"
VERIFICATION_OFFLINE_SCOPE = "offline-fixture-only"
VERIFICATION_POLICY_ARTIFACT = {
    "artifact_id": VERIFICATION_POLICY_ARTIFACT_ID,
    "rule_version": VERIFICATION_RULE_VERSION,
    "classifier_version": VERIFICATION_CLASSIFIER_VERSION,
    "verification_states": ["FAILURE", "PENDING", "SUCCESS", "UNKNOWN"],
    "missing_before_screenshot": "pause_without_visual_recovery",
}
# Filled from the canonical replay fixture after the fixture is updated.  A
# replay label change therefore requires a deliberate re-freeze, even when
# the classifier version string is unchanged.
VERIFICATION_FROZEN_REPLAY_SHA256 = "1e7b73a26aea35300fc79031072af0a207e02a46120064dd0e31c302bf88af09"
VERIFICATION_SPLITS = frozenset({"dev", "holdout"})
STATUS_VALUES = frozenset({"SUCCESS", "FAILURE", "PENDING", "UNKNOWN"})
FAILURE_CLASSES = frozenset({"agent", "environment"})

VerificationInputError = OfflineInputError


@dataclass(frozen=True)
class VerificationResult:
    status: str
    reason: str
    source: str
    evidence: dict[str, Any]
    rule_status: str | None = None

    def as_dict(self) -> dict[str, Any]:
        result = {
            "status": self.status,
            "reason": self.reason,
            "source": self.source,
            "evidence": deep_copy(self.evidence),
        }
        if self.rule_status is not None:
            result["rule_status"] = self.rule_status
        return result


@dataclass(frozen=True)
class ControlDecision:
    command: str
    reason: str
    status: str
    wait_attempt: int
    max_waits: int
    next_wait_attempt: int | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "command": self.command,
            "reason": self.reason,
            "status": self.status,
            "wait_attempt": self.wait_attempt,
            "max_waits": self.max_waits,
            "next_wait_attempt": self.next_wait_attempt,
        }


def _screenshot_available(evidence: Mapping[str, Any] | None) -> bool:
    if not isinstance(evidence, Mapping):
        return False
    screenshot = evidence.get("screenshot")
    return isinstance(screenshot, Mapping) and screenshot.get("available") is True


def _tree(evidence: Mapping[str, Any]) -> Mapping[str, Any] | None:
    value = evidence.get("tree")
    return value if isinstance(value, Mapping) else None


def _state_value(evidence: Mapping[str, Any], path: str) -> tuple[bool, Any]:
    if path.startswith("tree."):
        return get_path(evidence, path)
    tree = _tree(evidence)
    if tree is None:
        return False, None
    return get_path(tree, path)


def _missing_screenshot_evidence(case: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "before_screenshot_available": _screenshot_available(case.get("before")),
        "after_screenshot_available": _screenshot_available(case.get("after")),
    }


class RuleVerifier:
    """Deterministic rules for clear before/after evidence."""

    def verify(self, case: Mapping[str, Any]) -> VerificationResult:
        case = require_mapping(case, "case")
        before = require_mapping(case.get("before"), "case.before")
        after_value = case.get("after")
        after = None if after_value is None else require_mapping(after_value, "case.after")
        receipt = require_mapping(case.get("receipt"), "case.receipt")
        postcondition = require_mapping(case.get("postcondition"), "case.postcondition")
        assert_decision_truth_free(before, "case.before")
        if after is not None:
            assert_decision_truth_free(after, "case.after")
        assert_decision_truth_free(receipt, "case.receipt")
        assert_decision_truth_free(postcondition, "case.postcondition")

        accepted = receipt.get("accepted")
        if not isinstance(accepted, bool):
            raise VerificationInputError("case.receipt.accepted must be a boolean")
        outcome = receipt.get("outcome")
        if not accepted or outcome in {"REJECTED", "ERROR"}:
            return VerificationResult(
                "FAILURE",
                "receipt_rejected",
                "rules",
                {"receipt_accepted": accepted, "receipt_outcome": outcome},
            )
        if outcome != "EXECUTED":
            return VerificationResult(
                "UNKNOWN",
                "receipt_outcome_unknown",
                "rules",
                {"receipt_accepted": accepted, "receipt_outcome": outcome},
            )
        if after is None:
            return VerificationResult(
                "UNKNOWN",
                "after_observation_missing",
                "rules",
                _missing_screenshot_evidence(case),
            )

        tree = _tree(after)
        loading = after.get("loading") is True or (tree is not None and tree.get("loading") is True)
        if loading:
            return VerificationResult(
                "PENDING",
                "page_loading",
                "rules",
                {"loading": True, "after_observation_id": after.get("observation_id")},
            )
        if after.get("error") or (tree is not None and tree.get("error")):
            return VerificationResult(
                "FAILURE",
                "postcondition_error",
                "rules",
                {"error": after.get("error") or tree.get("error")},
            )
        if tree is None:
            return VerificationResult(
                "UNKNOWN",
                "after_tree_missing",
                "rules",
                _missing_screenshot_evidence(case),
            )

        if not _screenshot_available(before):
            return VerificationResult(
                "UNKNOWN",
                "before_screenshot_missing",
                "rules",
                _missing_screenshot_evidence(case),
            )

        kind = require_string(postcondition.get("kind"), "case.postcondition.kind")
        condition_met = False
        evidence: dict[str, Any] = {
            "before_observation_id": before.get("observation_id"),
            "after_observation_id": after.get("observation_id"),
            "screenshot_available": _screenshot_available(after),
        }
        if kind == "state_equals":
            path = require_string(postcondition.get("path"), "case.postcondition.path")
            present, actual = _state_value(after, path)
            condition_met = present and actual == postcondition.get("value")
            evidence.update({"path": path, "present": present, "actual": actual})
        elif kind == "state_changed":
            path = require_string(postcondition.get("path"), "case.postcondition.path")
            before_present, before_value = _state_value(before, path)
            after_present, after_value = _state_value(after, path)
            condition_met = before_present and after_present and before_value != after_value
            if "to" in postcondition:
                condition_met = condition_met and after_value == postcondition["to"]
            evidence.update(
                {
                    "path": path,
                    "before_present": before_present,
                    "after_present": after_present,
                    "before": before_value,
                    "after": after_value,
                }
            )
        elif kind == "page_is":
            expected_page = require_string(postcondition.get("page"), "case.postcondition.page")
            present, actual = _state_value(after, "page")
            condition_met = present and actual == expected_page
            evidence.update({"path": "page", "present": present, "actual": actual})
        else:
            return VerificationResult(
                "UNKNOWN",
                "unsupported_postcondition_kind",
                "rules",
                {"kind": kind},
            )

        # A receipt plus a tree can prove FAILURE, but SUCCESS always needs the
        # captured post screenshot.  This prevents a missing image from being
        # silently replaced by a later frame.
        if condition_met and not _screenshot_available(after):
            return VerificationResult(
                "UNKNOWN",
                "screenshot_missing",
                "rules",
                {**evidence, **_missing_screenshot_evidence(case)},
            )
        return VerificationResult(
            "SUCCESS" if condition_met else "FAILURE",
            "postcondition_met" if condition_met else "postcondition_not_met",
            "rules",
            evidence,
        )


class ReplayClassifier:
    """Replay a frozen classifier only after rules return UNKNOWN."""

    def __init__(self, classifications: Mapping[str, Mapping[str, Any]] | None = None):
        self._classifications = dict(classifications or {})

    @classmethod
    def from_document(cls, document: Mapping[str, Any]) -> "ReplayClassifier":
        document = require_mapping(document, "replay")
        if document.get("format") != VERIFICATION_REPLAY_FORMAT:
            raise VerificationInputError(f"replay must use format {VERIFICATION_REPLAY_FORMAT!r}")
        if document.get("scope") != VERIFICATION_OFFLINE_SCOPE:
            raise VerificationInputError(f"replay.scope must be {VERIFICATION_OFFLINE_SCOPE!r}")
        if document.get("classifier_version", VERIFICATION_CLASSIFIER_VERSION) != VERIFICATION_CLASSIFIER_VERSION:
            raise VerificationInputError(
                "replay classifier_version does not match the frozen verification classifier"
            )
        if document.get("artifact_id") != VERIFICATION_REPLAY_ARTIFACT_ID:
            raise VerificationInputError("replay artifact_id does not match the frozen verification replay")
        if VERIFICATION_FROZEN_REPLAY_SHA256 == "__verification_replay_hash_pending__":
            raise VerificationInputError("verification replay freeze hash is not configured")
        actual_hash = sha256_json(document)
        if actual_hash != VERIFICATION_FROZEN_REPLAY_SHA256:
            raise VerificationInputError(
                "replay artifact hash does not match the frozen verification replay"
            )
        records = require_list(document.get("classifications"), "replay.classifications")
        values: dict[str, Mapping[str, Any]] = {}
        for index, raw_record in enumerate(records):
            path = f"replay.classifications[{index}]"
            record = require_mapping(raw_record, path)
            case_id = require_string(record.get("case_id"), f"{path}.case_id")
            if case_id in values:
                raise VerificationInputError(f"duplicate replay case_id {case_id!r}")
            status = require_enum(record.get("status"), STATUS_VALUES, f"{path}.status")
            reason = require_string(record.get("reason"), f"{path}.reason")
            values[case_id] = {"status": status, "reason": reason, "source": "replay"}
        return cls(values)

    def classify(
        self,
        case_id: str,
        case: Mapping[str, Any],
        rule_result: VerificationResult,
    ) -> VerificationResult:
        if rule_result.status != "UNKNOWN":
            return rule_result
        record = self._classifications.get(case_id)
        if record is None:
            return rule_result
        status = record["status"]
        reason = record["reason"]
        after = case.get("after")
        if status == "SUCCESS":
            # Classifier replay cannot override safety guards.  In particular,
            # no post screenshot or a missing before screenshot remains unknown.
            if not isinstance(after, Mapping):
                return VerificationResult(
                    "UNKNOWN",
                    "after_observation_missing",
                    "safety_guard",
                    _missing_screenshot_evidence(case),
                    rule_status=rule_result.status,
                )
            postcondition = require_mapping(case.get("postcondition"), "case.postcondition")
            if not _screenshot_available(case.get("before")):
                return VerificationResult(
                    "UNKNOWN",
                    "before_screenshot_missing",
                    "safety_guard",
                    _missing_screenshot_evidence(case),
                    rule_status=rule_result.status,
                )
            if not _screenshot_available(after):
                return VerificationResult(
                    "UNKNOWN",
                    "screenshot_missing",
                    "safety_guard",
                    _missing_screenshot_evidence(case),
                    rule_status=rule_result.status,
                )
            after_tree = _tree(after)
            if postcondition.get("kind") in {"state_equals", "state_changed", "page_is"} and after_tree is None:
                return VerificationResult(
                    "UNKNOWN",
                    "after_tree_missing",
                    "safety_guard",
                    {"after_tree_available": False},
                    rule_status=rule_result.status,
                )
            if after.get("loading") is True or (after_tree is not None and after_tree.get("loading") is True):
                return VerificationResult(
                    "PENDING",
                    "page_loading",
                    "safety_guard",
                    {"loading": True},
                    rule_status=rule_result.status,
                )
        return VerificationResult(
            status,
            reason,
            "replay",
            {
                "replay_case_id": case_id,
                "rule_status": rule_result.status,
            },
            rule_status=rule_result.status,
        )


class VerificationController:
    """Map a four-state result to a bounded, observable next control step."""

    def decide(
        self,
        status: str,
        *,
        wait_attempt: int = 0,
        max_waits: int = 2,
        visual_fallback_available: bool = False,
        visual_fallback_attempted: bool = False,
        verification_reason: str | None = None,
        reason: str | None = None,
        before_screenshot_available: bool | None = None,
        evidence: Mapping[str, Any] | None = None,
    ) -> ControlDecision:
        status = require_enum(status, STATUS_VALUES, "verification.status")
        if isinstance(wait_attempt, bool) or wait_attempt < 0:
            raise VerificationInputError("wait_attempt must be a non-negative integer")
        if isinstance(max_waits, bool) or max_waits < 0:
            raise VerificationInputError("max_waits must be a non-negative integer")
        if verification_reason is not None and reason is not None and verification_reason != reason:
            raise VerificationInputError("verification_reason and reason must match when both are provided")
        verification_reason = verification_reason if verification_reason is not None else reason
        if evidence is not None:
            evidence = require_mapping(evidence, "verification.evidence")
            evidence_before = evidence.get("before_screenshot_available")
            if evidence_before is not None:
                if not isinstance(evidence_before, bool):
                    raise VerificationInputError(
                        "verification.evidence.before_screenshot_available must be a boolean"
                    )
                if before_screenshot_available is not None and before_screenshot_available != evidence_before:
                    raise VerificationInputError(
                        "before_screenshot_available does not match verification evidence"
                    )
                before_screenshot_available = evidence_before
        if status == "SUCCESS":
            return ControlDecision("CONTINUE", "action_postcondition_satisfied", status, wait_attempt, max_waits)
        if status == "FAILURE":
            return ControlDecision("PAUSE", "action_postcondition_failed", status, wait_attempt, max_waits)
        if status == "PENDING":
            if wait_attempt < max_waits:
                return ControlDecision(
                    "WAIT",
                    "bounded_wait",
                    status,
                    wait_attempt,
                    max_waits,
                    next_wait_attempt=wait_attempt + 1,
                )
            return ControlDecision("PAUSE", "wait_limit_reached", status, wait_attempt, max_waits)
        # A visual model can inspect the current frame, but it cannot recreate
        # the missing pre-action frame required by this verification contract.
        # Preserve that reason and pause even when the case advertises a visual
        # fallback capability.
        if verification_reason == "before_screenshot_missing" or before_screenshot_available is False:
            return ControlDecision("PAUSE", "before_screenshot_missing", status, wait_attempt, max_waits)
        if visual_fallback_available and not visual_fallback_attempted:
            return ControlDecision("VISUAL_FALLBACK", "evidence_insufficient", status, wait_attempt, max_waits)
        return ControlDecision("PAUSE", "evidence_insufficient", status, wait_attempt, max_waits)


def _verification_document(value: str | Path | Mapping[str, Any], name: str) -> dict[str, Any]:
    if isinstance(value, (str, Path)):
        return load_json(value)
    return require_mapping(value, name)


def _validate_freeze_manifest(
    cases_document: Mapping[str, Any], replay_document: Mapping[str, Any]
) -> dict[str, Any]:
    manifest = require_mapping(cases_document.get("freeze_manifest"), "cases.freeze_manifest")
    if manifest.get("format") != VERIFICATION_FREEZE_MANIFEST_FORMAT:
        raise VerificationInputError(
            f"cases.freeze_manifest must use format {VERIFICATION_FREEZE_MANIFEST_FORMAT!r}"
        )
    if manifest.get("scope") != VERIFICATION_OFFLINE_SCOPE:
        raise VerificationInputError(
            f"cases.freeze_manifest.scope must be {VERIFICATION_OFFLINE_SCOPE!r}"
        )
    policy = require_mapping(manifest.get("policy"), "cases.freeze_manifest.policy")
    replay = require_mapping(manifest.get("replay"), "cases.freeze_manifest.replay")
    policy_id = require_string(policy.get("artifact_id"), "cases.freeze_manifest.policy.artifact_id")
    replay_id = require_string(replay.get("artifact_id"), "cases.freeze_manifest.replay.artifact_id")
    policy_hash = require_string(policy.get("sha256"), "cases.freeze_manifest.policy.sha256")
    replay_hash = require_string(replay.get("sha256"), "cases.freeze_manifest.replay.sha256")
    if policy_id != VERIFICATION_POLICY_ARTIFACT_ID:
        raise VerificationInputError("verification policy artifact_id does not match the frozen policy")
    if replay_id != VERIFICATION_REPLAY_ARTIFACT_ID:
        raise VerificationInputError("verification replay artifact_id does not match the frozen replay")
    actual_policy_hash = sha256_json(VERIFICATION_POLICY_ARTIFACT)
    if policy_hash != actual_policy_hash:
        raise VerificationInputError("verification policy hash does not match the frozen policy artifact")
    actual_replay_hash = sha256_json(replay_document)
    if replay_hash != actual_replay_hash or replay_hash != VERIFICATION_FROZEN_REPLAY_SHA256:
        raise VerificationInputError(
            "verification replay hash does not match the frozen verification replay artifact"
        )
    if replay_document.get("artifact_id") != replay_id:
        raise VerificationInputError("replay artifact_id does not match cases.freeze_manifest")
    return {
        "format": VERIFICATION_FREEZE_MANIFEST_FORMAT,
        "scope": VERIFICATION_OFFLINE_SCOPE,
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
        split = require_enum(record.get("split"), VERIFICATION_SPLITS, f"{source}[{index}].split")
        for field in identities:
            value = require_string(record.get(field), f"{source}[{index}].{field}")
            splits = identities[field].setdefault(value, set())
            splits.add(split)
            if len(splits) > 1:
                raise VerificationInputError(
                    f"{source} {field} {value!r} crosses dev/holdout splits: {sorted(splits)}"
                )
    return True


def _validate_cases(document: Mapping[str, Any]) -> tuple[dict[str, Any], ...]:
    if document.get("format") != VERIFICATION_CASES_FORMAT:
        raise VerificationInputError(f"cases must use format {VERIFICATION_CASES_FORMAT!r}")
    if document.get("scope") != VERIFICATION_OFFLINE_SCOPE:
        raise VerificationInputError(f"cases.scope must be {VERIFICATION_OFFLINE_SCOPE!r}")
    cases = require_list(document.get("cases"), "cases.cases")
    values: list[dict[str, Any]] = []
    seen: set[str] = set()
    for index, raw_case in enumerate(cases):
        path = f"cases.cases[{index}]"
        case = require_mapping(raw_case, path)
        case_id = require_string(case.get("case_id"), f"{path}.case_id")
        if case_id in seen:
            raise VerificationInputError(f"duplicate case_id {case_id!r}")
        seen.add(case_id)
        require_string(case.get("task_id"), f"{path}.task_id")
        require_string(case.get("trajectory_id"), f"{path}.trajectory_id")
        require_enum(case.get("split"), VERIFICATION_SPLITS, f"{path}.split")
        require_string(case.get("instruction"), f"{path}.instruction")
        require_mapping(case.get("initial_state"), f"{path}.initial_state")
        before = require_mapping(case.get("before"), f"{path}.before")
        after = case.get("after")
        if after is not None:
            require_mapping(after, f"{path}.after")
        require_mapping(case.get("receipt"), f"{path}.receipt")
        require_mapping(case.get("postcondition"), f"{path}.postcondition")
        if "visual_fallback_available" in case:
            require_bool(case["visual_fallback_available"], f"{path}.visual_fallback_available")
        if "visual_fallback_attempted" in case:
            require_bool(case["visual_fallback_attempted"], f"{path}.visual_fallback_attempted")
        assert_decision_truth_free(before, f"{path}.before")
        if after is not None:
            assert_decision_truth_free(after, f"{path}.after")
        assert_decision_truth_free(case["receipt"], f"{path}.receipt")
        assert_decision_truth_free(case["postcondition"], f"{path}.postcondition")
        wait = case.get("wait", {})
        if "wait" in case:
            wait = require_mapping(wait, f"{path}.wait")
            require_integer(wait.get("attempt", 0), f"{path}.wait.attempt")
            require_integer(wait.get("max_attempts", 2), f"{path}.wait.max_attempts")
        values.append(case)
    _validate_split_identities(values, "cases.cases")
    return tuple(values)


def _validate_truth(document: Mapping[str, Any], case_by_id: Mapping[str, Mapping[str, Any]]) -> dict[str, dict[str, Any]]:
    if document.get("format") != VERIFICATION_TRUTH_FORMAT:
        raise VerificationInputError(f"truth must use format {VERIFICATION_TRUTH_FORMAT!r}")
    if document.get("scope") != VERIFICATION_OFFLINE_SCOPE:
        raise VerificationInputError(f"truth.scope must be {VERIFICATION_OFFLINE_SCOPE!r}")
    records = require_list(document.get("records"), "truth.records")
    values: dict[str, dict[str, Any]] = {}
    for index, raw_record in enumerate(records):
        path = f"truth.records[{index}]"
        record = require_mapping(raw_record, path)
        case_id = require_string(record.get("case_id"), f"{path}.case_id")
        if case_id not in case_by_id or case_id in values:
            raise VerificationInputError(f"invalid or duplicate truth case_id {case_id!r}")
        task_id = require_string(record.get("task_id"), f"{path}.task_id")
        trajectory_id = require_string(record.get("trajectory_id"), f"{path}.trajectory_id")
        split = require_enum(record.get("split"), VERIFICATION_SPLITS, f"{path}.split")
        case = case_by_id[case_id]
        if task_id != case["task_id"] or trajectory_id != case["trajectory_id"]:
            raise VerificationInputError(f"truth links for {case_id!r} do not match cases")
        if split != case["split"]:
            raise VerificationInputError(f"truth split for {case_id!r} does not match cases")
        require_string(record.get("source"), f"{path}.source")
        initial_state = require_mapping(record.get("initial_state"), f"{path}.initial_state")
        if initial_state != case["initial_state"]:
            raise VerificationInputError(f"truth initial_state for {case_id!r} does not match cases")
        environment = require_mapping(record.get("environment"), f"{path}.environment")
        require_enum(
            environment.get("status"),
            {"READY", "LOADING", "UNAVAILABLE", "EVIDENCE_MISSING"},
            f"{path}.environment.status",
        )
        require_string(environment.get("reason"), f"{path}.environment.reason")
        action = _validate_outcome(record.get("action_postcondition"), f"{path}.action_postcondition")
        completion = _validate_outcome(record.get("task_completion"), f"{path}.task_completion")
        values[case_id] = dict(record, action_postcondition=action, task_completion=completion)
    _validate_split_identities(list(values.values()), "truth.records")
    missing = set(case_by_id) - set(values)
    if missing:
        raise VerificationInputError(f"truth is missing cases: {sorted(missing)}")
    return values


def _validate_outcome(value: Any, path: str) -> dict[str, Any]:
    outcome = require_mapping(value, path)
    require_enum(outcome.get("status"), STATUS_VALUES, f"{path}.status")
    require_string(outcome.get("reason"), f"{path}.reason")
    require_mapping(outcome.get("evidence"), f"{path}.evidence")
    failure_class = outcome.get("failure_class")
    if failure_class is not None:
        require_enum(failure_class, FAILURE_CLASSES, f"{path}.failure_class")
    if outcome["status"] == "FAILURE" and failure_class is None:
        raise VerificationInputError(f"{path}.failure_class is required for FAILURE")
    if outcome["status"] == "SUCCESS" and failure_class is not None:
        raise VerificationInputError(f"{path}.failure_class must be null for SUCCESS")
    return outcome


def _task_payload(case: Mapping[str, Any]) -> dict[str, Any]:
    before = require_mapping(case["before"], "case.before")
    tree = _tree(before) or {}
    visible = tree.get("visible_controls", [])
    if not isinstance(visible, list):
        visible = []
    action = case.get("action", {})
    if not isinstance(action, Mapping):
        action = {}
    kind = action.get("kind", "unknown")
    target = action.get("target_node_id", "unknown")
    return {
        "instruction": case["instruction"],
        "initial_observation": {
            "page": str(tree.get("page", "unknown")),
            "visible_controls": [str(value) for value in visible],
        },
        "available_actions": [f"{kind}:{target}"],
    }


def run_verification_dataset(
    cases: str | Path | Mapping[str, Any],
    replay: str | Path | Mapping[str, Any],
    truth: str | Path | Mapping[str, Any],
    *,
    split: str = "all",
) -> dict[str, Any]:
    """Run rules, replay classification, control policy, and independent scoring."""

    if split != "all" and split not in VERIFICATION_SPLITS:
        raise VerificationInputError("split must be all, dev, or holdout")
    cases_document = _verification_document(cases, "cases")
    replay_document = _verification_document(replay, "replay")
    truth_document = _verification_document(truth, "truth")
    case_values = _validate_cases(cases_document)
    case_by_id = {case["case_id"]: case for case in case_values}
    truth_by_id = _validate_truth(truth_document, case_by_id)
    split_separation_verified = _validate_split_identities(case_values, "cases.cases") and _validate_split_identities(
        truth_by_id.values(), "truth.records"
    )
    freeze_manifest = _validate_freeze_manifest(cases_document, replay_document)
    classifier = ReplayClassifier.from_document(replay_document)
    rules = RuleVerifier()
    controller = VerificationController()
    selected_cases = [case for case in case_values if split == "all" or case["split"] == split]
    selected_cases.sort(key=lambda item: (item["case_id"], item["trajectory_id"]))

    results: list[dict[str, Any]] = []
    eval_tasks: list[dict[str, Any]] = []
    eval_traces: list[dict[str, Any]] = []
    eval_truth: list[dict[str, Any]] = []
    status_counts: dict[str, int] = {}
    command_counts: dict[str, int] = {}
    confusion: dict[str, dict[str, int]] = {
        predicted: {actual: 0 for actual in sorted(STATUS_VALUES)}
        for predicted in sorted(STATUS_VALUES)
    }
    for case in selected_cases:
        rule_result = rules.verify(case)
        final_result = classifier.classify(case["case_id"], case, rule_result)
        wait = require_mapping(case.get("wait", {}), f"case {case['case_id']}.wait")
        wait_attempt = require_integer(wait.get("attempt", 0), "wait.attempt")
        max_waits = require_integer(wait.get("max_attempts", 2), "wait.max_attempts")
        visual_available = bool(case.get("visual_fallback_available", False))
        visual_attempted = bool(case.get("visual_fallback_attempted", False))
        control = controller.decide(
            final_result.status,
            wait_attempt=wait_attempt,
            max_waits=max_waits,
            visual_fallback_available=visual_available,
            visual_fallback_attempted=visual_attempted,
            verification_reason=final_result.reason,
            before_screenshot_available=_screenshot_available(case.get("before")),
            evidence=final_result.evidence,
        )
        status_counts[final_result.status] = status_counts.get(final_result.status, 0) + 1
        command_counts[control.command] = command_counts.get(control.command, 0) + 1
        external_truth = truth_by_id[case["case_id"]]
        confusion[final_result.status][external_truth["action_postcondition"]["status"]] += 1
        result = {
            "case_id": case["case_id"],
            "task_id": case["task_id"],
            "trajectory_id": case["trajectory_id"],
            "split": case["split"],
            "rule_version": VERIFICATION_RULE_VERSION,
            "classifier_version": VERIFICATION_CLASSIFIER_VERSION,
            "classification": {
                "rules": rule_result.as_dict(),
                "final": final_result.as_dict(),
                "replay_used": rule_result.status == "UNKNOWN" and final_result.source == "replay",
            },
            "control": control.as_dict(),
            # This is copied from the independent document after classification;
            # it is deliberately not an input to either rules or replay.
            "independent_task_completion": deep_copy(external_truth["task_completion"]),
        }
        results.append(result)

        eval_tasks.append(
            {
                "task_id": case["task_id"],
                "trajectory_id": case["trajectory_id"],
                "split": case["split"],
                "initial_state": deep_copy(case["initial_state"]),
                "input": {"instruction": case["instruction"]},
                "goal": deep_copy(case.get("goal", {"description": case["instruction"], "completion_rule": "external_truth"})),
                "decision_payload": _task_payload(case),
            }
        )
        action = require_mapping(case.get("action", {}), f"case {case['case_id']}.action")
        trace_step: dict[str, Any] = {
            "step_id": "01-verify",
            "observation_before": sanitize_trace(case["before"]),
            "action": sanitize_trace(action),
            "receipt": sanitize_trace(case["receipt"]),
        }
        if case.get("after") is not None:
            trace_step["observation_after"] = sanitize_trace(case["after"])
        eval_traces.append(
            {
                "trajectory_id": case["trajectory_id"],
                "task_id": case["task_id"],
                "steps": [trace_step],
            }
        )
        eval_truth.append(
            {
                "task_id": case["task_id"],
                "trajectory_id": case["trajectory_id"],
                "source": external_truth["source"],
                "environment": deep_copy(external_truth["environment"]),
                "initial_state": deep_copy(external_truth["initial_state"]),
                "steps": [{"step_id": "01-verify", "action_postcondition": deep_copy(external_truth["action_postcondition"])}],
                "task_completion": deep_copy(external_truth["task_completion"]),
            }
        )

    independent_report = evaluate_independent_documents(
        tuple(eval_tasks), tuple(eval_traces), tuple(eval_truth), split=split
    )
    return {
        "report_version": VERIFICATION_REPORT_VERSION,
        "rule_version": VERIFICATION_RULE_VERSION,
        "classifier_version": VERIFICATION_CLASSIFIER_VERSION,
        "selected_split": split,
        "counts": {
            "verification": {key: status_counts[key] for key in sorted(status_counts)},
            "control": {key: command_counts[key] for key in sorted(command_counts)},
        },
        "confusion": confusion,
        "integrity": {
            "jev_key_required": False,
            "decision_input_truth_free": True,
            "reference_and_holdout_separated": split_separation_verified,
            "rules_before_replay": True,
            "frozen_dev_holdout_policy": freeze_manifest["policy"]["verified"]
            and freeze_manifest["replay"]["verified"],
            "freeze_manifest": freeze_manifest,
            "screenshot_absence_cannot_be_success": True,
            "task_completion_is_separate_from_action_effect": True,
        },
        "results": results,
        "independent_evaluation": independent_report,
    }


def render_verification_report(report: Mapping[str, Any]) -> str:
    return render_json(report)


__all__ = [
    "ControlDecision",
    "ReplayClassifier",
    "RuleVerifier",
    "VerificationController",
    "VerificationInputError",
    "VerificationResult",
    "render_verification_report",
    "run_verification_dataset",
]
