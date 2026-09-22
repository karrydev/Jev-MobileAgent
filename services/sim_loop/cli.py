"""Command line entry points for the simulated loop."""

from __future__ import annotations

import argparse
import json
import os
import threading
from typing import Any
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from .device import SimulatedDevice, create_device_server, utc_now
from .schema import check_fixtures
from .service import SimulationService, create_service_server


class FixedClock:
    def __init__(self, value: str = "2026-09-22T00:00:00Z"):
        self.value = value

    def __call__(self) -> str:
        return self.value


def _token(parser: argparse.ArgumentParser, value: str | None) -> str:
    token = value or os.environ.get("JEV_SIM_AUTH_TOKEN")
    if not token:
        parser.error("--auth-token or JEV_SIM_AUTH_TOKEN is required; no default credential exists")
    return token


def _start_servers(token: str, behavior: str, *, deterministic: bool, device_port: int = 0, service_port: int = 0):
    clock = FixedClock() if deterministic else utc_now
    device = SimulatedDevice(device_id="sim-device-01", token=token, behavior=behavior, clock=clock)
    device_server = create_device_server(device, port=device_port)
    device_thread = threading.Thread(target=device_server.serve_forever, daemon=True)
    device_thread.start()
    device_base_url = f"http://127.0.0.1:{device_server.server_address[1]}"
    service = SimulationService(
        device_base_url=device_base_url,
        token=token,
        device_id="sim-device-01",
        behavior=behavior,
        clock=clock,
    )
    service_server = create_service_server(service, port=service_port)
    service_thread = threading.Thread(target=service_server.serve_forever, daemon=True)
    service_thread.start()
    return device, device_server, service, service_server, device_thread, service_thread


def _stop_servers(device_server: Any, service_server: Any, device_thread: threading.Thread, service_thread: threading.Thread) -> None:
    for server, thread in ((service_server, service_thread), (device_server, device_thread)):
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def _submit(service_server: Any, token: str, payload: dict[str, Any]) -> dict[str, Any]:
    url = f"http://127.0.0.1:{service_server.server_address[1]}/v1/tasks"
    request = Request(
        url,
        data=json.dumps(payload, sort_keys=True).encode("utf-8"),
        method="POST",
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
            "X-JEV-Protocol-Version": "1",
        },
    )
    try:
        with urlopen(request, timeout=3) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        return json.loads(exc.read().decode("utf-8"))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run the Jev-MobileAgent Task 01 simulated loop")
    subparsers = parser.add_subparsers(dest="command", required=True)

    schema_parser = subparsers.add_parser("schema-check", help="validate v1 positive and negative fixtures")
    schema_parser.set_defaults(handler=_schema_check)

    demo_parser = subparsers.add_parser("demo", help="run one task through two localhost HTTP hops")
    demo_parser.add_argument("--auth-token")
    demo_parser.add_argument("--task-id", default="task-demo-001")
    demo_parser.add_argument("--scenario", choices=["apply_effect", "receipt_without_effect"], default="apply_effect")
    demo_parser.add_argument("--real-time", action="store_true", help="use wall-clock timestamps instead of fixed demo timestamps")
    demo_parser.set_defaults(handler=_demo)

    serve_parser = subparsers.add_parser("serve", help="serve the simulated device and task API")
    serve_parser.add_argument("--auth-token")
    serve_parser.add_argument("--scenario", choices=["apply_effect", "receipt_without_effect"], default="apply_effect")
    serve_parser.add_argument("--device-port", type=int, default=8766)
    serve_parser.add_argument("--service-port", type=int, default=8765)
    serve_parser.set_defaults(handler=_serve)
    return parser


def _schema_check(args: argparse.Namespace, parser: argparse.ArgumentParser) -> int:
    positive, negative = check_fixtures()
    print(json.dumps({"positive_fixtures": positive, "negative_fixtures": negative, "schema_version": "1.0"}, sort_keys=True))
    return 0


def _demo(args: argparse.Namespace, parser: argparse.ArgumentParser) -> int:
    token = _token(parser, args.auth_token)
    device, device_server, service, service_server, device_thread, service_thread = _start_servers(
        token,
        args.scenario,
        deterministic=not args.real_time,
    )
    try:
        result = _submit(
            service_server,
            token,
            {
                "schema_version": "1.0",
                "task_id": args.task_id,
                "device_id": device.device_id,
                "mode": "simulated",
                "goal": "advance_to_done",
                "scenario": args.scenario,
            },
        )
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    finally:
        _stop_servers(device_server, service_server, device_thread, service_thread)


def _serve(args: argparse.Namespace, parser: argparse.ArgumentParser) -> int:
    token = _token(parser, args.auth_token)
    _, device_server, _, service_server, device_thread, service_thread = _start_servers(
        token,
        args.scenario,
        deterministic=False,
        device_port=args.device_port,
        service_port=args.service_port,
    )
    print(f"service=http://127.0.0.1:{service_server.server_address[1]} device_simulator=http://127.0.0.1:{device_server.server_address[1]}", flush=True)
    try:
        service_thread.join()
    except KeyboardInterrupt:
        pass
    finally:
        _stop_servers(device_server, service_server, device_thread, service_thread)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.handler(args, parser)
