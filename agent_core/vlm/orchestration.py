"""A minimal observation/action adapter for the extracted v3.5 roles.

The loop is intentionally a small seam, not a new task server.  A caller
provides an observation callback and an action callback.  The model boundary
is injected, so the same role strategy can use the existing bounded transport,
a replay fixture, or a future service without importing a device framework.
"""

from __future__ import annotations

from dataclasses import dataclass, field
import datetime as dt
import json
import math
from typing import Any, Callable, Mapping, Protocol, Sequence

from .roles import ActionReflector, Executor, InfoPool, Manager, Notetaker


SCHEMA_VERSION = "1.0"
ANDROID_SCHEMA_VERSION = "1.0"
NORMALIZED_COORDINATE_MAX = 1000
ROLE_NAMES = ("manager", "executor", "action_reflector", "notetaker")


class ModelBoundary(Protocol):
    """The only model dependency required by the extracted orchestration."""

    def predict_mm(self, prompt: str, images: Sequence[Any] | None = None) -> Any:
        ...


@dataclass(frozen=True)
class ScreenFrame:
    """Dimensions and coordinate metadata from the Android contract."""

    width_px: int
    height_px: int
    rotation: int = 0
    content_width_px: int | None = None
    content_height_px: int | None = None
    system_bar_insets: Mapping[str, int] = field(default_factory=dict)
    window_offset: Mapping[str, int] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if isinstance(self.width_px, bool) or self.width_px < 1:
            raise ValueError("screen width_px must be positive")
        if isinstance(self.height_px, bool) or self.height_px < 1:
            raise ValueError("screen height_px must be positive")
        if self.rotation not in {0, 1, 2, 3}:
            raise ValueError("screen rotation must be between 0 and 3")

    def as_contract(self) -> dict[str, Any]:
        insets = {
            "left": int(self.system_bar_insets.get("left", 0)),
            "top": int(self.system_bar_insets.get("top", 0)),
            "right": int(self.system_bar_insets.get("right", 0)),
            "bottom": int(self.system_bar_insets.get("bottom", 0)),
        }
        offset = {
            "x": int(self.window_offset.get("x", 0)),
            "y": int(self.window_offset.get("y", 0)),
        }
        return {
            "screen_width_px": self.width_px,
            "screen_height_px": self.height_px,
            "rotation": self.rotation,
            "model_width_px": self.width_px,
            "model_height_px": self.height_px,
            "content_width_px": self.content_width_px or self.width_px,
            "content_height_px": self.content_height_px or self.height_px,
            "system_bar_insets": insets,
            "window_offset": offset,
            "coordinate_space": "screen",
        }


@dataclass(frozen=True)
class ObservationFrame:
    """The smallest observation required by the role loop.

    ``contract`` carries the validated v1/Android payload for callers that
    need the full accessibility tree.  The role loop only reads identity,
    dimensions and the image reference, so it remains independent from the
    concrete device implementation.
    """

    task_id: str
    device_id: str
    observation_id: str
    observation_version: int
    captured_at: str
    screen: ScreenFrame
    screenshot: Any = None
    contract: Mapping[str, Any] = field(default_factory=dict)

    @classmethod
    def from_contract(
        cls,
        value: Mapping[str, Any],
        *,
        screenshot: Any = None,
    ) -> "ObservationFrame":
        if not isinstance(value, Mapping):
            raise TypeError("observation must be a mapping")
        screen_value = value.get("screen") or {}
        if not isinstance(screen_value, Mapping):
            raise ValueError("observation.screen must be a mapping")
        width = screen_value.get("width_px", value.get("width_px"))
        height = screen_value.get("height_px", value.get("height_px"))
        if width is None or height is None:
            raise ValueError("observation screen dimensions are required")
        frame = ScreenFrame(
            width_px=int(width),
            height_px=int(height),
            rotation=int(screen_value.get("rotation", 0)),
            content_width_px=(
                int(screen_value["content_width_px"])
                if screen_value.get("content_width_px") is not None
                else None
            ),
            content_height_px=(
                int(screen_value["content_height_px"])
                if screen_value.get("content_height_px") is not None
                else None
            ),
            system_bar_insets=dict(screen_value.get("system_bar_insets") or {}),
            window_offset=dict(screen_value.get("window_offset") or {}),
        )
        image = screenshot
        if image is None:
            image = value.get("screenshot_path", value.get("image_path"))
        return cls(
            task_id=str(value.get("task_id", "")),
            device_id=str(value.get("device_id", "")),
            observation_id=str(value["observation_id"]),
            observation_version=int(value.get("observation_version", 1)),
            captured_at=str(value.get("captured_at", "")),
            screen=frame,
            screenshot=image,
            contract=dict(value),
        )

    def as_contract(self) -> dict[str, Any]:
        if self.contract:
            # screenshot_path/image_path are local adapter handles, not fields
            # in the versioned observation envelope.
            result = {
                key: value
                for key, value in self.contract.items()
                if key not in {"screenshot_path", "image_path"}
            }
        else:
            result = {
                "schema_version": SCHEMA_VERSION,
                "android_schema_version": ANDROID_SCHEMA_VERSION,
                "task_id": self.task_id,
                "device_id": self.device_id,
                "observation_id": self.observation_id,
                "observation_version": self.observation_version,
                "captured_at": self.captured_at,
            }
        result.update(
            {
                "task_id": self.task_id,
                "device_id": self.device_id,
                "observation_id": self.observation_id,
                "observation_version": self.observation_version,
                "captured_at": self.captured_at,
                "screen": {
                    "width_px": self.screen.width_px,
                    "height_px": self.screen.height_px,
                    "rotation": self.screen.rotation,
                    "content_width_px": self.screen.content_width_px or self.screen.width_px,
                    "content_height_px": self.screen.content_height_px or self.screen.height_px,
                    "system_bar_insets": {
                        "left": int(self.screen.system_bar_insets.get("left", 0)),
                        "top": int(self.screen.system_bar_insets.get("top", 0)),
                        "right": int(self.screen.system_bar_insets.get("right", 0)),
                        "bottom": int(self.screen.system_bar_insets.get("bottom", 0)),
                    },
                    "window_offset": {
                        "x": int(self.screen.window_offset.get("x", 0)),
                        "y": int(self.screen.window_offset.get("y", 0)),
                    },
                },
            }
        )
        return result


@dataclass(frozen=True)
class ActionCommand:
    """Parsed model action plus its Android-contract representation."""

    raw: dict[str, Any]
    kind: str
    parameters: dict[str, Any]
    contract_action: dict[str, Any] | None

    @property
    def terminal(self) -> bool:
        return self.kind in {"done", "terminate"}

    @property
    def answer(self) -> bool:
        return self.kind == "answer"

    def as_dict(self) -> dict[str, Any]:
        return {
            "raw": dict(self.raw),
            "kind": self.kind,
            "parameters": dict(self.parameters),
            "contract_action": dict(self.contract_action) if self.contract_action else None,
        }


@dataclass(frozen=True)
class StepResult:
    done: bool
    data: dict[str, Any]


def _utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def _pixel_coordinate(value: Any, size: int, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
        raise ValueError(f"{name} must be a finite number")
    numeric = float(value)
    if numeric < 0 or numeric > NORMALIZED_COORDINATE_MAX:
        raise ValueError(f"{name} must be between 0 and 1000")
    # The v3.5 convention uses the full inclusive 0..1000 model range.  A
    # model can therefore return 1000, while the Android coordinate contract
    # accepts only pixel indices below the observed width/height.
    return min(size - 1, int(numeric / NORMALIZED_COORDINATE_MAX * size))


def _coordinate_frame(observation: ObservationFrame) -> dict[str, Any]:
    return observation.screen.as_contract()


def _base_contract_action(
    observation: ObservationFrame,
    *,
    sequence: int,
    kind: str,
    parameters: Mapping[str, Any],
    target_role: str = "visual_surface",
) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "android_schema_version": ANDROID_SCHEMA_VERSION,
        "task_id": observation.task_id,
        "device_id": observation.device_id,
        "action_id": f"vlm-action-{observation.task_id}-{sequence}",
        "observation_id": observation.observation_id,
        "observation_version": observation.observation_version,
        "sequence": sequence,
        "kind": kind,
        "target_node_id": "",
        "target_node_label": "",
        "target_node_role": target_role,
        "expected_page_state": f"{kind}_requested",
        "parameters": dict(parameters),
        "coordinate_frame": _coordinate_frame(observation),
        "requires_screenshot": True,
        "before_screenshot_id": None,
        "source": "vlm-extracted-role",
        "created_at": _utc_now(),
    }


def parse_action_command(
    action_text: str,
    observation: ObservationFrame,
    *,
    sequence: int = 1,
    allow_uncontracted_actions: bool = False,
) -> ActionCommand:
    """Parse the original JSON action and map it to Android contract data."""

    try:
        raw = json.loads(action_text)
    except (TypeError, json.JSONDecodeError) as exc:
        raise ValueError("invalid action JSON") from exc
    if not isinstance(raw, dict) or not isinstance(raw.get("action"), str):
        raise ValueError("action JSON must contain an action string")
    kind = raw["action"]
    params: dict[str, Any] = {}
    contract: dict[str, Any] | None = None
    if kind == "click":
        coordinate = raw.get("coordinate")
        if not isinstance(coordinate, (list, tuple)) or len(coordinate) != 2:
            raise ValueError("click requires coordinate [x, y]")
        px = _pixel_coordinate(coordinate[0], observation.screen.width_px, "coordinate[0]")
        py = _pixel_coordinate(coordinate[1], observation.screen.height_px, "coordinate[1]")
        params = {
            "coordinate": [coordinate[0], coordinate[1]],
            "pixel_coordinate": [px, py],
            "coordinate_space": "screen",
        }
        contract = _base_contract_action(observation, sequence=sequence, kind="coordinate_tap", parameters={"x": px, "y": py})
    elif kind == "long_press":
        coordinate = raw.get("coordinate")
        if not isinstance(coordinate, (list, tuple)) or len(coordinate) != 2:
            raise ValueError("long_press requires coordinate [x, y]")
        px = _pixel_coordinate(coordinate[0], observation.screen.width_px, "coordinate[0]")
        py = _pixel_coordinate(coordinate[1], observation.screen.height_px, "coordinate[1]")
        params = {
            "coordinate": [coordinate[0], coordinate[1]],
            "pixel_coordinate": [px, py],
            "coordinate_space": "screen",
        }
        contract = _base_contract_action(observation, sequence=sequence, kind="long_press", parameters={"x": px, "y": py})
    elif kind == "swipe":
        start = raw.get("coordinate")
        end = raw.get("coordinate2")
        if not isinstance(start, (list, tuple)) or len(start) != 2 or not isinstance(end, (list, tuple)) or len(end) != 2:
            raise ValueError("swipe requires coordinate and coordinate2")
        points = [
            _pixel_coordinate(start[0], observation.screen.width_px, "coordinate[0]"),
            _pixel_coordinate(start[1], observation.screen.height_px, "coordinate[1]"),
            _pixel_coordinate(end[0], observation.screen.width_px, "coordinate2[0]"),
            _pixel_coordinate(end[1], observation.screen.height_px, "coordinate2[1]"),
        ]
        params = {
            "coordinate": list(start),
            "coordinate2": list(end),
            "pixel_coordinates": points,
            "coordinate_space": "screen",
        }
        contract = _base_contract_action(
            observation,
            sequence=sequence,
            kind="swipe",
            parameters={"x1": points[0], "y1": points[1], "x2": points[2], "y2": points[3]},
        )
    elif kind == "type":
        if not isinstance(raw.get("text"), str):
            raise ValueError("type requires text")
        params = {"text": raw["text"]}
        contract = _base_contract_action(observation, sequence=sequence, kind="set_text", parameters=params, target_role="edit_text")
    elif kind == "system_button":
        button = str(raw.get("button", "")).casefold()
        if button == "back":
            params = {"button": "Back"}
            contract = _base_contract_action(observation, sequence=sequence, kind="system_back", parameters={}, target_role="system_navigation")
        elif button in {"home", "enter"}:
            # These are valid original actions but are outside the Android
            # contract's first production action set.  The reference adapter
            # may still execute them through the original environment API.
            if not allow_uncontracted_actions:
                raise ValueError("Home and Enter are not available in the Android action contract")
            params = {"button": button.title()}
        else:
            raise ValueError("system_button must be Back, Home, or Enter")
    elif kind == "open_app":
        if not isinstance(raw.get("text"), str) or not raw["text"].strip():
            raise ValueError("open_app requires text")
        if not allow_uncontracted_actions:
            raise ValueError("open_app is not available in the Android action contract")
        params = {"text": raw["text"]}
    elif kind == "answer":
        if not isinstance(raw.get("text"), str):
            raise ValueError("answer requires text")
        if not allow_uncontracted_actions:
            raise ValueError("answer is not available in the Android action contract")
        params = {"text": raw["text"]}
    elif kind in {"done", "terminate"}:
        params = {}
    else:
        raise ValueError(f"unsupported action: {kind}")
    return ActionCommand(raw=dict(raw), kind=kind, parameters=params, contract_action=contract)


def default_note_policy(info_pool: InfoPool) -> bool:
    """Use the caller's generic note request; task rules belong at the edge."""

    return bool(info_pool.note_requested)


class RoleOrchestrator:
    """One bounded observe → decide → act → observe → reflect iteration."""

    def __init__(
        self,
        model: ModelBoundary,
        *,
        info_pool: InfoPool | None = None,
        manager: Manager | None = None,
        executor: Executor | None = None,
        action_reflector: ActionReflector | None = None,
        notetaker: Notetaker | None = None,
        note_policy: Callable[[InfoPool], bool] = default_note_policy,
        allow_uncontracted_actions: bool = False,
    ) -> None:
        self.model = model
        self.info_pool = info_pool or InfoPool()
        self.manager = manager or Manager()
        self.executor = executor or Executor()
        self.action_reflector = action_reflector or ActionReflector()
        self.notetaker = notetaker or Notetaker()
        self.note_policy = note_policy
        self.allow_uncontracted_actions = allow_uncontracted_actions

    def reset(self) -> None:
        self.info_pool = InfoPool(err_to_manager_thresh=self.info_pool.err_to_manager_thresh)

    def _predict(self, role: str, prompt: str, images: Sequence[Any], step: int) -> str:
        # The optional role-aware method lets the bounded transport record role
        # and step metadata while keeping the public model boundary tiny.
        predictor = getattr(self.model, "predict", None)
        if callable(predictor):
            result = predictor(role=role, prompt=prompt, images=images, step=step)
        else:
            result = self.model.predict_mm(prompt, images)
        if isinstance(result, tuple):
            if not result:
                raise RuntimeError(f"empty {role} model response")
            text = result[0]
            raw_response = result[2] if len(result) > 2 else True
            if not raw_response:
                raise RuntimeError(f"empty {role} model response")
        else:
            text = result
        if not isinstance(text, str) or not text.strip():
            raise RuntimeError(f"empty {role} model response")
        return text

    @staticmethod
    def _images(observation: ObservationFrame) -> list[Any]:
        return [] if observation.screenshot is None else [observation.screenshot]

    def _record_invalid(self, summary: str, reason: str) -> None:
        self.info_pool.last_action = {"action": "invalid"}
        self.info_pool.action_history.append({"action": "invalid"})
        self.info_pool.summary_history.append(summary)
        self.info_pool.action_outcomes.append("C")
        self.info_pool.error_descriptions.append(reason)

    def _result(
        self,
        *,
        done: bool,
        before: ObservationFrame,
        after: ObservationFrame | None = None,
        action: ActionCommand | None = None,
        receipt: Any = None,
    ) -> StepResult:
        data = self.info_pool.as_dict()
        data.update(
            {
                "observation": before.as_contract(),
                "after_observation": after.as_contract() if after is not None else None,
                "action": action.as_dict() if action is not None else None,
                "receipt": _jsonable(receipt),
                "roles": list(ROLE_NAMES),
            }
        )
        return StepResult(done=done, data=data)

    def step(
        self,
        goal: str,
        *,
        observe: Callable[[], ObservationFrame | Mapping[str, Any]],
        execute: Callable[[ActionCommand], Any],
    ) -> StepResult:
        """Run one original-strategy iteration using caller supplied device IO."""

        before = _coerce_observation(observe())
        self.info_pool.instruction = goal
        self.info_pool.screen_width_px = before.screen.width_px
        self.info_pool.screen_height_px = before.screen.height_px
        step_index = len(self.info_pool.action_history)

        # Preserve the original error escalation: two consecutive B/C outcomes
        # return to Manager, while an invalid action gets a direct Executor try.
        threshold = self.info_pool.err_to_manager_thresh
        recent = self.info_pool.action_outcomes[-threshold:]
        self.info_pool.error_flag_plan = len(recent) == threshold and all(outcome in {"B", "C"} for outcome in recent)
        skip_manager = bool(
            not self.info_pool.error_flag_plan
            and self.info_pool.action_history
            and isinstance(self.info_pool.action_history[-1], Mapping)
            and self.info_pool.action_history[-1].get("action") == "invalid"
        )

        planning_result: dict[str, Any] | None = None
        planner_finished = False
        if not skip_manager:
            planning = self._predict("manager", self.manager.get_prompt(self.info_pool), self._images(before), step_index)
            planning_result = self.manager.parse_response(planning)
            self.info_pool.completed_plan = str(planning_result["completed_subgoal"])
            self.info_pool.plan = str(planning_result["plan"])

        if "Finished" in self.info_pool.plan.strip() and len(self.info_pool.plan.strip()) < 15:
            planner_finished = True
            self.info_pool.finish_thought = str((planning_result or {}).get("thought", ""))
            action_text = '{"action": "done"}'
            action_thought = "Finished by planner"
            action_description = "Finished by planner"
        else:
            response = self._predict("executor", self.executor.get_prompt(self.info_pool), self._images(before), step_index)
            parsed = self.executor.parse_response(response)
            action_thought = str(parsed.get("thought", ""))
            action_text = str(parsed.get("action", ""))
            action_description = str(parsed.get("description", ""))
            self.info_pool.last_action_thought = action_thought
            self.info_pool.last_summary = action_description
            if not action_thought or not action_text:
                self._record_invalid(action_description, "invalid action format, do nothing.")
                return self._result(done=False, before=before)

        try:
            action = parse_action_command(
                action_text,
                before,
                sequence=step_index + 1,
                allow_uncontracted_actions=self.allow_uncontracted_actions,
            )
        except (TypeError, ValueError):
            self._record_invalid(action_description, "invalid action format, do nothing.")
            return self._result(done=False, before=before)

        self.info_pool.action_pool.append(action_text)
        if planner_finished:
            # The original entrypoint records the planner-generated terminal
            # action once in its planner branch and once after conversion.
            self.info_pool.action_pool.append(action_text)
        self.info_pool.last_action = dict(action.raw)
        if action.terminal:
            self.info_pool.action_history.append(dict(action.raw))
            self.info_pool.summary_history.append(action_description)
            self.info_pool.action_outcomes.append("A")
            self.info_pool.error_descriptions.append("None")
            return self._result(done=True, before=before, action=action)

        try:
            receipt = execute(action)
        except Exception as exc:  # device errors are an action failure, as upstream treats them
            self._record_invalid(action_description, f"failed to execute the action: {type(exc).__name__}: {exc}")
            return self._result(done=False, before=before, action=action)

        if action.answer:
            self.info_pool.progress_status = (
                self.info_pool.completed_plan
                + "\n"
                + "The `answer` action has been performed. Answer to the question: "
                + str(action.parameters["text"])
            )
            self.info_pool.action_history.append(dict(action.raw))
            self.info_pool.summary_history.append(action_description)
            self.info_pool.action_outcomes.append("A")
            self.info_pool.error_descriptions.append("None")
            return self._result(done=True, before=before, action=action, receipt=receipt)

        after = _coerce_observation(observe())
        self.info_pool.last_action = dict(action.raw)
        reflection = self._predict(
            "action_reflector",
            self.action_reflector.get_prompt(self.info_pool),
            self._images(before) + self._images(after),
            step_index,
        )
        parsed_reflection = self.action_reflector.parse_response(reflection)
        outcome = str(parsed_reflection.get("outcome", ""))
        if "A" in outcome:
            action_outcome = "A"
        elif "B" in outcome:
            action_outcome = "B"
        elif "C" in outcome:
            action_outcome = "C"
        else:
            raise ValueError(f"Invalid outcome: {outcome}")
        error_description = str(parsed_reflection.get("error_description", ""))
        self.info_pool.action_history.append(dict(action.raw))
        self.info_pool.summary_history.append(action_description)
        self.info_pool.action_outcomes.append(action_outcome)
        self.info_pool.error_descriptions.append(error_description)
        self.info_pool.progress_status = self.info_pool.completed_plan

        if action_outcome == "A" and self.note_policy(self.info_pool):
            note_response = self._predict("notetaker", self.notetaker.get_prompt(self.info_pool), self._images(after), step_index)
            self.info_pool.important_notes = str(self.notetaker.parse_response(note_response).get("important_notes", ""))
        return self._result(done=False, before=before, after=after, action=action, receipt=receipt)


def _coerce_observation(value: ObservationFrame | Mapping[str, Any]) -> ObservationFrame:
    if isinstance(value, ObservationFrame):
        return value
    return ObservationFrame.from_contract(value)


def _jsonable(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, Mapping):
        return {str(key): _jsonable(child) for key, child in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonable(child) for child in value]
    if hasattr(value, "__dict__"):
        return {str(key): _jsonable(child) for key, child in vars(value).items() if not key.startswith("_")}
    return str(value)


__all__ = [
    "ANDROID_SCHEMA_VERSION",
    "ActionCommand",
    "ModelBoundary",
    "ObservationFrame",
    "ROLE_NAMES",
    "RoleOrchestrator",
    "SCHEMA_VERSION",
    "ScreenFrame",
    "StepResult",
    "default_note_policy",
    "parse_action_command",
]
