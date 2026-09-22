from __future__ import annotations

import tempfile
import unittest
import runpy
import sys
import types
from pathlib import Path
from unittest.mock import patch

import numpy as np

from agent_core.vlm import (
    ActionReflector,
    Executor,
    InfoPool,
    Manager,
    Notetaker,
    ObservationFrame,
    RoleOrchestrator,
    default_note_policy,
    parse_action_command,
)
from services.android_bridge.schema import assert_valid
from services.android_bridge.schema import assert_observation_valid
from services.original_baselines.harness import run_androidworld_extracted_baseline


def _load_upstream_roles() -> types.SimpleNamespace:
    """Load the pure upstream role module with only its action constants stubbed."""

    action_module = types.ModuleType("android_world.agents.new_json_action")
    for name in (
        "ANSWER",
        "CLICK",
        "LONG_PRESS",
        "TYPE",
        "SYSTEM_BUTTON",
        "SWIPE",
        "OPEN",
    ):
        setattr(action_module, name, "open_app" if name == "OPEN" else name.casefold())
    package = types.ModuleType("android_world")
    agents = types.ModuleType("android_world.agents")
    package.agents = agents
    agents.new_json_action = action_module
    modules = {
        "android_world": package,
        "android_world.agents": agents,
        "android_world.agents.new_json_action": action_module,
    }
    source = Path(__file__).parents[1] / (
        "Mobile-Agent-v3.5/android_world_v3.5/android_world/agents/mobile_agent_v3_agent.py"
    )
    with patch.dict(sys.modules, modules):
        namespace = runpy.run_path(str(source), run_name="upstream_roles_fixture")
    return types.SimpleNamespace(**namespace)


def _load_upstream_mobile_agent() -> types.SimpleNamespace:
    """Load the original four-role entrypoint with only AndroidWorld seams stubbed."""

    roles = _load_upstream_roles()

    action_module = types.ModuleType("android_world.agents.new_json_action")
    for name, value in {
        "ANSWER": "answer",
        "CLICK": "click",
        "LONG_PRESS": "long_press",
        "TYPE": "type",
        "SYSTEM_BUTTON": "system_button",
        "SWIPE": "swipe",
        "OPEN": "open_app",
    }.items():
        setattr(action_module, name, value)
    action_module.OPEN_APP = "open_app"
    action_module.INPUT_TEXT = "type"
    action_module.KEYBOARD_ENTER = "enter"
    action_module.NAVIGATE_BACK = "back"
    action_module.NAVIGATE_HOME = "home"
    action_module.STATUS = "status"
    action_module.GOAL_STATUS = "completed"

    class JSONAction:
        def __init__(
            self,
            *,
            action_type: str,
            x: object = None,
            y: object = None,
            text: object = None,
            direction: object = None,
            goal_status: object = None,
            app_name: object = None,
        ) -> None:
            self.action_type = action_type
            self.x = x
            self.y = y
            self.text = text
            self.direction = direction
            self.goal_status = goal_status
            self.app_name = app_name

    action_module.JSONAction = JSONAction

    base_module = types.ModuleType("android_world.agents.base_agent")

    class AgentInteractionResult:
        def __init__(self, done: bool, data: dict[str, object]) -> None:
            self.done = done
            self.data = data

    class EnvironmentInteractingAgent:
        def __init__(self, env: object, name: str) -> None:
            self.env = env
            self.name = name

        def reset(self, go_home_on_reset: bool = False) -> None:
            del go_home_on_reset

        def get_post_transition_state(self) -> object:
            return self.env.get_state(wait_to_stabilize=True)  # type: ignore[attr-defined]

    base_module.AgentInteractionResult = AgentInteractionResult
    base_module.EnvironmentInteractingAgent = EnvironmentInteractingAgent

    infer_module = types.ModuleType("android_world.agents.infer_ma3")
    infer_module.MultimodalLlmWrapper = object
    m3a_utils_module = types.ModuleType("android_world.agents.m3a_utils")
    m3a_utils_module.add_screenshot_label = lambda *_args: None
    adb_utils_module = types.ModuleType("android_world.env.adb_utils")
    adb_utils_module.launch_app = lambda *_args: None
    adb_utils_module.press_home_button = lambda *_args: None
    tools_module = types.ModuleType("android_world.env.tools")
    tools_module.AndroidToolController = object
    interface_module = types.ModuleType("android_world.env.interface")
    interface_module.AsyncEnv = object

    roles_module = types.ModuleType("android_world.agents.mobile_agent_v3_agent")
    for name in ("InfoPool", "Manager", "Executor", "Notetaker", "ActionReflector", "ALL_APPS"):
        setattr(roles_module, name, getattr(roles, name))

    agents_package = types.ModuleType("android_world.agents")
    agents_package.__path__ = []
    env_package = types.ModuleType("android_world.env")
    env_package.__path__ = []
    android_world_package = types.ModuleType("android_world")
    android_world_package.__path__ = []
    android_world_package.agents = agents_package
    android_world_package.env = env_package
    for name, module in (
        ("base_agent", base_module),
        ("infer_ma3", infer_module),
        ("m3a_utils", m3a_utils_module),
        ("new_json_action", action_module),
        ("mobile_agent_v3_agent", roles_module),
    ):
        setattr(agents_package, name, module)
    for name, module in (
        ("adb_utils", adb_utils_module),
        ("tools", tools_module),
        ("interface", interface_module),
    ):
        setattr(env_package, name, module)

    modules = {
        "android_world": android_world_package,
        "android_world.agents": agents_package,
        "android_world.env": env_package,
        "android_world.agents.base_agent": base_module,
        "android_world.agents.infer_ma3": infer_module,
        "android_world.agents.m3a_utils": m3a_utils_module,
        "android_world.agents.new_json_action": action_module,
        "android_world.agents.mobile_agent_v3_agent": roles_module,
        "android_world.env.adb_utils": adb_utils_module,
        "android_world.env.tools": tools_module,
        "android_world.env.interface": interface_module,
    }
    source = Path(__file__).parents[1] / (
        "Mobile-Agent-v3.5/android_world_v3.5/android_world/agents/mobile_agent_v3.py"
    )
    with patch.dict(sys.modules, modules):
        namespace = runpy.run_path(str(source), run_name="upstream_mobile_agent_fixture")
    return types.SimpleNamespace(**namespace)


def _observation(*, version: int = 1, screenshot: str | None = None) -> ObservationFrame:
    return ObservationFrame.from_contract(
        {
            "schema_version": "1.0",
            "android_schema_version": "1.0",
            "task_id": "task-1",
            "device_id": "device-1",
            "observation_id": f"observation-{version}",
            "observation_version": version,
            "captured_at": "2026-09-23T00:00:00Z",
            "page_state": "observed",
            "availability": "AVAILABLE",
            "unavailable_reason": None,
            "permission": {"service_enabled": True, "can_observe": True, "reason": None},
            "screen": {"width_px": 1200, "height_px": 2000, "rotation": 0},
            "windows": [],
            "nodes": [],
            "root_node_ids": [],
            "capabilities": [],
        },
        screenshot=screenshot,
    )


class _FixtureModel:
    def __init__(self, responses: list[str]) -> None:
        self.responses = iter(responses)
        self.calls: list[tuple[str, str, list[object]]] = []

    def predict_mm(self, prompt: str, images: list[object] | None = None) -> str:
        self.calls.append(("unknown", prompt, list(images or [])))
        return next(self.responses)


class _RoleAwareFixtureModel:
    def __init__(self, responses: list[str]) -> None:
        self.responses = iter(responses)
        self.calls: list[tuple[str, str, list[object], int]] = []

    def predict(self, *, role: str, prompt: str, images: list[object], step: int) -> str:
        self.calls.append((role, prompt, list(images), step))
        return next(self.responses)


class _PairedFixtureModel:
    """The same model boundary fixture works for original and extracted loops."""

    def __init__(self, responses: list[str]) -> None:
        self.responses = iter(responses)
        self.calls: list[tuple[str, str, list[object]]] = []

    @staticmethod
    def _role(prompt: str) -> str:
        if "### Outcome ###" in prompt:
            return "action_reflector"
        if "### Action ###" in prompt:
            return "executor"
        if "### Plan ###" in prompt:
            return "manager"
        if "### Important Notes ###" in prompt:
            return "notetaker"
        return "unknown"

    def predict_mm(self, prompt: str, images: list[object] | None = None) -> tuple[str, None, bool]:
        self.calls.append((self._role(prompt), prompt, list(images or [])))
        return next(self.responses), None, True


class _OriginalState:
    pixels = np.zeros((2, 2, 3), dtype="uint8")


class _OriginalEnvironment:
    def __init__(self, state_count: int) -> None:
        self._states = iter(_OriginalState() for _ in range(state_count))
        self.get_state_calls: list[bool] = []
        self.actions: list[object] = []

    def hide_automation_ui(self) -> None:
        pass

    def get_state(self, *, wait_to_stabilize: bool) -> _OriginalState:
        self.get_state_calls.append(wait_to_stabilize)
        return next(self._states)

    def execute_action(self, action: object) -> None:
        self.actions.append(action)


def _critical_state(info_pool: object) -> dict[str, object]:
    """State that determines the four-role branch outcome, excluding adapters."""

    return {
        name: getattr(info_pool, name)
        for name in (
            "action_pool",
            "summary_history",
            "action_history",
            "action_outcomes",
            "error_descriptions",
            "last_summary",
            "last_action",
            "last_action_thought",
            "important_notes",
            "error_flag_plan",
            "plan",
            "completed_plan",
            "progress_status",
            "finish_thought",
        )
    }


def _seed_error_state(info_pool: object) -> None:
    info_pool.action_history = [{"action": "click"}, {"action": "swipe"}]
    info_pool.summary_history = ["wrong", "no change"]
    info_pool.action_outcomes = ["B", "C"]
    info_pool.error_descriptions = ["wrong page", "bottom reached"]
    info_pool.plan = "1. recover"


def _run_paired_branch(
    responses: list[str],
    *,
    goal: str,
    state_count: int,
    seed_error_state: bool = False,
    allow_answer: bool = False,
    request_note: bool = False,
) -> tuple[object, object, object, object, object, object, object, object]:
    """Run one frozen response sequence through original and extracted loops."""

    upstream = _load_upstream_mobile_agent()
    original_model = _PairedFixtureModel(list(responses))
    original_environment = _OriginalEnvironment(state_count)
    with tempfile.TemporaryDirectory() as directory:
        original_agent = upstream.MobileAgentV3_M3A(
            original_environment,
            original_model,
            wait_after_action_seconds=0,
            output_path=directory,
        )
        if seed_error_state:
            _seed_error_state(original_agent.info_pool)
        original_result = original_agent.step(goal)
    extracted_model = _PairedFixtureModel(list(responses))
    extracted_agent = RoleOrchestrator(
        extracted_model,
        note_policy=(lambda _info: request_note),
        allow_uncontracted_actions=allow_answer,
    )
    if seed_error_state:
        _seed_error_state(extracted_agent.info_pool)
    observations = iter(_observation(version=index + 1) for index in range(state_count))
    extracted_actions: list[object] = []
    extracted_result = extracted_agent.step(
        goal,
        observe=lambda: next(observations),
        execute=lambda action: extracted_actions.append(action) or {"outcome": "EXECUTED"},
    )
    return (
        original_result,
        extracted_result,
        original_agent.info_pool,
        extracted_agent.info_pool,
        original_model,
        extracted_model,
        original_environment,
        extracted_actions,
    )


class ExtractedRoleTests(unittest.TestCase):
    def test_neutral_roles_match_upstream_prompt_and_parser_shapes(self) -> None:
        upstream = _load_upstream_roles()
        pairs = (
            (upstream.Manager, Manager, "### Thought ###\nplan\n### Plan ###\n1. tap"),
            (
                upstream.Executor,
                Executor,
                '### Thought ###\nsee\n### Action ###\n{"action":"click","coordinate":[1,2]}\n### Description ###\ntap',
            ),
            (
                upstream.ActionReflector,
                ActionReflector,
                "### Outcome ###\nB\n### Error Description ###\nwrong page",
            ),
            (upstream.Notetaker, Notetaker, "### Important Notes ###\nvalue"),
        )
        for old_type, new_type, response in pairs:
            old_info = upstream.InfoPool(
                instruction="neutral task",
                additional_knowledge_manager=[""],
                additional_knowledge_executor=[""],
            )
            new_info = InfoPool(
                instruction="neutral task",
                additional_knowledge_manager=[""],
                additional_knowledge_executor=[""],
            )
            old_role, new_role = old_type(), new_type()
            old_prompt = old_role.get_prompt(old_info)
            new_prompt = new_role.get_prompt(new_info)
            if old_type is upstream.Executor:
                old_prompt = old_prompt.replace(" (y1 < 1400)", "")
            self.assertEqual(old_prompt, new_prompt, old_type.__name__)
            self.assertEqual(old_role.parse_response(response), new_role.parse_response(response), old_type.__name__)

    def test_reference_entrypoint_records_real_mode_without_faking_a_run(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = run_androidworld_extracted_baseline(
                adb_path="adb",
                credential_env="JEV_ROLE_EXTRACTION_TEST_MISSING",
                evidence_dir=directory,
            )
        self.assertEqual(report["entrypoint"], "extracted_roles_androidworld_reference")
        self.assertEqual(report["status"], "credentials_required")
        self.assertFalse(report["baseline_eligible"])
        self.assertEqual(report["baseline_result"], "not_run")
        self.assertEqual(report["execution_mode"], "real_environment")
        self.assertEqual(report["config"]["source_hash_mode"], "explicit_extracted_and_original")
        self.assertIn("agent_core/vlm/roles.py", report["source_files"])
        self.assertIn("services/original_baselines/extracted_androidworld.py", report["source_files"])

    def test_roles_keep_original_sections_without_benchmark_specific_hints(self) -> None:
        info = InfoPool(instruction="open settings and change brightness")
        prompts = [
            Manager().get_prompt(info),
            Executor().get_prompt(info),
            ActionReflector().get_prompt(info),
            Notetaker().get_prompt(info),
        ]
        combined = "\n".join(prompts)
        self.assertIn("### Thought ###", combined)
        self.assertIn("### Action ###", combined)
        self.assertIn("### Outcome ###", combined)
        self.assertIn("### Important Notes ###", combined)
        for forbidden in ("Audio Recorder", "exact duplicates", "Chrome initialization", "1080", "1400", "2400"):
            self.assertNotIn(forbidden, combined)

    def test_default_note_policy_is_caller_controlled(self) -> None:
        self.assertFalse(default_note_policy(InfoPool(instruction="answer whether it changed")))
        self.assertTrue(default_note_policy(InfoPool(note_requested=True)))

    def test_original_response_sections_are_parsed(self) -> None:
        self.assertEqual(
            Manager().parse_response("### Thought ###\nplan\n### Plan ###\n1. tap")['plan'],
            "1. tap",
        )
        self.assertEqual(
            Executor().parse_response(
                '### Thought ###\nsee\n### Action ###\n{"action":"click","coordinate":[1,2]}\n### Description ###\ntap'
            )["action"],
            '{"action":"click","coordinate":[1,2]}',
        )
        self.assertEqual(
            ActionReflector().parse_response("### Outcome ###\nB\n### Error Description ###\nwrong page")["outcome"],
            "B",
        )
        self.assertEqual(
            Notetaker().parse_response("### Important Notes ###\nvalue")["important_notes"],
            "value",
        )

    def test_coordinates_use_observation_dimensions_and_contract_frame(self) -> None:
        observation = _observation()
        assert_valid(observation.as_contract(), "observation")
        click = parse_action_command('{"action":"click","coordinate":[250,500]}', observation)
        self.assertEqual(click.parameters["pixel_coordinate"], [300, 1000])
        self.assertEqual(click.contract_action["kind"], "coordinate_tap")
        self.assertEqual(click.contract_action["coordinate_frame"]["screen_width_px"], 1200)
        assert_valid(click.contract_action, "action")
        edge = parse_action_command('{"action":"click","coordinate":[1000,1000]}', observation)
        self.assertEqual(edge.parameters["pixel_coordinate"], [1199, 1999])
        assert_valid(edge.contract_action, "action")
        swipe = parse_action_command(
            '{"action":"swipe","coordinate":[0,0],"coordinate2":[1000,1000]}', observation
        )
        self.assertEqual(swipe.contract_action["parameters"], {"x1": 0, "y1": 0, "x2": 1199, "y2": 1999})
        with self.assertRaises(ValueError):
            parse_action_command('{"action":"click","coordinate":[1001,500]}', observation)
        with self.assertRaises(ValueError):
            parse_action_command('{"action":"open_app","text":"settings"}', observation)
        reference_open = parse_action_command(
            '{"action":"open_app","text":"settings"}',
            observation,
            allow_uncontracted_actions=True,
        )
        self.assertIsNone(reference_open.contract_action)

    def test_reference_observation_contract_can_be_semantically_valid_without_a_tree(self) -> None:
        from services.original_baselines.extracted_androidworld import ExtractedAndroidWorldAgent

        class State:
            pixels = np.zeros((2000, 1200, 3), dtype="uint8")

        class Environment:
            def get_state(self, *, wait_to_stabilize: bool) -> State:
                del wait_to_stabilize
                return State()

        with tempfile.TemporaryDirectory() as directory:
            agent = ExtractedAndroidWorldAgent(Environment(), object(), directory, device_id="device-1")
            agent._goal = "Turn brightness to the max value."
            observation = agent._observation(phase="before")
        assert_observation_valid(observation.as_contract())

    def test_one_step_preserves_manager_executor_reflector_order(self) -> None:
        model = _FixtureModel(
            [
                "### Thought ###\nmake a plan\n### Plan ###\n1. click",
                '### Thought ###\nclick it\n### Action ###\n{"action":"click","coordinate":[250,500]}\n### Description ###\nclick the control',
                "### Outcome ###\nA\n### Error Description ###\nNone",
            ]
        )
        observations = iter([_observation(version=1), _observation(version=2)])
        executed: list[object] = []
        agent = RoleOrchestrator(model)
        result = agent.step(
            "click the control",
            observe=lambda: next(observations),
            execute=lambda action: executed.append(action) or {"outcome": "EXECUTED"},
        )
        self.assertFalse(result.done)
        self.assertEqual(len(model.calls), 3)
        self.assertEqual(len(executed), 1)
        self.assertEqual(executed[0].contract_action["parameters"], {"x": 300, "y": 1000})
        self.assertEqual(agent.info_pool.action_outcomes, ["A"])

    def test_finished_plan_stops_without_executor_or_device_action(self) -> None:
        model = _FixtureModel(["### Thought ###\ncomplete\n### Plan ###\nFinished"])
        executed: list[object] = []
        agent = RoleOrchestrator(model)
        result = agent.step(
            "finish the task",
            observe=lambda: _observation(),
            execute=lambda action: executed.append(action),
        )
        self.assertTrue(result.done)
        self.assertEqual(len(model.calls), 1)
        self.assertEqual(executed, [])
        self.assertEqual(agent.info_pool.action_outcomes, ["A"])

    def test_successful_note_trigger_keeps_notetaker_branch(self) -> None:
        model = _RoleAwareFixtureModel(
            [
                "### Thought ###\nmake a plan\n### Plan ###\n1. click",
                '### Thought ###\nclick it\n### Action ###\n{"action":"click","coordinate":[250,500]}\n### Description ###\nclick the control',
                "### Outcome ###\nA\n### Error Description ###\nNone",
                "### Important Notes ###\nbrightness changed",
            ]
        )
        observations = iter([_observation(version=1), _observation(version=2)])
        agent = RoleOrchestrator(model, note_policy=lambda _info: True)
        result = agent.step(
            "answer whether brightness changed",
            observe=lambda: next(observations),
            execute=lambda _action: {"outcome": "EXECUTED"},
        )
        self.assertFalse(result.done)
        self.assertEqual([call[0] for call in model.calls], ["manager", "executor", "action_reflector", "notetaker"])
        self.assertEqual(agent.info_pool.important_notes, "brightness changed")

    def test_two_failed_outcomes_escalate_to_manager_with_failure_context(self) -> None:
        model = _RoleAwareFixtureModel(
            [
                "### Thought ###\nrevise\n### Plan ###\n1. recover",
                "### Thought ###\nretry\n### Action ###\nnot-json\n### Description ###\ninvalid",
            ]
        )
        agent = RoleOrchestrator(model)
        agent.info_pool.action_history = [{"action": "click"}, {"action": "swipe"}]
        agent.info_pool.summary_history = ["wrong", "no change"]
        agent.info_pool.action_outcomes = ["B", "C"]
        agent.info_pool.error_descriptions = ["wrong page", "bottom reached"]
        agent.info_pool.plan = "1. recover"
        result = agent.step(
            "recover the task",
            observe=lambda: _observation(),
            execute=lambda _action: {"outcome": "EXECUTED"},
        )
        self.assertFalse(result.done)
        self.assertEqual([call[0] for call in model.calls], ["manager", "executor"])
        self.assertIn("### Potentially Stuck! ###", model.calls[0][1])
        self.assertEqual(agent.info_pool.action_outcomes[-1], "C")

    def test_invalid_action_is_recorded_without_device_execution(self) -> None:
        model = _FixtureModel(
            [
                "### Thought ###\nmake a plan\n### Plan ###\n1. act",
                "### Thought ###\nI cannot format this\n### Action ###\nnot-json\n### Description ###\ninvalid",
            ]
        )
        agent = RoleOrchestrator(model)
        executed: list[object] = []
        result = agent.step(
            "act",
            observe=lambda: _observation(),
            execute=lambda action: executed.append(action),
        )
        self.assertFalse(result.done)
        self.assertEqual(executed, [])
        self.assertEqual(agent.info_pool.action_history[-1], {"action": "invalid"})
        self.assertEqual(agent.info_pool.action_outcomes[-1], "C")

    def test_answer_branch_preserves_completion_status_and_skips_reflection(self) -> None:
        model = _FixtureModel(
            [
                "### Thought ###\nmake a plan\n### Plan ###\n1. answer",
                '### Thought ###\nanswer it\n### Action ###\n{"action":"answer","text":"done"}\n### Description ###\nanswer the user',
            ]
        )
        agent = RoleOrchestrator(model, allow_uncontracted_actions=True)
        executed: list[object] = []
        result = agent.step(
            "answer the user",
            observe=lambda: _observation(),
            execute=lambda action: executed.append(action) or {"outcome": "EXECUTED"},
        )
        self.assertTrue(result.done)
        self.assertEqual(len(model.calls), 2)
        self.assertEqual(len(executed), 1)
        self.assertIn("The `answer` action has been performed. Answer to the question: done", agent.info_pool.progress_status)
        self.assertEqual(agent.info_pool.action_outcomes, ["A"])

    def test_paired_invalid_action_keeps_original_branch_state_and_calls(self) -> None:
        responses = [
            "### Thought ###\nmake a plan\n### Plan ###\n1. act",
            "### Thought ###\nI cannot format this\n### Action ###\nnot-json\n### Description ###\ninvalid",
        ]
        (
            original_result,
            extracted_result,
            original_info,
            extracted_info,
            original_model,
            extracted_model,
            original_environment,
            extracted_actions,
        ) = _run_paired_branch(responses, goal="act", state_count=1)
        self.assertFalse(original_result.done)
        self.assertFalse(extracted_result.done)
        self.assertEqual(_critical_state(original_info), _critical_state(extracted_info))
        self.assertEqual(
            [call[0] for call in original_model.calls],
            ["manager", "executor"],
        )
        self.assertEqual(
            [call[0] for call in extracted_model.calls],
            ["manager", "executor"],
        )
        self.assertEqual(len(original_environment.actions), len(extracted_actions))
        self.assertEqual(original_info.action_outcomes[-1], "C")
        self.assertEqual(original_info.action_pool, [])
        self.assertEqual(extracted_info.action_pool, [])
        self.assertEqual(original_info.error_descriptions[-1], "invalid action format, do nothing.")
        self.assertEqual(extracted_info.error_descriptions[-1], "invalid action format, do nothing.")

    def test_paired_error_escalation_keeps_failure_context_and_calls(self) -> None:
        responses = [
            "### Thought ###\nrevise\n### Plan ###\n1. recover",
            "### Thought ###\nretry\n### Action ###\nnot-json\n### Description ###\ninvalid",
        ]
        (
            original_result,
            extracted_result,
            original_info,
            extracted_info,
            original_model,
            extracted_model,
            original_environment,
            extracted_actions,
        ) = _run_paired_branch(
            responses,
            goal="recover the task",
            state_count=1,
            seed_error_state=True,
        )
        self.assertFalse(original_result.done)
        self.assertFalse(extracted_result.done)
        self.assertEqual(_critical_state(original_info), _critical_state(extracted_info))
        self.assertTrue(original_info.error_flag_plan)
        self.assertTrue(extracted_info.error_flag_plan)
        self.assertIn("### Potentially Stuck! ###", original_model.calls[0][1])
        self.assertIn("### Potentially Stuck! ###", extracted_model.calls[0][1])
        self.assertEqual(
            [call[0] for call in original_model.calls],
            ["manager", "executor"],
        )
        self.assertEqual(
            [call[0] for call in extracted_model.calls],
            ["manager", "executor"],
        )
        self.assertEqual(len(original_environment.actions), len(extracted_actions))
        self.assertEqual(original_info.action_pool, [])
        self.assertEqual(extracted_info.action_pool, [])
        self.assertEqual(
            original_info.error_descriptions,
            ["wrong page", "bottom reached", "invalid action format, do nothing."],
        )
        self.assertEqual(extracted_info.error_descriptions, original_info.error_descriptions)

    def test_paired_finished_plan_keeps_terminal_state_and_skips_execution(self) -> None:
        responses = ["### Thought ###\ncomplete\n### Plan ###\nFinished"]
        (
            original_result,
            extracted_result,
            original_info,
            extracted_info,
            original_model,
            extracted_model,
            original_environment,
            extracted_actions,
        ) = _run_paired_branch(responses, goal="finish the task", state_count=1)
        self.assertTrue(original_result.done)
        self.assertTrue(extracted_result.done)
        self.assertEqual(_critical_state(original_info), _critical_state(extracted_info))
        self.assertEqual([call[0] for call in original_model.calls], ["manager"])
        self.assertEqual([call[0] for call in extracted_model.calls], ["manager"])
        self.assertEqual(len(original_environment.actions), 0)
        self.assertEqual(extracted_actions, [])

    def test_paired_answer_keeps_completion_state_and_skips_reflection(self) -> None:
        responses = [
            "### Thought ###\nmake a plan\n### Plan ###\n1. answer",
            '### Thought ###\nanswer it\n### Action ###\n{"action":"answer","text":"done"}\n### Description ###\nanswer the user',
        ]
        (
            original_result,
            extracted_result,
            original_info,
            extracted_info,
            original_model,
            extracted_model,
            original_environment,
            extracted_actions,
        ) = _run_paired_branch(
            responses,
            goal="answer the user",
            state_count=2,
            allow_answer=True,
        )
        self.assertTrue(original_result.done)
        self.assertTrue(extracted_result.done)
        self.assertEqual(_critical_state(original_info), _critical_state(extracted_info))
        self.assertEqual([call[0] for call in original_model.calls], ["manager", "executor"])
        self.assertEqual([call[0] for call in extracted_model.calls], ["manager", "executor"])
        self.assertEqual(len(original_environment.actions), 1)
        self.assertEqual(len(extracted_actions), 1)
        self.assertIn("The `answer` action has been performed. Answer to the question: done", original_info.progress_status)
        self.assertIn("The `answer` action has been performed. Answer to the question: done", extracted_info.progress_status)

    def test_paired_notetaker_keeps_success_branch_state_and_calls(self) -> None:
        responses = [
            "### Thought ###\nmake a plan\n### Plan ###\n1. click",
            '### Thought ###\nclick it\n### Action ###\n{"action":"click","coordinate":[250,500]}\n### Description ###\nclick the control',
            "### Outcome ###\nA\n### Error Description ###\nNone",
            "### Important Notes ###\nbrightness changed",
        ]
        (
            original_result,
            extracted_result,
            original_info,
            extracted_info,
            original_model,
            extracted_model,
            original_environment,
            extracted_actions,
        ) = _run_paired_branch(
            responses,
            goal="answer whether brightness changed",
            state_count=2,
            request_note=True,
        )
        self.assertFalse(original_result.done)
        self.assertFalse(extracted_result.done)
        self.assertEqual(_critical_state(original_info), _critical_state(extracted_info))
        self.assertEqual(original_info.important_notes, "brightness changed")
        self.assertEqual(extracted_info.important_notes, "brightness changed")
        expected_calls = ["manager", "executor", "action_reflector", "notetaker"]
        self.assertEqual([call[0] for call in original_model.calls], expected_calls)
        self.assertEqual([call[0] for call in extracted_model.calls], expected_calls)
        self.assertEqual(len(original_environment.actions), 1)
        self.assertEqual(len(extracted_actions), 1)


if __name__ == "__main__":
    unittest.main()
