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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal pairing and observation status screen for the emulator acceptance task. */
public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText endpointInput;
    private EditText tokenInput;
    private EditText deviceInput;
    private EditText taskInput;
    private TextView connectionStatus;
    private TextView permissionStatus;
    private TextView currentObservation;
    private Button connectButton;
    private volatile boolean foregroundRefreshInFlight;
    private volatile long observationGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
        loadConfigIntoForm();
        updatePermissionStatus();
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
        executor.shutdownNow();
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
        controlledPage.setOnClickListener(v -> startActivity(new Intent(this, ControlledPageActivity.class)));
        root.addView(controlledPage, widthMatchWrap());

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
    }

    private BridgeConfig readConfig() {
        return new BridgeConfig(
                endpointInput.getText().toString(),
                tokenInput.getText().toString(),
                deviceInput.getText().toString(),
                taskInput.getText().toString());
    }

    private void connectAndCapture() {
        final long generation = advanceObservationGeneration();
        final BridgeConfig config = readConfig();
        config.save(this, false);
        clearCurrent("Current observation: none; waiting for a fresh capture");
        connectButton.setEnabled(false);
        connectionStatus.setText("Connection: pairing with bridge…");
        executor.execute(() -> {
            try {
                BridgeClient.pair(config);
                boolean permission = ObservationAccessibilityService.isEnabled(this);
                if (!permission) {
                    sendUnavailableThenRead(
                            config,
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
                    config.save(this, true);
                    connectionStatus.setText("Connection: paired; capturing real Accessibility tree…");
                    ObservationAccessibilityService.requestCapture(config, new ObservationAccessibilityService.CaptureCallback() {
                        @Override
                        public void onSuccess(JSONObject acknowledgement) {
                            readLatest(config, generation);
                        }

                        @Override
                        public void onError(String code, String message) {
                            if ("permission_unavailable".equals(code)) {
                                sendUnavailableThenRead(config, message, false, false, generation);
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

    private void failConnection(String message, long generation) {
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
