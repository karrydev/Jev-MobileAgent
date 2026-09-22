# Android observation extension v1

This extension keeps the shared Task 01 envelope fields (`schema_version`,
`task_id`, `device_id`, and `observation_id`) and the same HTTP bearer,
`X-JEV-Protocol-Version`, and `X-JEV-Device-Id` guards. The Android payload
has its own `android_schema_version` so Android observation fields can evolve
without changing `contracts/v1`.

The observation is a flattened accessibility tree. `parent_node_id`,
`child_node_ids`, and `root_node_ids` preserve the tree while keeping the JSON
easy for the service and later planners to consume. Node text, content
description, state flags, and screen bounds are captured from
`AccessibilityNodeInfo`; window metadata comes from
`AccessibilityWindowInfo`.

`availability` is explicit:

* `AVAILABLE` means the service had a non-empty real tree.
* `EMPTY_TREE` means permission was present but no accessible root was
  available.
* `PERMISSION_UNAVAILABLE` means the app could not use its accessibility
  service.
* `DISCONNECTED` is an explicit empty-tree observation generated when the
  device loses its bridge connection.

The bridge accepts only strictly increasing `observation_version` values for
one device. Pairing starts a new observation session and clears the old latest
tree. Its latest endpoint refuses an old observation after the connection
freshness window, so clients cannot present an old tree as the current phone
state. `PERMISSION_UNAVAILABLE` and `DISCONNECTED` latest responses are
current status observations with empty `nodes` and `windows`; they have
`connection_status: DISCONNECTED` and must clear any locally cached tree.
