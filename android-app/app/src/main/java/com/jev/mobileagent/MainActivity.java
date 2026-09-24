package com.jev.mobileagent;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal pairing and observation status screen for the emulator acceptance task. */
public class MainActivity extends Activity {
    private static final String DEFAULT_NODE_GOAL = "在中文输入框中输入“手机验收，中文”";
    private static final String DEFAULT_CLICK_GOAL = "点击“切换受控状态”按钮";
    private static final String DEFAULT_LONG_PRESS_GOAL = "长按“切换受控状态”按钮";
    private static final String DEFAULT_SWIPE_GOAL = "滑动视觉目标";
    private static final String DEFAULT_COORDINATE_GOAL = "点击坐标(540,1200)";
    private static final String DEFAULT_BACK_GOAL = "系统返回";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** Short task-control requests are serialized; polling runs separately. */
    private final ExecutorService taskExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService taskLoopExecutor = Executors.newSingleThreadExecutor();
    private final ActionExecutionGate actionExecutionGate = new ActionExecutionGate();
    private EditText endpointInput;
    private EditText tokenInput;
    private EditText deviceInput;
    private EditText taskInput;
    private EditText modelProviderInput;
    private EditText modelEndpointInput;
    private EditText modelNameInput;
    private EditText modelApiKeyInput;
    private TextView connectionStatus;
    private TextView permissionStatus;
    private TextView currentObservation;
    private EditText goalInput;
    private TextView taskStatus;
    private Button startTaskButton;
    private Button startVlmTaskButton;
    private Button pauseTaskButton;
    private Button cancelTaskButton;
    private Button reconcileTaskButton;
    private Button resumeTaskButton;
    private Button connectButton;
    private TextView recoveryStatus;
    private volatile boolean foregroundRefreshInFlight;
    private volatile long observationGeneration;
    private volatile boolean taskRunnerActive;
    private volatile boolean taskResultUnknown;
    private volatile String dispatchedActionId;
    private volatile JSONObject pendingReceipt;
    private volatile boolean pendingReceiptWaitsForControl;
    private volatile BridgeConfig activeTaskConfig;
    private volatile boolean taskSubmissionInFlight;
    private volatile String pendingControlCommand;
    private volatile long taskControlEpoch;
    private volatile boolean vlmTaskActive;
    private volatile boolean actionInFlight;
    private volatile boolean recoveryInFlight;
    private volatile JSONObject reconciledRecovery;
    private volatile String reconciledSessionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
        loadConfigIntoForm();
        updatePermissionStatus();
        restoreRecoveryEntry();
    }

    @Override
    protected void onResume() {
        super.onResume();
        final long generation = advanceObservationGeneration();
        boolean permissionEnabled = updatePermissionStatus();
        if (!permissionEnabled) {
            BridgeConfig config = BridgeConfig.load(this);
            if (BridgeConfig.captureEnabled(this)) {
                invalidateFromForeground(config, "Accessibility permission was revoked", generation);
            } else {
                // The service may have disabled capture while it was being
                // destroyed. Clear the old frame even when there is no
                // foreground request left to send.
                connectionStatus.setText("Connection: unavailable — Accessibility permission is revoked");
                clearCurrent("Current observation: cleared; accessibility permission is unavailable");
                connectButton.setEnabled(true);
            }
            return;
        }
        if (!BridgeConfig.captureEnabled(this)) {
            // A service restart clears capture_enabled and invalidates the last
            // AVAILABLE tree. Stay fail-closed until the user explicitly
            // reconnects and captures a fresh observation.
            connectionStatus.setText("Connection: unavailable — capture is not active; reconnect required");
            clearCurrent("Current observation: cleared; reconnect to capture a fresh observation");
            connectButton.setEnabled(true);
            return;
        }

        final BridgeConfig config = BridgeConfig.load(this);
        if (foregroundRefreshInFlight) {
            return;
        }
        foregroundRefreshInFlight = true;
        executor.execute(() -> {
            try {
                // Refresh both pieces of state when returning from settings or
                // another activity.  A stale latest response must clear the
                // local tree instead of leaving the last frame on screen.
                BridgeClient.status(config);
                readLatest(config, generation);
            } catch (BridgeClient.BridgeException exception) {
                failConnection("Disconnected: " + exception.getMessage(), generation);
            } finally {
                foregroundRefreshInFlight = false;
            }
        });
    }

    @Override
    protected void onPause() {
        advanceObservationGeneration();
        foregroundRefreshInFlight = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        advanceObservationGeneration();
        actionExecutionGate.invalidate();
        executor.shutdownNow();
        taskExecutor.shutdownNow();
        taskLoopExecutor.shutdownNow();
        ObservationAccessibilityService.endTaskCapture();
        super.onDestroy();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(24));
        scroll.addView(root);

        TextView title = label("Jev Android observation", 22, Color.rgb(35, 50, 65));
        title.setContentDescription("Jev Android observation connection screen");
        root.addView(title, widthMatchWrap());
        root.addView(label("Pair this device with the local bridge, then capture the real Accessibility tree.", 15, Color.DKGRAY), widthMatchWrap());

        root.addView(label("Bridge connection", 18, Color.rgb(35, 50, 65)), marginTop(widthMatchWrap(), 20));
        endpointInput = input("Bridge URL, for example http://10.0.2.2:8765", false);
        tokenInput = input("Bearer token", true);
        deviceInput = input("Device identity", false);
        taskInput = input("Task identity", false);
        root.addView(endpointInput, widthMatchWrap());
        root.addView(tokenInput, widthMatchWrap());
        root.addView(deviceInput, widthMatchWrap());
        root.addView(taskInput, widthMatchWrap());

        root.addView(label("VLM provider (BYOK)", 18, Color.rgb(35, 50, 65)), marginTop(widthMatchWrap(), 16));
        modelProviderInput = input("Provider name, for example GUI-Plus", false);
        modelEndpointInput = input("HTTPS model endpoint", false);
        modelNameInput = input("Model name", false);
        modelApiKeyInput = input("Model API key (stored privately)", true);
        root.addView(modelProviderInput, widthMatchWrap());
        root.addView(modelEndpointInput, widthMatchWrap());
        root.addView(modelNameInput, widthMatchWrap());
        root.addView(modelApiKeyInput, widthMatchWrap());

        connectButton = new Button(this);
        connectButton.setText("Connect and capture observation");
        connectButton.setOnClickListener(v -> connectAndCapture());
        root.addView(connectButton, marginTop(widthMatchWrap(), 10));

        Button settings = new Button(this);
        settings.setText("Open Accessibility settings");
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(settings, widthMatchWrap());

        Button controlledPage = new Button(this);
        controlledPage.setText("Open controlled observation page");
        controlledPage.setOnClickListener(v -> {
            // Keep the user-entered goal with the controlled page so the task
            // can be started while that page is the observed foreground
            // window.  The action is still submitted through the same bridge
            // contract and is bound to that page's fresh observation.
            getSharedPreferences("jev_android_observation", MODE_PRIVATE)
                    .edit()
                    .putString("task_goal", goalInput == null ? "" : goalInput.getText().toString())
                    .apply();
            startActivity(new Intent(this, ControlledPageActivity.class));
        });
        root.addView(controlledPage, widthMatchWrap());

        root.addView(label("Deterministic node task", 18, Color.rgb(35, 50, 65)), marginTop(widthMatchWrap(), 20));
        goalInput = input("目标，例如：点击“切换受控状态”按钮", false);
        root.addView(goalInput, widthMatchWrap());
        Button sampleGoal = new Button(this);
        sampleGoal.setText("Fill deterministic Chinese input goal");
        sampleGoal.setContentDescription("Fill deterministic Chinese input goal");
        sampleGoal.setOnClickListener(v -> goalInput.setText(DEFAULT_NODE_GOAL));
        root.addView(sampleGoal, widthMatchWrap());
        Button clickGoal = new Button(this);
        clickGoal.setText("Fill deterministic click goal");
        clickGoal.setContentDescription("Fill deterministic click goal");
        clickGoal.setOnClickListener(v -> goalInput.setText(DEFAULT_CLICK_GOAL));
        root.addView(clickGoal, widthMatchWrap());
        Button longPressGoal = new Button(this);
        longPressGoal.setText("Fill visual long-press goal");
        longPressGoal.setOnClickListener(v -> goalInput.setText(DEFAULT_LONG_PRESS_GOAL));
        root.addView(longPressGoal, widthMatchWrap());
        Button swipeGoal = new Button(this);
        swipeGoal.setText("Fill visual swipe goal");
        swipeGoal.setOnClickListener(v -> goalInput.setText(DEFAULT_SWIPE_GOAL));
        root.addView(swipeGoal, widthMatchWrap());
        Button coordinateGoal = new Button(this);
        coordinateGoal.setText("Fill visual coordinate goal");
        coordinateGoal.setOnClickListener(v -> goalInput.setText(DEFAULT_COORDINATE_GOAL));
        root.addView(coordinateGoal, widthMatchWrap());
        Button backGoal = new Button(this);
        backGoal.setText("Fill system Back goal");
        backGoal.setOnClickListener(v -> goalInput.setText(DEFAULT_BACK_GOAL));
        root.addView(backGoal, widthMatchWrap());
        startTaskButton = new Button(this);
        startTaskButton.setText("Start node task");
        startTaskButton.setOnClickListener(v -> submitNodeTask());
        root.addView(startTaskButton, marginTop(widthMatchWrap(), 10));
        startVlmTaskButton = new Button(this);
        startVlmTaskButton.setText("Start real VLM task");
        startVlmTaskButton.setContentDescription("Start real VLM task");
        startVlmTaskButton.setOnClickListener(v -> submitVlmTask());
        root.addView(startVlmTaskButton, widthMatchWrap());
        pauseTaskButton = new Button(this);
        pauseTaskButton.setText("Pause task");
        pauseTaskButton.setEnabled(false);
        pauseTaskButton.setOnClickListener(v -> controlNodeTask("pause"));
        root.addView(pauseTaskButton, widthMatchWrap());
        cancelTaskButton = new Button(this);
        cancelTaskButton.setText("Cancel task");
        cancelTaskButton.setEnabled(false);
        cancelTaskButton.setOnClickListener(v -> controlNodeTask("cancel"));
        root.addView(cancelTaskButton, widthMatchWrap());
        LinearLayout recoveryButtons = new LinearLayout(this);
        recoveryButtons.setOrientation(LinearLayout.HORIZONTAL);
        reconcileTaskButton = new Button(this);
        reconcileTaskButton.setText("Reconcile before resume");
        reconcileTaskButton.setEnabled(false);
        reconcileTaskButton.setOnClickListener(v -> reconcileTask());
        recoveryButtons.addView(reconcileTaskButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        resumeTaskButton = new Button(this);
        resumeTaskButton.setText("Confirm resume");
        resumeTaskButton.setEnabled(false);
        resumeTaskButton.setOnClickListener(v -> confirmRecoveryResume());
        recoveryButtons.addView(resumeTaskButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        root.addView(recoveryButtons, widthMatchWrap());
        recoveryStatus = statusLabel("Recovery status: no interrupted task recorded on this device");
        recoveryStatus.setContentDescription("Recovery status: no interrupted task recorded on this device");
        recoveryStatus.setMaxLines(4);
        recoveryStatus.setTextIsSelectable(true);
        root.addView(recoveryStatus, widthMatchWrap());
        taskStatus = statusLabel("Task: no task submitted");
        taskStatus.setTextIsSelectable(true);
        root.addView(taskStatus, marginTop(widthMatchWrap(), 8));

        connectionStatus = statusLabel("Connection: not paired");
        permissionStatus = statusLabel("Accessibility permission: checking");
        currentObservation = statusLabel("Current observation: none loaded");
        currentObservation.setTextIsSelectable(true);
        root.addView(connectionStatus, marginTop(widthMatchWrap(), 18));
        root.addView(permissionStatus, widthMatchWrap());
        root.addView(label("Latest state from server", 18, Color.rgb(35, 50, 65)), marginTop(widthMatchWrap(), 18));
        root.addView(currentObservation, widthMatchWrap());
        return scroll;
    }

    private void loadConfigIntoForm() {
        BridgeConfig config = BridgeConfig.load(this);
        endpointInput.setText(config.endpoint);
        tokenInput.setText(config.token);
        deviceInput.setText(config.deviceId);
        taskInput.setText(config.taskId);
        modelProviderInput.setText(config.modelProvider);
        modelEndpointInput.setText(config.modelEndpoint);
        modelNameInput.setText(config.modelName);
        modelApiKeyInput.setText(config.modelApiKey);
        String configuredGoal = getSharedPreferences("jev_android_observation", MODE_PRIVATE)
                .getString("task_goal", "");
        if (!configuredGoal.isEmpty()) {
            goalInput.setText(configuredGoal);
        }
    }

    private BridgeConfig readConfig() {
        BridgeConfig saved = BridgeConfig.load(this);
        BridgeConfig entered = new BridgeConfig(
                endpointInput.getText().toString(),
                tokenInput.getText().toString(),
                deviceInput.getText().toString(),
                taskInput.getText().toString(),
                modelProviderInput.getText().toString(),
                modelEndpointInput.getText().toString(),
                modelNameInput.getText().toString(),
                modelApiKeyInput.getText().toString(),
                saved.deviceSessionId);
        return entered.hasSameSessionBinding(saved) ? entered : entered.withDeviceSessionId("");
    }

    private void connectAndCapture() {
        final long generation = advanceObservationGeneration();
        actionExecutionGate.invalidate();
        taskControlEpoch++;
        taskRunnerActive = false;
        pendingReceipt = null;
        vlmTaskActive = false;
        final BridgeConfig config = readConfig().withDeviceSessionId("");
        config.save(this, false);
        clearReconciledRecovery();
        clearCurrent("Current observation: none; waiting for a fresh capture");
        connectButton.setEnabled(false);
        connectionStatus.setText("Connection: pairing with bridge…");
        executor.execute(() -> {
            try {
                JSONObject paired = BridgeClient.pair(config);
                String sessionId = paired.optString("device_session_id", "").trim();
                if (sessionId.isEmpty() || !BridgeConfig.persistDeviceSessionId(this, sessionId)) {
                    throw new BridgeClient.BridgeException(
                            0, "invalid_pair_response", "bridge did not persist a device session id");
                }
                refreshRecoveryButtons();
                final BridgeConfig pairedConfig = config.withDeviceSessionId(sessionId);
                boolean permission = ObservationAccessibilityService.isEnabled(this);
                if (!permission) {
                    sendUnavailableThenRead(
                            pairedConfig,
                            "Accessibility service is disabled or not running",
                            false,
                            false,
                            generation);
                    return;
                }
                updateOnUi(() -> {
                    if (generation != observationGeneration) {
                        return;
                    }
                    pairedConfig.save(this, true);
                    refreshRecoveryButtons();
                    connectionStatus.setText("Connection: paired; capturing real Accessibility tree…");
                    ObservationAccessibilityService.requestCapture(pairedConfig, new ObservationAccessibilityService.CaptureCallback() {
                        @Override
                        public void onSuccess(JSONObject acknowledgement) {
                            readLatest(pairedConfig, generation);
                        }

                        @Override
                        public void onError(String code, String message) {
                            if ("permission_unavailable".equals(code)) {
                                sendUnavailableThenRead(pairedConfig, message, false, false, generation);
                            } else {
                                failConnection("Disconnected: " + message, generation);
                            }
                        }
                    });
                });
            } catch (BridgeClient.BridgeException exception) {
                failConnection("Disconnected: " + exception.getMessage(), generation);
            }
        });
    }

    private void sendUnavailableThenRead(
            BridgeConfig config,
            String reason,
            boolean serviceEnabled,
            boolean canObserve,
            long generation) {
        config.save(this, false);
        try {
            JSONObject unavailable = ObservationPayload.unavailable(
                    this, config, "PERMISSION_UNAVAILABLE", reason, serviceEnabled, canObserve);
            BridgeClient.postObservation(config, unavailable);
            readLatest(config, generation);
        } catch (JSONException exception) {
            failConnection("Disconnected: could not build permission status", generation);
        } catch (BridgeClient.BridgeException exception) {
            failConnection("Disconnected: " + exception.getMessage(), generation);
        }
    }

    private void invalidateFromForeground(BridgeConfig config, String reason, long generation) {
        // Stop automatic service uploads before creating the explicit
        // unavailable observation.  This also makes repeated onResume calls
        // idempotent while the bridge request is in flight.
        config.save(this, false);
        updateOnUi(() -> {
            if (generation != observationGeneration) {
                return;
            }
            connectionStatus.setText("Connection: unavailable — " + reason);
            clearCurrent("Current observation: cleared; accessibility permission is unavailable");
            connectButton.setEnabled(true);
        });
        executor.execute(() -> sendUnavailableThenRead(config, reason, false, false, generation));
    }

    private void readLatest(BridgeConfig config, long generation) {
        try {
            JSONObject latest = BridgeClient.latest(config);
            updateOnUi(() -> renderLatest(latest, generation));
        } catch (BridgeClient.BridgeException exception) {
            failConnection("Disconnected: " + exception.getMessage(), generation);
        }
    }

    private void renderLatest(JSONObject latest, long generation) {
        if (generation != observationGeneration) {
            return;
        }
        boolean permissionEnabled = updatePermissionStatus();
        if (!latest.optBoolean("is_current", false)) {
            connectionStatus.setText("Connection: unavailable; server did not mark the observation current");
            clearCurrent("Current observation: unavailable; server did not mark it current");
            connectButton.setEnabled(true);
            return;
        }
        JSONObject observation = latest.optJSONObject("observation");
        if (observation == null) {
            connectionStatus.setText("Connection: unavailable; server returned no observation");
            clearCurrent("Current observation: unavailable; server returned no observation");
            connectButton.setEnabled(true);
            return;
        }
        String availability = observation.optString("availability", "UNKNOWN");
        String reason = observation.optString("unavailable_reason", "none");
        boolean captureEnabled = BridgeConfig.captureEnabled(this);
        if ("AVAILABLE".equals(availability) && (!permissionEnabled || !captureEnabled)) {
            String currentReason = !permissionEnabled
                    ? "Accessibility permission is unavailable"
                    : "capture is not active; reconnect required";
            connectionStatus.setText("Connection: unavailable — " + currentReason);
            clearCurrent("Current observation: cleared; " + currentReason);
            connectButton.setEnabled(true);
            return;
        }
        if (!"AVAILABLE".equals(availability)) {
            clearCurrent("Current observation: unavailable (" + availability + ")\nreason=" + reason);
            if ("CONNECTED".equals(latest.optString("connection_status"))) {
                connectionStatus.setText("Connection: paired; observation unavailable — " + availability);
            } else {
                connectionStatus.setText("Connection: unavailable — " + availability + " (" + reason + ")");
            }
            connectButton.setEnabled(true);
            return;
        }
        if (!"CONNECTED".equals(latest.optString("connection_status"))) {
            clearCurrent("Current observation: unavailable; bridge connection is not current");
            connectionStatus.setText("Connection: unavailable; bridge did not confirm a current connection");
            connectButton.setEnabled(true);
            return;
        }
        JSONArray nodes = observation.optJSONArray("nodes");
        JSONArray windows = observation.optJSONArray("windows");
        JSONObject screen = observation.optJSONObject("screen");
        String firstText = firstNodeText(nodes);
        String dimensions = screen == null
                ? "unknown screen"
                : screen.optInt("width_px") + "x" + screen.optInt("height_px")
                + " rotation=" + screen.optInt("rotation");
        String message = "Current observation: " + observation.optString("observation_id")
                + "\nversion=" + observation.optInt("observation_version")
                + " availability=" + observation.optString("availability")
                + "\nwindows=" + (windows == null ? 0 : windows.length())
                + " nodes=" + (nodes == null ? 0 : nodes.length())
                + " screen=" + dimensions
                + "\ncaptured_at=" + observation.optString("captured_at")
                + "\nfirst node text=" + firstText
                + "\nreason=" + observation.optString("unavailable_reason", "none");
        currentObservation.setText(message);
        connectionStatus.setText("Connection: paired; latest real tree is current");
        connectButton.setEnabled(true);
    }

    private String firstNodeText(JSONArray nodes) {
        if (nodes == null) {
            return "none";
        }
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) {
                continue;
            }
            String text = node.optString("text", "");
            if (!text.isEmpty()) {
                return text;
            }
            String description = node.optString("content_description", "");
            if (!description.isEmpty()) {
                return description;
            }
        }
        return "none";
    }

    private void submitNodeTask() {
        submitTask(false);
    }

    private void submitVlmTask() {
        submitTask(true);
    }

    private void submitTask(final boolean vlm) {
        String goal = goalInput == null ? "" : goalInput.getText().toString().trim();
        if (goal.isEmpty()) {
            taskStatus.setText(vlm
                    ? "Task: enter a goal for the real VLM task first"
                    : "Task: enter a Chinese node goal first");
            return;
        }
        final BridgeConfig config = readConfig();
        if (DeviceActionLedger.activeTask(this) != null) {
            taskStatus.setText("Task: a local task record must be reconciled before starting another task");
            return;
        }
        if (config.deviceSessionId.isEmpty()) {
            taskStatus.setText("Task: connect and pair this device before starting a task");
            return;
        }
        if (vlm && (config.modelEndpoint.isEmpty() || config.modelName.isEmpty() || config.modelApiKey.isEmpty())) {
            taskStatus.setText("Task: VLM provider, HTTPS endpoint, model, and API key are required");
            return;
        }
        activeTaskConfig = config;
        vlmTaskActive = vlm;
        taskResultUnknown = false;
        clearReconciledRecovery();
        config.save(this, false);
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
        taskStatus.setText(vlm ? "Task: submitting real VLM goal…" : "Task: submitting deterministic goal…");
        // The start-button accessibility event can schedule an automatic
        // capture while submit is in flight. Reserve the stream and publish
        // one explicit fresh before frame first.
        beginTaskCaptureForGoal(config, goal, new ObservationAccessibilityService.CaptureCallback() {
            @Override
            public void onSuccess(JSONObject acknowledgement) {
                taskExecutor.execute(() -> submitTaskAfterCapture(config, goal, runEpoch, vlm, actionToken));
            }

            @Override
            public void onError(String code, String message) {
                taskSubmissionInFlight = false;
                taskRunnerActive = false;
                ObservationAccessibilityService.endTaskCapture();
                updateOnUi(() -> {
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

    private void submitTaskAfterCapture(
            BridgeConfig config,
            String goal,
            long runEpoch,
            boolean vlm,
            ActionExecutionGate.Token actionToken) {
        try {
            taskResultUnknown = true;
            if (!DeviceActionLedger.beginTask(this, config, vlm)) {
                throw new BridgeClient.BridgeException(
                        0, "local_recovery_record_failed", "could not persist the local task record");
            }
            updateOnUi(() -> taskInput.setEnabled(false));
            JSONObject status = vlm
                    ? BridgeClient.submitVlmTask(config, goal)
                    : BridgeClient.submitTask(config, goal);
            taskSubmissionInFlight = false;
            renderTaskStatus(status);
            // A pause/cancel click can legitimately happen before this
            // request returns. Apply that intent before polling can dispatch.
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
            updateOnUi(() -> {
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
            boolean receiptPending = pendingReceipt != null
                    && (!pendingReceiptWaitsForControl || pendingControlCommand == null);
            if (receiptPending) {
                try {
                    JSONObject pending = pendingReceipt;
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
                    pauseAfterBridgeFailure("receipt upload failed — " + exception.getMessage());
                    return;
                }
            }
            try {
                JSONObject status = BridgeClient.taskStatus(config);
                renderTaskStatus(status);
                String state = status.optString("state", "FAILED");
                JSONObject next = status.optJSONObject("next_action");
                boolean controlRequested = runEpoch != taskControlEpoch || pendingControlCommand != null;
                if ("RUNNING".equals(state) && next != null && pendingReceipt == null && !controlRequested) {
                    String actionId = next.optString("action_id", "");
                    if (!actionId.isEmpty() && !actionId.equals(dispatchedActionId)) {
                        dispatchedActionId = actionId;
                        // Re-read the bridge state at the delivery boundary;
                        // the user may have pressed pause/cancel while the
                        // first status request was in flight.
                        JSONObject beforeDispatch = BridgeClient.taskStatus(config);
                        if (runEpoch == taskControlEpoch
                                && pendingControlCommand == null
                                && "RUNNING".equals(beforeDispatch.optString("state", ""))
                                && beforeDispatch.optJSONObject("next_action") != null) {
                            executeNodeAction(config, actionToken, beforeDispatch.optJSONObject("next_action"));
                        }
                    }
                }
                if ("SUCCEEDED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state)) {
                    if (pendingReceipt == null && !actionInFlight
                            && !status.optBoolean("action_result_unknown", false)) {
                        taskRunnerActive = false;
                        vlmTaskActive = false;
                        ObservationAccessibilityService.endTaskCapture();
                        setTaskButtons(false, state);
                        return;
                    }
                }
                if ("PAUSED".equals(state) && pendingReceipt == null && !actionInFlight) {
                    taskRunnerActive = false;
                    vlmTaskActive = false;
                    ObservationAccessibilityService.endTaskCapture();
                    setTaskButtons(false, state);
                    return;
                }
                Thread.sleep(300L);
            } catch (BridgeClient.BridgeException exception) {
                pauseAfterBridgeFailure("bridge unavailable — " + exception.getMessage());
                return;
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
        String actionId = action == null ? "" : action.optString("action_id", "");
        if (!DeviceActionLedger.beginAction(this, config, action)) {
            actionInFlight = false;
            pauseAfterBridgeFailure("local action record could not be committed; action was not sent");
            return;
        }
        try {
            JSONObject intent = BridgeClient.commandIntent(config, actionId);
            if (!intent.optBoolean("intent_recorded", false)
                    || !config.taskId.equals(intent.optString("task_id", ""))
                    || !actionId.equals(intent.optString("action_id", ""))
                    || !config.deviceSessionId.equals(intent.optString("device_session_id", ""))
                    || !"UNKNOWN".equals(intent.optString("recovery_outcome", ""))) {
                throw new BridgeClient.BridgeException(
                        0, "command_intent_not_confirmed", "bridge did not confirm this action intent");
            }
        } catch (BridgeClient.BridgeException exception) {
            actionInFlight = false;
            pauseAfterBridgeFailure("command intent failed; no action was dispatched — " + exception.getMessage());
            return;
        }
        ObservationAccessibilityService.executeAction(config, action, actionToken,
                new ObservationAccessibilityService.ActionCallback() {
            @Override
            public void onSuccess() {
                // Capture the postcondition frame before publishing the
                // accepted receipt.  The acknowledgement supplies an
                // explicit after-observation association for the bridge.
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
                        // Keep the receipt truthful about execution; the
                        // fallback capture after receipt remains available.
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

    private void recordPendingReceipt(JSONObject receipt) {
        recordPendingReceipt(receipt, false);
    }

    private void recordPendingReceipt(JSONObject receipt, boolean waitsForControl) {
        String actionId = receipt == null ? "" : receipt.optString("action_id", "");
        if (receipt == null || !DeviceActionLedger.recordReceipt(
                this, configTaskId(), actionId, receipt)) {
            actionInFlight = false;
            pauseAfterBridgeFailure("local receipt could not be committed; device outcome remains UNKNOWN");
            return;
        }
        pendingReceiptWaitsForControl = waitsForControl;
        // Publish the wait flag before the volatile receipt reference so the
        // polling worker cannot observe a controlled receipt as immediately
        // sendable.
        pendingReceipt = receipt;
        actionInFlight = false;
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
                updateOnUi(() -> taskStatus.setText("Task: post-action observation pending — " + message));
            }
        });
    }

    /** Capture the next VLM before frame while retaining the task reservation. */
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
                                // The bridge runner is waiting on this exact
                                // observation id/version and screenshot id.
                            }

                            @Override
                            public void onError(String code, String message) {
                                updateOnUi(() -> taskStatus.setText("Task: next VLM screenshot unavailable — " + message));
                            }
                        });
            }

            @Override
            public void onError(String code, String message) {
                updateOnUi(() -> taskStatus.setText("Task: next VLM observation unavailable — " + message));
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
            updateOnUi(() -> taskStatus.setText("Task: could not build execution receipt — " + exception.getMessage()));
            return null;
        }
    }

    private void controlNodeTask(String command) {
        final BridgeConfig config = activeTaskConfig == null ? readConfig() : activeTaskConfig;
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
                // Submit/control can cross on the wire.  Keep the local epoch
                // cancelled and let the submit worker apply this intent once
                // its task has been created instead of allowing an action.
                if (exception.status == 404 && taskSubmissionInFlight) {
                    return;
                }
                pauseAfterBridgeFailure(command + " failed — " + exception.getMessage());
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
            updateOnUi(() -> taskStatus.setText("Task: " + command + " failed — " + exception.getMessage()));
            return null;
        }
    }

    private void renderTaskStatus(JSONObject status) {
        if (status == null) {
            return;
        }
        String state = status.optString("state", "UNKNOWN");
        taskResultUnknown = status.optBoolean("action_result_unknown", false);
        if (("SUCCEEDED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state))
                && !taskResultUnknown) {
            DeviceActionLedger.clearTask(this, configTaskId());
            taskResultUnknown = DeviceActionLedger.task(this, configTaskId()) != null;
        }
        String phase = status.optString("phase", "");
        StringBuilder message = new StringBuilder("Task: ").append(state);
        String mode = status.optString("mode", "deterministic");
        message.append("\nmode=").append(mode);
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
        updateOnUi(() -> {
            taskStatus.setText(message.toString());
            taskInput.setEnabled(DeviceActionLedger.activeTask(this) == null);
            setTaskButtons(taskRunnerActive, state);
            refreshRecoveryButtons();
        });
    }

    private String configTaskId() {
        BridgeConfig config = activeTaskConfig;
        return config == null ? BridgeConfig.load(this).taskId : config.taskId;
    }

    private void restoreRecoveryEntry() {
        DeviceActionLedger.TaskRecord local = DeviceActionLedger.activeTask(this);
        if (local == null) {
            taskInput.setEnabled(true);
            setRecoveryMessage("no interrupted task recorded on this device");
        } else {
            taskInput.setText(local.taskId);
            taskInput.setEnabled(false);
            setRecoveryMessage("local task record found; reconnect, then tap Reconcile before resume");
            taskResultUnknown = true;
            setTaskButtons(false, "PAUSED");
        }
        refreshRecoveryButtons();
    }

    private void refreshRecoveryButtons() {
        if (reconcileTaskButton == null || resumeTaskButton == null) {
            return;
        }
        runOnUiThread(() -> {
            BridgeConfig config = readConfig();
            boolean hasTask = DeviceActionLedger.activeTask(this) != null;
            reconcileTaskButton.setEnabled(hasTask && !config.deviceSessionId.isEmpty()
                    && !recoveryInFlight && !taskRunnerActive && !taskSubmissionInFlight && !actionInFlight);
            resumeTaskButton.setEnabled(canResumeRecovery(config));
        });
    }

    private boolean canResumeRecovery(BridgeConfig config) {
        JSONObject recovery = reconciledRecovery;
        DeviceActionLedger.TaskRecord local = DeviceActionLedger.task(this, config.taskId);
        if (recovery == null || local == null || recoveryInFlight
                || !"READY_TO_RESUME".equals(recovery.optString("phase", ""))
                || !recovery.optBoolean("eligible", false)
                || recovery.optString("resume_token", "").isEmpty()
                || recovery.optInt("observation_version", 0) <= 0
                || config.deviceSessionId.isEmpty()
                || !config.deviceSessionId.equals(reconciledSessionId)
                || !config.deviceSessionId.equals(recovery.optString("device_session_id", ""))) {
            return false;
        }
        return !local.vlm || (!config.modelProvider.isEmpty()
                && !config.modelEndpoint.isEmpty()
                && !config.modelName.isEmpty()
                && !config.modelApiKey.isEmpty());
    }

    private void reconcileTask() {
        final BridgeConfig config = readConfig();
        if (config.deviceSessionId.isEmpty()) {
            setRecoveryMessage("pair this device before reconciliation");
            return;
        }
        if (taskRunnerActive || taskSubmissionInFlight || actionInFlight) {
            setRecoveryMessage("wait for the in-flight task action to settle before reconciliation");
            return;
        }
        if (DeviceActionLedger.task(this, config.taskId) == null) {
            setRecoveryMessage("no local task record is available for this task id");
            return;
        }
        recoveryInFlight = true;
        clearReconciledRecovery();
        actionExecutionGate.invalidate();
        ++taskControlEpoch;
        taskRunnerActive = false;
        vlmTaskActive = false;
        pendingReceipt = null;
        pendingReceiptWaitsForControl = false;
        ObservationAccessibilityService.endTaskCapture();
        setRecoveryMessage("loading server status and capturing a fresh BEFORE frame…");
        refreshRecoveryButtons();
        taskExecutor.execute(() -> {
            try {
                JSONObject current = BridgeClient.recovery(config);
                String actionId = current.isNull("action_id") ? "" : current.optString("action_id", "");
                JSONObject deviceRecord = DeviceActionLedger.deviceRecord(this, config.taskId, actionId);
                ObservationAccessibilityService.beginTaskCapture(config,
                        new ObservationAccessibilityService.CaptureCallback() {
                    @Override
                    public void onSuccess(JSONObject acknowledgement) {
                        ObservationAccessibilityService.requestScreenshot(
                                config, acknowledgement, "BEFORE",
                                new ObservationAccessibilityService.ScreenshotCallback() {
                            @Override
                            public void onSuccess(JSONObject screenshotAcknowledgement) {
                                taskExecutor.execute(() -> {
                                    try {
                                        JSONObject result = BridgeClient.reconcile(config, deviceRecord);
                                        String state = result.optString("state", "");
                                        String phase = result.optString("phase", "");
                                        String outcome = result.optString("action_outcome", "");
                                        if (("SUCCEEDED".equals(state) || "FAILED".equals(state)
                                                || "CANCELLED".equals(state))
                                                && "NOT_REQUIRED".equals(phase)
                                                && !"UNKNOWN".equals(outcome)) {
                                            DeviceActionLedger.clearTask(MainActivity.this, config.taskId);
                                            taskResultUnknown = DeviceActionLedger.task(MainActivity.this, config.taskId) != null;
                                        }
                                        reconciledRecovery = result;
                                        reconciledSessionId = config.deviceSessionId;
                                        recoveryInFlight = false;
                                        runOnUiThread(() -> {
                                            setRecoveryMessage(recoverySummary(result));
                                            taskInput.setEnabled(DeviceActionLedger.activeTask(MainActivity.this) == null);
                                            setTaskButtons(false, result.optString("state", "PAUSED"));
                                            refreshRecoveryButtons();
                                        });
                                    } catch (BridgeClient.BridgeException exception) {
                                        failRecovery("reconciliation failed — " + exception.getMessage());
                                    }
                                });
                            }

                            @Override
                            public void onError(String code, String message) {
                                ObservationAccessibilityService.endTaskCapture();
                                failRecovery("fresh BEFORE screenshot failed; no reconciliation was sent — " + message);
                            }
                        });
                    }

                    @Override
                    public void onError(String code, String message) {
                        ObservationAccessibilityService.endTaskCapture();
                        failRecovery("fresh observation failed; no reconciliation was sent — " + message);
                    }
                });
            } catch (BridgeClient.BridgeException exception) {
                failRecovery("server recovery status unavailable — " + exception.getMessage());
            }
        });
    }

    private void confirmRecoveryResume() {
        final BridgeConfig config = readConfig();
        final JSONObject confirmedStatus = reconciledRecovery;
        final DeviceActionLedger.TaskRecord local = DeviceActionLedger.task(this, config.taskId);
        if (!canResumeRecovery(config) || confirmedStatus == null || local == null) {
            setRecoveryMessage("eligibility or session changed; reconcile again before confirming");
            clearReconciledRecovery();
            refreshRecoveryButtons();
            return;
        }
        recoveryInFlight = true;
        refreshRecoveryButtons();
        setRecoveryMessage("capturing a fresh observation and BEFORE screenshot before explicit resume…");
        ObservationAccessibilityService.beginTaskCapture(config,
                new ObservationAccessibilityService.CaptureCallback() {
            @Override
            public void onSuccess(JSONObject acknowledgement) {
                ObservationAccessibilityService.requestScreenshot(
                        config, acknowledgement, "BEFORE",
                        new ObservationAccessibilityService.ScreenshotCallback() {
                    @Override
                    public void onSuccess(JSONObject screenshotAcknowledgement) {
                        taskExecutor.execute(() -> {
                            try {
                                JSONObject latest = BridgeClient.recovery(config);
                                if (!sameResumeWindow(confirmedStatus, latest, config)) {
                                    failRecovery("resume token, observation version, or device session changed; reconcile again");
                                    return;
                                }
                                // This API call is reachable only from the user's Confirm resume button.
                                JSONObject response = BridgeClient.resume(
                                        config,
                                        true,
                                        confirmedStatus.optString("resume_token", ""),
                                        confirmedStatus.optInt("observation_version", 0),
                                        local.vlm);
                                JSONObject task = response.optJSONObject("task");
                                JSONObject recovery = response.optJSONObject("recovery");
                                String resumedState = task == null ? "" : task.optString("state", "");
                                JSONObject verification = task == null ? null : task.optJSONObject("verification");
                                JSONObject independent = task == null ? null : task.optJSONObject("independent_result");
                                boolean confirmedTerminalSuccess = recovery != null
                                        && "SUCCEEDED".equals(resumedState)
                                        && "SUCCEEDED".equals(task.optString("phase", ""))
                                        && verification != null && "SUCCESS".equals(verification.optString("status", ""))
                                        && independent != null && independent.optBoolean("success", false)
                                        && "goal_already_confirmed_by_current_observation".equals(recovery.optString("reason", ""))
                                        && !recovery.optBoolean("eligible", true);
                                if (task == null || recovery == null
                                        || (!"RUNNING".equals(resumedState) && !confirmedTerminalSuccess)
                                        || !config.taskId.equals(task.optString("task_id", ""))
                                        || !config.deviceId.equals(task.optString("device_id", ""))
                                        || !config.taskId.equals(recovery.optString("task_id", ""))
                                        || !"RESUMED".equals(recovery.optString("phase", ""))
                                        || !recovery.optBoolean("confirmed", false)
                                        || !config.deviceSessionId.equals(recovery.optString("device_session_id", ""))) {
                                    failRecovery("server did not confirm a matched resumed task");
                                    return;
                                }
                                String actionId = latest.isNull("action_id") ? "" : latest.optString("action_id", "");
                                if ("NOT_EXECUTED".equals(latest.optString("action_outcome", "")) && !actionId.isEmpty()) {
                                    DeviceActionLedger.clearKnownNotExecutedAction(MainActivity.this, config.taskId, actionId);
                                }
                                activeTaskConfig = config;
                                if (confirmedTerminalSuccess) {
                                    actionExecutionGate.invalidate();
                                    ++taskControlEpoch;
                                    taskRunnerActive = false;
                                    vlmTaskActive = false;
                                    taskSubmissionInFlight = false;
                                    pendingControlCommand = null;
                                    pendingReceipt = null;
                                    pendingReceiptWaitsForControl = false;
                                    actionInFlight = false;
                                    dispatchedActionId = null;
                                    if (!DeviceActionLedger.clearTask(MainActivity.this, config.taskId)) {
                                        renderTaskStatus(task);
                                        failRecovery("goal completion was confirmed, but local recovery history could not be cleared");
                                        return;
                                    }
                                    taskResultUnknown = false;
                                    reconciledRecovery = null;
                                    reconciledSessionId = null;
                                    recoveryInFlight = false;
                                    ObservationAccessibilityService.endTaskCapture();
                                    renderTaskStatus(task);
                                    runOnUiThread(() -> {
                                        setRecoveryMessage("goal already completed; confirmed by a fresh independent observation");
                                        refreshRecoveryButtons();
                                    });
                                    return;
                                }
                                vlmTaskActive = local.vlm;
                                taskSubmissionInFlight = false;
                                pendingControlCommand = null;
                                pendingReceipt = null;
                                pendingReceiptWaitsForControl = false;
                                actionInFlight = false;
                                dispatchedActionId = null;
                                long runEpoch = ++taskControlEpoch;
                                ActionExecutionGate.Token actionToken = actionExecutionGate.begin();
                                taskRunnerActive = true;
                                reconciledRecovery = null;
                                reconciledSessionId = null;
                                recoveryInFlight = false;
                                renderTaskStatus(task);
                                runOnUiThread(() -> {
                                    setRecoveryMessage("RESUMED after explicit confirmation");
                                    refreshRecoveryButtons();
                                });
                                taskLoopExecutor.execute(() -> runNodeTaskLoop(config, runEpoch, actionToken));
                            } catch (BridgeClient.BridgeException exception) {
                                pauseAfterBridgeFailure("explicit resume failed — " + exception.getMessage());
                                recoveryInFlight = false;
                                refreshRecoveryButtons();
                            }
                        });
                    }

                    @Override
                    public void onError(String code, String message) {
                        ObservationAccessibilityService.endTaskCapture();
                        failRecovery("fresh BEFORE screenshot failed; no resume was sent — " + message);
                    }
                });
            }

            @Override
            public void onError(String code, String message) {
                ObservationAccessibilityService.endTaskCapture();
                failRecovery("fresh observation failed; no resume was sent — " + message);
            }
        });
    }

    private boolean sameResumeWindow(JSONObject confirmed, JSONObject latest, BridgeConfig config) {
        return "READY_TO_RESUME".equals(latest.optString("phase", ""))
                && latest.optBoolean("eligible", false)
                && !latest.optString("resume_token", "").isEmpty()
                && confirmed.optString("resume_token", "").equals(latest.optString("resume_token", ""))
                && confirmed.optInt("observation_version", 0) == latest.optInt("observation_version", 0)
                && config.deviceSessionId.equals(latest.optString("device_session_id", ""))
                && config.deviceSessionId.equals(reconciledSessionId);
    }

    private String recoverySummary(JSONObject recovery) {
        StringBuilder message = new StringBuilder("phase=")
                .append(recovery.optString("phase", "UNKNOWN"))
                .append(" state=").append(recovery.optString("state", "UNKNOWN"))
                .append(" eligible=").append(recovery.optBoolean("eligible", false))
                .append(" confirmed=").append(recovery.optBoolean("confirmed", false));
        if (!recovery.isNull("reason")) {
            message.append("\nreason=").append(String.valueOf(recovery.opt("reason")));
        }
        if (!recovery.isNull("action_id")) {
            message.append("\naction_id=").append(recovery.optString("action_id", ""));
        }
        if (!recovery.isNull("action_outcome")) {
            message.append(" outcome=").append(recovery.optString("action_outcome", ""));
        }
        JSONObject effect = recovery.optJSONObject("actual_effect");
        if (effect != null) {
            message.append("\nactual_effect=").append(effect.optString("status", "UNKNOWN"));
        }
        JSONObject independent = recovery.optJSONObject("independent_result");
        if (independent != null) {
            message.append(" independent_result=").append(independent.optString("status", "UNKNOWN"));
        }
        if ("READY_TO_RESUME".equals(recovery.optString("phase", ""))
                && !recovery.optBoolean("eligible", false)) {
            message.append("\nResume unavailable: server did not mark this task eligible");
        }
        return message.toString();
    }

    private void clearReconciledRecovery() {
        reconciledRecovery = null;
        reconciledSessionId = null;
    }

    private void setRecoveryMessage(String message) {
        if (recoveryStatus == null) {
            return;
        }
        String value = "Recovery status: " + (message == null ? "unknown" : message);
        recoveryStatus.setText(value);
        recoveryStatus.setContentDescription(value);
    }

    private void failRecovery(String message) {
        recoveryInFlight = false;
        clearReconciledRecovery();
        runOnUiThread(() -> {
            setRecoveryMessage(message);
            refreshRecoveryButtons();
        });
    }

    private void pauseAfterBridgeFailure(String message) {
        actionExecutionGate.invalidate();
        ++taskControlEpoch;
        taskRunnerActive = false;
        vlmTaskActive = false;
        pendingReceipt = null;
        pendingReceiptWaitsForControl = false;
        pendingControlCommand = null;
        taskSubmissionInFlight = false;
        ObservationAccessibilityService.endTaskCapture();
        clearReconciledRecovery();
        runOnUiThread(() -> {
            taskStatus.setText("Task: paused for device reconciliation — " + message);
            setTaskButtons(false, "PAUSED");
            setRecoveryMessage("local task/action history retained; reconcile before resuming");
            refreshRecoveryButtons();
        });
    }

    private void setTaskButtons(boolean active, String state) {
        if (startTaskButton == null) {
            return;
        }
        boolean terminal = "SUCCEEDED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state);
        boolean paused = "PAUSED".equals(state);
        // A paused task remains the same server task. Starting here would
        // submit a second task with the same identity instead of resuming it.
        startTaskButton.setEnabled(!active && !taskResultUnknown && (terminal || "".equals(state)));
        if (startVlmTaskButton != null) {
            startVlmTaskButton.setEnabled(!active && !taskResultUnknown && (terminal || "".equals(state)));
        }
        pauseTaskButton.setEnabled(active && "RUNNING".equals(state));
        cancelTaskButton.setEnabled(!terminal && (active || paused));
        refreshRecoveryButtons();
    }

    private void failConnection(String message, long generation) {
        actionExecutionGate.invalidate();
        ++taskControlEpoch;
        taskRunnerActive = false;
        vlmTaskActive = false;
        pendingReceipt = null;
        pendingReceiptWaitsForControl = false;
        clearReconciledRecovery();
        ObservationAccessibilityService.endTaskCapture();
        updateOnUi(() -> {
            if (generation != observationGeneration) {
                return;
            }
            advanceObservationGeneration();
            BridgeConfig.load(this).save(this, false);
            connectionStatus.setText("Connection: " + message);
            clearCurrent("Current observation: cleared; no fresh server state is available");
            connectButton.setEnabled(true);
            updatePermissionStatus();
            if (DeviceActionLedger.task(this, BridgeConfig.load(this).taskId) != null) {
                taskResultUnknown = true;
                taskStatus.setText("Task: paused for device reconciliation after connection failure");
                setTaskButtons(false, "PAUSED");
            }
            setRecoveryMessage("connection failed; local action history is retained for manual reconciliation");
            refreshRecoveryButtons();
        });
    }

    private long advanceObservationGeneration() {
        return ++observationGeneration;
    }

    private boolean updatePermissionStatus() {
        boolean enabled = ObservationAccessibilityService.isEnabled(this);
        boolean running = ObservationAccessibilityService.isRunning();
        String state;
        if (!enabled) {
            state = "unavailable — enable Jev observation service";
        } else if (running) {
            state = "enabled and running";
        } else {
            state = "enabled; waiting for service";
        }
        permissionStatus.setText("Accessibility permission: " + state);
        return enabled;
    }

    private void clearCurrent(String text) {
        if (currentObservation != null) {
            currentObservation.setText(text);
        }
    }

    private void updateOnUi(Runnable runnable) {
        runOnUiThread(runnable);
    }

    private TextView label(String text, int size, int color) {
        TextView result = new TextView(this);
        result.setText(text);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setPadding(0, dp(4), 0, dp(4));
        return result;
    }

    private TextView statusLabel(String text) {
        TextView result = label(text, 15, Color.DKGRAY);
        result.setGravity(Gravity.START);
        return result;
    }

    private EditText input(String hint, boolean password) {
        EditText result = new EditText(this);
        result.setHint(hint);
        result.setSingleLine(true);
        if (password) {
            result.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        return result;
    }

    private LinearLayout.LayoutParams widthMatchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams marginTop(LinearLayout.LayoutParams params, int margin) {
        params.topMargin = dp(margin);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
