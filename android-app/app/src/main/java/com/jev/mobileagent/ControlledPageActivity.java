package com.jev.mobileagent;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A deterministic, fully accessible page used only for emulator evidence. */
public class ControlledPageActivity extends Activity {
    private TextView state;
    private TextView taskStatus;
    private Button startTaskButton;
    private Button pauseTaskButton;
    private Button cancelTaskButton;
    /** Serialize submit/control requests while keeping status polling responsive. */
    private final ExecutorService taskExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService taskLoopExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean taskRunnerActive;
    private volatile String dispatchedActionId;
    private volatile JSONObject pendingReceipt;
    private volatile BridgeConfig activeTaskConfig;
    private volatile boolean taskSubmissionInFlight;
    private volatile String pendingControlCommand;
    private volatile long taskControlEpoch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Controlled observation page");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(32));

        TextView heading = new TextView(this);
        heading.setText("Controlled observation page");
        heading.setTextSize(24);
        heading.setTextColor(Color.rgb(35, 50, 65));
        heading.setContentDescription("Controlled page heading");
        root.addView(heading, params());

        TextView text = new TextView(this);
        text.setText("Visible controlled text");
        text.setTextSize(18);
        text.setContentDescription("The tree must contain this visible text");
        root.addView(text, params());

        state = new TextView(this);
        state.setText("Controlled action state: ready");
        state.setTextSize(16);
        state.setContentDescription("Controlled action state ready");
        root.addView(state, params());

        TextView inputHeading = new TextView(this);
        inputHeading.setText("Chinese input target");
        inputHeading.setTextSize(16);
        inputHeading.setContentDescription("Chinese input target heading");
        root.addView(inputHeading, params());

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint("请输入中文");
        input.setContentDescription("中文输入框");
        root.addView(input, params());

        TextView inputState = new TextView(this);
        inputState.setText("Controlled input state: empty");
        inputState.setTextSize(16);
        inputState.setContentDescription("Controlled input state empty");
        root.addView(inputState, params());
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String value = s == null ? "" : s.toString();
                String suffix = value.isEmpty() ? "empty" : value;
                inputState.setText("Controlled input state: " + suffix);
                inputState.setContentDescription("Controlled input state " + suffix);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        Button action = new Button(this);
        action.setText("Toggle controlled state");
        action.setContentDescription("Toggle controlled state button");
        action.setOnClickListener(v -> {
            String next = state.getText().toString().endsWith("ready") ? "completed" : "ready";
            state.setText("Controlled action state: " + next);
            state.setContentDescription("Controlled action state " + next);
        });
        root.addView(action, params());

        TextView taskHeading = new TextView(this);
        taskHeading.setText("Configured node task");
        taskHeading.setTextSize(18);
        taskHeading.setTextColor(Color.rgb(35, 50, 65));
        taskHeading.setContentDescription("Configured node task heading");
        root.addView(taskHeading, params());

        String configuredGoal = getSharedPreferences("jev_android_observation", MODE_PRIVATE)
                .getString("task_goal", "");
        TextView goal = new TextView(this);
        goal.setText(configuredGoal.isEmpty() ? "Task goal: none configured" : "Task goal: " + configuredGoal);
        goal.setTextSize(15);
        goal.setContentDescription("Configured task goal " + configuredGoal);
        root.addView(goal, params());

        startTaskButton = new Button(this);
        startTaskButton.setText("Start configured node task");
        startTaskButton.setOnClickListener(v -> submitConfiguredTask(configuredGoal));
        root.addView(startTaskButton, params());

        pauseTaskButton = new Button(this);
        pauseTaskButton.setText("Pause task");
        pauseTaskButton.setEnabled(false);
        pauseTaskButton.setOnClickListener(v -> controlNodeTask("pause"));
        root.addView(pauseTaskButton, params());

        cancelTaskButton = new Button(this);
        cancelTaskButton.setText("Cancel task");
        cancelTaskButton.setEnabled(false);
        cancelTaskButton.setOnClickListener(v -> controlNodeTask("cancel"));
        root.addView(cancelTaskButton, params());

        taskStatus = new TextView(this);
        taskStatus.setText("Task: no task submitted");
        taskStatus.setTextSize(15);
        taskStatus.setTextIsSelectable(true);
        root.addView(taskStatus, params());
        setContentView(root);
    }

    @Override
    protected void onDestroy() {
        taskRunnerActive = false;
        taskExecutor.shutdownNow();
        taskLoopExecutor.shutdownNow();
        ObservationAccessibilityService.endTaskCapture();
        super.onDestroy();
    }

    private void submitConfiguredTask(String goal) {
        if (goal == null || goal.trim().isEmpty()) {
            taskStatus.setText("Task: configure a Chinese node goal on the connection screen first");
            return;
        }
        final BridgeConfig config = BridgeConfig.load(this);
        activeTaskConfig = config;
        final long runEpoch = ++taskControlEpoch;
        pendingControlCommand = null;
        taskSubmissionInFlight = true;
        taskRunnerActive = true;
        dispatchedActionId = null;
        pendingReceipt = null;
        setTaskButtons(true, "RUNNING");
        taskStatus.setText("Task: submitting goal…");
        // The start-button accessibility event can schedule an automatic
        // capture while submit is in flight. Reserve the stream and publish
        // one explicit fresh before frame first.
        ObservationAccessibilityService.beginTaskCapture(config, new ObservationAccessibilityService.CaptureCallback() {
            @Override
            public void onSuccess(JSONObject acknowledgement) {
                taskExecutor.execute(() -> submitConfiguredTaskAfterCapture(config, goal.trim(), runEpoch));
            }

            @Override
            public void onError(String code, String message) {
                taskSubmissionInFlight = false;
                taskRunnerActive = false;
                ObservationAccessibilityService.endTaskCapture();
                runOnUiThread(() -> {
                    taskStatus.setText("Task: fresh observation failed — " + message);
                    setTaskButtons(false, "FAILED");
                });
            }
        });
    }

    private void submitConfiguredTaskAfterCapture(BridgeConfig config, String goal, long runEpoch) {
        try {
            JSONObject status = BridgeClient.submitTask(config, goal);
            taskSubmissionInFlight = false;
            renderTaskStatus(status);
            // A control click may have happened while submit was in flight.
            // Apply it before the polling worker can dispatch.
            if (runEpoch != taskControlEpoch || pendingControlCommand != null) {
                JSONObject controlled = applyPendingControl(config);
                if (controlled != null && controlled.optBoolean("action_result_unknown", false)) {
                    taskLoopExecutor.execute(() -> runNodeTaskLoop(config, taskControlEpoch));
                } else {
                    taskRunnerActive = false;
                    ObservationAccessibilityService.endTaskCapture();
                }
                return;
            }
            taskLoopExecutor.execute(() -> runNodeTaskLoop(config, runEpoch));
        } catch (BridgeClient.BridgeException exception) {
            taskSubmissionInFlight = false;
            taskRunnerActive = false;
            ObservationAccessibilityService.endTaskCapture();
            runOnUiThread(() -> {
                taskStatus.setText("Task: submission failed — " + exception.getMessage());
                setTaskButtons(false, "FAILED");
            });
        }
    }

    private void runNodeTaskLoop(BridgeConfig config, long runEpoch) {
        while (taskRunnerActive && !Thread.currentThread().isInterrupted()) {
            JSONObject pending = pendingReceipt;
            if (pending != null) {
                try {
                    JSONObject status = BridgeClient.postReceipt(config, pending);
                    pendingReceipt = null;
                    renderTaskStatus(status);
                    if (pending.optBoolean("accepted", false)
                            && !pending.has("after_observation_id")) {
                        requestPostActionCapture(config);
                    } else {
                        ObservationAccessibilityService.endTaskCapture();
                    }
                } catch (BridgeClient.BridgeException exception) {
                    runOnUiThread(() -> taskStatus.setText("Task: receipt pending — " + exception.getMessage()));
                }
            }
            try {
                JSONObject status = BridgeClient.taskStatus(config);
                renderTaskStatus(status);
                String stateValue = status.optString("state", "FAILED");
                JSONObject next = status.optJSONObject("next_action");
                boolean controlRequested = runEpoch != taskControlEpoch || pendingControlCommand != null;
                if ("RUNNING".equals(stateValue) && next != null && pendingReceipt == null && !controlRequested) {
                    String actionId = next.optString("action_id", "");
                    if (!actionId.isEmpty() && !actionId.equals(dispatchedActionId)) {
                        dispatchedActionId = actionId;
                        JSONObject beforeDispatch = BridgeClient.taskStatus(config);
                        if (runEpoch == taskControlEpoch
                                && pendingControlCommand == null
                                && "RUNNING".equals(beforeDispatch.optString("state", ""))
                                && beforeDispatch.optJSONObject("next_action") != null) {
                            executeNodeAction(config, beforeDispatch.optJSONObject("next_action"));
                        }
                    }
                }
                if ("SUCCEEDED".equals(stateValue) || "FAILED".equals(stateValue)
                        || "CANCELLED".equals(stateValue) || "PAUSED".equals(stateValue)) {
                    if (pendingReceipt == null && !status.optBoolean("action_result_unknown", false)) {
                        taskRunnerActive = false;
                        ObservationAccessibilityService.endTaskCapture();
                        runOnUiThread(() -> setTaskButtons(false, stateValue));
                        return;
                    }
                }
                Thread.sleep(300L);
            } catch (BridgeClient.BridgeException exception) {
                runOnUiThread(() -> taskStatus.setText("Task: bridge unavailable — " + exception.getMessage()));
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void executeNodeAction(final BridgeConfig config, final JSONObject action) {
        ObservationAccessibilityService.executeAction(config, action,
                new ObservationAccessibilityService.ActionCallback() {
                    @Override
                    public void onSuccess() {
                        // Capture the postcondition frame before publishing
                        // the receipt so it can be explicitly associated.
                        requestPostActionCapture(config, new ObservationAccessibilityService.CaptureCallback() {
                            @Override
                            public void onSuccess(JSONObject acknowledgement) {
                                pendingReceipt = buildReceipt(action, true, null, null, acknowledgement);
                            }

                            @Override
                            public void onError(String code, String message) {
                                pendingReceipt = buildReceipt(action, true, null, null, null);
                            }
                        });
                    }

                    @Override
                    public void onError(String code, String message) {
                        pendingReceipt = buildReceipt(action, false, code, message);
                    }
                });
    }

    private void requestPostActionCapture(
            final BridgeConfig config,
            final ObservationAccessibilityService.CaptureCallback callback) {
        ObservationAccessibilityService.requestCaptureAfterAction(config, callback);
    }

    private void requestPostActionCapture(final BridgeConfig config) {
        requestPostActionCapture(config, new ObservationAccessibilityService.CaptureCallback() {
            @Override
            public void onSuccess(JSONObject acknowledgement) {
                // The fallback observation is associated by its receipt-time
                // version gate when no explicit token was available.
                ObservationAccessibilityService.endTaskCapture();
            }

            @Override
            public void onError(String code, String message) {
                ObservationAccessibilityService.endTaskCapture();
                runOnUiThread(() -> taskStatus.setText("Task: post-action observation pending — " + message));
            }
        });
    }

    private JSONObject buildReceipt(JSONObject action, boolean accepted, String errorCode, String errorMessage) {
        return buildReceipt(action, accepted, errorCode, errorMessage, null);
    }

    private JSONObject buildReceipt(
            JSONObject action,
            boolean accepted,
            String errorCode,
            String errorMessage,
            JSONObject afterObservation) {
        try {
            JSONObject receipt = new JSONObject();
            receipt.put("schema_version", "1.0");
            receipt.put("android_schema_version", "1.0");
            receipt.put("task_id", action.optString("task_id"));
            receipt.put("receipt_id", "android-receipt-" + action.optString("action_id"));
            receipt.put("action_id", action.optString("action_id"));
            receipt.put("device_id", action.optString("device_id"));
            receipt.put("accepted", accepted);
            receipt.put("outcome", accepted ? "EXECUTED" : "REJECTED");
            receipt.put("received_at", Instant.now().toString());
            receipt.put("error_code", errorCode == null ? JSONObject.NULL : errorCode);
            receipt.put("error_message", errorMessage == null ? JSONObject.NULL : errorMessage);
            receipt.put("observation_id", action.optString("observation_id"));
            receipt.put("observation_version", action.optInt("observation_version"));
            if (afterObservation != null) {
                String afterId = afterObservation.optString("observation_id", "");
                int afterVersion = afterObservation.optInt("observation_version", 0);
                if (!afterId.isEmpty() && afterVersion > 0) {
                    receipt.put("after_observation_id", afterId);
                    receipt.put("after_observation_version", afterVersion);
                }
            }
            receipt.put("deduplicated", false);
            return receipt;
        } catch (JSONException exception) {
            runOnUiThread(() -> taskStatus.setText("Task: could not build execution receipt — " + exception.getMessage()));
            return null;
        }
    }

    private void controlNodeTask(String command) {
        final BridgeConfig config = activeTaskConfig == null ? BridgeConfig.load(this) : activeTaskConfig;
        final long controlEpoch = ++taskControlEpoch;
        pendingControlCommand = command;
        taskRunnerActive = true;
        setTaskButtons(true, "RUNNING");
        taskExecutor.execute(() -> {
            if (controlEpoch != taskControlEpoch || !command.equals(pendingControlCommand)) {
                return;
            }
            try {
                JSONObject status = BridgeClient.controlTask(config, command);
                if (controlEpoch != taskControlEpoch || !command.equals(pendingControlCommand)) {
                    return;
                }
                pendingControlCommand = null;
                renderTaskStatus(status);
                if ("cancel".equals(command) && !status.optBoolean("action_result_unknown", false)) {
                    taskRunnerActive = false;
                    ObservationAccessibilityService.endTaskCapture();
                }
            } catch (BridgeClient.BridgeException exception) {
                // A control request can reach the bridge before submit. The
                // submit worker will apply the local intent once the task is
                // created, so this 404 must not release the epoch.
                if (exception.status == 404 && taskSubmissionInFlight) {
                    return;
                }
                runOnUiThread(() -> taskStatus.setText("Task: " + command + " failed — " + exception.getMessage()));
            }
        });
    }

    private JSONObject applyPendingControl(BridgeConfig config) {
        String command = pendingControlCommand;
        if (command == null) {
            return null;
        }
        try {
            JSONObject status = BridgeClient.controlTask(config, command);
            pendingControlCommand = null;
            renderTaskStatus(status);
            if (!status.optBoolean("action_result_unknown", false)) {
                taskRunnerActive = false;
                ObservationAccessibilityService.endTaskCapture();
            }
            return status;
        } catch (BridgeClient.BridgeException exception) {
            runOnUiThread(() -> taskStatus.setText("Task: " + command + " failed — " + exception.getMessage()));
            return null;
        }
    }

    private void renderTaskStatus(JSONObject status) {
        if (status == null) {
            return;
        }
        String stateValue = status.optString("state", "UNKNOWN");
        String phase = status.optString("phase", "");
        StringBuilder message = new StringBuilder("Task: ").append(stateValue);
        if (!phase.isEmpty()) {
            message.append("\nphase=").append(phase);
        }
        message.append("\ngoal=").append(status.optString("goal", ""));
        JSONObject action = status.optJSONObject("action");
        if (action != null) {
            message.append("\naction=").append(action.optString("kind", ""))
                    .append(" target=").append(action.optString("target_node_label", ""));
        }
        JSONObject receipt = status.optJSONObject("receipt");
        if (receipt != null) {
            message.append("\nreceipt=").append(receipt.optString("outcome", ""));
        }
        JSONObject verification = status.optJSONObject("verification");
        if (verification != null) {
            message.append("\nverification=").append(verification.optString("status", ""))
                    .append(" reason=").append(verification.optString("reason", ""));
        }
        JSONObject failure = status.optJSONObject("failure");
        if (failure != null) {
            message.append("\nfailure=").append(failure.optString("code", ""))
                    .append(" ").append(failure.optString("message", ""));
        }
        runOnUiThread(() -> {
            taskStatus.setText(message.toString());
            setTaskButtons(taskRunnerActive, stateValue);
        });
    }

    private void setTaskButtons(boolean active, String stateValue) {
        if (startTaskButton == null) {
            return;
        }
        boolean terminal = "SUCCEEDED".equals(stateValue) || "FAILED".equals(stateValue)
                || "CANCELLED".equals(stateValue);
        boolean paused = "PAUSED".equals(stateValue);
        // A paused task remains the same server task. Starting here would
        // submit a second task with the same identity instead of resuming it.
        startTaskButton.setEnabled(!active && (terminal || "".equals(stateValue)));
        pauseTaskButton.setEnabled(active && "RUNNING".equals(stateValue));
        cancelTaskButton.setEnabled(!terminal && (active || paused));
    }

    private LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
