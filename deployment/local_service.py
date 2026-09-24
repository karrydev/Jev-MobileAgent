#!/usr/bin/env python3
"""Start and manage the local Android bridge service."""

from __future__ import annotations

import argparse
import http.client
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import time


DEPLOYMENT = Path(__file__).resolve().parent
CONFIG = DEPLOYMENT / "local-service.json"
TEMP_ROOT = Path(tempfile.gettempdir()) / "jev-mobileagent-local"
RUNTIME = TEMP_ROOT / "runtime"
STATE_DIR = TEMP_ROOT / "state"
LOG_DIR = TEMP_ROOT / "logs"
PID_FILE = RUNTIME / "android-bridge.pid.json"
RELEASE_FILE = RUNTIME / "releases.json"
STATE_FILE = STATE_DIR / "android-bridge.json"
LOG_FILE = LOG_DIR / "android-bridge.log"


def helper_env() -> dict[str, str]:
    env = os.environ.copy()
    env.pop("JEV_ANDROID_AUTH_TOKEN", None)
    return env


def config() -> dict[str, object]:
    try:
        value = json.loads(CONFIG.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"无法读取配置 {CONFIG}: {exc}") from exc
    if not isinstance(value, dict):
        raise RuntimeError("配置必须是 JSON 对象")
    if value.get("host") != "127.0.0.1":
        raise RuntimeError("本地服务只允许绑定 127.0.0.1")
    if not isinstance(value.get("port"), int) or not 1 <= value["port"] <= 65535:
        raise RuntimeError("配置 port 无效")
    if not isinstance(value.get("device_id"), str) or not value["device_id"]:
        raise RuntimeError("配置 device_id 无效")
    release = value.get("release_path")
    if not isinstance(release, str) or not release:
        raise RuntimeError("配置 release_path 无效")
    value["default_release"] = (CONFIG.parent / release).resolve()
    return value


def ensure_dirs() -> None:
    for directory in (RUNTIME, STATE_DIR, LOG_DIR):
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(directory, 0o700)


def write_json(path: Path, value: object) -> None:
    ensure_dirs()
    temp = path.with_name(f"{path.name}.{os.getpid()}.tmp")
    temp.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    os.chmod(temp, 0o600)
    os.replace(temp, path)


def releases(cfg: dict[str, object]) -> tuple[Path, Path | None]:
    if not RELEASE_FILE.exists():
        return cfg["default_release"], None  # type: ignore[return-value]
    try:
        value = json.loads(RELEASE_FILE.read_text(encoding="utf-8"))
        current = Path(value["current"]).resolve()
        previous = Path(value["previous"]).resolve() if value.get("previous") else None
    except (OSError, json.JSONDecodeError, KeyError, TypeError) as exc:
        raise RuntimeError(f"版本记录无效: {RELEASE_FILE}") from exc
    return current, previous


def save_releases(current: Path, previous: Path | None) -> None:
    write_json(RELEASE_FILE, {
        "current": str(current.resolve()),
        "previous": str(previous.resolve()) if previous else None,
    })


def validate_release(release: Path) -> None:
    if not (release / "services/android_bridge/__main__.py").is_file():
        raise RuntimeError(f"release_path 无效: {release}")
    result = subprocess.run(
        [sys.executable, "-m", "services.android_bridge", "--help"],
        cwd=release,
        env=helper_env(),
        capture_output=True,
        text=True,
        timeout=10,
    )
    if result.returncode or "--state-file" not in result.stdout:
        raise RuntimeError("release 未提供服务 CLI --state-file 接口")


def process_info(pid: int) -> tuple[str, str] | None:
    result = subprocess.run(
        ["ps", "-ww", "-p", str(pid), "-o", "lstart=", "-o", "command="],
        env=helper_env(),
        capture_output=True,
        text=True,
    )
    parts = result.stdout.strip().split(None, 5)
    if result.returncode or len(parts) < 6:
        return None
    return " ".join(parts[:5]), parts[5]


def owned_pid() -> int | None:
    try:
        record = json.loads(PID_FILE.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return None
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeError("PID 记录不可读；不会发送信号") from exc
    pid = record.get("pid") if isinstance(record, dict) else None
    pgid = record.get("pgid") if isinstance(record, dict) else None
    if not isinstance(pid, int) or isinstance(pid, bool) or pid <= 1 or pgid != pid:
        raise RuntimeError("PID 记录不安全；不会发送信号")
    try:
        if os.getpgid(pid) != pid:
            raise RuntimeError("PID 进程组不匹配；不会发送信号")
    except ProcessLookupError:
        PID_FILE.unlink(missing_ok=True)
        return None
    info = process_info(pid)
    if info is None:
        raise RuntimeError("无法验证 PID；不会发送信号")
    started, command = info
    if (
        started != record.get("start_time")
        or "-m services.android_bridge" not in command
        or "--state-file" not in command
        or str(STATE_FILE) not in command
    ):
        raise RuntimeError("PID 身份与本地服务记录不符；不会发送信号")
    return pid


def group_exists(pgid: int) -> bool:
    try:
        os.killpg(pgid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def terminate_group(pgid: int) -> None:
    try:
        os.killpg(pgid, signal.SIGTERM)
    except ProcessLookupError:
        return
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline and group_exists(pgid):
        time.sleep(0.1)
    if group_exists(pgid):
        os.killpg(pgid, signal.SIGKILL)
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline and group_exists(pgid):
            time.sleep(0.1)
    if group_exists(pgid):
        raise RuntimeError("服务进程组未退出")


def stop() -> None:
    pid = owned_pid()
    if pid is None:
        print("没有运行中的受控服务；state-file 已保留")
        return
    terminate_group(pid)
    PID_FILE.unlink(missing_ok=True)
    print("服务已停止；state-file 已保留")


def health(cfg: dict[str, object]) -> None:
    connection = http.client.HTTPConnection(str(cfg["host"]), int(cfg["port"]), timeout=2)
    try:
        connection.request("GET", "/healthz")
        response = connection.getresponse()
        body = response.read(65536)
    finally:
        connection.close()
    if response.status != 200:
        raise RuntimeError(f"GET /healthz 返回 HTTP {response.status}")
    try:
        value = json.loads(body)
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise RuntimeError("/healthz 未返回 JSON") from exc
    if not isinstance(value, dict):
        raise RuntimeError("/healthz 响应格式无效")
    report = {
        "service_status": value.get("service_status"),
        "persistence_status": value.get("persistence_status"),
        "pending_reconciliation_count": value.get("pending_reconciliation_count"),
    }
    if report["service_status"] not in ("ok", "degraded"):
        raise RuntimeError("/healthz 缺少有效 service_status")
    if report["persistence_status"] != "enabled":
        raise RuntimeError("/healthz 缺少有效 persistence_status")
    count = report["pending_reconciliation_count"]
    if not isinstance(count, int) or isinstance(count, bool) or count < 0:
        raise RuntimeError("/healthz 缺少有效 pending_reconciliation_count")
    print(json.dumps(report, ensure_ascii=False))


def start(cfg: dict[str, object], release: Path) -> None:
    if not os.environ.get("JEV_ANDROID_AUTH_TOKEN"):
        raise RuntimeError("请先在当前环境提供 JEV_ANDROID_AUTH_TOKEN；控制器不保存 Token")
    validate_release(release)
    if owned_pid() is not None:
        raise RuntimeError("本地服务已在运行")
    host, port = str(cfg["host"]), int(cfg["port"])
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            listener.bind((host, port))
        except OSError as exc:
            raise RuntimeError(f"端口 {host}:{port} 已占用；不会终止该进程") from exc
    ensure_dirs()
    command = [
        sys.executable, "-m", "services.android_bridge",
        "--host", host, "--port", str(port),
        "--device-id", str(cfg["device_id"]),
        "--state-file", str(STATE_FILE),
    ]
    with LOG_FILE.open("ab", buffering=0) as log:
        process = subprocess.Popen(
            command, cwd=release, env=os.environ.copy(), stdin=subprocess.DEVNULL,
            stdout=log, stderr=subprocess.STDOUT, start_new_session=True, close_fds=True,
        )
    try:
        deadline = time.monotonic() + 2
        info = None
        while time.monotonic() < deadline and process.poll() is None:
            info = process_info(process.pid)
            if info:
                break
            time.sleep(0.05)
        if info is None:
            raise RuntimeError(f"无法验证服务 PID；查看日志: {LOG_FILE}")
        write_json(PID_FILE, {"pid": process.pid, "pgid": process.pid, "start_time": info[0]})
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline and process.poll() is None:
            try:
                health(cfg)
                print(f"服务已启动，PID {process.pid}；state-file: {STATE_FILE}")
                return
            except (OSError, RuntimeError):
                time.sleep(0.2)
        raise RuntimeError(f"服务未能通过 healthz；查看日志: {LOG_FILE}")
    except Exception:
        try:
            if os.getpgid(process.pid) == process.pid:
                terminate_group(process.pid)
                process.wait(timeout=2)
        except ProcessLookupError:
            pass
        PID_FILE.unlink(missing_ok=True)
        raise


def switch(cfg: dict[str, object], current: Path, previous: Path | None, target: Path) -> None:
    target = target.expanduser().resolve()
    validate_release(target)
    if target == current:
        raise RuntimeError("目标版本已经是当前版本")
    if not os.environ.get("JEV_ANDROID_AUTH_TOKEN"):
        raise RuntimeError("请先在当前环境提供 JEV_ANDROID_AUTH_TOKEN；当前服务保持运行")
    stop()
    save_releases(target, current)
    try:
        start(cfg, target)
    except Exception as exc:
        save_releases(current, previous)
        try:
            start(cfg, current)
        except Exception as restore_exc:
            raise RuntimeError(f"切换失败，配置已恢复；原版本启动也失败: {restore_exc}") from exc
        raise RuntimeError(f"切换失败，已恢复原版本: {exc}") from exc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="本机 Android bridge 启停与回退")
    commands = parser.add_subparsers(dest="action", required=True)
    for name in ("start", "stop", "restart", "health", "rollback", "paths"):
        commands.add_parser(name)
    commands.add_parser("switch").add_argument("release_path", type=Path)
    args = parser.parse_args(argv)
    try:
        cfg = config()
        current, previous = releases(cfg)
        if args.action == "start":
            start(cfg, current)
        elif args.action == "stop":
            stop()
        elif args.action == "restart":
            if not os.environ.get("JEV_ANDROID_AUTH_TOKEN"):
                raise RuntimeError("缺少 JEV_ANDROID_AUTH_TOKEN；当前服务保持运行")
            validate_release(current)
            stop()
            start(cfg, current)
        elif args.action == "health":
            health(cfg)
        elif args.action == "switch":
            switch(cfg, current, previous, args.release_path)
        elif args.action == "rollback":
            if previous is None:
                raise RuntimeError("没有上一版本；先用 switch 指定另一个本地 release")
            switch(cfg, current, previous, previous)
        else:
            print(f"release: {current}")
            print(f"previous release: {previous or '(none)'}")
            print(f"state-file: {STATE_FILE}")
            print(f"runtime: {RUNTIME}")
            print(f"log: {LOG_FILE}")
    except (OSError, RuntimeError, subprocess.SubprocessError) as exc:
        print(f"错误: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
