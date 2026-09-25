package com.jev.mobileagent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Stable geometry and accessibility-content signature for a coordinate-bound scene. */
final class CoordinateObservationFingerprint {
    private CoordinateObservationFingerprint() {
    }

    static boolean sameCoordinateContext(JSONObject expected, JSONObject current, JSONObject frame) {
        return matches(create(expected), current, frame);
    }

    static boolean matches(String expectedFingerprint, JSONObject current, JSONObject frame) {
        return expectedFingerprint != null && !expectedFingerprint.isEmpty()
                && expectedFingerprint.equals(create(current)) && frameMatches(frame, current);
    }

    static String create(JSONObject observation) {
        if (observation == null || !"AVAILABLE".equals(observation.optString("availability", ""))) {
            return "";
        }
        JSONObject screen = observation.optJSONObject("screen");
        JSONArray windows = observation.optJSONArray("windows");
        JSONArray nodes = observation.optJSONArray("nodes");
        if (screen == null || windows == null || nodes == null) {
            return "";
        }
        int activeWindowId = screen.optInt("active_window_id", -1);
        if (activeWindowId < 0) {
            return "";
        }
        JSONObject activeWindow = null;
        List<JSONObject> orderedWindows = objects(windows);
        orderedWindows.sort(Comparator.comparingInt(value -> value.optInt("window_id", -1)));
        for (JSONObject window : orderedWindows) {
            if (window.optInt("window_id", -1) == activeWindowId) {
                activeWindow = window;
                break;
            }
        }
        if (activeWindow == null) {
            return "";
        }

        StringBuilder fingerprint = new StringBuilder();
        append(fingerprint, screen.optInt("width_px", -1));
        append(fingerprint, screen.optInt("height_px", -1));
        append(fingerprint, screen.optInt("rotation", -1));
        append(fingerprint, screen.optInt("content_width_px", -1));
        append(fingerprint, screen.optInt("content_height_px", -1));
        appendBounds(fingerprint, screen.optJSONObject("active_window_bounds"));
        appendWindowGeometry(fingerprint, orderedWindows);

        String activePackage = activeWindow.optString("package_name", "");
        append(fingerprint, activeWindowId);
        append(fingerprint, activePackage);
        List<JSONObject> orderedNodes = new ArrayList<>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node != null && activePackage.equals(node.optString("package_name", ""))) {
                orderedNodes.add(node);
            }
        }
        orderedNodes.sort(Comparator.comparing(value -> value.optString("node_id", "")));
        append(fingerprint, orderedNodes.size());
        for (JSONObject node : orderedNodes) {
            append(fingerprint, node.optString("node_id", ""));
            append(fingerprint, node.optString("parent_node_id", ""));
            append(fingerprint, node.optString("class_name", ""));
            append(fingerprint, node.optString("package_name", ""));
            append(fingerprint, node.optString("view_id_resource_name", ""));
            if (isLocalTaskControlText(node)) {
                append(fingerprint, "<local-task-control-text>");
            } else {
                append(fingerprint, node.optString("text", ""));
                append(fingerprint, node.optString("content_description", ""));
                append(fingerprint, node.optString("state_description", ""));
            }
            append(fingerprint, node.optBoolean("enabled", false));
            append(fingerprint, node.optBoolean("visible_to_user", false));
            append(fingerprint, node.optBoolean("clickable", false));
            append(fingerprint, node.optBoolean("focusable", false));
            append(fingerprint, node.optBoolean("focused", false));
            append(fingerprint, node.optBoolean("selected", false));
            append(fingerprint, node.optBoolean("scrollable", false));
            append(fingerprint, node.optBoolean("editable", false));
            appendBounds(fingerprint, node.optJSONObject("bounds"));
        }
        return fingerprint.toString();
    }

    private static boolean frameMatches(JSONObject frame, JSONObject observation) {
        if (frame == null || observation == null) {
            return false;
        }
        JSONObject screen = observation.optJSONObject("screen");
        if (screen == null
                || frame.optInt("screen_width_px", frame.optInt("width_px", -1))
                        != screen.optInt("width_px", -2)
                || frame.optInt("screen_height_px", frame.optInt("height_px", -1))
                        != screen.optInt("height_px", -2)
                || frame.optInt("rotation", -1) != screen.optInt("rotation", -2)
                || frame.optInt("active_window_id", -1) != screen.optInt("active_window_id", -2)
                || !sameBounds(frame.optJSONObject("active_window_bounds"),
                        screen.optJSONObject("active_window_bounds"))) {
            return false;
        }
        return sameOptionalObject(frame.optJSONObject("window_offset"), screen.optJSONObject("window_offset"))
                && sameOptionalObject(frame.optJSONObject("system_bar_insets"), screen.optJSONObject("system_bar_insets"))
                && frame.optInt("content_width_px", -1) == screen.optInt("content_width_px", -2)
                && frame.optInt("content_height_px", -1) == screen.optInt("content_height_px", -2);
    }

    private static boolean sameOptionalObject(JSONObject left, JSONObject right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.optInt("x", left.optInt("left", 0)) == right.optInt("x", right.optInt("left", 0))
                && left.optInt("y", left.optInt("top", 0)) == right.optInt("y", right.optInt("top", 0))
                && left.optInt("right", 0) == right.optInt("right", 0)
                && left.optInt("bottom", 0) == right.optInt("bottom", 0);
    }

    private static boolean sameBounds(JSONObject left, JSONObject right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.optInt("left", Integer.MIN_VALUE) == right.optInt("left", Integer.MAX_VALUE)
                && left.optInt("top", Integer.MIN_VALUE) == right.optInt("top", Integer.MAX_VALUE)
                && left.optInt("right", Integer.MIN_VALUE) == right.optInt("right", Integer.MAX_VALUE)
                && left.optInt("bottom", Integer.MIN_VALUE) == right.optInt("bottom", Integer.MAX_VALUE);
    }

    private static void appendWindowGeometry(StringBuilder target, List<JSONObject> windows) {
        append(target, windows.size());
        for (JSONObject window : windows) {
            append(target, window.optInt("window_id", -1));
            append(target, window.optInt("window_type", -1));
            append(target, window.optString("package_name", ""));
            append(target, window.optBoolean("active", false));
            append(target, window.optBoolean("focused", false));
            append(target, window.optInt("layer", -1));
            appendBounds(target, window.optJSONObject("bounds"));
        }
    }

    private static boolean isLocalTaskControlText(JSONObject node) {
        String description = node.optString("content_description", "");
        String resourceId = node.optString("view_id_resource_name", "");
        return "Local VLM task goal".equals(description)
                || "Local VLM task status".equals(description)
                || resourceId.endsWith("/task_goal")
                || resourceId.endsWith("/task_status");
    }

    private static List<JSONObject> objects(JSONArray values) {
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    private static void appendBounds(StringBuilder target, JSONObject bounds) {
        append(target, bounds == null ? Integer.MIN_VALUE : bounds.optInt("left", Integer.MIN_VALUE));
        append(target, bounds == null ? Integer.MIN_VALUE : bounds.optInt("top", Integer.MIN_VALUE));
        append(target, bounds == null ? Integer.MIN_VALUE : bounds.optInt("right", Integer.MIN_VALUE));
        append(target, bounds == null ? Integer.MIN_VALUE : bounds.optInt("bottom", Integer.MIN_VALUE));
    }

    private static void append(StringBuilder target, Object value) {
        String text = String.valueOf(value);
        target.append(text.length()).append(':').append(text).append(';');
    }
}
