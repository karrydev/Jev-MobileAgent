package com.jev.mobileagent;

import android.content.Context;
import android.content.SharedPreferences;

/** Values shared by the visible activity and the bound accessibility service. */
public final class BridgeConfig {
    private static final String PREFS = "jev_android_observation";
    private static final String ENDPOINT = "endpoint";
    private static final String TOKEN = "token";
    private static final String DEVICE_ID = "device_id";
    private static final String TASK_ID = "task_id";
    private static final String MODEL_PROVIDER = "model_provider";
    private static final String MODEL_ENDPOINT = "model_endpoint";
    private static final String MODEL_NAME = "model_name";
    private static final String MODEL_API_KEY = "model_api_key";
    private static final String CAPTURE_ENABLED = "capture_enabled";
    private static final String OBSERVATION_VERSION = "observation_version";

    public final String endpoint;
    public final String token;
    public final String deviceId;
    public final String taskId;
    public final String modelProvider;
    public final String modelEndpoint;
    public final String modelName;
    public final String modelApiKey;

    public BridgeConfig(String endpoint, String token, String deviceId, String taskId) {
        this(endpoint, token, deviceId, taskId, "GUI-Plus", "", "", "");
    }

    public BridgeConfig(
            String endpoint,
            String token,
            String deviceId,
            String taskId,
            String modelProvider,
            String modelEndpoint,
            String modelName,
            String modelApiKey) {
        this.endpoint = trimTrailingSlash(endpoint);
        this.token = token == null ? "" : token.trim();
        this.deviceId = deviceId == null ? "" : deviceId.trim();
        this.taskId = taskId == null ? "" : taskId.trim();
        this.modelProvider = modelProvider == null ? "" : modelProvider.trim();
        this.modelEndpoint = trimTrailingSlash(modelEndpoint);
        this.modelName = modelName == null ? "" : modelName.trim();
        // Keep this value private to the config object and submit call.  Never
        // include it in status, observation, receipt, or trace payloads.
        this.modelApiKey = modelApiKey == null ? "" : modelApiKey;
    }

    public static BridgeConfig load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new BridgeConfig(
                preferences.getString(ENDPOINT, "http://10.0.2.2:8765"),
                preferences.getString(TOKEN, ""),
                preferences.getString(DEVICE_ID, "android-emulator-01"),
                preferences.getString(TASK_ID, "observation-session"),
                preferences.getString(MODEL_PROVIDER, "GUI-Plus"),
                preferences.getString(MODEL_ENDPOINT, "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"),
                preferences.getString(MODEL_NAME, "gui-plus-2026-02-26"),
                preferences.getString(MODEL_API_KEY, ""));
    }

    public void save(Context context, boolean captureEnabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(ENDPOINT, endpoint)
                .putString(TOKEN, token)
                .putString(DEVICE_ID, deviceId)
                .putString(TASK_ID, taskId)
                .putString(MODEL_PROVIDER, modelProvider)
                .putString(MODEL_ENDPOINT, modelEndpoint)
                .putString(MODEL_NAME, modelName)
                .putString(MODEL_API_KEY, modelApiKey)
                .putBoolean(CAPTURE_ENABLED, captureEnabled)
                .apply();
    }

    public static boolean captureEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(CAPTURE_ENABLED, false);
    }

    public static long nextObservationVersion(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long version = preferences.getLong(OBSERVATION_VERSION, 0L) + 1L;
        preferences.edit().putLong(OBSERVATION_VERSION, version).apply();
        return version;
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
