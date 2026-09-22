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

For a long-running local endpoint, use `serve`; it starts both listeners and
prints their loopback URLs:

```bash
JEV_SIM_AUTH_TOKEN=local-demo-token \
  python3 -m services.sim_loop serve --service-port 8765 --device-port 8766
```

The first slice intentionally does not implement pause, restart recovery,
command deduplication, persistent checkpoints, real Android Accessibility, or
real accounts/devices. Those are later behavior slices.
