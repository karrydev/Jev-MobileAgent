"""Extracted MobileAgent v3.5 role strategy.

This package is device independent.  The reference adapter and bounded
transport live outside this package.
"""

from .orchestration import (
    ANDROID_SCHEMA_VERSION,
    ActionCommand,
    ModelBoundary,
    ObservationFrame,
    ROLE_NAMES,
    RoleOrchestrator,
    SCHEMA_VERSION,
    ScreenFrame,
    StepResult,
    default_note_policy,
    parse_action_command,
)
from .roles import (
    ACTION_SIGNATURES,
    ATOMIC_ACTION_SIGNITURES_noxml,
    ActionReflector,
    BaseAgent,
    DEFAULT_EXECUTOR_GUIDELINES,
    Executor,
    InfoPool,
    Manager,
    Notetaker,
)

__all__ = [
    "ACTION_SIGNATURES",
    "ANDROID_SCHEMA_VERSION",
    "ATOMIC_ACTION_SIGNITURES_noxml",
    "ActionCommand",
    "ActionReflector",
    "BaseAgent",
    "DEFAULT_EXECUTOR_GUIDELINES",
    "Executor",
    "InfoPool",
    "Manager",
    "ModelBoundary",
    "Notetaker",
    "ObservationFrame",
    "ROLE_NAMES",
    "RoleOrchestrator",
    "SCHEMA_VERSION",
    "ScreenFrame",
    "StepResult",
    "default_note_policy",
    "parse_action_command",
]
