package com.jev.mobileagent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Safety rules for releasing app-local tasks after a control request. */
final class LocalTaskControlPolicy {
    private static final String LOCAL_APP_PACKAGE = "com.jev.mobileagent";
    private static final String LOCAL_TASK_STATUS_DESCRIPTION = "Local VLM task status";

    enum RecoveryConfirmationSample {
        MATCH,
        RESAMPLE,
        REJECT
    }

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

    /** Stable UI scene identity; excludes capture metadata and volatile text from the app's own status label. */
    static String sceneFingerprint(JSONObject observation) {
        return sceneFingerprint(observation, "");
    }

    static String sceneFingerprint(JSONObject observation, String screenshotFingerprint) {
        if (observation == null) return "";
        StringBuilder stable = new StringBuilder();
        append(stable, observation, "availability", "page_state");
        JSONObject screen = observation.optJSONObject("screen");
        append(stable, screen, "width_px", "height_px", "rotation", "system_bar_insets",
                "recovery_system_bar_insets",
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

    /** Compare the observable target scene while ignoring observation identity and screenshot-only caret noise. */
    static boolean sameDecisionScene(JSONObject expected, JSONObject current) {
        if (expected == null || current == null
                || !"AVAILABLE".equals(expected.optString("availability", ""))
                || !"AVAILABLE".equals(current.optString("availability", ""))) {
            return false;
        }
        String expectedPackage = activeApplicationPackage(expected);
        return !expectedPackage.isEmpty()
                && expectedPackage.equals(activeApplicationPackage(current))
                && sceneFingerprint(expected, "").equals(sceneFingerprint(current, ""));
    }

    static boolean matchesDecisionScene(String expectedFingerprint, JSONObject current) {
        return expectedFingerprint != null && !expectedFingerprint.isEmpty()
                && current != null && "AVAILABLE".equals(current.optString("availability", ""))
                && !activeApplicationPackage(current).isEmpty()
                && expectedFingerprint.equals(sceneFingerprint(current, ""));
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

    static boolean sameReviewedSemantics(JSONObject task, String expectedReviewObservationId,
            JSONObject currentObservation, String goalOutcome) {
        JSONObject review = task == null ? null : task.optJSONObject("recovery_review");
        if (review == null || !review.optBoolean("valid", false)
                || expectedReviewObservationId == null
                || !expectedReviewObservationId.equals(review.optString("observation_id", ""))
                || !task.optString("goal", "").equals(review.optString("goal", ""))
                || goalOutcome == null
                || !goalOutcome.equals(review.optString("goal_outcome", "UNKNOWN"))) {
            return false;
        }
        String targetPackage = review.optString("target_application_package", "");
        String reviewedSemantics = review.optString("semantic_fingerprint", "");
        return !targetPackage.isEmpty() && !reviewedSemantics.isEmpty()
                && recoveryTargetReadinessError(currentObservation, targetPackage).isEmpty()
                && reviewedSemantics.equals(sceneFingerprint(currentObservation, ""));
    }

    /**
     * Keep recovery confirmation pixel-exact. A semantic-only match can authorize another fresh sample,
     * but it can never authorize the decision itself.
     */
    static RecoveryConfirmationSample classifyRecoveryConfirmationSample(String decision,
            JSONObject task, String expectedReviewObservationId, JSONObject observation,
            String screenshotFingerprint, String goalOutcome, int sampleNumber, int maxSamples,
            boolean safetyGatesOpen) {
        JSONObject review = task == null ? null : task.optJSONObject("recovery_review");
        if (!safetyGatesOpen || task == null || review == null
                || !("PAUSED".equals(task.optString("state", ""))
                        || "NEEDS_REVIEW".equals(task.optString("state", "")))
                || !review.optBoolean("valid", false)
                || expectedReviewObservationId == null
                || !expectedReviewObservationId.equals(review.optString("observation_id", ""))
                || !task.optString("goal", "").equals(review.optString("goal", ""))
                || goalOutcome == null
                || !goalOutcome.equals(review.optString("goal_outcome", "UNKNOWN"))
                || screenshotFingerprint == null || screenshotFingerprint.isEmpty()
                || sampleNumber < 1 || maxSamples < sampleNumber
                || !allowsRecoveryDecision(decision, task, review, goalOutcome)) {
            return RecoveryConfirmationSample.REJECT;
        }
        String targetPackage = review.optString("target_application_package", "");
        if (targetPackage.isEmpty()
                || !recoveryTargetReadinessError(observation, targetPackage).isEmpty()) {
            return RecoveryConfirmationSample.REJECT;
        }
        if (sameReviewedScene(task, observation, screenshotFingerprint)) {
            return RecoveryConfirmationSample.MATCH;
        }
        if (!sameReviewedSemantics(task, expectedReviewObservationId, observation, goalOutcome)) {
            return RecoveryConfirmationSample.REJECT;
        }
        return sampleNumber < maxSamples
                ? RecoveryConfirmationSample.RESAMPLE : RecoveryConfirmationSample.REJECT;
    }

    static String activeApplicationPackage(JSONObject observation) {
        JSONObject active = activeWindow(observation);
        return active != null && active.optInt("window_type", -1) == 1
                ? active.optString("package_name", "") : "";
    }

    /**
     * A recovery target may advance only from a readable application observed by the active task loop.
     * Callers must not use this for review or confirmation observations.
     */
    static String trustedRunningTargetPackage(JSONObject task, JSONObject observation) {
        if (task == null || !"RUNNING".equals(task.optString("state", ""))
                || observation == null || !"AVAILABLE".equals(observation.optString("availability", ""))) {
            return "";
        }
        String activePackage = activeApplicationPackage(observation);
        return activePackage.isEmpty()
                || !isTargetApplicationForeground(observation, activePackage)
                ? "" : activePackage;
    }

    static String rememberRunningTargetPackage(JSONObject task, JSONObject observation) throws JSONException {
        String targetPackage = trustedRunningTargetPackage(task, observation);
        if (targetPackage.isEmpty()) return "";
        task.put("last_running_target_application_package", targetPackage)
                .put("target_application_package", targetPackage)
                .put("target_application_observation_id", observation.optString("observation_id", ""))
                .put("target_application_observation_version", observation.optLong("observation_version", 0L));
        return targetPackage;
    }

    static boolean isTargetApplicationForeground(JSONObject observation, String targetPackage) {
        return observation != null
                && "AVAILABLE".equals(observation.optString("availability", ""))
                && targetPackage != null && !targetPackage.isEmpty()
                && targetPackage.equals(activeApplicationPackage(observation))
                && !hasFullScreenSystemOverlayAboveTarget(observation, targetPackage);
    }

    /** Recovery accepts only a readable target window with no unrelated window covering it. */
    static String recoveryTargetReadinessError(JSONObject observation, String targetPackage) {
        if (observation == null || !"AVAILABLE".equals(observation.optString("availability", ""))) {
            return "observation_unavailable";
        }
        if (targetPackage == null || targetPackage.isEmpty()) return "recovery_target_unknown";
        JSONObject targetWindow = activeWindow(observation);
        if (targetWindow == null || targetWindow.optInt("window_type", -1) != 1
                || !targetPackage.equals(targetWindow.optString("package_name", ""))) {
            return "target_package_mismatch";
        }
        JSONObject targetBounds = targetWindow.optJSONObject("bounds");
        JSONArray windows = observation.optJSONArray("windows");
        if (targetBounds == null || windows == null) return "target_window_metadata_missing";
        int targetLayer = targetWindow.optInt("layer", Integer.MIN_VALUE);
        for (int i = 0; i < windows.length(); i++) {
            JSONObject window = windows.optJSONObject(i);
            if (window == null || window.optInt("window_id", -2)
                    == targetWindow.optInt("window_id", -1)
                    || window.optInt("layer", Integer.MIN_VALUE) <= targetLayer
                    || targetPackage.equals(window.optString("package_name", ""))) {
                continue;
            }
            if (isRecoverySystemBarWindow(observation, window)) continue;
            if (overlaps(window.optJSONObject("bounds"), targetBounds)) {
                return "target_window_covered";
            }
        }
        return "";
    }

    static boolean isStableRecoveryObservationPair(JSONObject previous, JSONObject current,
            String targetPackage) {
        return recoveryTargetReadinessError(previous, targetPackage).isEmpty()
                && recoveryTargetReadinessError(current, targetPackage).isEmpty()
                && sceneFingerprint(previous, "").equals(sceneFingerprint(current, ""));
    }

    static boolean shouldContinueRecoveryWindowWait(boolean cancelled, long elapsedMs,
            long timeoutMs) {
        return !cancelled && elapsedMs >= 0L && elapsedMs < timeoutMs;
    }

    /**
     * A notification shade can hide the target app's Accessibility window entirely.
     * Only an explicit, unlocked recovery may try the dedicated shade action, and only
     * when the active/focused system window covers the display.
     */
    static boolean shouldAttemptNotificationShadeDismiss(JSONObject observation,
            boolean explicitRecovery, boolean deviceUnlocked) {
        if (!explicitRecovery || !deviceUnlocked) return false;
        JSONObject active = activeWindow(observation);
        return active != null && active.optInt("window_type", -1) == 3
                && coversFullDisplay(active.optJSONObject("bounds"),
                        observation == null ? null : observation.optJSONObject("screen"));
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

    private static boolean hasFullScreenSystemOverlayAboveTarget(
            JSONObject observation, String targetPackage) {
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
                    && window.optInt("layer", Integer.MIN_VALUE) > targetLayer
                    && coversFullDisplay(window.optJSONObject("bounds"), screen)) return true;
        }
        return false;
    }

    private static boolean coversFullDisplay(JSONObject bounds, JSONObject screen) {
        if (bounds == null || screen == null) return false;
        int width = screen.optInt("width_px", 0);
        int height = screen.optInt("height_px", 0);
        return width > 0 && height > 0
                && bounds.optInt("left", Integer.MAX_VALUE) <= 0
                && bounds.optInt("top", Integer.MAX_VALUE) <= 0
                && bounds.optInt("right", Integer.MIN_VALUE) >= width
                && bounds.optInt("bottom", Integer.MIN_VALUE) >= height;
    }

    /** Display-coordinate crop for recovery images, using platform insets or a named edge-bar fallback. */
    static int[] targetScreenshotBounds(JSONObject observation) {
        JSONObject screen = observation == null ? null : observation.optJSONObject("screen");
        JSONObject activeBounds = screen == null ? null : screen.optJSONObject("active_window_bounds");
        if (screen == null || activeBounds == null) return null;
        int screenWidth = screen.optInt("width_px", 0);
        int screenHeight = screen.optInt("height_px", 0);
        int left = activeBounds.optInt("left", 0);
        int top = activeBounds.optInt("top", 0);
        int right = activeBounds.optInt("right", 0);
        int bottom = activeBounds.optInt("bottom", 0);
        if (screenWidth <= 0 || screenHeight <= 0 || right <= left || bottom <= top) return null;

        JSONObject insets = trustedRecoverySystemBarInsets(observation);
        if (insets != null) {
            left = Math.max(left, insets.optInt("left", 0));
            top = Math.max(top, insets.optInt("top", 0));
            right = Math.min(right, screenWidth - insets.optInt("right", 0));
            bottom = Math.min(bottom, screenHeight - insets.optInt("bottom", 0));
        } else {
            boolean identifiedBar = false;
            JSONArray windows = observation.optJSONArray("windows");
            if (windows != null) {
                for (int i = 0; i < windows.length(); i++) {
                    JSONObject window = windows.optJSONObject(i);
                    if (window == null || window.optInt("window_type", -1) != 3
                            || window.optBoolean("active", false) || window.optBoolean("focused", false)) {
                        continue;
                    }
                    int side = identifiedSystemBarSide(window, screen);
                    JSONObject bar = window.optJSONObject("bounds");
                    if (side == 0 || bar == null) continue;
                    identifiedBar = true;
                    if (side == 1 && left <= 0 && right >= screenWidth && top <= 0) {
                        top = Math.max(top, bar.optInt("bottom", top));
                    } else if (side == 2 && left <= 0 && right >= screenWidth && bottom >= screenHeight) {
                        bottom = Math.min(bottom, bar.optInt("top", bottom));
                    } else if (side == 3 && top <= 0 && bottom >= screenHeight && left <= 0) {
                        left = Math.max(left, bar.optInt("right", left));
                    } else if (side == 4 && top <= 0 && bottom >= screenHeight && right >= screenWidth) {
                        right = Math.min(right, bar.optInt("left", right));
                    }
                }
            }
            // With no platform insets and no clearly named bar, the screenshot cannot be safely cropped.
            if (!identifiedBar) return null;
        }
        return right > left && bottom > top ? new int[] {left, top, right, bottom} : null;
    }

    private static JSONObject trustedRecoverySystemBarInsets(JSONObject observation) {
        JSONObject screen = observation == null ? null : observation.optJSONObject("screen");
        JSONObject insets = screen == null ? null : screen.optJSONObject("recovery_system_bar_insets");
        if (insets == null || !insets.optBoolean("available", false)
                || !"window_metrics_system_bars".equals(insets.optString("source", ""))) return null;
        JSONObject metricsBounds = insets.optJSONObject("metrics_bounds_px");
        int width = screen.optInt("width_px", 0);
        int height = screen.optInt("height_px", 0);
        if (metricsBounds == null || width <= 0 || height <= 0
                || metricsBounds.optInt("left", Integer.MIN_VALUE) != 0
                || metricsBounds.optInt("top", Integer.MIN_VALUE) != 0
                || metricsBounds.optInt("right", Integer.MIN_VALUE) != width
                || metricsBounds.optInt("bottom", Integer.MIN_VALUE) != height) return null;
        int left = insets.optInt("left", -1);
        int top = insets.optInt("top", -1);
        int right = insets.optInt("right", -1);
        int bottom = insets.optInt("bottom", -1);
        if (left < 0 || top < 0 || right < 0 || bottom < 0
                || left >= width || right >= width || top >= height || bottom >= height) return null;
        return insets;
    }

    private static boolean isRecoverySystemBarWindow(JSONObject observation, JSONObject window) {
        if (window.optInt("window_type", -1) != 3
                || window.optBoolean("active", false) || window.optBoolean("focused", false)) return false;
        JSONObject screen = observation == null ? null : observation.optJSONObject("screen");
        if (screen == null) return false;
        JSONObject insets = trustedRecoverySystemBarInsets(observation);
        if (insets == null) return identifiedSystemBarSide(window, screen) != 0;
        JSONObject bounds = window.optJSONObject("bounds");
        if (bounds == null) return false;
        int width = screen.optInt("width_px", 0);
        int height = screen.optInt("height_px", 0);
        int left = bounds.optInt("left", Integer.MAX_VALUE);
        int top = bounds.optInt("top", Integer.MAX_VALUE);
        int right = bounds.optInt("right", Integer.MIN_VALUE);
        int bottom = bounds.optInt("bottom", Integer.MIN_VALUE);
        return (insets.optInt("top", 0) > 0 && left <= 0 && right >= width
                        && top <= 0 && bottom > 0 && bottom <= insets.optInt("top", 0))
                || (insets.optInt("bottom", 0) > 0 && left <= 0 && right >= width
                        && bottom >= height && top >= height - insets.optInt("bottom", 0))
                || (insets.optInt("left", 0) > 0 && top <= 0 && bottom >= height
                        && left <= 0 && right > 0 && right <= insets.optInt("left", 0))
                || (insets.optInt("right", 0) > 0 && top <= 0 && bottom >= height
                        && right >= width && left >= width - insets.optInt("right", 0));
    }

    /** Returns 1=top, 2=bottom, 3=left, 4=right for an explicitly named bar at that display edge. */
    private static int identifiedSystemBarSide(JSONObject window, JSONObject screen) {
        String identity = (window.optString("class_name", "") + " "
                + window.optString("title", "")).toLowerCase(Locale.ROOT);
        boolean statusBar = identity.contains("statusbar") || identity.contains("status_bar")
                || identity.contains("status bar") || identity.contains("状态栏");
        boolean navigationBar = identity.contains("navigationbar") || identity.contains("navigation_bar")
                || identity.contains("navigation bar") || identity.contains("导航栏");
        if (!statusBar && !navigationBar) return 0;
        JSONObject bounds = window.optJSONObject("bounds");
        if (bounds == null) return 0;
        int width = screen.optInt("width_px", 0);
        int height = screen.optInt("height_px", 0);
        int left = bounds.optInt("left", Integer.MAX_VALUE);
        int top = bounds.optInt("top", Integer.MAX_VALUE);
        int right = bounds.optInt("right", Integer.MIN_VALUE);
        int bottom = bounds.optInt("bottom", Integer.MIN_VALUE);
        if (width <= 0 || height <= 0) return 0;
        if (statusBar && left <= 0 && right >= width && top <= 0
                && bottom > 0 && bottom < height) return 1;
        if (navigationBar && left <= 0 && right >= width && bottom >= height
                && top > 0 && top < height) return 2;
        if (navigationBar && top <= 0 && bottom >= height && left <= 0
                && right > 0 && right < width) return 3;
        if (navigationBar && top <= 0 && bottom >= height && right >= width
                && left > 0 && left < width) return 4;
        return 0;
    }

    private static boolean overlaps(JSONObject left, JSONObject right) {
        return left != null && right != null
                && left.optInt("left", Integer.MAX_VALUE) < right.optInt("right", Integer.MIN_VALUE)
                && left.optInt("right", Integer.MIN_VALUE) > right.optInt("left", Integer.MAX_VALUE)
                && left.optInt("top", Integer.MAX_VALUE) < right.optInt("bottom", Integer.MIN_VALUE)
                && left.optInt("bottom", Integer.MIN_VALUE) > right.optInt("top", Integer.MAX_VALUE);
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
            if (isLocalTaskStatusNode(value, packageName)) {
                appendWithoutVolatileStatusText(target, value, keys);
            } else {
                append(target, value, keys);
            }
        }
        target.append(']');
    }

    private static boolean isLocalTaskStatusNode(JSONObject node, String activePackage) {
        return LOCAL_APP_PACKAGE.equals(activePackage)
                && LOCAL_APP_PACKAGE.equals(node.optString("package_name", ""))
                && LOCAL_TASK_STATUS_DESCRIPTION.equals(node.optString("content_description", ""));
    }

    private static void appendWithoutVolatileStatusText(StringBuilder target, JSONObject node,
            String[] keys) {
        for (String key : keys) {
            if ("text".equals(key) || "state_description".equals(key)) continue;
            target.append(key).append('=').append(node.opt(key)).append('|');
        }
    }
}
