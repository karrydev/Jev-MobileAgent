package com.jev.mobileagent;

import android.content.Context;

/** One-shot screenshot failure used only by the visible Debug verification fixture. */
final class DebugTreeVerificationFixtures {
    private static final String PREFS = "debug_tree_verification_fixtures_v1";
    private static final String TASK_ID = "fail_after_screenshot_task_id";

    private DebugTreeVerificationFixtures() {
    }

    static void armAfterScreenshotFailure(Context context, String taskId) {
        if (!BuildConfig.DEBUG || context == null || taskId == null || taskId.isEmpty()) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(TASK_ID, taskId).commit();
    }

    static boolean consumeAfterScreenshotFailure(Context context, String taskId, String captureType) {
        if (!BuildConfig.DEBUG || context == null || taskId == null || taskId.isEmpty()
                || !"AFTER".equals(captureType)) return false;
        android.content.SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!taskId.equals(preferences.getString(TASK_ID, ""))) return false;
        return preferences.edit().remove(TASK_ID).commit();
    }

    static void clearForTask(Context context, String taskId) {
        if (!BuildConfig.DEBUG || context == null || taskId == null || taskId.isEmpty()) return;
        android.content.SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (taskId.equals(preferences.getString(TASK_ID, ""))) {
            preferences.edit().remove(TASK_ID).commit();
        }
    }
}
