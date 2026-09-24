package com.jev.mobileagent;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.content.res.Configuration;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
    private TextView visualStatus;
    private Button startTaskButton;
    private Button startVlmTaskButton;
    private Button pauseTaskButton;
    private Button cancelTaskButton;
    /** Serialize submit/control requests while keeping status polling responsive. */
    private final ExecutorService taskExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService taskLoopExecutor = Executors.newSingleThreadExecutor();
    private final ActionExecutionGate actionExecutionGate = new ActionExecutionGate();
    private volatile boolean taskRunnerActive;
    private volatile String dispatchedActionId;
    private volatile JSONObject pendingReceipt;
    private volatile boolean pendingReceiptWaitsForControl;
    private volatile BridgeConfig activeTaskConfig;
    private volatile boolean taskSubmissionInFlight;
    private volatile String pendingControlCommand;
    private volatile long taskControlEpoch;
    private volatile boolean vlmTaskActive;
    private volatile boolean actionInFlight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Controlled observation page");
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        top.setPadding(dp(landscape ? 6 : 20), dp(landscape ? 2 : 12),
                dp(landscape ? 6 : 20), 0);
        LinearLayout topInfo = top;
        LinearLayout topInput = top;
        if (landscape) {
            topInfo = new LinearLayout(this);
            topInfo.setOrientation(LinearLayout.VERTICAL);
            topInput = new LinearLayout(this);
            topInput.setOrientation(LinearLayout.VERTICAL);
            top.addView(topInfo, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));
            top.addView(topInput, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));
        }
        LinearLayout.LayoutParams topLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                landscape ? dp(108) : LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(top, topLayout);

        TextView heading = new TextView(this);
        heading.setText("Controlled observation page");
        heading.setTextSize(24);
        heading.setTextColor(Color.rgb(35, 50, 65));
        heading.setContentDescription("Controlled page heading");
        topInfo.addView(heading, compactParams());

        TextView text = new TextView(this);
        text.setText("Visible controlled text");
        text.setTextSize(18);
        text.setContentDescription("The tree must contain this visible text");
        topInfo.addView(text, compactParams());

        state = new TextView(this);
        state.setText("Controlled action state: ready");
        state.setTextSize(16);
        state.setContentDescription("Controlled action state ready");
        topInfo.addView(state, compactParams());

        TextView inputHeading = new TextView(this);
        inputHeading.setText("Chinese input target");
        inputHeading.setTextSize(16);
        inputHeading.setContentDescription("Chinese input target heading");
        topInput.addView(inputHeading, compactParams());

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint("请输入中文");
        input.setContentDescription("中文输入框");
        topInput.addView(input, compactParams());

        TextView inputState = new TextView(this);
        inputState.setText("Controlled input state: empty");
        inputState.setTextSize(16);
        inputState.setContentDescription("Controlled input state empty");
        topInput.addView(inputState, compactParams());
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

        TextView visualHeading = new TextView(this);
        visualHeading.setText("Visual gesture surface (semantic tree intentionally incomplete)");
        visualHeading.setTextSize(landscape ? 12 : 16);
        visualHeading.setContentDescription("Visual gesture surface heading");
        VisualGestureSurface visualSurface = new VisualGestureSurface();
        visualSurface.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        visualStatus = new TextView(this);
        visualStatus.setText("Visual gesture state: ready");
        visualStatus.setTextSize(landscape ? 12 : 16);
        visualStatus.setContentDescription("Visual gesture state ready");

        LinearLayout visualPanel = new LinearLayout(this);
        visualPanel.setOrientation(LinearLayout.VERTICAL);
        visualPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        visualPanel.setPadding(dp(landscape ? 6 : 20), 0, dp(landscape ? 6 : 20), 0);
        visualPanel.addView(visualHeading, compactParams());
        LinearLayout.LayoutParams surfaceLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        surfaceLayout.topMargin = dp(2);
        visualPanel.addView(visualSurface, surfaceLayout);
        visualPanel.addView(visualStatus, compactParams());
        LinearLayout.LayoutParams visualLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        root.addView(visualPanel, visualLayout);

        Button action = new Button(this);
        action.setText("Toggle controlled state");
        action.setContentDescription("Toggle controlled state button");
        action.setOnClickListener(v -> {
            String next = state.getText().toString().endsWith("ready") ? "completed" : "ready";
            state.setText("Controlled action state: " + next);
            state.setContentDescription("Controlled action state " + next);
        });
        action.setOnLongClickListener(v -> {
            state.setText("Controlled action state: long press completed");
            state.setContentDescription("Controlled action state long press completed");
            return true;
        });
        topInfo.addView(action, compactParams());

        TextView taskHeading = new TextView(this);
        taskHeading.setText("Configured node task");
        taskHeading.setTextSize(18);
        taskHeading.setTextColor(Color.rgb(35, 50, 65));
        taskHeading.setContentDescription("Configured node task heading");
        String configuredGoal = getSharedPreferences("jev_android_observation", MODE_PRIVATE)
                .getString("task_goal", "");
        TextView goal = new TextView(this);
        goal.setText(configuredGoal.isEmpty() ? "Task goal: none configured" : "Task goal: " + configuredGoal);
        goal.setTextSize(15);
        goal.setContentDescription("Configured task goal " + configuredGoal);

        startTaskButton = new Button(this);
        startTaskButton.setText("Start configured node task");
        startTaskButton.setOnClickListener(v -> submitConfiguredTask(configuredGoal, false));

        startVlmTaskButton = new Button(this);
        startVlmTaskButton.setText("Start real VLM task");
        startVlmTaskButton.setContentDescription("Start real VLM task");
        startVlmTaskButton.setOnClickListener(v -> submitConfiguredTask(configuredGoal, true));

        pauseTaskButton = new Button(this);
        pauseTaskButton.setText("Pause task");
        pauseTaskButton.setEnabled(false);
        pauseTaskButton.setOnClickListener(v -> controlNodeTask("pause"));

        cancelTaskButton = new Button(this);
        cancelTaskButton.setText("Cancel task");
        cancelTaskButton.setEnabled(false);
        cancelTaskButton.setOnClickListener(v -> controlNodeTask("cancel"));

        taskStatus = new TextView(this);
        taskStatus.setText("Task: no task submitted");
        taskStatus.setTextSize(15);
        taskStatus.setTextIsSelectable(true);
        taskStatus.setMaxLines(landscape ? 2 : 3);

        LinearLayout taskPanel = new LinearLayout(this);
        taskPanel.setOrientation(LinearLayout.VERTICAL);
        taskPanel.setPadding(dp(16), dp(6), dp(16), dp(8));
        taskPanel.setBackgroundColor(Color.WHITE);
        taskPanel.addView(taskHeading, compactParams());
        taskPanel.addView(goal, compactParams());
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_HORIZONTAL);
        buttonRow.addView(startTaskButton, rowButtonParams());
        buttonRow.addView(pauseTaskButton, rowButtonParams());
        buttonRow.addView(cancelTaskButton, rowButtonParams());
        taskPanel.addView(buttonRow, compactParams());
        taskPanel.addView(startVlmTaskButton, compactParams());
        taskPanel.addView(taskStatus, compactParams());
        if (landscape) {
            startTaskButton.setText("Start");
            pauseTaskButton.setText("Pause");
            cancelTaskButton.setText("Cancel");
            taskStatus.setTextSize(12);
        }
        LinearLayout.LayoutParams taskLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, landscape ? dp(150) : dp(285));
        root.addView(taskPanel, taskLayout);
        setContentView(root);
    }

    @Override
    protected void onDestroy() {
        actionExecutionGate.invalidate();
        taskRunnerActive = false;
        taskExecutor.shutdownNow();
        taskLoopExecutor.shutdownNow();
        ObservationAccessibilityService.endTaskCapture();
        super.onDestroy();
    }

    private void submitConfiguredTask(String goal, final boolean vlm) {
        if (goal == null || goal.trim().isEmpty()) {
            taskStatus.setText("Task: configure a Chinese node goal on the connection screen first");
            return;
        }
        final BridgeConfig config = BridgeConfig.load(this);
        if (vlm && (config.modelEndpoint.isEmpty() || config.modelName.isEmpty() || config.modelApiKey.isEmpty())) {
            taskStatus.setText("Task: configure VLM provider, HTTPS endpoint, model, and API key on the connection screen first");
            return;
        }
        activeTaskConfig = config;
        vlmTaskActive = vlm;
        final long runEpoch = ++taskControlEpoch;
        final ActionExecutionGate.Token actionToken = actionExecutionGate.begin();
        pendingControlCommand = null;
        taskSubmissionInFlight = true;
        taskRunnerActive = true;
        actionInFlight = false;
        dispatchedActionId = null;
        pendingReceipt = null;
        pendingReceiptWaitsForControl = false;
        setTaskButtons(true, "RUNNING");
        taskStatus.setText(vlm ? "Task: submitting real VLM goal…" : "Task: submitting configured goal…");
        // The start-button accessibility event can schedule an automatic
        // capture while submit is in flight. Reserve the stream and publish
        // one explicit fresh before frame first.
        beginTaskCaptureForGoal(config, goal, new ObservationAccessibilityService.CaptureCallback() {
            @Override
            public void onSuccess(JSONObject acknowledgement) {
                taskExecutor.execute(() -> submitConfiguredTaskAfterCapture(
                        config, goal.trim(), runEpoch, vlm, actionToken));
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

    private boolean requiresVisualTask(String goal) {
        String value = goal == null ? "" : goal.trim();
        return vlmTaskActive || value.contains("长按") || value.contains("滑动") || value.contains("坐标")
                || value.contains("点击屏幕") || value.startsWith("点击(") || value.startsWith("点击（")
                || value.equals("返回") || value.contains("系统返回")
                || value.equalsIgnoreCase("back") || value.equalsIgnoreCase("system back");
    }

    private void beginTaskCaptureForGoal(
            final BridgeConfig config,
            final String goal,
            final ObservationAccessibilityService.CaptureCallback callback) {
        if (!requiresVisualTask(goal)) {
            ObservationAccessibilityService.beginTaskCapture(config, callback);
            return;
        }
        ObservationAccessibilityService.beginTaskCapture(config, new ObservationAccessibilityService.CaptureCallback() {
            @Override
            public void onSuccess(JSONObject acknowledgement) {
                ObservationAccessibilityService.requestScreenshot(
                        config,
                        acknowledgement,
                        "BEFORE",
                        new ObservationAccessibilityService.ScreenshotCallback() {
                            @Override
                            public void onSuccess(JSONObject screenshotAcknowledgement) {
                                callback.onSuccess(acknowledgement);
                            }

                            @Override
                            public void onError(String code, String message) {
                                callback.onError("before_screenshot_missing", message);
                            }
                        });
            }

            @Override
            public void onError(String code, String message) {
                callback.onError(code, message);
            }
        });
    }

    private void submitConfiguredTaskAfterCapture(
            BridgeConfig config,
            String goal,
            long runEpoch,
            boolean vlm,
            ActionExecutionGate.Token actionToken) {
        try {
            JSONObject status = vlm ? BridgeClient.submitVlmTask(config, goal) : BridgeClient.submitTask(config, goal);
            taskSubmissionInFlight = false;
            renderTaskStatus(status);
            // A control click may have happened while submit was in flight.
            // Apply it before the polling worker can dispatch.
            if (runEpoch != taskControlEpoch || pendingControlCommand != null) {
                JSONObject controlled = applyPendingControl(config);
                if (controlled != null && controlled.optBoolean("action_result_unknown", false)) {
                    taskLoopExecutor.execute(() -> runNodeTaskLoop(config, taskControlEpoch, actionToken));
                } else {
                    taskRunnerActive = false;
                    vlmTaskActive = false;
                    ObservationAccessibilityService.endTaskCapture();
                }
                return;
            }
            taskLoopExecutor.execute(() -> runNodeTaskLoop(config, runEpoch, actionToken));
        } catch (BridgeClient.BridgeException exception) {
            taskSubmissionInFlight = false;
            taskRunnerActive = false;
            vlmTaskActive = false;
            ObservationAccessibilityService.endTaskCapture();
            runOnUiThread(() -> {
                taskStatus.setText("Task: submission failed — " + exception.getMessage());
                setTaskButtons(false, "FAILED");
            });
        }
    }

    private void runNodeTaskLoop(
            BridgeConfig config,
            long runEpoch,
            ActionExecutionGate.Token actionToken) {
        while ((taskRunnerActive || actionInFlight || pendingReceipt != null)
                && !Thread.currentThread().isInterrupted()) {
            JSONObject pending = pendingReceipt;
            if (pending != null
                    && (!pendingReceiptWaitsForControl || pendingControlCommand == null)) {
                try {
                    JSONObject status = BridgeClient.postReceipt(config, pending);
                    pendingReceipt = null;
                    pendingReceiptWaitsForControl = false;
                    renderTaskStatus(status);
                    if ("vlm".equals(status.optString("mode"))
                            && pending.optBoolean("accepted", false)
                            && "RUNNING".equals(status.optString("state"))) {
                        requestNextVlmObservation(config);
                    } else if (pending.optBoolean("accepted", false)
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
                            executeNodeAction(config, actionToken, beforeDispatch.optJSONObject("next_action"));
                        }
                    }
                }
                if ("SUCCEEDED".equals(stateValue) || "FAILED".equals(stateValue)
                        || "CANCELLED".equals(stateValue) || "PAUSED".equals(stateValue)) {
                    if (pendingReceipt == null && !actionInFlight
                            && !status.optBoolean("action_result_unknown", false)) {
                        taskRunnerActive = false;
                        vlmTaskActive = false;
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

    private void executeNodeAction(
            final BridgeConfig config,
            final ActionExecutionGate.Token actionToken,
            final JSONObject action) {
        actionInFlight = true;
        ObservationAccessibilityService.executeAction(config, action, actionToken,
                new ObservationAccessibilityService.ActionCallback() {
                    @Override
                    public void onSuccess() {
                        // Capture the postcondition frame before publishing
                        // the receipt so it can be explicitly associated.
                        requestPostActionCapture(config, new ObservationAccessibilityService.CaptureCallback() {
                            @Override
                    public void onSuccess(JSONObject acknowledgement) {
                        if (requiresVisualAction(action)) {
                            ObservationAccessibilityService.requestScreenshot(
                                    config,
                                    acknowledgement,
                                    "AFTER",
                                    new ObservationAccessibilityService.ScreenshotCallback() {
                                        @Override
                                        public void onSuccess(JSONObject screenshotAcknowledgement) {
                                            recordPendingReceipt(buildReceipt(
                                                    action, true, null, null, acknowledgement,
                                                    screenshotAcknowledgement, null));
                                        }

                                        @Override
                                        public void onError(String code, String message) {
                                            recordPendingReceipt(buildReceipt(
                                                    action, true, null, null, acknowledgement,
                                                    null, code + ": " + message));
                                        }
                                    });
                        } else {
                            recordPendingReceipt(buildReceipt(action, true, null, null, acknowledgement));
                        }
                            }

                            @Override
                            public void onError(String code, String message) {
                        recordPendingReceipt(buildReceipt(action, true, null, null, null, null, code + ": " + message));
                            }
                        });
                    }

                    @Override
                    public void onError(String code, String message) {
                        recordPendingReceipt(buildReceipt(action, false, code, message), "action_controlled".equals(code));
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

    private void recordPendingReceipt(JSONObject receipt) {
        recordPendingReceipt(receipt, false);
    }

    private void recordPendingReceipt(JSONObject receipt, boolean waitsForControl) {
        pendingReceiptWaitsForControl = waitsForControl;
        // Publish the wait flag before the volatile receipt reference so the
        // polling worker cannot observe a controlled receipt as immediately
        // sendable.
        pendingReceipt = receipt;
        actionInFlight = false;
    }

    private void requestNextVlmObservation(final BridgeConfig config) {
        requestPostActionCapture(config, new ObservationAccessibilityService.CaptureCallback() {
            @Override
            public void onSuccess(JSONObject acknowledgement) {
                ObservationAccessibilityService.requestScreenshot(
                        config,
                        acknowledgement,
                        "BEFORE",
                        new ObservationAccessibilityService.ScreenshotCallback() {
                            @Override
                            public void onSuccess(JSONObject screenshotAcknowledgement) {
                            }

                            @Override
                            public void onError(String code, String message) {
                                runOnUiThread(() -> taskStatus.setText("Task: next VLM screenshot unavailable — " + message));
                            }
                        });
            }

            @Override
            public void onError(String code, String message) {
                runOnUiThread(() -> taskStatus.setText("Task: next VLM observation unavailable — " + message));
            }
        });
    }

    private JSONObject buildReceipt(JSONObject action, boolean accepted, String errorCode, String errorMessage) {
        return buildReceipt(action, accepted, errorCode, errorMessage, null);
    }

    private boolean requiresVisualAction(JSONObject action) {
        return action != null && (vlmTaskActive || action.optBoolean("requires_screenshot", false)
                || "long_press".equals(action.optString("kind"))
                || "swipe".equals(action.optString("kind"))
                || "coordinate_tap".equals(action.optString("kind"))
                || "system_back".equals(action.optString("kind")));
    }

    private JSONObject buildReceipt(
            JSONObject action,
            boolean accepted,
            String errorCode,
            String errorMessage,
            JSONObject afterObservation) {
        return buildReceipt(action, accepted, errorCode, errorMessage, afterObservation, null, null);
    }

    private JSONObject buildReceipt(
            JSONObject action,
            boolean accepted,
            String errorCode,
            String errorMessage,
            JSONObject afterObservation,
            JSONObject afterScreenshot,
            String afterScreenshotMissingReason) {
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
            if (afterScreenshot != null) {
                String screenshotId = afterScreenshot.optString("screenshot_id", "");
                if (!screenshotId.isEmpty()) {
                    receipt.put("after_screenshot_id", screenshotId);
                }
            }
            if (afterScreenshotMissingReason != null) {
                receipt.put("after_screenshot_missing_reason", afterScreenshotMissingReason);
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
        // Invalidate before the remote request so a queued Accessibility
        // runnable cannot dispatch the old action while control is in flight.
        actionExecutionGate.invalidate();
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
                    if (!actionInFlight && pendingReceipt == null) {
                        vlmTaskActive = false;
                        ObservationAccessibilityService.endTaskCapture();
                    }
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
        message.append("\nmode=").append(status.optString("mode", "deterministic"));
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
        JSONObject actualEffect = status.optJSONObject("actual_effect");
        if (actualEffect != null) {
            message.append("\nactual_effect=").append(actualEffect.optString("status", ""));
        }
        JSONObject completion = status.optJSONObject("vlm_completion");
        if (completion != null) {
            message.append("\nvlm_completion=").append(completion.optString("status", ""))
                    .append(" steps=").append(completion.optInt("steps", 0));
        }
        JSONObject independent = status.optJSONObject("independent_result");
        if (independent != null) {
            message.append("\nindependent_result=").append(independent.optString("status", ""))
                    .append(" reason=").append(independent.optString("reason", ""));
        }
        JSONObject usage = status.optJSONObject("usage");
        if (usage != null) {
            message.append("\nusage requests=").append(usage.optInt("requests", 0))
                    .append(" estimated_cny=").append(usage.optDouble("estimated_cost_cny", 0.0))
                    .append(" usage_missing=").append(usage.optBoolean("usage_missing", false));
        }
        if (status.has("task_output") && !status.isNull("task_output")) {
            message.append("\ntask_output=").append(status.optString("task_output", ""));
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
        if (startVlmTaskButton != null) {
            startVlmTaskButton.setEnabled(!active && (terminal || "".equals(stateValue)));
        }
        pauseTaskButton.setEnabled(active && "RUNNING".equals(stateValue));
        cancelTaskButton.setEnabled(!terminal && (active || paused));
    }

    @Override
    public void onBackPressed() {
        // Keep the controlled page alive so a system-back task has an
        // independently observable postcondition instead of closing the test
        // activity before the after observation can be captured.
        setVisualStatus("system back completed");
    }

    private void setVisualStatus(String value) {
        if (visualStatus == null) {
            return;
        }
        visualStatus.setText("Visual gesture state: " + value);
        visualStatus.setContentDescription("Visual gesture state " + value);
    }

    private final class VisualGestureSurface extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downX;
        private float downY;
        private long downAt;

        VisualGestureSurface() {
            super(ControlledPageActivity.this);
            setBackgroundColor(Color.rgb(242, 246, 250));
            // No content description by design: this panel exercises the
            // screenshot/coordinate fallback while visualStatus remains the
            // independent result state visible to the tree.
            setContentDescription(null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(Color.rgb(45, 100, 165));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            float left = getWidth() * 0.22f;
            float top = getHeight() * 0.22f;
            float right = getWidth() * 0.78f;
            float bottom = getHeight() * 0.78f;
            canvas.drawRect(left, top, right, bottom, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(dp(14));
            canvas.drawText("自绘目标 · 坐标/长按/滑动", left, top - dp(10), paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    downAt = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    long duration = System.currentTimeMillis() - downAt;
                    // A swipe may intentionally last longer than the long
                    // press threshold. Classify displacement first; a long
                    // press is the stationary gesture only.
                    if (Math.hypot(dx, dy) >= dp(40)) {
                        setVisualStatus("swipe completed");
                    } else if (duration >= 500L) {
                        setVisualStatus("long press completed");
                    } else {
                        setVisualStatus("coordinate tap completed");
                    }
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    private LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        return params;
    }

    private LinearLayout.LayoutParams compactParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(2);
        return params;
    }

    private LinearLayout.LayoutParams rowButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
