"""Small, bounded runners for the two unmodified MobileAgent v3.5 entries.

The package deliberately keeps the upstream loops in charge of planning and
execution.  It only supplies a no-retry model transport, a bounded evidence
ledger, and the safety/independent-judging adapters needed by task 16.
"""

from .harness import (
    DEFAULT_ENDPOINT,
    DEFAULT_MODEL,
    ENTRY_BUDGET_CNY,
    MAX_REQUESTS_PER_RUN,
    MAX_STEPS,
    MAX_TOKENS,
    BaselineStop,
    BoundedVlmTransport,
    EvidenceWriter,
    evaluate_controlled_observation,
    evaluate_controlled_observations,
    run_androidworld_baseline,
    run_androidworld_extracted_baseline,
    run_phone_baseline,
)

__all__ = [
    "DEFAULT_ENDPOINT",
    "DEFAULT_MODEL",
    "ENTRY_BUDGET_CNY",
    "MAX_REQUESTS_PER_RUN",
    "MAX_STEPS",
    "MAX_TOKENS",
    "BaselineStop",
    "BoundedVlmTransport",
    "EvidenceWriter",
    "evaluate_controlled_observation",
    "evaluate_controlled_observations",
    "run_androidworld_baseline",
    "run_androidworld_extracted_baseline",
    "run_phone_baseline",
]
