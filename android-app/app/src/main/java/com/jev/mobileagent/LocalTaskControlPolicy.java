package com.jev.mobileagent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Safety rules for releasing app-local tasks after a control request. */
final class LocalTaskControlPolicy {
    enum ExecutionFact {
        NOT_EXECUTED,
        EXECUTED,
        UNKNOWN
    }

    private LocalTaskControlPolicy() {
    }

    /** Classify device execution from the durable intent/receipt order, not the postcondition label. */
    static ExecutionFact executionFact(JSONObject task, JSONObject action) {
        if (action == null) return ExecutionFact.UNKNOWN;
        String phase = action.optString("phase", "pending");
        JSONObject receipt = action.optJSONObject("result");
        if (receipt != null && receipt.optBoolean("success", false)) {
            return ExecutionFact.EXECUTED;
        }
        if ("not_dispatched".equals(phase)) return ExecutionFact.NOT_EXECUTED;
        JSONObject fault = task == null ? null : task.optJSONObject("debug_recovery_fault");
        if (fault != null
                && "fired".equals(fault.optString("status", ""))
                && "BEFORE_DISPATCH".equals(fault.optString("point", ""))
                && action.optString("action_id", "").equals(fault.optString("action_id", ""))) {
            return ExecutionFact.NOT_EXECUTED;
        }
        return ExecutionFact.UNKNOWN;
    }

    static boolean allExecutionFactsKnown(JSONObject task) {
        JSONArray actions = task == null ? null : task.optJSONArray("actions");
        if (actions == null) return true;
        for (int i = 0; i < actions.length(); i++) {
            if (executionFact(task, actions.optJSONObject(i)) == ExecutionFact.UNKNOWN) return false;
        }
        return true;
    }

    static boolean allowsRecoveryDecision(String decision, JSONObject task, JSONObject review,
            String confirmationGoalOutcome) {
        if ("resume".equals(decision)) {
            return allExecutionFactsKnown(task) && !hasUnresolvedDeviceAction(task)
                    && (review == null || !"VERIFIED".equals(review.optString("goal_outcome", "")))
                    && !"VERIFIED".equals(confirmationGoalOutcome);
        }
        if ("end".equals(decision)) {
            return allExecutionFactsKnown(task);
        }
        if ("complete_goal".equals(decision)) {
            return review != null
                    && "VERIFIED".equals(review.optString("goal_outcome", ""))
                    && "VERIFIED".equals(confirmationGoalOutcome);
        }
        return false;
    }

    /** Stable UI scene identity: excludes observation IDs, versions and capture timestamps. */
    static String sceneFingerprint(JSONObject observation) {
        return sceneFingerprint(observation, "");
    }

    static String sceneFingerprint(JSONObject observation, String screenshotFingerprint) {
        if (observation == null) return "";
        StringBuilder stable = new StringBuilder();
        append(stable, observation, "availability", "page_state");
        JSONObject screen = observation.optJSONObject("screen");
        append(stable, screen, "width_px", "height_px", "rotation", "system_bar_insets",
                "window_offset", "content_width_px", "content_height_px", "active_window_bounds");
        String activePackage = activeApplicationPackage(observation);
        stable.append("active_application=").append(activePackage).append('|');
        appendPackageArray(stable, observation.optJSONArray("windows"), new String[] {
                "window_type", "title", "package_name", "active", "focused", "bounds"
        }, activePackage);
        appendPackageArray(stable, observation.optJSONArray("nodes"), new String[] {
                "package_name", "class_name", "text", "content_description", "state_description",
                "view_id_resource_name", "enabled", "visible_to_user", "clickable", "focusable",
                "focused", "selected", "scrollable", "editable", "bounds"
        }, activePackage);
        stable.append("screenshot=").append(screenshotFingerprint == null ? "" : screenshotFingerprint);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(stable.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static boolean sameReviewedScene(JSONObject task, JSONObject currentObservation,
            String screenshotFingerprint) {
        JSONObject review = task == null ? null : task.optJSONObject("recovery_review");
        return review != null
                && review.optBoolean("valid", false)
                && task.optString("goal", "").equals(review.optString("goal", ""))
                && !review.optString("scene_fingerprint", "").isEmpty()
                && review.optString("scene_fingerprint", "")
                        .equals(sceneFingerprint(currentObservation, screenshotFingerprint));
    }

    static String activeApplicationPackage(JSONObject observation) {
        JSONObject active = activeWindow(observation);
        return active != null && active.optInt("window_type", -1) == 1
                ? active.optString("package_name", "") : "";
    }

    static boolean isTargetApplicationForeground(JSONObject observation, String targetPackage) {
        return observation != null
                && "AVAILABLE".equals(observation.optString("availability", ""))
                && targetPackage != null && !targetPackage.isEmpty()
                && targetPackage.equals(activeApplicationPackage(observation))
                && !hasNotificationShadeOverlay(observation, targetPackage);
    }

    /** Only a confirmed full notification-shade window is eligible for dismissal. */
    static boolean isNotificationShade(JSONObject observation, String targetPackage) {
        JSONObject active = activeWindow(observation);
        return active != null && active.optInt("window_type", -1) == 3
                && "com.android.systemui".equals(active.optString("package_name", ""))
                && isNotificationShadeWindow(active)
                && hasTargetApplicationWindow(observation, targetPackage)
                && coversMostDisplay(active, observation.optJSONObject("screen"));
    }

    private static JSONObject activeWindow(JSONObject observation) {
        if (observation == null) return null;
        JSONArray windows = observation.optJSONArray("windows");
        if (windows == null) return null;
        JSONObject screen = observation.optJSONObject("screen");
        int activeId = screen == null ? -1 : screen.optInt("active_window_id", -1);
        for (int i = 0; i < windows.length(); i++) {
            JSONObject window = windows.optJSONObject(i);
            if (window != null && window.optInt("window_id", -2) == activeId
                    && window.optBoolean("active", false) && window.optBoolean("focused", false)) {
                return window;
            }
        }
        for (int i = 0; i < windows.length(); i++) {
            JSONObject window = windows.optJSONObject(i);
            if (window != null && window.optBoolean("active", false)
                    && window.optBoolean("focused", false)) return window;
        }
        return null;
    }

    private static boolean hasNotificationShadeOverlay(JSONObject observation, String targetPackage) {
        JSONArray windows = observation == null ? null : observation.optJSONArray("windows");
        JSONObject screen = observation == null ? null : observation.optJSONObject("screen");
        if (windows == null || screen == null) return false;
        int targetLayer = Integer.MIN_VALUE;
        for (int i = 0; i < windows.length(); i++) {
            JSONObject window = windows.optJSONObject(i);
            if (window != null && window.optInt("window_type", -1) == 1
                    && targetPackage.equals(window.optString("package_name", ""))) {
                targetLayer = window.optInt("layer", Integer.MIN_VALUE);
                break;
            }
        }
        if (targetLayer == Integer.MIN_VALUE) return false;
        for (int i = 0; i < windows.length(); i++) {
            JSONObject window = windows.optJSONObject(i);
            if (window != null && window.optInt("window_type", -1) == 3
                    && "com.android.systemui".equals(window.optString("package_name", ""))
                    && window.optInt("layer", Integer.MIN_VALUE) > targetLayer
                    && isNotificationShadeWindow(window) && coversMostDisplay(window, screen)) return true;
        }
        return false;
    }

    private static boolean hasTargetApplicationWindow(JSONObject observation, String targetPackage) {
        JSONArray windows = observation == null ? null : observation.optJSONArray("windows");
        if (windows == null || targetPackage == null || targetPackage.isEmpty()) return false;
        for (int i = 0; i < windows.length(); i++) {
            JSONObject window = windows.optJSONObject(i);
            if (window != null && window.optInt("window_type", -1) == 1
                    && targetPackage.equals(window.optString("package_name", ""))) return true;
        }
        return false;
    }

    private static boolean isNotificationShadeWindow(JSONObject window) {
        String identity = (window.optString("class_name", "") + " "
                + window.optString("title", "")).toLowerCase(Locale.ROOT);
        return identity.contains("notificationshad") || identity.contains("notification_shade");
    }

    private static boolean coversMostDisplay(JSONObject window, JSONObject screen) {
        JSONObject bounds = window.optJSONObject("bounds");
        if (bounds == null || screen == null) return false;
        int width = screen.optInt("width_px", 0);
        int height = screen.optInt("height_px", 0);
        int overlayWidth = bounds.optInt("right", 0) - bounds.optInt("left", 0);
        int overlayHeight = bounds.optInt("bottom", 0) - bounds.optInt("top", 0);
        return width > 0 && height > 0
                && overlayWidth >= width * 0.75 && overlayHeight >= height * 0.55;
    }

    static boolean hasUnresolvedDeviceAction(JSONObject task) {
        if (task == null) {
            return false;
        }
        JSONArray actions = task.optJSONArray("actions");
        if (actions == null) {
            return false;
        }
        for (int i = 0; i < actions.length(); i++) {
            JSONObject action = actions.optJSONObject(i);
            if (action == null) {
                continue;
            }
            String phase = action.optString("phase", "pending");
            if (!"verified".equals(phase) && !"reflected_failure".equals(phase)
                    && executionFact(task, action) != ExecutionFact.NOT_EXECUTED) {
                return true;
            }
        }
        return false;
    }

    private static void append(StringBuilder target, JSONObject object, String... keys) {
        if (object == null) {
            target.append("null|");
            return;
        }
        for (String key : keys) {
            target.append(key).append('=').append(object.opt(key)).append('|');
        }
    }

    private static void appendArray(StringBuilder target, JSONArray values, String[] keys) {
        if (values == null) {
            target.append("null-array|");
            return;
        }
        target.append('[');
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            append(target, value, keys);
        }
        target.append(']');
    }

    private static void appendPackageArray(StringBuilder target, JSONArray values, String[] keys,
            String packageName) {
        if (values == null) {
            target.append("null-array|");
            return;
        }
        target.append('[');
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value == null || packageName.isEmpty()
                    || !packageName.equals(value.optString("package_name", ""))) continue;
            append(target, value, keys);
        }
        target.append(']');
    }
}
