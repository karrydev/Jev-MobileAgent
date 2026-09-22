from __future__ import annotations

import copy
import json
import tempfile
import unittest
from pathlib import Path

from services.task_evidence.evidence import (
    EVIDENCE_REPORT_VERSION,
    EvidenceIntegrityError,
    PriceBook,
    build_report,
    render_report,
)
from services.task_evidence.cli import main as evidence_cli
from services.task_evidence.runner import run_simulated_task, write_json


class TaskEvidenceTests(unittest.TestCase):
    def test_one_command_writes_versioned_report_and_replayable_raw_result(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw_path = root / "raw.json"
            report_path = root / "report.json"
            replay_path = root / "replay.json"
            self.assertEqual(
                evidence_cli(
                    [
                        "run",
                        "--scenario",
                        "failure",
                        "--task-id",
                        "evidence-cli",
                        "--output",
                        str(report_path),
                        "--raw-output",
                        str(raw_path),
                    ]
                ),
                0,
            )
            report = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual(report["report_version"], EVIDENCE_REPORT_VERSION)
            self.assertTrue(raw_path.with_suffix(".truth.json").exists())
            self.assertEqual(
                evidence_cli(["replay", "--input", str(raw_path), "--output", str(replay_path)]),
                0,
            )
            replayed = json.loads(replay_path.read_text(encoding="utf-8"))
            self.assertEqual(replayed["run"]["independent_status"], "FAILURE")

    def test_public_report_uses_real_runtime_trace_and_independent_eval(self) -> None:
        runtime = run_simulated_task(flavor="success", task_id="evidence-success")
        self.assertEqual(runtime["state"], "SUCCEEDED")
        self.assertEqual([event["kind"] for event in runtime["trace"]][0], "task.submitted")
        report = build_report(runtime)

        self.assertEqual(report["report_version"], EVIDENCE_REPORT_VERSION)
        self.assertTrue(report["integrity"]["runtime_trace_validated"])
        self.assertTrue(report["integrity"]["independent_evaluation_used"])
        self.assertEqual(report["run"]["independent_status"], "SUCCESS")
        self.assertEqual(report["independent_evaluation"]["counts"]["task_completion"], {"SUCCESS": 1})
        self.assertEqual(report["attempts"]["model_calls"], 1)
        self.assertEqual(report["usage"]["by_role_model"][0]["role"], "planner")
        self.assertEqual(report["latency"]["by_role_model"][0]["model"], "replay-fixture")

        rendered = render_report(report)
        self.assertNotIn("private prompt omitted", rendered)
        self.assertNotIn("private-bytes", rendered)
        self.assertNotIn("private-image", rendered)

    def test_default_fallback_is_accounted_without_fabricating_model_usage(self) -> None:
        runtime = run_simulated_task(flavor="fallback", task_id="evidence-fallback")
        report = build_report(runtime)
        self.assertEqual(report["run"]["independent_status"], "SUCCESS")
        self.assertEqual(report["attempts"]["total"], 1)
        self.assertEqual(report["attempts"]["fallbacks"], 1)
        self.assertEqual(report["attempts"]["model_calls"], 0)
        self.assertFalse(report["attempts"]["records"][0]["call_made"])
        self.assertEqual(report["attempts"]["records"][0]["usage"]["status"], "unknown")
        self.assertEqual(report["cost"]["status"], "unknown")

    def test_failure_is_independently_scored_and_can_be_replayed(self) -> None:
        runtime = run_simulated_task(flavor="failure", task_id="evidence-failure")
        report = build_report(runtime)
        self.assertEqual(runtime["state"], "FAILED")
        self.assertEqual(report["run"]["independent_status"], "FAILURE")
        self.assertEqual(report["independent_evaluation"]["counts"]["failure_class"], {"agent": 1})
        self.assertEqual(report["independent_evaluation"]["results"][0]["steps"][0]["status"], "FAILURE")

        with tempfile.TemporaryDirectory() as directory:
            raw_path = Path(directory) / "raw.json"
            write_json(raw_path, runtime)
            replayed = build_report(json.loads(raw_path.read_text(encoding="utf-8")))
        self.assertEqual(replayed["run"]["runtime_result_sha256"], report["run"]["runtime_result_sha256"])
        self.assertEqual(replayed["run"]["independent_status"], "FAILURE")

    def test_retry_and_missing_usage_are_counted_per_role(self) -> None:
        runtime = run_simulated_task(flavor="retry", task_id="evidence-retry")
        report = build_report(runtime)
        self.assertEqual(runtime["state"], "SUCCEEDED")
        self.assertEqual(report["attempts"]["total"], 3)
        self.assertEqual(report["attempts"]["model_calls"], 3)
        self.assertEqual(report["attempts"]["retries"], 2)
        self.assertEqual(report["usage"]["input_tokens"], 12)
        self.assertEqual(report["usage"]["output_tokens"], 2)
        self.assertEqual(report["usage"]["status"], "partial")
        self.assertEqual(report["usage"]["by_role_model"][0]["usage_status"], "partial")
        self.assertEqual(report["attempts"]["records"][1]["usage"]["status"], "unknown")
        self.assertEqual(report["cost"]["status"], "unknown")

    def test_cancellation_keeps_late_model_attempt_but_never_reports_success(self) -> None:
        runtime = run_simulated_task(flavor="cancel", task_id="evidence-cancel")
        report = build_report(runtime)
        self.assertEqual(runtime["state"], "CANCELLED")
        kinds = [event["kind"] for event in runtime["trace"]]
        self.assertIn("task.cancelled", kinds)
        self.assertIn("model.responded", kinds)
        self.assertEqual(report["attempts"]["model_calls"], 1)
        self.assertEqual(report["attempts"]["cancelled"], 1)
        self.assertEqual(report["run"]["independent_status"], "UNKNOWN")
        self.assertNotEqual(report["run"]["independent_status"], "SUCCESS")

    def test_missing_event_cannot_be_reported_as_success(self) -> None:
        runtime = run_simulated_task(flavor="success", task_id="evidence-tampered")
        runtime["trace"] = [event for event in runtime["trace"] if event["kind"] != "verification.recorded"]
        with self.assertRaises(EvidenceIntegrityError):
            build_report(runtime)

    def test_duplicate_receipt_fact_cannot_rewrite_causal_order(self) -> None:
        runtime = run_simulated_task(flavor="success", task_id="evidence-duplicate-receipt")
        truth = runtime["evidence_context"]["truth_sidecar"]
        tampered = copy.deepcopy(runtime)
        tampered.pop("evidence_context")
        trace = tampered["trace"]
        action_position = next(index for index, event in enumerate(trace) if event["kind"] == "action.dispatched")
        receipt_event = next(event for event in trace if event["kind"] == "receipt.received")

        # Keep the receipt's entity and links valid, but give the inserted fact
        # a fresh event id and rebuild the sequence chain as a real producer
        # would.  The old position map used the later copy and reported success.
        inserted_receipt = copy.deepcopy(receipt_event)
        inserted_receipt["event_id"] = "receipt-injected-before-action"
        trace.insert(action_position, inserted_receipt)
        for sequence, event in enumerate(trace):
            event["sequence"] = sequence
            event["previous_event_id"] = None if sequence == 0 else trace[sequence - 1]["event_id"]

        with self.assertRaises(EvidenceIntegrityError):
            build_report(tampered, truth=truth)

    def test_versioned_local_price_book_can_price_only_known_usage(self) -> None:
        runtime = run_simulated_task(flavor="success", task_id="evidence-priced")
        prices = PriceBook(
            {
                "format": "jev-task-evidence/pricing-v1",
                "price_book_version": "fixture-reviewed-v1",
                "currency": "USD",
                "models": {
                    "replay-fixture": {
                        "input_usd_per_million": 1.0,
                        "output_usd_per_million": 2.0,
                    }
                },
            }
        )
        report = build_report(runtime, price_book=prices)
        self.assertEqual(report["versions"]["price_book"], "fixture-reviewed-v1")
        self.assertEqual(report["cost"]["status"], "known")
        self.assertEqual(report["cost"]["amount"], 0.000018)

    def test_replay_uses_device_sidecar_when_raw_flavor_is_tampered(self) -> None:
        runtime = run_simulated_task(flavor="failure", task_id="evidence-sidecar")
        tampered = copy.deepcopy(runtime)
        tampered["evidence_context"]["scenario_flavor"] = "success"
        report = build_report(tampered)
        self.assertEqual(report["run"]["independent_status"], "FAILURE")

    def test_scene_tampering_cannot_change_the_independent_score(self) -> None:
        runtime = run_simulated_task(flavor="failure", task_id="evidence-scene-tamper")
        tampered = copy.deepcopy(runtime)
        tampered["observations"][-1]["page_state"] = "done"
        with self.assertRaises(EvidenceIntegrityError):
            build_report(tampered)

    def test_verification_trace_link_mismatch_is_rejected(self) -> None:
        runtime = run_simulated_task(flavor="success", task_id="evidence-link-mismatch")
        truth = runtime["evidence_context"]["truth_sidecar"]
        tampered = copy.deepcopy(runtime)
        tampered.pop("evidence_context")
        verification_event = next(event for event in tampered["trace"] if event["kind"] == "verification.recorded")
        verification_event["links"]["action_id"] = "wrong-action-id"
        with self.assertRaises(EvidenceIntegrityError):
            build_report(tampered, truth=truth)

    def test_verification_observation_relationship_is_rejected(self) -> None:
        runtime = run_simulated_task(flavor="success", task_id="evidence-verification-observation")
        truth = runtime["evidence_context"]["truth_sidecar"]
        tampered = copy.deepcopy(runtime)
        tampered.pop("evidence_context")
        tampered["verification"]["before_observation_id"] = "wrong-before-observation"
        with self.assertRaises(EvidenceIntegrityError):
            build_report(tampered, truth=truth)

    def test_public_trace_anonymizes_ids_and_omits_raw_links(self) -> None:
        runtime = run_simulated_task(flavor="success", task_id="evidence-public-trace")
        truth = runtime["evidence_context"]["truth_sidecar"]
        tampered = copy.deepcopy(runtime)
        tampered.pop("evidence_context")
        sentinel = "credential-shaped-sentinel"
        action_id = tampered["action"]["action_id"]
        tampered["action"]["action_id"] = sentinel
        tampered["receipt"]["action_id"] = sentinel
        tampered["verification"]["action_id"] = sentinel
        for event in tampered["trace"]:
            if event["kind"] == "action.dispatched":
                event["entity_id"] = sentinel
                event["links"]["action_id"] = sentinel
            elif event["kind"] == "receipt.received":
                event["links"]["action_id"] = sentinel
            elif event["kind"] == "verification.recorded":
                event["links"]["action_id"] = sentinel
        report = build_report(tampered, truth=truth)
        rendered = json.dumps(report, ensure_ascii=False, sort_keys=True)
        self.assertNotIn(sentinel, rendered)
        self.assertTrue(all("links" not in event for event in report["trace"]))
        self.assertNotEqual(action_id, sentinel)


if __name__ == "__main__":
    unittest.main()
