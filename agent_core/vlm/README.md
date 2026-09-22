# Extracted v3.5 roles

This package is the small, device-independent extraction of the Mobile-Agent
v3.5 role strategy. It keeps the original `Manager`, `Executor`,
`ActionReflector`, `Notetaker`, and `InfoPool` prompt/state behavior while
putting observation, action, and model I/O behind explicit boundaries.

The extraction is based on upstream commit
`11cea575561fb7800b5fb6b6cafa56f7a91de11f` and the checked-in source files
below. Their hashes are recorded here so a later prompt change can be
distinguished from the source extraction:

| Source | SHA-256 |
| --- | --- |
| `Mobile-Agent-v3.5/android_world_v3.5/android_world/agents/mobile_agent_v3_agent.py` | `00a1b656ada4a100b85b6b1347f6a983f2a4547230a8058aeca92ac2d26352be` |
| `Mobile-Agent-v3.5/android_world_v3.5/android_world/agents/mobile_agent_v3.py` | `79c067d3f4bbf526c52c5434771d109e6c117e8e8c32591c93cc48e913f8f38a` |

The role source is derived under the Apache License 2.0. The upstream license
is retained at `Mobile-Agent-v3.5/android_world_v3.5/LICENSE`; the header in
`roles.py` records the applicable notice. The original v3.5 entry remains
unchanged.

## Minimal API

Inject a model boundary and supply observation/action callbacks:

```python
from agent_core.vlm import RoleOrchestrator

loop = RoleOrchestrator(model)
result = loop.step(
    goal,
    observe=read_observation,
    execute=execute_action,
)
```

`model` can implement `predict_mm(prompt, images)` or the optional
role-aware `predict(role=..., prompt=..., images=..., step=...)` method. The
loop consumes the versioned observation envelope, maps the original inclusive
0..1000 coordinates using the observed screen width and height (clamping the
1000 endpoint to the last valid pixel), and emits only the existing Android
action kinds: `coordinate_tap`, `set_text`, `long_press`, `swipe`, and
`system_back`. Unsupported original actions such as `open_app`,
`home`, `enter`, and `answer` are rejected by the production default instead
of widening the shared contract.

`services/original_baselines/extracted_androidworld.py` is the reference-only
compatibility adapter. It enables the legacy actions only at that adapter
boundary so the existing AndroidWorld baseline can be compared with the same
bounded transport, budget, episode lifecycle, and evidence writer.
