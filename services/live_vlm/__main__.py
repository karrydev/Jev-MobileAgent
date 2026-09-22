"""CLI for the bounded live VLM compatibility probe."""

from __future__ import annotations

import argparse
import json
import os
import sys

from .probe import DEFAULT_BUDGET_CNY, DEFAULT_ENDPOINT, DEFAULT_MAX_REQUESTS, DEFAULT_MAX_TOKENS, DEFAULT_MODEL, run_probe


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Probe one GUI-Plus model against the original v3.5 prompts and parsers")
    parser.add_argument("--endpoint", default=os.environ.get("JEV_VLM_ENDPOINT", DEFAULT_ENDPOINT))
    parser.add_argument("--model", default=os.environ.get("JEV_VLM_MODEL", DEFAULT_MODEL))
    parser.add_argument("--credential-env", default="JEV_VLM_API_KEY")
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--max-requests", type=int, default=DEFAULT_MAX_REQUESTS)
    parser.add_argument("--max-tokens", type=int, default=DEFAULT_MAX_TOKENS)
    parser.add_argument("--budget-cny", type=float, default=DEFAULT_BUDGET_CNY)
    parser.add_argument("--execute", action="store_true", help="send at most the bounded five-role request suite")
    parser.add_argument(
        "--coordinate-adaptation",
        action="store_true",
        help="append an explicit normalized 0..1000 coordinate instruction to the four role prompts only",
    )
    parser.add_argument("--output", help="also write the JSON report to this local path")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    report = run_probe(
        endpoint=args.endpoint,
        model=args.model,
        credential_env=args.credential_env,
        timeout=args.timeout,
        execute=args.execute,
        max_requests=args.max_requests,
        max_tokens=args.max_tokens,
        budget_cny=args.budget_cny,
        coordinate_adaptation=args.coordinate_adaptation,
    )
    encoded = json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2)
    print(encoded)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as handle:
            handle.write(encoded)
            handle.write("\n")
    if report.get("status") in {"invalid_configuration", "configuration_required", "credentials_required", "source_load_failed", "budget_exceeded_before_execution"}:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
