"""Reference-only adapter for running the extracted role loop in AndroidWorld.

This module is deliberately kept beside the task-16 baseline harness.  It is
the compatibility layer that knows how AndroidWorld states/actions look.  The
extracted role package itself has no AndroidWorld, ADB, or baseline imports.
"""

from __future__ import annotations

import datetime as dt
import hashlib
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import numpy as np
from PIL import Image

from agent_core.vlm import ActionCommand, ObservationFrame, RoleOrchestrator


def _now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def _legacy_note_policy(info_pool: Any) -> bool:
    """Keep task-16's note triggers inside the reference-only adapter."""

    text = str(info_pool.instruction).casefold()
    return any(marker in text for marker in ("answer", "transactions from", "enter their product"))


class RoleAwareTransport:
    """Add role/step labels while preserving ``BoundedVlmTransport`` calls."""

    def __init__(self, transport: Any):
        self.transport = transport

    def predict(self, *, role: str, prompt: str, images: list[Any], step: int) -> Any:
        return self.transport.request(prompt, images, role=role, step=step)


class ExtractedAndroidWorldAgent:
    """Episode-runner-shaped shell around the device-independent role loop."""

    def __init__(self, env: Any, transport: Any, output_path: str, *, device_id: str):
        self.env = env
        self.transport = RoleAwareTransport(transport)
        self.output_path = Path(output_path)
        self.output_path.mkdir(parents=True, exist_ok=True)
        self.device_id = device_id
        self.orchestrator = RoleOrchestrator(
            self.transport,
            note_policy=_legacy_note_policy,
            allow_uncontracted_actions=True,
        )
        self._max_steps = 0
        self._observation_version = 0
        self._goal = ""
        self._task_id = ""
        self._step_index = 0
        self._json_action = None
        self._hide_automation_ui()

    def _hide_automation_ui(self) -> None:
        hide_automation_ui = getattr(self.env, "hide_automation_ui", None)
        if callable(hide_automation_ui):
            hide_automation_ui()

    def reset(self, go_home: bool = False) -> None:
        # AndroidWorld's episode runner calls this in addition to the task
        # setup performed by the baseline harness; keep that lifecycle intact.
        self.env.reset(go_home=go_home)
        self._hide_automation_ui()
        self.orchestrator.reset()
        self.orchestrator.allow_uncontracted_actions = True
        self._observation_version = 0
        self._step_index = 0
        self._goal = ""
        self._task_id = ""

    def set_max_steps(self, max_steps: int) -> None:
        self._max_steps = max_steps

    def set_task_guidelines(self, _task_guidelines: list[str]) -> None:
        # The original entry exposes this hook.  Task-specific prompt patches
        # are intentionally not carried into the extracted production roles.
        return None

    def _state_image(self, state: Any) -> tuple[np.ndarray, int, int]:
        pixels = np.asarray(state.pixels)
        if pixels.ndim < 2:
            raise ValueError("AndroidWorld state pixels must have two dimensions")
        height, width = int(pixels.shape[0]), int(pixels.shape[1])
        return pixels, width, height

    def _observation(self, *, phase: str) -> ObservationFrame:
        state = self.env.get_state(wait_to_stabilize=False)
        pixels, width, height = self._state_image(state)
        self._observation_version += 1
        self._task_id = self._task_id or "androidworld-" + hashlib.sha256(self._goal.encode("utf-8")).hexdigest()[:12]
        screenshot_path = self.output_path / f"step-{self._step_index:02d}-{phase}.png"
        Image.fromarray(pixels).save(screenshot_path)
        observation_id = f"{self._task_id}-observation-{self._observation_version}"
        contract = {
            "schema_version": "1.0",
            "android_schema_version": "1.0",
            "task_id": self._task_id,
            "device_id": self.device_id,
            "observation_id": observation_id,
            "observation_version": self._observation_version,
            "captured_at": _now(),
            # The reference backend only exposes AndroidWorld's screenshot
            # state, so report the accessibility part honestly as an empty
            # tree instead of fabricating nodes for the production contract.
            "page_state": "empty",
            "availability": "EMPTY_TREE",
            "unavailable_reason": "reference screenshot state has no accessibility tree",
            "permission": {"service_enabled": True, "can_observe": True, "reason": None},
            "screen": {
                "width_px": width,
                "height_px": height,
                "rotation": 0,
                "content_width_px": width,
                "content_height_px": height,
                "system_bar_insets": {"left": 0, "top": 0, "right": 0, "bottom": 0},
                "window_offset": {"x": 0, "y": 0},
            },
            # AndroidWorld exposes a screenshot-backed state here.  Its
            # accessibility tree is not needed by the original VLM strategy;
            # keep the fields explicit rather than inventing nodes.
            "windows": [],
            "nodes": [],
            "root_node_ids": [],
            "capabilities": ["screenshot"],
            "visual": {
                "capture_state": "CAPTURED",
                "capture_count": 1,
                "upload_count": 0,
                "missing_reason": None,
                "screenshot_id": f"{observation_id}-screenshot",
            },
            "screenshot_path": str(screenshot_path),
        }
        return ObservationFrame.from_contract(contract, screenshot=str(screenshot_path))

    def _load_json_action(self) -> Any:
        if self._json_action is None:
            from android_world.agents import new_json_action

            self._json_action = new_json_action
        return self._json_action

    def _to_android_action(self, command: ActionCommand) -> Any:
        json_action = self._load_json_action()
        raw = command.raw
        action_type = raw["action"]
        x = y = None
        text = None
        direction = None
        goal_status = None
        app_name = None
        if action_type == "open_app":
            action_type = json_action.OPEN_APP
            app_name = str(raw["text"]).lower().strip()
        elif action_type == "click":
            action_type = json_action.CLICK
            x, y = command.parameters["pixel_coordinate"]
        elif action_type == "long_press":
            action_type = json_action.LONG_PRESS
            x, y = command.parameters["pixel_coordinate"]
        elif action_type == "type":
            action_type = json_action.INPUT_TEXT
            text = raw["text"]
        elif action_type == "swipe":
            action_type = json_action.SWIPE
            direction = command.parameters["pixel_coordinates"]
        elif action_type == "system_button":
            button = str(raw.get("button", "")).casefold()
            if button == "enter":
                action_type = json_action.KEYBOARD_ENTER
            elif button == "back":
                action_type = json_action.NAVIGATE_BACK
            elif button == "home":
                action_type = json_action.NAVIGATE_HOME
            else:
                raise ValueError(f"unsupported system button: {button}")
        elif action_type == "answer":
            action_type = json_action.ANSWER
            text = raw["text"]
        else:
            raise ValueError(f"action is not executable by AndroidWorld adapter: {action_type}")
        return json_action.JSONAction(
            action_type=action_type,
            x=x,
            y=y,
            text=text,
            direction=direction,
            goal_status=goal_status,
            app_name=app_name,
        )

    def _execute(self, command: ActionCommand) -> dict[str, Any]:
        action = self._to_android_action(command)
        self.env.execute_action(action)
        if action.action_type == getattr(self._load_json_action(), "ANSWER", object()):
            return {"outcome": "EXECUTED", "kind": command.kind, "answer": True}
        return {"outcome": "EXECUTED", "kind": command.kind}

    def step(self, goal: str) -> Any:
        self._goal = goal
        if not self._task_id:
            self._task_id = "androidworld-" + hashlib.sha256(goal.encode("utf-8")).hexdigest()[:12]
        before = self._observation(phase="before")
        # ``RoleOrchestrator.step`` observes itself.  Return the captured frame
        # first only to keep the callback deterministic and avoid an extra
        # model-facing image; the callback below supplies the next fresh frame.
        frames = iter([before])

        def observe() -> ObservationFrame:
            try:
                return next(frames)
            except StopIteration:
                return self._observation(phase="after")

        self._step_index += 1
        result = self.orchestrator.step(goal, observe=observe, execute=self._execute)
        return SimpleNamespace(done=result.done, data=result.data)


__all__ = ["ExtractedAndroidWorldAgent", "RoleAwareTransport"]
