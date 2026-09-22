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
    private static final String CAPTURE_ENABLED = "capture_enabled";
    private static final String OBSERVATION_VERSION = "observation_version";

    public final String endpoint;
    public final String token;
    public final String deviceId;
    public final String taskId;

    public BridgeConfig(String endpoint, String token, String deviceId, String taskId) {
        this.endpoint = trimTrailingSlash(endpoint);
        this.token = token == null ? "" : token.trim();
        this.deviceId = deviceId == null ? "" : deviceId.trim();
        this.taskId = taskId == null ? "" : taskId.trim();
    }

    public static BridgeConfig load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new BridgeConfig(
                preferences.getString(ENDPOINT, "http://10.0.2.2:8765"),
                preferences.getString(TOKEN, ""),
                preferences.getString(DEVICE_ID, "android-emulator-01"),
                preferences.getString(TASK_ID, "observation-session"));
    }

    public void save(Context context, boolean captureEnabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(ENDPOINT, endpoint)
                .putString(TOKEN, token)
                .putString(DEVICE_ID, deviceId)
                .putString(TASK_ID, taskId)
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
