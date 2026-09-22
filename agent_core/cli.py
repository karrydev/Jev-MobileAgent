"""Public offline CLI for the batch 11 and 12 pipelines."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

from .common import OfflineInputError
from .selection import render_selection_report, run_selection_dataset
from .verification import render_verification_report, run_verification_dataset


FIXTURE_ROOT = Path(__file__).resolve().parent / "fixtures"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Run deterministic Jev-MobileAgent offline selection and verification replays."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    selection = subparsers.add_parser(
        "selection",
        help="build complete candidates, replay a selector, simulate effects, and report independently",
    )
    selection.add_argument(
        "--cases",
        type=Path,
        default=FIXTURE_ROOT / "selection_cases.json",
        help="truth-free current observations and deterministic simulator rules",
    )
    selection.add_argument(
        "--replay",
        type=Path,
        default=FIXTURE_ROOT / "selection_replay.json",
        help="candidate IDs selected by the offline replay",
    )
    selection.add_argument(
        "--truth",
        type=Path,
        default=FIXTURE_ROOT / "selection_truth.json",
        help="independent action and task truth, never passed to candidate construction",
    )
    selection.add_argument("--split", choices=("all", "dev", "holdout"), default="all")
    selection.add_argument("--now", type=int, default=None, help="explicit observation clock for expiry checks")
    selection.add_argument("--output", type=Path, default=None)

    verification = subparsers.add_parser(
        "verification",
        help="classify before/after evidence into four states and drive bounded control",
    )
    verification.add_argument(
        "--cases",
        type=Path,
        default=FIXTURE_ROOT / "verification_cases.json",
        help="before/after evidence and action postconditions",
    )
    verification.add_argument(
        "--replay",
        type=Path,
        default=FIXTURE_ROOT / "verification_replay.json",
        help="frozen classifier labels used only after rules return UNKNOWN",
    )
    verification.add_argument(
        "--truth",
        type=Path,
        default=FIXTURE_ROOT / "verification_truth.json",
        help="independent action and task truth",
    )
    verification.add_argument("--split", choices=("all", "dev", "holdout"), default="all")
    verification.add_argument("--output", type=Path, default=None)
    return parser


def _write_or_print(rendered: str, output: Path | None) -> None:
    if output is None or str(output) == "-":
        sys.stdout.write(rendered)
    else:
        output.write_text(rendered, encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "selection":
            report = run_selection_dataset(
                args.cases,
                args.replay,
                args.truth,
                split=args.split,
                now=args.now,
            )
            _write_or_print(render_selection_report(report), args.output)
        else:
            report = run_verification_dataset(
                args.cases,
                args.replay,
                args.truth,
                split=args.split,
            )
            _write_or_print(render_verification_report(report), args.output)
    except (OfflineInputError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
