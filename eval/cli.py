"""Command-line entry point for the task-02 synthetic evaluator."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

if __package__ in (None, ""):
    # Keep the CLI independently runnable both as ``python -m eval.cli`` and
    # as ``python eval/cli.py`` from the repository root.
    from engine import EvaluationInputError, evaluate_files, render_report
else:
    from .engine import EvaluationInputError, evaluate_files, render_report


DEFAULT_FIXTURES = Path(__file__).resolve().parent / "fixtures"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Evaluate synthetic Jev-MobileAgent trajectories using separate page truth."
    )
    parser.add_argument(
        "--tasks",
        type=Path,
        default=DEFAULT_FIXTURES / "synthetic_tasks.json",
        help="task manifest and truth-free decision payloads",
    )
    parser.add_argument(
        "--traces",
        type=Path,
        default=DEFAULT_FIXTURES / "synthetic_traces.json",
        help="agent observations, actions, and receipts",
    )
    parser.add_argument(
        "--truth",
        type=Path,
        default=DEFAULT_FIXTURES / "synthetic_truth.json",
        help="independent external page truth and task labels",
    )
    parser.add_argument(
        "--split",
        choices=("all", "dev", "holdout"),
        default="all",
        help="evaluate all records or one task/trajectory split",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="write deterministic JSON here; stdout is used when omitted",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        report = evaluate_files(args.tasks, args.traces, args.truth, split=args.split)
        rendered = render_report(report)
        if args.output is None or str(args.output) == "-":
            sys.stdout.write(rendered)
        else:
            args.output.write_text(rendered, encoding="utf-8")
    except (EvaluationInputError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
