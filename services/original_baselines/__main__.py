"""CLI for the bounded task16 original-entry baselines."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

from .harness import (
    DEFAULT_ANDROID_CONSOLE_PORT,
    DEFAULT_ANDROID_GRPC_PORT,
    DEFAULT_CREDENTIAL_ENV,
    DEFAULT_ENDPOINT,
    DEFAULT_MODEL,
    DEFAULT_PHONE_PACKAGE,
    ENTRY_BUDGET_CNY,
    MAX_REQUESTS_PER_RUN,
    MAX_STEPS,
    MAX_TOKENS,
    evaluate_controlled_observation,
    run_androidworld_baseline,
    run_phone_baseline,
)


def _default_adb() -> str:
    return os.environ.get("JEV_ADB_PATH") or shutil.which("adb") or "adb"


def _common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--endpoint", default=os.environ.get("JEV_VLM_ENDPOINT", DEFAULT_ENDPOINT))
    parser.add_argument("--model", default=os.environ.get("JEV_VLM_MODEL", DEFAULT_MODEL))
    parser.add_argument("--credential-env", default=DEFAULT_CREDENTIAL_ENV)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--max-steps", type=int, default=MAX_STEPS)
    parser.add_argument("--max-requests", type=int, default=MAX_REQUESTS_PER_RUN)
    parser.add_argument("--max-tokens", type=int, default=MAX_TOKENS)
    parser.add_argument("--budget-cny", type=float, default=ENTRY_BUDGET_CNY)
    parser.add_argument("--evidence-dir", type=Path, default=None)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Run one bounded original MobileAgent v3.5 baseline entrypoint"
    )
    subparsers = parser.add_subparsers(dest="entrypoint", required=True)

    phone = subparsers.add_parser("phone", help="original mobile_use loop on a selected ADB serial")
    phone.add_argument("--adb-path", default=_default_adb())
    phone.add_argument("--serial", required=True, help="selected ADB device serial")
    phone.add_argument("--instruction", default="点击 Toggle controlled state，使状态变为 completed")
    phone.add_argument("--allowed-package", default=DEFAULT_PHONE_PACKAGE)
    phone.add_argument(
        "--tap-bounds",
        type=int,
        nargs=4,
        required=True,
        metavar=("LEFT", "TOP", "RIGHT", "BOTTOM"),
        help="controlled button bounds from the independent pre-run observation",
    )
    phone.add_argument(
        "--observation-json",
        type=Path,
        help="optional offline observation evidence; never certifies a physical run",
    )
    _common(phone)

    android = subparsers.add_parser("androidworld", help="original AndroidWorld MobileAgentV3_M3A loop")
    android.add_argument("--adb-path", default=_default_adb())
    android.add_argument("--console-port", type=int, default=DEFAULT_ANDROID_CONSOLE_PORT)
    android.add_argument("--grpc-port", type=int, default=DEFAULT_ANDROID_GRPC_PORT)
    android.add_argument(
        "--original-coordinate-prompts",
        action="store_true",
        help="do not append the explicit 0..1000 coordinate convention to role prompts",
    )
    _common(android)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    common = {
        "endpoint": args.endpoint,
        "model": args.model,
        "credential_env": args.credential_env,
        "max_steps": args.max_steps,
        "max_requests": args.max_requests,
        "max_tokens": args.max_tokens,
        "budget_cny": args.budget_cny,
        "timeout": args.timeout,
        "evidence_dir": args.evidence_dir,
    }
    if args.entrypoint == "phone":
        report = run_phone_baseline(
            adb_path=args.adb_path,
            serial=args.serial,
            instruction=args.instruction,
            allowed_package=args.allowed_package,
            allowed_tap_bounds=tuple(args.tap_bounds) if args.tap_bounds else None,
            **common,
        )
        if args.observation_json is not None:
            try:
                offline = json.loads(args.observation_json.read_text(encoding="utf-8"))
                report["offline_observation_evidence"] = {
                    "path": str(args.observation_json.resolve()),
                    "judge": evaluate_controlled_observation(offline),
                    "physical_success_eligible": False,
                }
            except Exception as exc:
                report["offline_observation_evidence"] = {
                    "path": str(args.observation_json.resolve()),
                    "judge": {"status": "unknown", "success": None, "reason": f"read_error:{type(exc).__name__}"},
                    "physical_success_eligible": False,
                }
            # A file selected through the CLI is explicitly offline evidence;
            # it must never upgrade the physical run's success state.
            report["physical_success_eligible"] = False
            if report.get("success") is True:
                report["success"] = None
                report["status"] = "completed_without_independent_judge"
                report["reason"] = "observation_json_offline_only"
    else:
        report = run_androidworld_baseline(
            adb_path=args.adb_path,
            console_port=args.console_port,
            grpc_port=args.grpc_port,
            coordinate_adaptation=not args.original_coordinate_prompts,
            **common,
        )
    print(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2))
    return 0 if report.get("status") in {"success", "completed", "completed_without_independent_judge", "stopped"} else 2


if __name__ == "__main__":
    sys.exit(main())
