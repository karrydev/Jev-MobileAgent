"""Offline runner that obtains evidence through the existing public task API."""

from __future__ import annotations

import copy
import hashlib
import json
import threading
import time
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from services.sim_loop.device import SimulatedDevice, create_device_server
from services.sim_loop.service import SimulationService, create_service_server


class FixedClock:
    """Stable clock for repeatable local evidence."""

    def __init__(self, value: str = "2026-09-22T00:00:00Z"):
        self.value = value

    def __call__(self) -> str:
        return self.value


def _start_servers(token: str, behavior: str) -> tuple[Any, Any, Any, Any, threading.Thread, threading.Thread]:
    clock = FixedClock()
    device = SimulatedDevice(device_id="sim-device-01", token=token, behavior=behavior, clock=clock)
    device_server = create_device_server(device)
    device_thread = threading.Thread(target=device_server.serve_forever, daemon=True)
    device_thread.start()
    service = SimulationService(
        device_base_url=f"http://127.0.0.1:{device_server.server_address[1]}",
        token=token,
        device_id=device.device_id,
        behavior=behavior,
        clock=clock,
    )
    service_server = create_service_server(service)
    service_thread = threading.Thread(target=service_server.serve_forever, daemon=True)
    service_thread.start()
    return device, device_server, service, service_server, device_thread, service_thread


def _stop_servers(device_server: Any, service_server: Any, device_thread: threading.Thread, service_thread: threading.Thread) -> None:
    for server, thread in ((service_server, service_thread), (device_server, device_thread)):
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def _request(service_server: Any, token: str, method: str, path: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
    body = None if payload is None else json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
    headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {token}",
        "X-JEV-Protocol-Version": "1",
    }
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = Request(
        f"http://127.0.0.1:{service_server.server_address[1]}{path}",
        data=body,
        headers=headers,
        method=method,
    )
    try:
        with urlopen(request, timeout=5) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        return json.loads(exc.read().decode("utf-8"))


def _payload(task_id: str, device_id: str, scenario: str, flavor: str) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "schema_version": "1.0",
        "task_id": task_id,
        "device_id": device_id,
        "mode": "simulated",
        "goal": "advance_to_done",
        "scenario": scenario,
    }
    if flavor == "success":
        payload["model"] = {
            "mode": "replay",
            "prompt": "private prompt omitted from public report",
            "images": [{"image_id": "private-image", "media_type": "image/png", "data": "private-bytes"}],
            "responses": [
                {
                    "kind": "action",
                    "action": {"kind": "tap", "target_node_id": "start-button", "expected_page_state": "done"},
                    "usage": {"input_tokens": 12, "output_tokens": 3},
                }
            ],
        }
    elif flavor == "retry":
        payload["model"] = {
            "mode": "replay",
            "max_attempts": 3,
            "retry_limit": 2,
            "prompt": "private retry prompt",
            "responses": [
                {"kind": "error", "code": "timeout", "retryable": True, "usage": {"input_tokens": 2, "output_tokens": 0}},
                {"kind": "error", "code": "rate_limited", "retryable": True},
                {
                    "kind": "action",
                    "action": {"kind": "tap", "target_node_id": "start-button", "expected_page_state": "done"},
                    "usage": {"input_tokens": 10, "output_tokens": 2},
                },
            ],
        }
    elif flavor == "cancel":
        payload["model"] = {
            "mode": "replay",
            "delay_ms": 150,
            "prompt": "private cancellation prompt",
            "responses": [
                {
                    "kind": "action",
                    "action": {"kind": "tap", "target_node_id": "start-button", "expected_page_state": "done"},
                }
            ],
        }
    # ``fallback`` and ``failure`` intentionally omit ``model``.  The former
    # exercises the existing default action path; the latter keeps that same
    # fallback while the simulated device supplies no page effect.
    return payload


def run_simulated_task(
    *,
    flavor: str = "success",
    task_id: str | None = None,
    token: str | None = None,
) -> dict[str, Any]:
    """Run a task through localhost HTTP and return its actual runtime result.

    The default token is an in-process synthetic device token, not a supplier
    credential.  No live model or external network call is made by this
    runner.  ``flavor`` is deliberately explicit so a report can identify
    success, failure, retry, fallback, and cancellation evidence.
    """

    allowed = {"success", "failure", "retry", "fallback", "cancel"}
    if flavor not in allowed:
        raise ValueError(f"flavor must be one of {sorted(allowed)}")
    task_id = task_id or f"task-evidence-{flavor}"
    # This token is generated for the in-process localhost adapter only.  Do
    # not inspect a credential environment variable: the evidence runner must
    # never accidentally consume a real service or model credential.
    token = token or "task-evidence-local-token"
    scenario = "receipt_without_effect" if flavor == "failure" else "apply_effect"
    device, device_server, service, service_server, device_thread, service_thread = _start_servers(token, scenario)
    try:
        payload = _payload(task_id, device.device_id, scenario, flavor)
        control_state: str | None = None
        if flavor != "cancel":
            result = _request(service_server, token, "POST", "/v1/tasks", payload)
            control_state = result.get("state")
        else:
            submitted: list[dict[str, Any]] = []

            def submit() -> None:
                submitted.append(_request(service_server, token, "POST", "/v1/tasks", payload))

            thread = threading.Thread(target=submit)
            thread.start()
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                current = service.status(task_id) or {}
                kinds = {event.get("kind") for event in current.get("trace", [])}
                if "model.requested" in kinds:
                    break
                time.sleep(0.005)
            control = _request(service_server, token, "POST", f"/v1/tasks/{task_id}/cancel", {})
            if control.get("state") != "CANCELLED":
                raise RuntimeError(f"cancel control did not become terminal: {control}")
            control_state = control.get("state")
            thread.join(timeout=5)
            if thread.is_alive() or not submitted:
                raise RuntimeError("cancelled public task did not return a result")
            result = submitted[0]
        if result.get("task_id") != task_id:
            raise RuntimeError(f"public task endpoint returned an unexpected result: {result}")
        truth = independent_truth_from_device(
            task_id,
            device=device,
            control_state=control_state,
        )
        runtime_result_sha256 = _canonical_hash(result)
        truth_sha256 = _canonical_hash(truth)
        # This context is local metadata for report attribution.  The truth
        # sidecar is collected from the simulated device state, separately
        # from the service result; hashes bind both controlled artifacts for
        # replay without pretending to defend a user who can rewrite the
        # entire evidence directory.
        result = copy.deepcopy(result)
        result["evidence_context"] = {
            "model": "replay-fixture" if flavor in {"success", "retry", "cancel"} else "none",
            "role": "planner",
            "scenario_flavor": flavor,
            "truth_sidecar": truth,
            "truth_sha256": truth_sha256,
            "runtime_result_sha256": runtime_result_sha256,
        }
        return result
    finally:
        _stop_servers(device_server, service_server, device_thread, service_thread)


def _canonical_hash(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def independent_truth_from_device(
    task_id: str,
    *,
    device: SimulatedDevice,
    control_state: str | None,
) -> dict[str, Any]:
    """Capture an oracle from the device sidecar, outside the runtime result."""

    page_state = device.page_state
    action_count = int(device.action_count)
    steps: list[dict[str, Any]] = []
    if action_count:
        if control_state == "CANCELLED":
            action_outcome = {
                "status": "UNKNOWN",
                "reason": "task_cancelled_before_completion",
                "evidence": {
                    "source": "simulated-device-sidecar-v1",
                    "actual_page_state": page_state,
                    "action_count": action_count,
                },
            }
        elif page_state == "done":
            action_outcome = {
                "status": "SUCCESS",
                "reason": "postcondition_met_by_device_page_state",
                "evidence": {
                    "source": "simulated-device-sidecar-v1",
                    "actual_page_state": page_state,
                    "action_count": action_count,
                },
            }
        else:
            action_outcome = {
                "status": "FAILURE",
                "reason": "postcondition_not_met_by_device_page_state",
                "failure_class": "agent",
                "evidence": {
                    "source": "simulated-device-sidecar-v1",
                    "actual_page_state": page_state,
                    "action_count": action_count,
                },
            }
        steps.append({"step_id": "step-01", "action_postcondition": action_outcome})

    if page_state == "done":
        completion = {
            "status": "SUCCESS",
            "reason": "task_goal_met_by_device_page_state",
            "evidence": {
                "source": "simulated-device-sidecar-v1",
                "actual_page_state": page_state,
                "action_count": action_count,
            },
        }
    elif control_state == "CANCELLED":
        completion = {
            "status": "UNKNOWN",
            "reason": "task_cancelled_before_completion",
            "evidence": {
                "source": "simulated-device-sidecar-v1",
                "actual_page_state": page_state,
                "action_count": action_count,
                "cancelled": True,
            },
        }
    elif action_count:
        completion = {
            "status": "FAILURE",
            "reason": "task_goal_not_met_by_device_page_state",
            "failure_class": "agent",
            "evidence": {
                "source": "simulated-device-sidecar-v1",
                "actual_page_state": page_state,
                "action_count": action_count,
            },
        }
    else:
        completion = {
            "status": "UNKNOWN",
            "reason": "completion_evidence_unavailable",
            "evidence": {
                "source": "simulated-device-sidecar-v1",
                "actual_page_state": page_state,
                "action_count": action_count,
            },
        }

    return {
        "task_id": str(task_id),
        "trajectory_id": f"trajectory-{task_id}",
        "source": "simulated-device-sidecar-v1",
        "environment": {"status": "READY", "reason": "controlled_simulated_device"},
        "initial_state": {"page_state": "landing"},
        "steps": steps,
        "task_completion": completion,
    }


def independent_truth_for_flavor(task_id: str, flavor: str) -> dict[str, Any]:
    """Return the fixture oracle for a synthetic scenario.

    This record is prepared from the controlled scenario before the runtime
    result is inspected.  It is therefore independent of the service's own
    ``verification`` and ``state`` fields; the actual runtime trace still has
    to link to the record before the evaluator can score it.
    """

    if flavor not in {"success", "failure", "retry", "fallback", "cancel"}:
        raise ValueError(f"flavor must be one of {sorted({'success', 'failure', 'retry', 'fallback', 'cancel'})}")
    task_id = str(task_id)
    if flavor == "cancel":
        completion = {"status": "UNKNOWN", "reason": "task_cancelled_before_completion", "evidence": {"source": "fixture-page-oracle-v1"}}
        steps: list[dict[str, Any]] = []
    elif flavor == "failure":
        failure = {
            "status": "FAILURE",
            "reason": "postcondition_not_met_by_fixture_page_state",
            "failure_class": "agent",
            "evidence": {"source": "fixture-page-oracle-v1", "actual_page_state": "landing"},
        }
        completion = dict(failure)
        steps = [{"step_id": "step-01", "action_postcondition": failure}]
    else:
        success = {
            "status": "SUCCESS",
            "reason": "postcondition_met_by_fixture_page_state",
            "evidence": {"source": "fixture-page-oracle-v1", "actual_page_state": "done"},
        }
        completion = {
            "status": "SUCCESS",
            "reason": "task_goal_met_by_fixture_page_state",
            "evidence": {"source": "fixture-page-oracle-v1", "actual_page_state": "done"},
        }
        steps = [{"step_id": "step-01", "action_postcondition": success}]
    return {
        "task_id": task_id,
        "trajectory_id": f"trajectory-{task_id}",
        "source": "fixture-page-oracle-v1",
        "environment": {"status": "READY", "reason": "controlled_synthetic_page"},
        "initial_state": {"page_state": "landing"},
        "steps": steps,
        "task_completion": completion,
    }


def write_json(path: str | Path, value: Any) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
