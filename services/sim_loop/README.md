# Task 01 simulated loop

This is the first runnable server slice for Jev-MobileAgent. It keeps one
small loop explicit:

1. the service accepts a versioned simulated task;
2. the service observes the simulated device over a real localhost HTTP hop;
3. it sends one observation-bound tap action and records the device receipt;
4. it observes again and independently compares the known page state with the
   action postcondition;
5. it returns the final state and a linked trace.

The public service endpoints are:

```text
POST /v1/tasks
GET  /v1/tasks/{task_id}
POST /v1/tasks/{task_id}/pause
POST /v1/tasks/{task_id}/cancel
```

Every request needs `Authorization: Bearer <token>` and
`X-JEV-Protocol-Version: 1`. Task JSON must use `schema_version: "1.0"`.
There is no built-in token. Pass one explicitly with `--auth-token` or set
`JEV_SIM_AUTH_TOKEN`; it is never printed by the servers.

The simulated device is a localhost-only adapter at
`/v1/simulated/observations` and `/v1/simulated/actions`. The service reaches
it over HTTP so the transport, identity and contract checks are exercised.
This adapter is deliberately not a production phone protocol: in the target
deployment the Android App actively connects to the service and replaces this
local adapter. A localhost listener is therefore only an offline test
implementation and is not evidence that a physical phone can be reached from
the developer computer.

Run the deterministic success demo from a clean checkout:

```bash
JEV_SIM_AUTH_TOKEN=local-demo-token \
  python3 -m services.sim_loop demo --task-id task-demo-001
```

The demo uses fixed synthetic timestamps so repeated runs with the same task
id produce the same JSON behavior. To demonstrate the critical negative case:

```bash
JEV_SIM_AUTH_TOKEN=local-demo-token \
  python3 -m services.sim_loop demo \
  --scenario receipt_without_effect --task-id task-no-effect-001
```

This returns an `EXECUTED`/`accepted` receipt but `state: "FAILED"`, because
the second observation remains on `page_state: "landing"` and the independent
postcondition is not met.

Check all versioned positive and negative fixtures without third-party
dependencies:

```bash
python3 -m services.sim_loop schema-check
python3 -m unittest discover -s tests -p 'test_*.py' -v
```

Controls are safe to send from a second HTTP connection while the submit
request is waiting on a model or simulated device. Pause leaves the task as
the one active task and suppresses future dispatch. Cancel is terminal. An
action already in flight is not claimed to be undone: a returned receipt can
be retained, while its verification is `UNKNOWN` until a later reconciliation
slice supplies a new observation. A transport timeout after dispatch retains
the task reservation even after cancel because the execution result itself is
unknown; a confirmed receipt ends the in-flight delivery even if its
postcondition remains unknown. A trusted device `409 stale_observation` is a
known refusal with no action side effect: the task is failed and the active
reservation is released. Other unknown transport responses, including proxy
5xx responses, retain the `UNKNOWN` result and reservation.

Run the deterministic replay demo with a Chinese prompt and two image
references:

```bash
JEV_SIM_AUTH_TOKEN=local-demo-token \
  python3 -m services.sim_loop replay \
  --task-id task-replay-001 \
  --prompt '请点击开始按钮' \
  --image screen-before --image screen-context
```

The result includes the model request, every bounded attempt, provider error
classification and usage. Replay is synthetic evidence for the adapter and
does not claim compatibility with a provider. To inspect the live boundary
without making any request:

```bash
python3 -m services.sim_loop probe \
  --endpoint http://127.0.0.1:9000/v1/chat/completions \
  --model fixture-model \
  --credential-env JEV_VLM_API_KEY
```

The probe requires an explicit complete chat-completions `--endpoint`, a
`--model`, and the name of an environment variable containing the credential.
It reports `ready: false` and exits with status 2 when any item is missing. It
never silently falls back to replay. The request body is the small
OpenAI-compatible shape used by this slice: one Chinese text prompt and two
image references. This proves only the local adapter wire and error handling;
it does not claim compatibility with a real supplier.

Only an explicit `--execute` sends the probe. For a local HTTP fixture whose
credential is `fixture-token`:

```bash
JEV_VLM_API_KEY=fixture-token \
  python3 -m services.sim_loop probe \
  --endpoint http://127.0.0.1:9000/v1/chat/completions \
  --model fixture-model \
  --credential-env JEV_VLM_API_KEY \
  --timeout 5 \
  --execute
```

The timeout is bounded to 30 seconds. HTTP errors, malformed responses, and
provider `usage` are retained in the probe result and task attempt. Never put
a real credential in source, fixtures, or task traces.

For a long-running local endpoint, use `serve`; it starts both listeners and
prints their loopback URLs:

```bash
JEV_SIM_AUTH_TOKEN=local-demo-token \
  python3 -m services.sim_loop serve --service-port 8765 --device-port 8766
```

This slice intentionally does not implement restart recovery, persistent
checkpoints, real Android Accessibility, or real accounts/devices. A paused
task has no automatic resume path; the explicit reconciliation and resume
boundary is a later behavior slice.
