from __future__ import annotations

import json
import copy
from pathlib import Path
import subprocess
import sys
import unittest

from agent_core.selection import SelectionInputError, build_candidates, run_selection_dataset


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "agent_core" / "fixtures"


def _load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def _walk(value):
    if isinstance(value, dict):
        for key, child in value.items():
            yield key
            yield from _walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk(child)


class OfflineSelectionBehaviorTests(unittest.TestCase):
    def test_public_pipeline_selects_chinese_equivalent_and_simulates_known_text(self) -> None:
        report = run_selection_dataset(
            _load("selection_cases.json"),
            _load("selection_replay.json"),
            _load("selection_truth.json"),
        )
        results = {item["case_id"]: item for item in report["results"]}
        save = results["sel-dev-zh-save"]
        self.assertEqual(save["selection"]["status"], "SELECTED")
        candidate = save["selection"]["candidate"]
        self.assertTrue(candidate["accepted_action_set"]["equivalent"])
        self.assertEqual(candidate["accepted_action_set"]["labels"], ["保存", "Save"])
        self.assertEqual(save["simulation"]["after"]["saved"], True)

        text = results["sel-dev-zh-input"]["selection"]["candidate"]["action"]
        self.assertEqual(text["parameters"], {"text": "蓝色杯子"})
        self.assertEqual(
            results["sel-dev-zh-input"]["simulation"]["after"]["query"],
            "蓝色杯子",
        )
        self.assertEqual(report["independent_evaluation"]["counts"]["task_completion"]["SUCCESS"], 2)

    def test_invalid_empty_expired_and_missing_parameter_have_explicit_fallbacks(self) -> None:
        report = run_selection_dataset(
            _load("selection_cases.json"),
            _load("selection_replay.json"),
            _load("selection_truth.json"),
        )
        results = {item["case_id"]: item for item in report["results"]}
        self.assertEqual(
            results["sel-dev-invalid-id"]["selection"]["fallback"]["reason"],
            "invalid_candidate_id",
        )
        self.assertEqual(
            results["sel-dev-missing-parameter"]["selection"]["fallback"]["reason"],
            "missing_required_parameter",
        )
        self.assertEqual(
            results["sel-holdout-expired"]["selection"]["fallback"]["reason"],
            "observation_expired",
        )
        self.assertEqual(
            results["sel-holdout-empty"]["selection"]["fallback"]["reason"],
            "empty_candidates",
        )
        wrong = results["sel-dev-wrong-selection"]
        self.assertEqual(wrong["selection"]["status"], "SELECTED")
        self.assertFalse(wrong["simulation"]["changed"])
        self.assertEqual(
            report["independent_evaluation"]["results"][0]["task_completion"]["status"],
            "FAILURE",
        )
        self.assertEqual(report["counts"]["selection"], {"FALLBACK": 4, "SELECTED": 3})
        self.assertEqual(
            report["counts"]["fallback_reason"],
            {
                "empty_candidates": 1,
                "invalid_candidate_id": 1,
                "missing_required_parameter": 1,
                "observation_expired": 1,
            },
        )

    def test_candidates_do_not_contain_reference_or_future_fields(self) -> None:
        report = run_selection_dataset(
            _load("selection_cases.json"),
            _load("selection_replay.json"),
            _load("selection_truth.json"),
        )
        forbidden = {
            "answer",
            "backend_truth",
            "future",
            "future_frame",
            "future_state",
            "ground_truth",
            "next_frame",
            "next_observation",
            "oracle",
            "reference",
            "reference_answer",
            "task_completion",
            "truth",
        }
        candidate_keys = set()
        for result in report["results"]:
            candidate_keys.update(_walk(result["candidates"]))
        self.assertTrue(forbidden.isdisjoint(candidate_keys))

    def test_dev_and_holdout_use_the_same_frozen_policy_and_report_is_deterministic(self) -> None:
        cases = _load("selection_cases.json")
        replay = _load("selection_replay.json")
        truth = _load("selection_truth.json")
        dev = run_selection_dataset(cases, replay, truth, split="dev")
        holdout = run_selection_dataset(cases, replay, truth, split="holdout")
        self.assertEqual(dev["policy_version"], holdout["policy_version"])
        self.assertTrue(dev["integrity"]["policy_frozen_before_holdout"])
        self.assertEqual(
            json.dumps(dev, ensure_ascii=False, sort_keys=True),
            json.dumps(run_selection_dataset(cases, replay, truth, split="dev"), ensure_ascii=False, sort_keys=True),
        )
        self.assertEqual(dev["selected_split"], "dev")
        self.assertEqual(holdout["selected_split"], "holdout")

    def test_public_selection_cli_runs_without_jev_key(self) -> None:
        completed = subprocess.run(
            [sys.executable, "-m", "agent_core", "selection", "--split", "holdout"],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertFalse(report["integrity"]["jev_key_required"])
        self.assertEqual(report["selected_split"], "holdout")

    def test_builder_uses_only_known_parameter_for_input_text(self) -> None:
        observation = {
            "observation_id": "obs-1",
            "version": 1,
            "captured_at": 1,
            "expires_at": 9,
            "page": "form",
            "nodes": [
                {
                    "node_id": "field",
                    "role": "text_field",
                    "label": "内容",
                    "enabled": True,
                    "actions": [{"kind": "input_text", "parameter": "text"}],
                }
            ],
        }
        self.assertEqual(build_candidates(observation, {}, now=2).fallback["reason"], "missing_required_parameter")
        built = build_candidates(observation, {"text": "用户给定"}, now=2)
        self.assertEqual(built.candidates[0]["action"]["parameters"]["text"], "用户给定")

    def test_builder_without_reliable_clock_returns_explicit_fallback(self) -> None:
        observation = {
            "observation_id": "obs-clock-missing",
            "version": 1,
            "captured_at": 1,
            "expires_at": 2,
            "page": "home",
            "nodes": [
                {
                    "node_id": "continue",
                    "role": "button",
                    "label": "继续",
                    "enabled": True,
                    "actions": ["tap"],
                }
            ],
        }
        build = build_candidates(observation, {})
        self.assertEqual(build.candidates, ())
        self.assertEqual(build.fallback["reason"], "observation_clock_unavailable")

    def test_dev_run_rejects_cross_split_case_identity_before_filtering(self) -> None:
        cases = _load("selection_cases.json")
        cases["cases"][0]["task_id"] = cases["cases"][-1]["task_id"]
        with self.assertRaisesRegex(SelectionInputError, "crosses dev/holdout"):
            run_selection_dataset(
                cases,
                _load("selection_replay.json"),
                _load("selection_truth.json"),
                split="dev",
            )

    def test_dev_run_rejects_changed_holdout_replay_under_same_version(self) -> None:
        replay = _load("selection_replay.json")
        replay["selections"][-1]["candidate_id"] = "candidate-tap-something-else"
        with self.assertRaisesRegex(SelectionInputError, "frozen selection replay"):
            run_selection_dataset(
                _load("selection_cases.json"), replay, _load("selection_truth.json"), split="dev"
            )


if __name__ == "__main__":
    unittest.main()
