"""Small durable JSON helpers used by the simulated recovery slice.

The simulated service deliberately keeps persistence boring: a single local
JSON checkpoint is written atomically after every state boundary.  It is
enough to demonstrate process restart semantics without pretending to be a
database or a distributed exactly-once log.
"""

from __future__ import annotations

import json
import os
import tempfile
from hashlib import sha256
from pathlib import Path
from typing import Any


def load_json(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    with path.open(encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"persistent state at {path} must be a JSON object")
    return value


def save_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_name, path)
    except BaseException:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise


def scene_fingerprint(observation: dict[str, Any]) -> str:
    """Return a stable identity for the visible simulated scene.

    Observation ids change on every read.  Recovery binds to this fingerprint
    and to the device's stable ``observation_version`` so an ordinary repeat
    read remains valid while a changed page invalidates an old confirmation.
    """

    scene = {
        "device_id": observation.get("device_id"),
        "task_id": observation.get("task_id"),
        "page_state": observation.get("page_state"),
        "nodes": observation.get("nodes"),
        "capabilities": observation.get("capabilities"),
        "observation_version": observation.get("observation_version"),
    }
    encoded = json.dumps(scene, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256(encoded).hexdigest()
