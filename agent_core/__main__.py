"""Allow ``python -m agent_core`` to run the public offline CLI."""

from .cli import main


raise SystemExit(main())
