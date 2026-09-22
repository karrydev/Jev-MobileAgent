"""Run the Android observation bridge as a local HTTP service."""

from __future__ import annotations

import argparse
import os

from .bridge import AndroidBridge, create_server


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Serve the Jev-MobileAgent Android observation bridge")
    parser.add_argument("--auth-token", help="bearer token; JEV_ANDROID_AUTH_TOKEN is also accepted")
    parser.add_argument("--device-id", default="android-emulator-01")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--freshness-seconds", type=float, default=30.0)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    token = args.auth_token or os.environ.get("JEV_ANDROID_AUTH_TOKEN")
    if not token:
        parser.error("--auth-token or JEV_ANDROID_AUTH_TOKEN is required; no default credential exists")
    bridge = AndroidBridge(
        token=token,
        device_id=args.device_id,
        freshness_seconds=args.freshness_seconds,
    )
    server = create_server(bridge, host=args.host, port=args.port)
    print(f"android_bridge=http://{args.host}:{server.server_address[1]} device_id={args.device_id}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
