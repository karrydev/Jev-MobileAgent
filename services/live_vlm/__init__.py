"""Small, explicit live VLM compatibility helpers.

The production Android transport is importable without loading the reference
probe or its simulation-only dependencies.  Probe helpers stay lazy so the
legacy ``run_probe`` API remains available to callers that explicitly use it.
"""


def run_probe(*args, **kwargs):
    from .probe import run_probe as implementation

    return implementation(*args, **kwargs)


def synthetic_probe_images(*args, **kwargs):
    from .images import synthetic_probe_images as implementation

    return implementation(*args, **kwargs)

__all__ = ["run_probe", "synthetic_probe_images"]
