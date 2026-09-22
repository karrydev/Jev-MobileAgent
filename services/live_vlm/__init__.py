"""Small, explicit live VLM compatibility probe.

The package is intentionally independent from the simulated task runner.  It
loads the upstream MobileAgent v3.5 prompt and parser files at probe time and
only performs network I/O when the caller opts into ``execute``.
"""

from .images import synthetic_probe_images
from .probe import run_probe

__all__ = ["run_probe", "synthetic_probe_images"]
