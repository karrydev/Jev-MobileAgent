import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "eval" / "fixtures"


def run_cli_files(
    tasks_path: Path,
    traces_path: Path,
    truth_path: Path,
    *extra_args: str,
) -> subprocess.CompletedProcess[str]:
    command = [
        sys.executable,
        "-m",
        "eval.cli",
        "--tasks",
        str(tasks_path),
        "--traces",
        str(traces_path),
        "--truth",
        str(truth_path),
        *extra_args,
    ]
    return subprocess.run(
        command,
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
    )


def run_cli(*extra_args: str) -> subprocess.CompletedProcess[str]:
    return run_cli_files(
        FIXTURES / "synthetic_tasks.json",
        FIXTURES / "synthetic_traces.json",
        FIXTURES / "synthetic_truth.json",
        *extra_args,
    )


class PublicCliTests(unittest.TestCase):
    def test_report_is_deterministic_and_separates_action_from_task(self) -> None:
        first = run_cli()
        second = run_cli()
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(first.stdout, second.stdout)

        report = json.loads(first.stdout)
        self.assertEqual(report["report_version"], "jev-independent-evaluation/report-v1")
        self.assertEqual(report["selected_split"], "all")
        self.assertEqual(report["counts"]["tasks"], 6)

        results = {item["task_id"]: item for item in report["results"]}
        incomplete = results["task-dev-action-success-incomplete"]
        self.assertEqual(
            incomplete["steps"][0]["action_verification"]["status"], "SUCCESS"
        )
        self.assertEqual(incomplete["task_completion"]["status"], "FAILURE")
        self.assertEqual(incomplete["task_completion"]["failure_class"], "agent")
        self.assertEqual(incomplete["task_completion"]["reason"], "task_incomplete")

        no_effect = results["task-dev-no-effect"]
        self.assertEqual(
            no_effect["steps"][0]["action_verification"]["status"], "FAILURE"
        )
        self.assertEqual(
            no_effect["steps"][0]["action_verification"]["failure_class"], "agent"
        )
        self.assertEqual(no_effect["task_completion"]["status"], "FAILURE")

        loading = results["task-holdout-loading"]
        self.assertEqual(
            loading["steps"][0]["action_verification"]["status"], "PENDING"
        )
        self.assertEqual(loading["task_completion"]["status"], "PENDING")

        missing = results["task-holdout-insufficient-evidence"]
        self.assertEqual(
            missing["steps"][0]["action_verification"]["status"], "UNKNOWN"
        )
        self.assertEqual(missing["task_completion"]["status"], "UNKNOWN")
        self.assertEqual(missing["task_completion"]["failure_class"], "environment")

        environment = results["task-holdout-environment-failure"]
        self.assertEqual(environment["task_completion"]["failure_class"], "environment")
        self.assertEqual(
            report["counts"]["failure_class"], {"agent": 2, "environment": 2}
        )

    def test_split_selection_keeps_task_and_trajectory_boundaries(self) -> None:
        completed = run_cli("--split", "dev")
        self.assertEqual(completed.returncode, 0, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual(report["counts"]["tasks"], 3)
        self.assertEqual({item["split"] for item in report["results"]}, {"dev"})
        self.assertTrue(report["integrity"]["split_isolation"])
        self.assertTrue(report["integrity"]["decision_payload_schema_validated"])

        holdout = run_cli("--split", "holdout")
        self.assertEqual(holdout.returncode, 0, holdout.stderr)
        holdout_report = json.loads(holdout.stdout)
        self.assertEqual(holdout_report["counts"]["tasks"], 3)
        self.assertEqual({item["split"] for item in holdout_report["results"]}, {"holdout"})

    def test_report_names_structural_decision_payload_guarantee(self) -> None:
        completed = run_cli()
        self.assertEqual(completed.returncode, 0, completed.stderr)
        report = json.loads(completed.stdout)
        integrity = report["integrity"]
        self.assertEqual(
            integrity["decision_payload_schema"],
            "jev-independent-evaluation/decision-payload-v1",
        )
        self.assertTrue(integrity["decision_payload_schema_validated"])
        self.assertNotIn("decision_payload_is_truth_free", integrity)
        self.assertTrue(integrity["trace_truth_step_sets_match"])

    def test_truth_leak_in_decision_payload_is_rejected(self) -> None:
        tasks_path = FIXTURES / "synthetic_tasks.json"
        tasks = json.loads(tasks_path.read_text(encoding="utf-8"))
        tasks["tasks"][0]["decision_payload"]["ground_truth"] = {"status": "SUCCESS"}

        with tempfile.TemporaryDirectory() as directory:
            leaked_tasks = Path(directory) / "tasks.json"
            leaked_tasks.write_text(json.dumps(tasks), encoding="utf-8")
            completed = subprocess.run(
                [
                    sys.executable,
                    "-m",
                    "eval.cli",
                    "--tasks",
                    str(leaked_tasks),
                    "--traces",
                    str(FIXTURES / "synthetic_traces.json"),
                    "--truth",
                    str(FIXTURES / "synthetic_truth.json"),
                ],
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("decision_payload", completed.stderr)

    def test_future_decision_payload_fields_are_rejected_at_any_depth(self) -> None:
        for location in ("top_level", "initial_observation"):
            tasks = json.loads(
                (FIXTURES / "synthetic_tasks.json").read_text(encoding="utf-8")
            )
            if location == "top_level":
                tasks["tasks"][0]["decision_payload"]["observation_after"] = {
                    "order_submitted": True
                }
            else:
                tasks["tasks"][0]["decision_payload"]["initial_observation"][
                    "observation_after"
                ] = {"order_submitted": True}

            with self.subTest(location=location), tempfile.TemporaryDirectory() as directory:
                leaked_tasks = Path(directory) / "tasks.json"
                leaked_tasks.write_text(json.dumps(tasks), encoding="utf-8")
                completed = run_cli_files(
                    leaked_tasks,
                    FIXTURES / "synthetic_traces.json",
                    FIXTURES / "synthetic_truth.json",
                )

            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("unsupported field", completed.stderr)
            self.assertIn("observation_after", completed.stderr)

    def test_trace_step_without_truth_label_is_rejected(self) -> None:
        traces = json.loads(
            (FIXTURES / "synthetic_traces.json").read_text(encoding="utf-8")
        )
        traces["trajectories"][0]["steps"].append(
            {
                "step_id": "99-unscored",
                "observation_before": {"page": "product"},
                "action": {"target": "delete_order", "type": "tap"},
                "receipt": {"accepted": True},
                "observation_after": {"page": "product"},
            }
        )

        with tempfile.TemporaryDirectory() as directory:
            mutated_traces = Path(directory) / "traces.json"
            mutated_traces.write_text(json.dumps(traces), encoding="utf-8")
            completed = run_cli_files(
                FIXTURES / "synthetic_tasks.json",
                mutated_traces,
                FIXTURES / "synthetic_truth.json",
            )

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("extra_trace_steps", completed.stderr)
        self.assertIn("99-unscored", completed.stderr)

    def test_trace_step_without_agent_action_is_rejected(self) -> None:
        traces = json.loads(
            (FIXTURES / "synthetic_traces.json").read_text(encoding="utf-8")
        )
        traces["trajectories"][0]["steps"] = []

        with tempfile.TemporaryDirectory() as directory:
            mutated_traces = Path(directory) / "traces.json"
            mutated_traces.write_text(json.dumps(traces), encoding="utf-8")
            completed = run_cli_files(
                FIXTURES / "synthetic_tasks.json",
                mutated_traces,
                FIXTURES / "synthetic_truth.json",
            )

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("missing_trace_steps", completed.stderr)
        self.assertIn("01-add", completed.stderr)

    def test_duplicate_trace_step_id_is_rejected(self) -> None:
        traces = json.loads(
            (FIXTURES / "synthetic_traces.json").read_text(encoding="utf-8")
        )
        duplicate = dict(traces["trajectories"][0]["steps"][0])
        traces["trajectories"][0]["steps"].append(duplicate)

        with tempfile.TemporaryDirectory() as directory:
            mutated_traces = Path(directory) / "traces.json"
            mutated_traces.write_text(json.dumps(traces), encoding="utf-8")
            completed = run_cli_files(
                FIXTURES / "synthetic_tasks.json",
                mutated_traces,
                FIXTURES / "synthetic_truth.json",
            )

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("duplicate id '01-add'", completed.stderr)


if __name__ == "__main__":
    unittest.main()
