"""Command line interface for local task evidence reports."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from .evidence import EvidenceError, build_report, load_price_book, render_report
from .runner import run_simulated_task, write_json


FLAVORS = ("success", "failure", "retry", "fallback", "cancel")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run the public simulated task entry point and emit a redacted evidence report")
    subparsers = parser.add_subparsers(dest="command", required=True)

    run_parser = subparsers.add_parser("run", help="run one synthetic task over localhost HTTP")
    run_parser.add_argument("--scenario", choices=(*FLAVORS, "all"), default="success")
    run_parser.add_argument("--task-id", default=None)
    run_parser.add_argument("--auth-token", default=None, help="local synthetic token; no supplier credential is read")
    run_parser.add_argument("--output", type=Path, default=None, help="public report path; stdout when omitted")
    run_parser.add_argument("--raw-output", type=Path, default=None, help="optional controlled local path for raw runtime result(s)")
    run_parser.add_argument("--price-book", type=Path, default=None)
    run_parser.add_argument("--split", choices=("dev", "holdout"), default="dev")
    run_parser.set_defaults(handler=_run)

    replay_parser = subparsers.add_parser("replay", help="rebuild a report from a locally retained raw runtime result")
    replay_parser.add_argument("--input", type=Path, required=True)
    replay_parser.add_argument("--truth", type=Path, default=None, help="optional independent truth record/document")
    replay_parser.add_argument("--output", type=Path, default=None)
    replay_parser.add_argument("--price-book", type=Path, default=None)
    replay_parser.add_argument("--split", choices=("dev", "holdout"), default="dev")
    replay_parser.set_defaults(handler=_replay)
    return parser


def _load_json(path: Path) -> Any:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise EvidenceError(f"cannot read JSON input {path}: {exc}") from exc
    return value


def _emit(report: Any, output: Path | None) -> None:
    rendered = render_report(report) if isinstance(report, dict) else json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if output is None or str(output) == "-":
        sys.stdout.write(rendered)
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered, encoding="utf-8")


def _batch_report(reports: list[dict[str, Any]]) -> dict[str, Any]:
    states: dict[str, int] = {}
    independent: dict[str, int] = {}
    total_attempts = 0
    model_calls = 0
    retries = 0
    fallbacks = 0
    for report in reports:
        state = report["run"]["state"]
        states[state] = states.get(state, 0) + 1
        status = report["run"]["independent_status"]
        independent[status] = independent.get(status, 0) + 1
        attempts = report["attempts"]
        total_attempts += attempts["total"]
        model_calls += attempts["model_calls"]
        retries += attempts["retries"]
        fallbacks += attempts["fallbacks"]
    return {
        "report_version": "jev-task-evidence/batch-report-v1",
        "versions": {"member_report": reports[0]["report_version"] if reports else None},
        "counts": {"runs": len(reports), "state": dict(sorted(states.items())), "independent_status": dict(sorted(independent.items()))},
        "attempts": {"total": total_attempts, "model_calls": model_calls, "retries": retries, "fallbacks": fallbacks},
        "runs": reports,
    }


def _truth_sidecar_path(raw_path: Path) -> Path:
    return raw_path.with_suffix(".truth.json")


def _run(args: argparse.Namespace, parser: argparse.ArgumentParser) -> int:
    del parser
    price_book = load_price_book(args.price_book)
    flavors = FLAVORS if args.scenario == "all" else (args.scenario,)
    raw_results: list[dict[str, Any]] = []
    reports: list[dict[str, Any]] = []
    for index, flavor in enumerate(flavors, start=1):
        task_id = args.task_id if args.task_id and len(flavors) == 1 else f"task-evidence-{flavor}-{index}"
        raw = run_simulated_task(flavor=flavor, task_id=task_id, token=args.auth_token)
        raw_results.append(raw)
        reports.append(
            build_report(
                raw,
                split=args.split,
                price_book=price_book,
            )
        )
    if args.raw_output is not None:
        raw_value = raw_results[0] if len(raw_results) == 1 else raw_results
        write_json(args.raw_output, raw_value)
        if len(raw_results) == 1:
            sidecar = raw_results[0].get("evidence_context", {}).get("truth_sidecar")
            if isinstance(sidecar, dict):
                write_json(_truth_sidecar_path(args.raw_output), sidecar)
    _emit(reports[0] if len(reports) == 1 else _batch_report(reports), args.output)
    return 0


def _replay(args: argparse.Namespace, parser: argparse.ArgumentParser) -> int:
    del parser
    raw = _load_json(args.input)
    if not isinstance(raw, dict):
        raise EvidenceError("--input must contain one raw runtime result object")
    if args.truth is not None:
        truth = _load_json(args.truth)
    else:
        sidecar_path = _truth_sidecar_path(args.input)
        truth = _load_json(sidecar_path) if sidecar_path.exists() else None
    price_book = load_price_book(args.price_book)
    _emit(build_report(raw, truth=truth, split=args.split, price_book=price_book), args.output)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.handler(args, parser)
    except (EvidenceError, OSError, RuntimeError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
