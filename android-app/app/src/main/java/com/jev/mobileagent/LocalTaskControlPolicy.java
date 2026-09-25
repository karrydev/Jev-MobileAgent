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
                "window_offset", "content_width_px", "content_height_px");
        appendArray(stable, observation.optJSONArray("windows"), new String[] {
                "window_type", "title", "package_name", "active", "focused", "bounds"
        });
        appendArray(stable, observation.optJSONArray("nodes"), new String[] {
                "package_name", "class_name", "text", "content_description", "state_description",
                "view_id_resource_name", "enabled", "visible_to_user", "clickable", "focusable",
                "focused", "selected", "scrollable", "editable", "bounds"
        });
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
}
