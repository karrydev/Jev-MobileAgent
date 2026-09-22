"""A small, stdlib-only simulated task loop.

The package deliberately keeps the public task entry point independent from
the simulated device implementation.  The latter is a localhost adapter for
offline tests; a future Android app can replace it with the app's transport.
"""

from .device import SimulatedDevice, create_device_server
from .model import LiveModel, ModelError, ReplayModel, build_model_request, minimal_probe
from .service import SimulationService, create_service_server

__all__ = [
    "SimulatedDevice",
    "SimulationService",
    "create_device_server",
    "create_service_server",
    "LiveModel",
    "ModelError",
    "ReplayModel",
    "build_model_request",
    "minimal_probe",
]
