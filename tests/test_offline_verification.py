from __future__ import annotations

import json
import copy
from pathlib import Path
import subprocess
import sys
import unittest

from agent_core.verification import (
    RuleVerifier,
    VerificationController,
    VerificationInputError,
    run_verification_dataset,
)


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "agent_core" / "fixtures"


def _load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


class OfflineVerificationBehaviorTests(unittest.TestCase):
    def test_all_four_states_and_control_branches_are_reported(self) -> None:
        report = run_verification_dataset(
            _load("verification_cases.json"),
            _load("verification_replay.json"),
            _load("verification_truth.json"),
        )
        self.assertEqual(
            report["counts"]["verification"],
            {"FAILURE": 1, "PENDING": 2, "SUCCESS": 2, "UNKNOWN": 2},
        )
        self.assertEqual(
            report["counts"]["control"],
            {"CONTINUE": 2, "PAUSE": 3, "VISUAL_FALLBACK": 1, "WAIT": 1},
        )
        self.assertEqual(report["confusion"]["SUCCESS"]["SUCCESS"], 2)
        self.assertEqual(report["confusion"]["FAILURE"]["FAILURE"], 1)
        self.assertEqual(report["confusion"]["PENDING"]["PENDING"], 2)
        self.assertEqual(report["confusion"]["UNKNOWN"]["UNKNOWN"], 2)
        results = {item["case_id"]: item for item in report["results"]}
        self.assertEqual(results["ver-dev-success-incomplete"]["classification"]["final"]["status"], "SUCCESS")
        self.assertEqual(results["ver-dev-failure"]["control"]["command"], "PAUSE")
        self.assertEqual(results["ver-dev-pending"]["control"]["command"], "WAIT")
        self.assertEqual(results["ver-holdout-after-missing"]["control"]["command"], "VISUAL_FALLBACK")
        self.assertEqual(results["ver-holdout-wait-limit"]["control"]["reason"], "wait_limit_reached")

    def test_screenshot_absence_cannot_be_promoted_to_success_by_replay(self) -> None:
        report = run_verification_dataset(
            _load("verification_cases.json"),
            _load("verification_replay.json"),
            _load("verification_truth.json"),
            split="holdout",
        )
        result = next(item for item in report["results"] if item["case_id"] == "ver-holdout-screenshot-missing")
        self.assertEqual(result["classification"]["rules"]["status"], "UNKNOWN")
        self.assertEqual(result["classification"]["final"]["status"], "UNKNOWN")
        self.assertEqual(result["classification"]["final"]["reason"], "screenshot_missing")
        self.assertNotEqual(result["classification"]["final"]["status"], "SUCCESS")
        self.assertTrue(report["integrity"]["screenshot_absence_cannot_be_success"])

    def test_missing_before_screenshot_also_cannot_be_success(self) -> None:
        case = next(
            item
            for item in _load("verification_cases.json")["cases"]
            if item["case_id"] == "ver-dev-success-incomplete"
        )
        case["before"]["screenshot"] = {"available": False}
        result = RuleVerifier().verify(case)
        self.assertEqual(result.status, "UNKNOWN")
        self.assertEqual(result.reason, "before_screenshot_missing")

    def test_missing_before_screenshot_pauses_even_when_visual_fallback_is_available(self) -> None:
        case = copy.deepcopy(
            next(
                item
                for item in _load("verification_cases.json")["cases"]
                if item["case_id"] == "ver-dev-success-incomplete"
            )
        )
        case["before"]["screenshot"] = {"available": False}
        result = RuleVerifier().verify(case)
        decision = VerificationController().decide(
            result.status,
            reason=result.reason,
            before_screenshot_available=result.evidence["before_screenshot_available"],
            visual_fallback_available=True,
        )
        self.assertEqual(decision.command, "PAUSE")
        self.assertEqual(decision.reason, "before_screenshot_missing")

    def test_runner_passes_missing_before_screenshot_reason_to_controller(self) -> None:
        cases = _load("verification_cases.json")
        case = next(item for item in cases["cases"] if item["case_id"] == "ver-dev-success-incomplete")
        case["before"]["screenshot"] = {"available": False}
        report = run_verification_dataset(
            cases,
            _load("verification_replay.json"),
            _load("verification_truth.json"),
            split="dev",
        )
        result = next(item for item in report["results"] if item["case_id"] == "ver-dev-success-incomplete")
        self.assertEqual(result["classification"]["final"]["reason"], "before_screenshot_missing")
        self.assertEqual(result["control"]["command"], "PAUSE")

    def test_action_effect_is_separate_from_task_completion(self) -> None:
        report = run_verification_dataset(
            _load("verification_cases.json"),
            _load("verification_replay.json"),
            _load("verification_truth.json"),
            split="dev",
        )
        result = next(item for item in report["results"] if item["case_id"] == "ver-dev-success-incomplete")
        self.assertEqual(result["classification"]["final"]["status"], "SUCCESS")
        self.assertEqual(result["control"]["command"], "CONTINUE")
        self.assertEqual(result["independent_task_completion"]["status"], "FAILURE")
        self.assertEqual(
            report["independent_evaluation"]["results"][0]["task_completion"]["status"],
            "FAILURE",
        )

    def test_replay_only_runs_after_rules_and_has_success_guard(self) -> None:
        report = run_verification_dataset(
            _load("verification_cases.json"),
            _load("verification_replay.json"),
            _load("verification_truth.json"),
            split="holdout",
        )
        results = {item["case_id"]: item for item in report["results"]}
        replay_success = results["ver-holdout-replay-success"]
        self.assertEqual(replay_success["classification"]["rules"]["status"], "UNKNOWN")
        self.assertEqual(replay_success["classification"]["final"]["status"], "SUCCESS")
        self.assertTrue(replay_success["classification"]["replay_used"])
        missing = results["ver-holdout-after-missing"]
        self.assertEqual(missing["classification"]["final"]["source"], "safety_guard")

    def test_controller_bounds_wait_and_pauses_after_limit(self) -> None:
        controller = VerificationController()
        self.assertEqual(
            controller.decide("PENDING", wait_attempt=1, max_waits=2).as_dict()["command"],
            "WAIT",
        )
        decision = controller.decide("PENDING", wait_attempt=2, max_waits=2)
        self.assertEqual(decision.command, "PAUSE")
        self.assertEqual(decision.reason, "wait_limit_reached")
        self.assertEqual(controller.decide("UNKNOWN", visual_fallback_available=True).command, "VISUAL_FALLBACK")
        self.assertEqual(
            controller.decide("UNKNOWN", visual_fallback_available=True, visual_fallback_attempted=True).command,
            "PAUSE",
        )

    def test_dev_and_holdout_share_frozen_policy_and_cli_is_public(self) -> None:
        cases = _load("verification_cases.json")
        replay = _load("verification_replay.json")
        truth = _load("verification_truth.json")
        dev = run_verification_dataset(cases, replay, truth, split="dev")
        holdout = run_verification_dataset(cases, replay, truth, split="holdout")
        self.assertEqual(dev["rule_version"], holdout["rule_version"])
        self.assertEqual(dev["classifier_version"], holdout["classifier_version"])
        self.assertTrue(dev["integrity"]["frozen_dev_holdout_policy"])
        completed = subprocess.run(
            [sys.executable, "-m", "agent_core", "verification", "--split", "dev"],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        cli_report = json.loads(completed.stdout)
        self.assertFalse(cli_report["integrity"]["jev_key_required"])
        self.assertEqual(cli_report["selected_split"], "dev")

    def test_dev_run_rejects_cross_split_case_identity_before_filtering(self) -> None:
        cases = _load("verification_cases.json")
        cases["cases"][0]["trajectory_id"] = cases["cases"][-1]["trajectory_id"]
        with self.assertRaisesRegex(VerificationInputError, "crosses dev/holdout"):
            run_verification_dataset(
                cases,
                _load("verification_replay.json"),
                _load("verification_truth.json"),
                split="dev",
            )

    def test_dev_run_rejects_changed_holdout_replay_under_same_version(self) -> None:
        replay = _load("verification_replay.json")
        replay["classifications"][0]["reason"] = "changed_holdout_label"
        with self.assertRaisesRegex(VerificationInputError, "frozen verification replay"):
            run_verification_dataset(
                _load("verification_cases.json"), replay, _load("verification_truth.json"), split="dev"
            )


if __name__ == "__main__":
    unittest.main()
