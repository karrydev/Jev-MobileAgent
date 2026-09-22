# Task evidence reports

`services.task_evidence` adapts the result returned by the existing public
`services.sim_loop` HTTP task entry point. It validates the linked runtime
trace, maps the actual observations/actions/receipts into the task-02
independent evaluator, and emits a public whitelist report.

Run one synthetic task locally:

```bash
python3 -m services.task_evidence run \
  --scenario success \
  --output /tmp/jev-task-evidence-report.json \
  --raw-output /tmp/jev-task-evidence-raw.json
```

The runner uses only localhost simulated device and replay responses. It does
not read a supplier credential or make a paid API request. The `--raw-output`
path is an explicit controlled local evidence path; the public report does not
include raw prompts, image bytes, model responses, or credential values.
When `--raw-output` is supplied, the runner also writes a sibling `.truth.json`
sidecar captured from the simulated device state. Replay verifies the sidecar
and runtime hashes before evaluating; it never rebuilds truth from the raw
scenario label or agent result.

The scenarios cover the terminal and accounting branches used by this slice:

```bash
python3 -m services.task_evidence run --scenario failure
python3 -m services.task_evidence run --scenario retry
python3 -m services.task_evidence run --scenario fallback
python3 -m services.task_evidence run --scenario cancel
python3 -m services.task_evidence run --scenario all --output /tmp/jev-task-evidence-all.json
```

A retained raw result can be replayed without rerunning the task:

```bash
python3 -m services.task_evidence replay \
  --input /tmp/jev-task-evidence-raw.json \
  --output /tmp/jev-task-evidence-replayed.json
```

Every report carries `jev-task-evidence/report-v1`, the runtime contract
version, the independent evaluator report version, a public-redaction version,
and the local price-book version. The checked-in price book is explicitly
`local-unpriced-v1`; costs stay `unknown` until a reviewed local rate is
provided with `--price-book`. No current supplier price is inferred.

The validator rejects broken event chains, missing entity events, mismatched
task/action/receipt links, and incomplete successful traces. Failed, paused and
cancelled results remain replayable when their emitted evidence is intact;
cancelled tasks may retain a late model response, but a late action dispatch is
rejected.
