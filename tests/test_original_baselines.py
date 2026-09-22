from __future__ import annotations

import base64
import datetime as dt
import json
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from PIL import Image

from services.original_baselines.harness import (
    ANDROID_AGENT_SOURCE,
    ANDROID_ENV_LAUNCHER_SOURCE,
    ANDROID_EPISODE_RUNNER_SOURCE,
    BaselineStop,
    BoundedVlmTransport,
    BudgetLedger,
    EvidenceWriter,
    GuardedPhoneAdb,
    UnsafePhoneAction,
    _current_package,
    evaluate_controlled_observation,
    evaluate_controlled_observations,
    run_androidworld_baseline,
    run_phone_baseline,
    usage_cost_cny,
)


class _FakeAdb:
    def __init__(self) -> None:
        self.calls: list[tuple[str, int, int]] = []

    def click(self, x: int, y: int) -> None:
        self.calls.append(("click", x, y))

    def back(self) -> None:
        self.calls.append(("back", 0, 0))

    def home(self) -> None:
        self.calls.append(("home", 0, 0))


class OriginalBaselineTests(unittest.TestCase):
    def test_usage_cost_uses_the_frozen_price_book(self) -> None:
        self.assertEqual(usage_cost_cny({"prompt_tokens": 1000, "completion_tokens": 1000}), 0.006)
        self.assertIsNone(usage_cost_cny({"prompt_tokens": 1000}))

    def test_missing_usage_stops_the_next_network_attempt(self) -> None:
        ledger = BudgetLedger(max_requests=25, max_tokens=1024, budget_cny=1.0)
        first = ledger.reserve()
        ledger.finish(first, http_status=200, response={"choices": []}, response_text="", transport_error=None)
        self.assertEqual(ledger.stop_reason, "usage_missing")
        with self.assertRaises(BaselineStop) as raised:
            ledger.reserve()
        self.assertEqual(raised.exception.reason, "usage_missing")
        self.assertEqual(len(ledger.attempts), 1)

    def test_transport_converts_local_file_uri_and_does_not_retry(self) -> None:
        requests: list[dict] = []

        def request_fn(endpoint: str, credential: str, payload: dict, timeout: float):
            del endpoint, credential, timeout
            requests.append(payload)
            return (
                200,
                {
                    "choices": [{"message": {"content": "ok"}}],
                    "usage": {"prompt_tokens": 2, "completion_tokens": 3},
                    "echoed_credential": "fakekey",
                },
                "provider text fakekey",
                None,
            )

        with tempfile.TemporaryDirectory() as directory:
            image_path = Path(directory) / "frame.png"
            Image.new("RGB", (2, 2), "red").save(image_path)
            evidence = EvidenceWriter(Path(directory) / "evidence")
            transport = BoundedVlmTransport(
                endpoint="http://fixture/v1/chat/completions",
                model="fixture",
                credential="fakekey",
                evidence=evidence,
                request_fn=request_fn,
                ledger=BudgetLedger(max_requests=1, max_tokens=1024, budget_cny=1.0),
            )
            content, _, _ = transport.request(
                [{"role": "user", "content": [{"text": "goal"}, {"image": f"file://{image_path}"}]}]
            )
            raw_artifact = (Path(directory) / "evidence/raw/attempt-001.json").read_text(encoding="utf-8")
        self.assertEqual(content, "ok")
        self.assertEqual(len(requests), 1)
        self.assertEqual(requests[0]["max_tokens"], 1024)
        self.assertFalse(requests[0]["enable_thinking"])
        image_url = requests[0]["messages"][0]["content"][1]["image_url"]["url"]
        self.assertTrue(image_url.startswith("data:image/png;base64,"))
        self.assertNotIn("fakekey", raw_artifact)

    def test_phone_guard_allows_one_bound_tap_and_safe_navigation_only(self) -> None:
        upstream = _FakeAdb()
        package = ["com.jev.mobileagent"]
        guard = GuardedPhoneAdb(
            upstream,
            adb_path="adb",
            serial="phone",
            package_checker=lambda: package[0],
            allowed_tap_bounds=(100, 200, 300, 400),
        )
        guard.image_info = (1080, 2400)
        guard.click(200, 300)
        guard.back()
        with self.assertRaises(BaselineStop):
            guard.click(200, 300)
        package[0] = "com.android.settings"
        with self.assertRaises(BaselineStop):
            guard.ensure_project_app()
        self.assertEqual(upstream.calls, [("click", 200, 300), ("back", 0, 0)])

    def test_current_package_uses_current_focus_and_ignores_focused_app(self) -> None:
        dumps = iter(
            [
                "mCurrentFocus=Window{83dec56 u0 com.jev.mobileagent/com.jev.mobileagent.ControlledPageActivity}\n"
                "mFocusedApp=ActivityRecord{8202206 u0 com.android.launcher3/.Launcher t157}",
                "mCurrentFocus=Window{83dec56 u0 NotificationShade}\n"
                "mFocusedApp=ActivityRecord{8202206 u0 com.jev.mobileagent/.ControlledPageActivity t157}",
                "mFocusedApp=ActivityRecord{8202206 u0 com.jev.mobileagent/.ControlledPageActivity t157}",
            ]
        )

        def run(command: list[str], **_kwargs: object) -> SimpleNamespace:
            return SimpleNamespace(stdout=next(dumps), stderr="")

        with patch("services.original_baselines.harness.subprocess.run", side_effect=run) as mocked_run:
            self.assertEqual(_current_package("adb", "phone"), "com.jev.mobileagent")
            self.assertIsNone(_current_package("adb", "phone"))
            self.assertIsNone(_current_package("adb", "phone"))

        self.assertEqual(mocked_run.call_args_list[0].args[0][-3:], ["shell", "dumpsys", "window"])

    def test_phone_policy_stop_overrides_successful_live_observation(self) -> None:
        class FakeUpstream:
            def click(self, x: int, y: int) -> None:
                del x, y

        class FakePhoneRun:
            def __init__(self) -> None:
                self.parse_args = lambda: None
                self.parse_action = lambda _text: {"arguments": {"action": "click"}}
                self.AdbTools = None
                self.GUIOwlWrapper = None

            def main(self) -> None:
                args = self.parse_args()
                adb = self.AdbTools(adb_path=args.adb_path, device=args.device)
                adb.image_info = (1080, 1920)
                adb.click(100, 100)
                raise UnsafePhoneAction("action_not_allowed:swipe")

        def load_module(path: Path, _name: str) -> object:
            if path.name == "utils.py":
                return SimpleNamespace(AdbTools=lambda **_kwargs: FakeUpstream())
            return FakePhoneRun()

        def observe(phase: str) -> dict[str, object]:
            now = dt.datetime.now(dt.timezone.utc)
            return {
                "task_id": "controlled-task",
                "device_id": "phone",
                "observation_id": f"observation-{phase}",
                "captured_at": now.isoformat(),
                "nodes": [{
                    "text": "Controlled action state: ready"
                    if phase == "before"
                    else "Controlled action state: completed"
                }],
            }

        with (
            patch.dict(os.environ, {"JEV_TEST_API_KEY": "fakekey"}, clear=False),
            patch("services.original_baselines.harness._load_module", side_effect=load_module),
            tempfile.TemporaryDirectory() as directory,
        ):
            report = run_phone_baseline(
                adb_path="adb",
                serial="phone",
                credential_env="JEV_TEST_API_KEY",
                evidence_dir=directory,
                observation_provider=observe,
                allowed_tap_bounds=(50, 50, 150, 150),
                package_checker=lambda: "com.jev.mobileagent",
            )

        self.assertEqual(report["independent_judge"]["success"], True, report)
        self.assertEqual(report["error"]["type"], "UnsafePhoneAction")
        self.assertEqual(report["status"], "stopped")
        self.assertFalse(report["success"])

    def test_original_answer_does_not_raise_unsafe(self) -> None:
        class FakeUpstream:
            def click(self, x: int, y: int) -> None:
                del x, y

        class FakePhoneRun:
            def __init__(self) -> None:
                self.parse_args = lambda: SimpleNamespace(adb_path="adb", device="phone")
                self.parse_action = lambda _text: {"arguments": {"action": "answer"}}
                self.AdbTools = None
                self.GUIOwlWrapper = None
                self.answer_parsed = False

            def main(self) -> None:
                self.parse_action("original answer")
                self.answer_parsed = True

        loaded: dict[str, FakePhoneRun] = {}

        def load_module(path: Path, _name: str) -> object:
            if path.name == "utils.py":
                return SimpleNamespace(AdbTools=lambda **_kwargs: FakeUpstream())
            run = FakePhoneRun()
            loaded["run"] = run
            return run

        with (
            patch.dict(os.environ, {"JEV_TEST_API_KEY": "fakekey"}, clear=False),
            patch("services.original_baselines.harness._load_module", side_effect=load_module),
            tempfile.TemporaryDirectory() as directory,
        ):
            report = run_phone_baseline(
                adb_path="adb",
                serial="phone",
                credential_env="JEV_TEST_API_KEY",
                evidence_dir=directory,
                allowed_tap_bounds=(50, 50, 150, 150),
                package_checker=lambda: "com.jev.mobileagent",
            )

        self.assertTrue(loaded["run"].answer_parsed)
        self.assertNotIn("error", report)

    def test_original_answer_does_not_self_certify_without_independent_completed(self) -> None:
        class FakePhoneRun:
            def __init__(self) -> None:
                self.parse_args = lambda: SimpleNamespace(adb_path="adb", device="phone")
                self.parse_action = lambda _text: {"arguments": {"action": "answer"}}
                self.AdbTools = None
                self.GUIOwlWrapper = None

            def main(self) -> None:
                self.parse_action("original answer")

        def load_module(path: Path, _name: str) -> object:
            if path.name == "utils.py":
                return SimpleNamespace(AdbTools=lambda **_kwargs: object())
            return FakePhoneRun()

        def observe(phase: str) -> dict[str, object]:
            now = dt.datetime.now(dt.timezone.utc)
            return {
                "task_id": "controlled-task",
                "device_id": "phone",
                "observation_id": f"observation-{phase}",
                "captured_at": now.isoformat(),
                "nodes": [{"text": "Controlled action state: ready"}],
            }

        with (
            patch.dict(os.environ, {"JEV_TEST_API_KEY": "fakekey"}, clear=False),
            patch("services.original_baselines.harness._load_module", side_effect=load_module),
            tempfile.TemporaryDirectory() as directory,
        ):
            report = run_phone_baseline(
                adb_path="adb",
                serial="phone",
                credential_env="JEV_TEST_API_KEY",
                evidence_dir=directory,
                observation_provider=observe,
                allowed_tap_bounds=(50, 50, 150, 150),
                package_checker=lambda: "com.jev.mobileagent",
            )

        self.assertNotIn("error", report)
        self.assertEqual(report["independent_judge"]["success"], False, report)
        self.assertIsNone(report["success"], report)

    def test_controlled_page_judge_requires_exact_state_text(self) -> None:
        self.assertTrue(
            evaluate_controlled_observation(
                {"observation": {"nodes": [{"text": "Controlled action state: completed"}]}}
            )["success"]
        )
        self.assertFalse(
            evaluate_controlled_observation(
                {"nodes": [{"content_description": "Controlled action state completed"}]}
            )["success"]
        )

    def test_android_runner_uses_task_score_and_bounded_episode_done(self) -> None:
        class FakeEnv:
            def __init__(self) -> None:
                self.reset_calls = 0
                self.closed = False

            def reset(self, go_home: bool = False) -> None:
                self.reset_calls += 1
                self.go_home = go_home

            def close(self) -> None:
                self.closed = True

        class FakeTask:
            goal = "Turn brightness to the max value."

            def __init__(self) -> None:
                self.initialized = False
                self.torn_down = False
                self.score_calls = 0

            def initialize_task(self, env: FakeEnv) -> None:
                self.initialized = True
                self.env = env

            def is_successful(self, env: FakeEnv) -> float:
                self.judged_env = env
                self.score_calls += 1
                return 0.0 if self.score_calls == 1 else 1.0

            def tear_down(self, env: FakeEnv) -> None:
                self.torn_down = True

        env = FakeEnv()
        task = FakeTask()
        seen: dict[str, object] = {}

        def env_factory() -> FakeEnv:
            return env

        def task_factory() -> FakeTask:
            return task

        def agent_factory(_env: FakeEnv, wrapper: object, output_path: str) -> object:
            seen["wrapper"] = wrapper
            seen["output_path"] = output_path
            return object()

        def episode_runner(goal: str, agent: object, *, max_n_steps: int, start_on_home_screen: bool):
            seen.update({"goal": goal, "agent": agent, "max_n_steps": max_n_steps, "start_on_home_screen": start_on_home_screen})
            return SimpleNamespace(done=True, step_data={"step_number": [0]})

        with patch.dict(os.environ, {"JEV_TEST_API_KEY": "secret"}, clear=False), tempfile.TemporaryDirectory() as directory:
            report = run_androidworld_baseline(
                adb_path="adb",
                credential_env="JEV_TEST_API_KEY",
                evidence_dir=directory,
                max_tokens=99999,
                budget_cny=99.0,
                env_factory=env_factory,
                task_factory=task_factory,
                agent_factory=agent_factory,
                episode_runner=episode_runner,
            )
        self.assertEqual(report["status"], "success")
        self.assertTrue(report["success"])
        self.assertEqual(seen["max_n_steps"], 5)
        self.assertFalse(seen["start_on_home_screen"])
        self.assertEqual(report["config"]["max_tokens"], 1024)
        self.assertEqual(report["config"]["budget_cny"], 1.0)
        self.assertEqual(report["task"]["initial_score"], 0.0)
        self.assertEqual(report["task"]["final_score"], 1.0)
        self.assertEqual(report["execution_mode"], "injected_offline")
        self.assertFalse(report["baseline_eligible"])
        self.assertIn(str(ANDROID_EPISODE_RUNNER_SOURCE.relative_to(Path.cwd())), report["source_files"])
        self.assertIn(str(ANDROID_ENV_LAUNCHER_SOURCE.relative_to(Path.cwd())), report["source_files"])
        self.assertTrue(env.closed)
        self.assertTrue(task.initialized)
        self.assertTrue(task.torn_down)
        self.assertNotIn("secret", json.dumps(report, ensure_ascii=False))

    def test_phone_requires_independent_controlled_button_bounds_before_execution(self) -> None:
        calls: list[str] = []

        def should_not_construct_adb(*args: object, **kwargs: object) -> object:
            del args, kwargs
            calls.append("adb")
            raise AssertionError("physical ADB must not be constructed without independent tap bounds")

        with patch.dict(os.environ, {"JEV_TEST_API_KEY": "secret"}, clear=False), tempfile.TemporaryDirectory() as directory:
            report = run_phone_baseline(
                adb_path="adb",
                serial="phone",
                credential_env="JEV_TEST_API_KEY",
                evidence_dir=directory,
                adb_factory=should_not_construct_adb,
            )
        self.assertEqual(report["status"], "invalid_configuration")
        self.assertEqual(report["reason"], "allowed_tap_bounds_required_from_independent_observation")
        self.assertEqual(calls, [])

    def test_android_rejects_nonfinite_budget_before_environment_factory(self) -> None:
        calls: list[str] = []

        def should_not_construct_env() -> object:
            calls.append("env")
            raise AssertionError("invalid config must stop before environment setup")

        with patch.dict(os.environ, {"JEV_TEST_API_KEY": "fakekey"}, clear=False), tempfile.TemporaryDirectory() as directory:
            report = run_androidworld_baseline(
                adb_path="adb",
                credential_env="JEV_TEST_API_KEY",
                evidence_dir=directory,
                max_tokens=float("nan"),
                env_factory=should_not_construct_env,
            )
        self.assertEqual(report["status"], "invalid_configuration")
        self.assertEqual(report["reason"], "max_tokens_must_be_finite_integer")
        self.assertEqual(calls, [])

    def test_live_controlled_judge_rejects_stale_or_reused_observation(self) -> None:
        now = dt.datetime.now(dt.timezone.utc)
        before = {
            "task_id": "task",
            "device_id": "phone",
            "observation_id": "obs-before",
            "captured_at": (now - dt.timedelta(seconds=1)).isoformat(),
            "nodes": [{"text": "Controlled action state: ready"}],
        }
        after = {
            **before,
            "captured_at": now.isoformat(),
            "nodes": [{"text": "Controlled action state: completed"}],
        }
        result = evaluate_controlled_observations(before, after, action_at=now - dt.timedelta(milliseconds=1), checked_at=now)
        self.assertEqual(result["reason"], "before_after_observation_id_same")
        self.assertIsNone(result["success"])

    def test_original_source_is_still_present_for_hashing(self) -> None:
        self.assertTrue(ANDROID_AGENT_SOURCE.is_file())
        self.assertIn("class MobileAgentV3_M3A", ANDROID_AGENT_SOURCE.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
