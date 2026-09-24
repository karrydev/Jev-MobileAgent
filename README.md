# Jev-MobileAgent

Jev-MobileAgent is an Android-focused derivation of MobileAgent v3.5. The
current work combines the retained v3.5 model and reference entries with an
Android app, Python reference services, extracted roles, explicit contracts,
offline evaluation, and regression tests. The product target is a standalone
APK: users configure their model keys in the App, which runs orchestration
on the phone and calls model providers directly. No user-hosted backend or
ADB is required for normal use. This migration is not yet implemented; see
[the current decision](docs/adr/0003-standalone-android-runtime.md).

## Start here

- [Documentation index](docs/README.md)
- [Project direction and boundaries](docs/project-direction.md)
- [Development roadmap](docs/development-roadmap.md)
- [首版任务与验收索引](.scratch/mobile-agent-v1/index.md)
- [Repository pruning inventory](docs/repository-pruning.md)

The main implementation areas are [agent_core](agent_core/),
[android-app](android-app/), [contracts](contracts/), [eval](eval/), and
[services](services/). The focused checks are under [tests](tests/).

## Source and license

The upstream source is [X-PLUG/MobileAgent](https://github.com/X-PLUG/MobileAgent),
with the v3.5 source baseline recorded as
`11cea575561fb7800b5fb6b6cafa56f7a91de11f`. The complete retained upstream
v3.5 tree is under [Mobile-Agent-v3.5](Mobile-Agent-v3.5/); extracted role
code and project adapters keep their source paths and hashes in
[agent_core/vlm/README.md](agent_core/vlm/README.md). The root
[LICENSE](LICENSE) and the retained upstream license files are unchanged.
Pruning removes files in the current tree only; Git history is not rewritten.

## Baselines and current limits

The recorded original phone baseline (`mobile04`) completed its controlled
page task. The original and extracted AndroidWorld `SystemBrightnessMax`
reference runs both stopped after five steps with an independent score of
`0.0`; this is retained as a failure result and is not presented as an
improvement. The reports and reproduction commands are in
[.scratch/mobile-agent-v1/evidence](.scratch/mobile-agent-v1/evidence) and
[services/original_baselines/README.md](services/original_baselines/README.md).

The bridge-based App plus VLM loop and physical-device recovery have passed
their controlled-phone checks. They do not certify standalone operation.
The Jev API access probe has passed. The on-phone runtime, Jev selection,
tree verification, and final standalone APK acceptance remain pending
(tickets 22–28; see individual tickets for execution status). Running the real reference
commands requires the configured VLM service and Android device or emulator;
offline regression tests do not certify those external capabilities.
