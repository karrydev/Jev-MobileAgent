# Original v3.5 baseline runners

This package provides the small, bounded task 16 adapters for the two
upstream Android entries. The upstream source files remain untouched. Each
run is capped at five steps, 25 model requests, 1024 output tokens per
request, and ¥1 of reserved request budget. A response without complete
input/output usage stops the next model request; failed attempts remain in the
local evidence report and are never retried.

The API key is read only from `JEV_VLM_API_KEY` (or the explicitly named
`--credential-env`). It is never a CLI option. Raw request/response evidence
and screenshots are written below `--evidence-dir`; keep that directory out of
the repository.

For the physical phone entry, provide the already selected ADB serial and the
button bounds obtained from the independent bridge observation. The safety
guard delegates screenshots and taps to the original `AdbTools`, permits one
tap inside the supplied project button bounds, and permits only system Back or
Home navigation otherwise. It rejects open, type, swipe, long press, off-app
actions, and a second project tap. The final success result comes from an
independent observation containing the exact text `Controlled action state:
completed`; the model's terminate/answer text is never used as a success
signal.

```bash
 # Configure JEV_VLM_API_KEY, DEVICE_SERIAL, and the four toggle bounds in the shell first.
python3 -m services.original_baselines phone \
  --adb-path "$HOME/Library/Android/sdk/platform-tools/adb" \
  --serial "$DEVICE_SERIAL" \
  --tap-bounds "$TOGGLE_LEFT" "$TOGGLE_TOP" "$TOGGLE_RIGHT" "$TOGGLE_BOTTOM" \
  --evidence-dir /tmp/jev-original-mobile-baseline
```

The coordinator can instead call `run_phone_baseline(...,
observation_provider=...)` and pass a live bridge observation callback. The
runner invokes it before and after the original loop, then requires a fresh
same-device pair with different observation IDs, an initial `ready` state, and
the exact completed state after a real project tap. This avoids giving the
model the evaluator's reference state. A local `--observation-json` file is
reported as offline evidence only and can never certify a physical run.

The AndroidWorld entry calls the original `MobileAgentV3_M3A.reset` and
`step` through the original episode runner, with the concrete
`SystemBrightnessMax({'max_or_min': 'max'})` task. Its independent result is
`SystemBrightnessMax.is_successful`, combined with the episode's bounded
`done` result. The default transport adds only an explicitly recorded
normalized 0..1000 coordinate convention to role prompts because the original
four-role prompt does not state that frame; set
`--original-coordinate-prompts` when reproducing the unadapted prompt
variant.

```bash
# Use the already configured JEV_VLM_API_KEY environment variable.
/tmp/jev-androidworld-venv/bin/python -m services.original_baselines androidworld \
  --adb-path "$HOME/Library/Android/sdk/platform-tools/adb" \
  --console-port 5556 --grpc-port 8554 \
  --evidence-dir /tmp/jev-original-androidworld-baseline
```

The two reports are separate evidence records. They do not claim a baseline
passed when the environment, usage, parser, or independent judge was
unavailable.
