# Portions of this file are derived from Mobile-Agent v3.5.
# Copyright 2024 The Mobile-Agent contributors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""The small, device-independent role prompts used by MobileAgent v3.5.

The role classes intentionally keep the original prompt and response shape.
They know about the task and the accumulated agent state, but they do not know
about a device framework, ADB, a particular screen size, or an evaluation task.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import asdict, dataclass, field
import re
from typing import Any


DEFAULT_EXECUTOR_GUIDELINES = (
    "General:\n"
    "- For any pop-up window, such as a permission request, you need to close it (e.g., by clicking `Don't Allow` or `Accept & continue`) before proceeding. Never choose to add any account or log in.\n"
    "- For requests that are questions (or chat messages), remember to use the `answer` action to reply to user explicitly before finish!\n"
    "- If the desired state is already achieved (e.g., enabling Wi-Fi when it's already on), you can just complete the task.\n"
    "Action Related:\n"
    "- Use the `open_app` action whenever you want to open an app (nothing will happen if the app is not installed), do not use the app drawer to open an app.\n"
    "- Consider exploring the screen by using the `swipe` action with different directions to reveal additional content. Or use search to quickly find a specific entry, if applicable.\n"
    "- If you cannot change the page content by swiping in the same direction continuously, the page may have been swiped to the bottom. Please try another operation to display more content.\n"
    "- For some horizontally distributed tags, you can swipe horizontally to view more.\n"
    "Text Related Operations:\n"
    "- Activated input box: If an input box is activated, it may have a cursor inside it and the keyboard is visible. If there is no cursor on the screen but the keyboard is visible, it may be because the cursor is blinking. The color of the activated input box will be highlighted. If you are not sure whether the input box is activated, click it before typing.\n"
    "- To input some text: first click the input box that you want to input, make sure the correct input box is activated and the keyboard is visible, then use `type` action to enter the specified text.\n"
    "- To clear the text: long press the backspace button in the keyboard.\n"
    "- To copy some text: first long press the text you want to copy, then click the `copy` button in bar.\n"
    "- To paste text into a text box: first long press the text box, then click the `paste` button in bar."
)


@dataclass
class InfoPool:
    """Working memory shared by the four original roles."""

    # User input and optional caller supplied knowledge.
    instruction: str = ""
    task_name: str = ""
    additional_knowledge_manager: str = ""
    additional_knowledge_executor: str = DEFAULT_EXECUTOR_GUIDELINES
    add_info_token: str = "[add_info]"
    note_requested: bool = False

    # The unified observation/action boundary supplies these values.  They are
    # deliberately unset until the first observation instead of using the
    # device-specific defaults.
    screen_width_px: int = 0
    screen_height_px: int = 0
    ui_elements_list_before: str = ""
    ui_elements_list_after: str = ""
    action_pool: list[str] = field(default_factory=list)

    # Working memory.
    summary_history: list[str] = field(default_factory=list)
    action_history: list[dict[str, Any] | str] = field(default_factory=list)
    action_outcomes: list[str] = field(default_factory=list)
    error_descriptions: list[str] = field(default_factory=list)
    last_summary: str = ""
    last_action: dict[str, Any] | str = ""
    last_action_thought: str = ""
    important_notes: str = ""

    # Planning and escalation.
    error_flag_plan: bool = False
    error_description_plan: bool = False
    plan: str = ""
    completed_plan: str = ""
    progress_status: str = ""
    progress_status_history: list[str] = field(default_factory=list)
    finish_thought: str = ""
    current_subgoal: str = ""
    err_to_manager_thresh: int = 2
    future_tasks: list[str] = field(default_factory=list)

    def as_dict(self) -> dict[str, Any]:
        """Return the state shape used by the existing episode runner."""

        return asdict(self)


class BaseAgent(ABC):
    @abstractmethod
    def get_prompt(self, info_pool: InfoPool) -> str:
        raise NotImplementedError

    @abstractmethod
    def parse_response(self, response: str) -> dict[str, Any]:
        raise NotImplementedError


def _knowledge(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, (list, tuple)):
        return "\n".join(str(item) for item in value if item)
    return str(value)


def _clean(value: str) -> str:
    return value.replace("\n", " ").replace("  ", " ").replace("###", "").strip()


class Manager(BaseAgent):
    """Original high-level planner, without benchmark-specific advice."""

    def get_prompt(self, info_pool: InfoPool) -> str:
        prompt = (
            "You are an agent who can operate an Android phone on behalf of a user. "
            "Your goal is to track progress and devise high-level plans to achieve "
            "the user's requests.\n\n"
        )
        prompt += "### User Request ###\n" + f"{info_pool.instruction}\n\n"

        knowledge = _knowledge(info_pool.additional_knowledge_manager)
        if info_pool.plan == "":
            prompt += "---\n"
            prompt += (
                "Make a high-level plan to achieve the user's request. If the request "
                "is complex, break it down into subgoals. The screenshot displays the "
                "starting state of the phone.\n"
            )
            prompt += (
                "IMPORTANT: For requests that explicitly require an answer, always add "
                "'perform the `answer` action' as the last step to the plan! Please use "
                "open_app to open an app instead of the app drawer.\n\n"
            )
            prompt += "### Guidelines ###\n"
            prompt += "The following guidelines will help you plan this request.\n"
            prompt += "General:\n"
            prompt += "1. Use the `open_app` action whenever you want to open an app, do not use the app drawer to open an app.\n"
            prompt += "2. Use search to quickly find a file or entry with a specific name, if search function is applicable.\n"
            prompt += "Task-specific:\n"
            prompt += f"{knowledge if knowledge else info_pool.add_info_token}\n\n"
            prompt += "Provide your output in the following format which contains two parts:\n"
            prompt += "### Thought ###\nA detailed explanation of your rationale for the plan and subgoals.\n\n"
            prompt += "### Plan ###\n1. first subgoal\n2. second subgoal\n...\n"
        else:
            if info_pool.completed_plan != "No completed subgoal.":
                prompt += "### Historical Operations ###\n"
                prompt += "Operations that have been completed before:\n"
                prompt += f"{info_pool.completed_plan}\n\n"
            prompt += f"### Plan ###\n{info_pool.plan}\n\n"
            prompt += f"### Last Action ###\n{info_pool.last_action}\n\n"
            prompt += f"### Last Action Description ###\n{info_pool.last_summary}\n\n"
            prompt += "### Important Notes ###\n"
            prompt += f"{info_pool.important_notes}\n\n" if info_pool.important_notes else "No important notes recorded.\n\n"
            prompt += "### Guidelines ###\n"
            prompt += "The following guidelines will help you plan this request.\n"
            prompt += "General:\n"
            prompt += "1. Use the `open_app` action whenever you want to open an app, do not use the app drawer to open an app.\n"
            prompt += "2. Use search to quickly find a file or entry with a specific name, if search function is applicable.\n"
            prompt += "Task-specific:\n"
            prompt += f"{knowledge if knowledge else info_pool.add_info_token}\n\n"
            if info_pool.error_flag_plan:
                prompt += "### Potentially Stuck! ###\n"
                prompt += "You have encountered several failed attempts. Here are some logs:\n"
                k = info_pool.err_to_manager_thresh
                for action, summary, error in zip(
                    info_pool.action_history[-k:],
                    info_pool.summary_history[-k:],
                    info_pool.error_descriptions[-k:],
                ):
                    prompt += f"- Attempt: Action: {action} | Description: {summary} | Outcome: Failed | Feedback: {error}\n"
            prompt += "---\n"
            prompt += (
                "Carefully assess the current status and the provided screenshot. Check if the current plan needs to be revised.\n"
                "Determine if the user request has been fully completed. If you are confident that no further actions are required, mark the plan as \"Finished\" in your output. If the user request is not finished, update the plan. If you are stuck with errors, think step by step about whether the overall plan needs to be revised to address the error.\n"
            )
            prompt += (
                "NOTE: 1. If the current situation prevents proceeding with the original plan or requires clarification from the user, make reasonable assumptions and revise the plan accordingly. Act as though you are the user in such cases. 2. Please refer to the helpful information and steps in the Guidelines first for planning. 3. If the first subgoal in plan has been completed, please update the plan in time according to the screenshot and progress to ensure that the next subgoal is always the first item in the plan. 4. If the first subgoal is not completed, please copy the previous round's plan or update the plan based on the completion of the subgoal.\n"
            )
            prompt += (
                "IMPORTANT: If the next steps require an `answer` action, make sure that there is a plan to perform the `answer` action. In this case, you should not mark the plan as \"Finished\" unless the last action is `answer`.\n"
            )
            prompt += "Provide your output in the following format, which contains three parts:\n\n"
            prompt += "### Thought ###\nAn explanation of your rationale for the updated plan and current subgoal.\n\n"
            prompt += "### Historical Operations ###\nTry to add the most recently completed subgoal on top of the existing historical operations. Please do not delete any existing historical operation. If there is no newly completed subgoal, just copy the existing historical operations.\n\n"
            prompt += "### Plan ###\nPlease update or copy the existing plan according to the current page and progress. Please pay close attention to the historical operations. Please do not repeat the plan of completed content unless you can judge from the screen status that a subgoal is indeed not completed.\n"
        return prompt

    def parse_response(self, response: str) -> dict[str, Any]:
        if "### Historical Operations" in response:
            thought = _clean(response.split("### Thought")[-1].split("### Historical Operations")[0])
            completed = _clean(response.split("### Historical Operations")[-1].split("### Plan")[0])
        else:
            thought = _clean(response.split("### Thought")[-1].split("### Plan")[0])
            completed = "No completed subgoal."
        plan = _clean(response.split("### Plan")[-1])
        return {"thought": thought, "completed_subgoal": completed, "plan": plan}


ACTION_SIGNATURES = {
    "answer": ("text", 'Answer user\'s question. Usage example: {"action": "answer", "text": "the content of your answer"}'),
    "click": ("coordinate", 'Click the point on the screen with specified (x, y) coordinates. Usage Example: {"action": "click", "coordinate": [x, y]}'),
    "long_press": ("coordinate", 'Long press on the position (x, y) on the screen. Usage Example: {"action": "long_press", "coordinate": [x, y]}'),
    "type": ("text", 'Type text into current activated input box or text field. There may be a cursor in the activated input box. If not, click the input box to confirm again. Please make sure the correct input box has been activated before typing. Usage Example: {"action": "type", "text": "the text you want to type"}'),
    "system_button": ("button", 'Press a system button, including back, home, and enter. Usage example: {"action": "system_button", "button": "Home"}'),
    "swipe": ("coordinate, coordinate2", 'Scroll from the position with coordinate to the position with coordinate2. Please make sure the start and end points of your swipe are within the swipeable area and away from the keyboard. Usage Example: {"action": "swipe", "coordinate": [x1, y1], "coordinate2": [x2, y2]}'),
    "open_app": ("text", 'Open an app. Usage example: {"action": "open_app", "text": "the name of app"}'),
}

# Preserve the upstream spelling for callers that used the source constant.
ATOMIC_ACTION_SIGNITURES_noxml = {
    key: {"arguments": value[0].split(", "), "description": (lambda _info, text=value[1]: text)}
    for key, value in ACTION_SIGNATURES.items()
}


class Executor(BaseAgent):
    """Original action selector with a device-independent prompt."""

    def get_prompt(self, info_pool: InfoPool) -> str:
        prompt = (
            "You are an agent who can operate an Android phone on behalf of a user. "
            "Your goal is to decide the next action to perform based on the current "
            "state of the phone and the user's request.\n\n"
        )
        prompt += f"### User Request ###\n{info_pool.instruction}\n\n"
        prompt += f"### Overall Plan ###\n{info_pool.plan}\n\n"
        prompt += "### Current Subgoal ###\n"
        goals = re.split(r"(?<=\d)\. ", info_pool.plan)
        current_goal = ". ".join(goals[:4]) + "."
        prompt += f"{current_goal[:-2].strip()}\n\n"
        prompt += "### Progress Status ###\n"
        prompt += f"{info_pool.progress_status}\n\n" if info_pool.progress_status else "No progress yet.\n\n"
        knowledge = _knowledge(info_pool.additional_knowledge_executor)
        if knowledge:
            prompt += f"### Guidelines ###\n{knowledge}\n"
        prompt += "\n---\n"
        prompt += (
            "Carefully examine all the information provided above and decide on the next action to perform. "
            "If you notice an unsolved error in the previous action, think as a human user and attempt to rectify them. "
            "You must choose your action from one of the atomic actions.\n\n"
        )
        prompt += "#### Atomic Actions ####\n"
        prompt += "The atomic action functions are listed in the format of `action(arguments): description` as follows:\n"
        for action, (arguments, description) in ACTION_SIGNATURES.items():
            prompt += f"- {action}({arguments}): {description}\n"
        prompt += "\n### Latest Action History ###\n"
        if info_pool.action_history:
            prompt += "Recent actions you took previously and whether they were successful:\n"
            count = min(5, len(info_pool.action_history))
            for action, summary, outcome, error in zip(
                info_pool.action_history[-count:],
                info_pool.summary_history[-count:],
                info_pool.action_outcomes[-count:],
                info_pool.error_descriptions[-count:],
            ):
                if outcome == "A":
                    prompt += f"Action: {action} | Description: {summary} | Outcome: Successful\n"
                else:
                    prompt += f"Action: {action} | Description: {summary} | Outcome: Failed | Feedback: {error}\n"
            prompt += "\n"
        else:
            prompt += "No actions have been taken yet.\n\n"
        prompt += "---\n"
        prompt += "IMPORTANT:\n1. Do NOT repeat previously failed actions multiple times. Try changing to another action.\n2. Please prioritize the current subgoal.\n\n"
        prompt += "Provide your output in the following format, which contains three parts:\n"
        prompt += "### Thought ###\nProvide a detailed explanation of your rationale for the chosen action.\n\n"
        prompt += "### Action ###\nChoose only one action or shortcut from the options provided.\n"
        prompt += "You must provide your decision using a valid JSON format specifying the `action` and the arguments of the action. For example, if you want to open an App, you should write {\"action\":\"open_app\", \"text\": \"app name\"}.\n\n"
        prompt += "### Description ###\nA brief description of the chosen action. Do not describe expected outcome.\n"
        return prompt

    def parse_response(self, response: str) -> dict[str, Any]:
        thought = _clean(response.split("### Thought")[-1].split("### Action")[0])
        action = _clean(response.split("### Action")[-1].split("### Description")[0])
        description = _clean(response.split("### Description")[-1])
        return {"thought": thought, "action": action, "description": description}


class ActionReflector(BaseAgent):
    """Original before/after action critic."""

    def get_prompt(self, info_pool: InfoPool) -> str:
        prompt = (
            "You are an agent who can operate an Android phone on behalf of a user. "
            "Your goal is to verify whether the last action produced the expected "
            "behavior and to keep track of the overall progress.\n\n"
        )
        prompt += f"### User Request ###\n{info_pool.instruction}\n\n"
        prompt += "### Progress Status ###\n"
        prompt += f"{info_pool.completed_plan}\n\n" if info_pool.completed_plan else "No progress yet.\n\n"
        prompt += "---\n"
        prompt += "The two attached images are phone screenshots taken before and after your last action. \n"
        prompt += "---\n"
        prompt += f"### Latest Action ###\nAction: {info_pool.last_action}\nExpectation: {info_pool.last_summary}\n\n---\n"
        prompt += (
            "Carefully examine the information provided above to determine whether the last action produced the expected behavior. "
            "If the action was successful, update the progress status accordingly. If the action failed, identify the failure mode and provide reasoning on the potential reason causing this failure.\n\n"
            "Note: For swiping to scroll the screen to view more content, if the content displayed before and after the swipe is exactly the same, the swipe is considered to be C: Failed. The last action produces no changes. This may be because the content has been scrolled to the bottom.\n\n"
        )
        prompt += "Provide your output in the following format containing two parts:\n"
        prompt += "### Outcome ###\nChoose from the following options. Give your response as \"A\", \"B\" or \"C\":\n"
        prompt += "A: Successful or Partially Successful. The result of the last action meets the expectation.\n"
        prompt += "B: Failed. The last action results in a wrong page. I need to return to the previous state.\n"
        prompt += "C: Failed. The last action produces no changes.\n\n"
        prompt += "### Error Description ###\nIf the action failed, provide a detailed description of the error and the potential reason causing this failure. If the action succeeded, put \"None\" here.\n"
        return prompt

    def parse_response(self, response: str) -> dict[str, Any]:
        outcome = _clean(response.split("### Outcome")[-1].split("### Error Description")[0])
        error = _clean(response.split("### Error Description")[-1])
        return {"outcome": outcome, "error_description": error}


class Notetaker(BaseAgent):
    """Original successful-step note keeper with generic guidance."""

    def get_prompt(self, info_pool: InfoPool) -> str:
        prompt = "You are a helpful AI assistant for operating mobile phones. Your goal is to take notes of important content relevant to the user's request.\n\n"
        prompt += f"### User Request ###\n{info_pool.instruction}\n\n"
        prompt += f"### Progress Status ###\n{info_pool.progress_status}\n\n"
        prompt += "### Existing Important Notes ###\n"
        prompt += f"{info_pool.important_notes}\n\n" if info_pool.important_notes else "No important notes recorded.\n\n"
        prompt += "---\n"
        prompt += (
            "Carefully examine the information above to identify any important content on the current screen that needs to be recorded.\n"
            "IMPORTANT:\nDo not take notes on low-level actions; only keep track of significant textual or visual information relevant to the user's request. Do not repeat user request or progress status. Do not make up content that you are not sure about.\n\n"
        )
        prompt += "Provide your output in the following format:\n### Important Notes ###\nThe updated important notes, combining the old and new ones. If nothing new to record, copy the existing important notes.\n"
        return prompt

    def parse_response(self, response: str) -> dict[str, Any]:
        return {"important_notes": _clean(response.split("### Important Notes")[-1])}


__all__ = [
    "ACTION_SIGNATURES",
    "ATOMIC_ACTION_SIGNITURES_noxml",
    "DEFAULT_EXECUTOR_GUIDELINES",
    "ActionReflector",
    "BaseAgent",
    "Executor",
    "InfoPool",
    "Manager",
    "Notetaker",
]
