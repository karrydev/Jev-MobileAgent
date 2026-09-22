"""Task evidence and public report adapter for the simulated task entry point.

The adapter consumes a completed result returned by the public ``sim_loop``
HTTP entry point.  It validates the event chain, adapts the runtime trace to
the task-02 independent evaluator, and emits a redacted report.  Runtime
payloads are never copied into the public report wholesale.
"""

from .evidence import (
    EVIDENCE_INPUT_VERSION,
    EVIDENCE_REPORT_VERSION,
    PRICE_BOOK_FORMAT,
    EvidenceError,
    EvidenceIntegrityError,
    PriceBook,
    build_evaluation_documents,
    build_report,
    evaluate_runtime,
    load_price_book,
    render_report,
    validate_runtime_result,
)
from .runner import independent_truth_for_flavor, independent_truth_from_device, run_simulated_task

__all__ = [
    "EVIDENCE_INPUT_VERSION",
    "EVIDENCE_REPORT_VERSION",
    "PRICE_BOOK_FORMAT",
    "EvidenceError",
    "EvidenceIntegrityError",
    "PriceBook",
    "build_evaluation_documents",
    "build_report",
    "evaluate_runtime",
    "load_price_book",
    "render_report",
    "independent_truth_for_flavor",
    "independent_truth_from_device",
    "run_simulated_task",
    "validate_runtime_result",
]
