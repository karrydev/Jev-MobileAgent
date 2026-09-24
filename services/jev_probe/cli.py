"""Command line entry point for the Jev access probe."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from .probe import (
    DEFAULT_ENDPOINT,
    DEFAULT_MODEL,
    DEFAULT_TIMEOUT_SECONDS,
    ProbeConfigurationError,
    _configuration_report,
    run_probe,
)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="运行 Jev 最小中文 choice 探针（默认不联网）")
    parser.add_argument("--config", type=Path, help="JSON 配置示例或用户自己的脱敏配置")
    parser.add_argument("--endpoint", help=f"覆盖 endpoint，默认 {DEFAULT_ENDPOINT}")
    parser.add_argument("--model", help=f"覆盖 model，默认 {DEFAULT_MODEL}")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--live", action="store_true", help="显式发送一次真实 choice 请求")
    parser.add_argument(
        "--error-probe",
        action="store_true",
        help="在主请求成功且 usage 完整后，再发送一次故意无效请求",
    )
    parser.add_argument("--output", type=Path, help="将脱敏 JSON 报告写入此路径；同时输出到 stdout")
    return parser


def _emit(report: dict[str, Any], output: Path | None) -> None:
    rendered = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    sys.stdout.write(rendered)
    if output is not None:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered, encoding="utf-8")


def _exit_code(report: dict[str, Any]) -> int:
    status = report.get("run", {}).get("status")
    if status in {"dry_run", "ok"}:
        return 0
    if status == "semantic_error":
        return 4
    if status == "configuration_error" and report.get("run", {}).get("stop_reason") == "missing_credential":
        return 3
    return 2


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        report = run_probe(
            config_path=args.config,
            endpoint=args.endpoint,
            model=args.model,
            live=args.live,
            error_probe=args.error_probe,
            timeout=args.timeout,
        )
    except ProbeConfigurationError as exc:
        report = _configuration_report(
            model=args.model or DEFAULT_MODEL,
            mode="live" if args.live else "dry-run",
            config=None,
            error=str(exc),
        )
    _emit(report, args.output)
    return _exit_code(report)


if __name__ == "__main__":
    raise SystemExit(main())
