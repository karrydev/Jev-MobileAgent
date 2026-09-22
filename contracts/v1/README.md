# Simulated loop contract v1

`schema.json` is the versioned source for the first simulated loop. The
Python validator in `services.sim_loop.schema` intentionally supports only the
small JSON Schema vocabulary used here, so a clean checkout needs no third
party package. Positive and negative fixtures are checked by:

```bash
python3 -m services.sim_loop schema-check
```

The service requires both `schema_version: "1.0"` in JSON and
`X-JEV-Protocol-Version: 1` on every request. A future breaking contract must
add a new `contracts/vN/` directory and a new protocol version; consumers do
not silently accept an unknown version.

The receipt records that the device accepted and executed the command. The
independent verification record reads the post-action page state and can be
`FAILURE` even when the receipt is successful. Actions carry an observation
binding and may carry a session and monotonic sequence. The simulated device
deduplicates a repeated `action_id`, rejects a stale session or sequence, and
never treats a receipt as proof of the postcondition.

The task submission accepts an optional `model` object. `mode: "replay"`
drives the same request boundary used by the live adapter; each attempt
preserves the task id, Chinese prompt, image list, response/error and usage.
`mode: "live"` requires an explicit complete OpenAI-compatible
chat-completions `endpoint`, `model`, and `credential_env`. The isolated
adapter is verified only against a local HTTP fixture and does not claim
compatibility with any particular supplier. Missing configuration and
credentials are explicit failures; there is no replay fallback.

Task control is exposed at:

```text
POST /v1/tasks/{task_id}/pause
POST /v1/tasks/{task_id}/cancel
POST /v1/tasks/{task_id}/reconnect
POST /v1/tasks/{task_id}/reconcile
POST /v1/tasks/{task_id}/resume
```

Controls are idempotent. A paused task remains the single active task and
does not resume automatically. Cancellation is terminal. If a physical
action was already in flight, the eventual receipt may be recorded, while
postcondition verification remains `UNKNOWN`. A request timeout after
dispatch leaves the execution result itself unknown and keeps the device
session reserved even after a later cancel request; no second task is
admitted until the reconciliation slice closes that action. A cancellation
with a confirmed receipt but an unknown postcondition has ended the in-flight
delivery, so it can release the reservation while still reporting
verification `UNKNOWN`. A trusted device rejection such as
`409 stale_observation` proves that the action was refused before execution,
so it fails the task and releases the reservation. Arbitrary proxy 5xx
responses do not prove refusal and remain `UNKNOWN`. A pause or cancellation
before dispatch prevents the action from being sent. Reconnect only restores
communication and keeps the task `PAUSED`; reconcile records a fresh
observation plus device action history. Only an `EXECUTED` or `NOT_EXECUTED`
result can produce an observation-bound resume token. `/resume` requires that
token, the same stable observation version, and explicit confirmation. Unknown
actions are never replayed, and a cancelled task is never revived. The local
simulator can persist these checkpoints with `state_path`, but it does not
claim physical exactly-once execution.
