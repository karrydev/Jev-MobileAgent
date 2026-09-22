"""Offline candidate selection and action verification for Jev-MobileAgent.

The package deliberately stays outside the live task runner.  It provides
small, deterministic pipelines that can be replayed without model credentials,
an Android device, or a Jev key.
"""

from .selection import (
    CandidateBuild,
    DeterministicEffectSimulator,
    ReplaySelector,
    SelectionDecision,
    SelectionInputError,
    SimulationResult,
    build_candidates,
    run_selection_dataset,
)
from .verification import (
    ControlDecision,
    ReplayClassifier,
    RuleVerifier,
    VerificationInputError,
    VerificationController,
    VerificationResult,
    run_verification_dataset,
)

__all__ = [
    "CandidateBuild",
    "DeterministicEffectSimulator",
    "ReplaySelector",
    "SelectionDecision",
    "SelectionInputError",
    "SimulationResult",
    "build_candidates",
    "run_selection_dataset",
    "ControlDecision",
    "ReplayClassifier",
    "RuleVerifier",
    "VerificationInputError",
    "VerificationController",
    "VerificationResult",
    "run_verification_dataset",
]
