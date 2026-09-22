# Task 01 contract v1

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
`FAILURE` even when the receipt is successful.
