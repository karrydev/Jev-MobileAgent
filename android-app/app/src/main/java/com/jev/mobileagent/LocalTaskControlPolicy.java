package com.jev.mobileagent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Safety rules for releasing app-local tasks after a control request. */
final class LocalTaskControlPolicy {
    private static final String LOCAL_APP_PACKAGE = "com.jev.mobileagent";
    private static final String LOCAL_TASK_STATUS_DESCRIPTION = "Local VLM task status";
    static final int POST_ACTION_SCENE_MAX_RESAMPLES = 3;
    static final long POST_ACTION_SCENE_SETTLING_AGE_MS = 700L;
    static final long POST_ACTION_SCENE_SAMPLE_INTERVAL_MS = 100L;
    static final long POST_ACTION_SCENE_CONTROL_POLL_MS = 50L;
    static final long POST_ACTION_SCENE_TIMEOUT_MS = 2000L;

    enum RecoveryConfirmationSample {
        MATCH,
        RESAMPLE,
        REJECT
    }

    /** Short, non-uniform waits keep repeated screenshots from sampling at a fixed visual phase. */
    static long recoveryConfirmationResampleDelayMs(int completedSampleNumber) {
        if (completedSampleNumber < 1 || completedSampleNumber >= 5) return 0L;
        return 100L + (completedSampleNumber - 1L) * 25L;
    }

    /** Time and pair-acceptance rules shared by post-action production sampling and behavior tests. */
    static final class PostActionSceneSampler {
        enum Decision {
            CONTINUE,
            ACCEPT,
            CONTEXT_CHANGED,
            LIMIT_REACHED
        }

        private int sampleCount;
        private long lastSampleElapsedMs = -1L;
        private long previousStableSampleElapsedMs = -1L;
        private long previousStableEventSequence = -1L;
        private boolean hasPreviousStableSample;

        int sampleCount() {
            return sampleCount;
        }

        boolean shouldContinue(long elapsedMs) {
            return sampleCount < POST_ACTION_SCENE_MAX_RESAMPLES
                    && elapsedMs >= 0L
                    && elapsedMs < POST_ACTION_SCENE_TIMEOUT_MS;
        }

        boolean canTakeSampleAt(long elapsedMs) {
            if (!shouldContinue(elapsedMs) || elapsedMs < POST_ACTION_SCENE_SETTLING_AGE_MS) {
                return false;
            }
            return lastSampleElapsedMs < 0L
                    || elapsedMs - lastSampleElapsedMs >= POST_ACTION_SCENE_SAMPLE_INTERVAL_MS;
        }

        /** Delay in one control-responsive slice before the next eligible tree sample. */
        long delayUntilNextSampleMs(long elapsedMs) {
            if (elapsedMs < 0L || elapsedMs >= POST_ACTION_SCENE_TIMEOUT_MS
                    || sampleCount >= POST_ACTION_SCENE_MAX_RESAMPLES) {
                return 0L;
            }
            long nextSampleAt = Math.max(POST_ACTION_SCENE_SETTLING_AGE_MS,
                    lastSampleElapsedMs < 0L ? POST_ACTION_SCENE_SETTLING_AGE_MS
                            : lastSampleElapsedMs + POST_ACTION_SCENE_SAMPLE_INTERVAL_MS);
            return Math.min(POST_ACTION_SCENE_CONTROL_POLL_MS,
                    Math.max(0L, nextSampleAt - elapsedMs));
        }

        Decision recordSample(long elapsedMs, long eventSequenceBefore, long eventSequenceAfter,
                boolean sameContextAndStructure, boolean sameAsPreviousScene) {
            if (!shouldContinue(elapsedMs)) return Decision.LIMIT_REACHED;
            sampleCount++;
            lastSampleElapsedMs = elapsedMs;

            if (!sameContextAndStructure) {
                invalidateStablePair();
                return Decision.CONTEXT_CHANGED;
            }
            if (elapsedMs < POST_ACTION_SCENE_SETTLING_AGE_MS
                    || eventSequenceBefore != eventSequenceAfter) {
                invalidateStablePair();
                return Decision.CONTINUE;
            }

            boolean stablePair = hasPreviousStableSample
                    && sameAsPreviousScene
                    && previousStableEventSequence == eventSequenceBefore
                    && elapsedMs - previousStableSampleElapsedMs >= POST_ACTION_SCENE_SAMPLE_INTERVAL_MS;
            if (stablePair) return Decision.ACCEPT;

            hasPreviousStableSample = true;
            previousStableSampleElapsedMs = elapsedMs;
            previousStableEventSequence = eventSequenceAfter;
            return Decision.CONTINUE;
        }

        void invalidateStablePair() {
            hasPreviousStableSample = false;
            previousStableSampleElapsedMs = -1L;
            previousStableEventSequence = -1L;
        }

        /** Rebase only after a fresh tree matches the just-sampled scene following a screenshot event. */
        boolean prepareFreshSampleAfterScreenshotEvent(long elapsedMs, long eventSequenceAfterScreenshot) {
            if (!hasPreviousStableSample || !shouldContinue(elapsedMs)) {
                invalidateStablePair();
                return false;
            }
            previousStableEventSequence = eventSequenceAfterScreenshot;
            return true;
        }
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

    /** A target-free cancellation is safe only when the complete durable ledger proves no dispatch. */
    static boolean canCancelWithoutRecoveryTarget(JSONObject task) {
        if (task == null
                || !"standalone-vlm-task-v1".equals(task.optString("schema_version", ""))
                || !task.has("actions")) {
            return false;
        }
        JSONArray actions = task.optJSONArray("actions");
        if (actions == null) return false;
        Set<String> actionIds = new HashSet<>();
        for (int i = 0; i < actions.length(); i++) {
            JSONObject action = actions.optJSONObject(i);
            if (action == null
                    || !hasCompleteNotDispatchedReceipt(task, action)
                    || !actionIds.add(action.optString("action_id", ""))
                    || executionFact(task, action) != ExecutionFact.NOT_EXECUTED) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasCompleteNotDispatchedReceipt(JSONObject task, JSONObject entry) {
        String actionId = entry.optString("action_id", "");
        JSONObject contractAction = entry.optJSONObject("action");
        JSONObject receipt = entry.optJSONObject("result");
        String taskId = task.optString("task_id", "");
        if (actionId.isEmpty()
                || taskId.isEmpty()
                || !"not_dispatched".equals(entry.optString("phase", ""))
                || contractAction == null
                || !actionId.equals(contractAction.optString("action_id", ""))
                || !taskId.equals(contractAction.optString("task_id", ""))
                || !"1.0".equals(contractAction.optString("schema_version", ""))
                || !hasInstantTimestamp(contractAction, "created_at")
                || contractAction.optString("kind", "").isEmpty()
                || contractAction.optString("observation_id", "").isEmpty()
                || contractAction.optLong("observation_version", 0L) < 1L
                || contractAction.optInt("sequence", 0) < 1
                || !hasInstantTimestamp(entry, "created_at")
                || !hasInstantTimestamp(entry, "completed_at")
                || receipt == null
                || !(receipt.opt("success") instanceof Boolean)
                || receipt.optBoolean("success", true)) {
            return false;
        }

        Object errorCodeValue = receipt.opt("error_code");
        Object messageValue = receipt.opt("message");
        if (!(errorCodeValue instanceof String) || !(messageValue instanceof String)) return false;
        String expectedMessage = knownNotDispatchedMessage((String) errorCodeValue);
        return expectedMessage != null && expectedMessage.equals(messageValue);
    }

    private static boolean hasInstantTimestamp(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof String)) return false;
        try {
            Instant.parse((String) value);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    /** Mirrors only codes whose production writers persist a known pre-dispatch failure receipt. */
    private static String knownNotDispatchedMessage(String errorCode) {
        if ("permission_unavailable".equals(errorCode)) return "无障碍权限不可用";
        if ("action_controlled".equals(errorCode)
                || "invalid_action".equals(errorCode)
                || "stale_observation".equals(errorCode)
                || "target_node_not_found".equals(errorCode)
                || "decision_scene_changed_before_dispatch".equals(errorCode)
                || "decision_scene_unavailable_before_dispatch".equals(errorCode)) {
            return "本地操作失败";
        }
        return null;
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

    /**
     * Keep action-after resampling within one unchanged app/window/layout frame. Text semantics may
     * settle after an action; focus, geometry, node identity, actionability, and overlays may not.
     */
    static boolean samePostActionSceneContextAndStructure(JSONObject expected, JSONObject current) {
        if (expected == null || current == null
                || !"AVAILABLE".equals(expected.optString("availability", ""))
                || !"AVAILABLE".equals(current.optString("availability", ""))
                || !expected.optString("page_state", "").equals(current.optString("page_state", ""))) {
            return false;
        }
        String expectedPackage = activeApplicationPackage(expected);
        if (expectedPackage.isEmpty() || !expectedPackage.equals(activeApplicationPackage(current))) {
            return false;
        }
        JSONObject expectedScreen = expected.optJSONObject("screen");
        JSONObject currentScreen = current.optJSONObject("screen");
        JSONArray expectedWindows = expected.optJSONArray("windows");
        JSONArray currentWindows = current.optJSONArray("windows");
        JSONArray expectedNodes = expected.optJSONArray("nodes");
        JSONArray currentNodes = current.optJSONArray("nodes");
        if (!hasNonNullFields(expectedScreen, "width_px", "height_px", "rotation",
                "active_window_id", "active_window_bounds")
                || !hasNonNullFields(currentScreen, "width_px", "height_px", "rotation",
                        "active_window_id", "active_window_bounds")
                || expectedWindows == null || expectedWindows.length() == 0
                || currentWindows == null || currentWindows.length() == 0
                || expectedNodes == null || expectedNodes.length() == 0
                || currentNodes == null || currentNodes.length() == 0) {
            return false;
        }
        if (!sameFields(expectedScreen, currentScreen, "width_px", "height_px", "rotation",
                "system_bar_insets", "recovery_system_bar_insets", "window_offset",
                "content_width_px", "content_height_px", "active_window_id", "active_window_bounds")) {
            return false;
        }
        return sameObjectArrayById(expectedWindows, currentWindows,
                "window_id", new String[] {"window_type", "title", "class_name", "package_name",
                        "active", "focused", "layer", "bounds", "root_node_id"})
                && samePostActionNodes(expected, current, expectedNodes, currentNodes, expectedPackage);
    }

    private static boolean samePostActionNodes(JSONObject expectedObservation, JSONObject currentObservation,
            JSONArray expectedNodes, JSONArray currentNodes, String targetPackage) {
        String[] comparedFields = {"parent_node_id", "package_name", "class_name",
                "view_id_resource_name", "enabled", "visible_to_user", "clickable",
                "focusable", "focused", "selected", "scrollable", "editable",
                "bounds", "child_node_ids"};
        String[] semanticFields = {"text", "content_description", "state_description", "hint_text"};
        for (int i = 0; i < expectedNodes.length(); i++) {
            JSONObject expectedNode = expectedNodes.optJSONObject(i);
            if (expectedNode == null || !expectedNode.has("node_id")) return false;
            String nodeId = expectedNode.optString("node_id", "");
            if (nodeId.isEmpty()) return false;
            JSONObject currentNode = uniqueObjectById(currentNodes, "node_id", nodeId);
            if (currentNode == null) return false;
            if (sameFields(expectedNode, currentNode, comparedFields)) {
                if (!targetPackage.equals(expectedNode.optString("package_name", ""))
                        && !sameFields(expectedNode, currentNode, semanticFields)) return false;
                continue;
            }
            if (!sameObjectExceptBounds(expectedNode, currentNode)
                    || !isDecorativeStatusBarNode(expectedObservation, currentObservation,
                            expectedNode, currentNode, expectedNodes, currentNodes)
                    || !isOnePixelHorizontalTranslation(expectedNode, currentNode,
                            expectedObservation, currentObservation)) {
                return false;
            }
        }
        return expectedNodes.length() == currentNodes.length();
    }

    private static boolean isDecorativeStatusBarNode(JSONObject expectedObservation,
            JSONObject currentObservation, JSONObject expectedNode, JSONObject currentNode,
            JSONArray expectedNodes, JSONArray currentNodes) {
        if (!"com.android.systemui".equals(expectedNode.optString("package_name", ""))
                || !"com.android.systemui".equals(currentNode.optString("package_name", ""))
                || !isNonInteractiveVisibleNode(expectedNode)
                || !isNonInteractiveVisibleNode(currentNode)) {
            return false;
        }
        JSONObject expectedWindow = containingDecorativeStatusBarWindow(
                expectedObservation, expectedNode, expectedNodes);
        JSONObject currentWindow = containingDecorativeStatusBarWindow(
                currentObservation, currentNode, currentNodes);
        if (expectedWindow == null || currentWindow == null
                || !sameFields(expectedWindow, currentWindow, "window_id", "window_type", "title",
                        "class_name", "package_name", "active", "focused", "layer", "bounds",
                        "root_node_id")) {
            return false;
        }
        return sameObjectExceptBounds(expectedNode, currentNode);
    }

    private static boolean isNonInteractiveVisibleNode(JSONObject node) {
        return node.optBoolean("visible_to_user", false)
                && !node.optBoolean("clickable", true)
                && !node.optBoolean("focusable", true)
                && !node.optBoolean("focused", true)
                && !node.optBoolean("selected", true)
                && !node.optBoolean("scrollable", true)
                && !node.optBoolean("editable", true);
    }

    private static JSONObject containingDecorativeStatusBarWindow(JSONObject observation,
            JSONObject node, JSONArray nodes) {
        JSONArray windows = observation == null ? null : observation.optJSONArray("windows");
        JSONObject screen = observation == null ? null : observation.optJSONObject("screen");
        JSONObject activeWindow = activeWindow(observation);
        JSONObject activeBounds = activeWindow == null ? null : activeWindow.optJSONObject("bounds");
        if (windows == null || screen == null || activeWindow == null || activeBounds == null) return null;
        int screenWidth = screen.optInt("width_px", 0);
        int screenHeight = screen.optInt("height_px", 0);
        int activeLayer = activeWindow.optInt("layer", Integer.MIN_VALUE);
        for (int i = 0; i < windows.length(); i++) {
            JSONObject window = windows.optJSONObject(i);
            if (!isDecorativeStatusBarWindow(window, screenWidth, screenHeight, activeLayer)) continue;
            String rootNodeId = window.optString("root_node_id", "");
            if (rootNodeId.isEmpty()) continue;
            JSONObject root = uniqueObjectById(nodes, "node_id", rootNodeId);
            if (root == null || !"com.android.systemui".equals(root.optString("package_name", ""))
                    || !isNonInteractiveVisibleNode(root)
                    || !isDescendantOfWindowRoot(node, rootNodeId, nodes)) {
                continue;
            }
            boolean allDecorative = true;
            for (int nodeIndex = 0; nodeIndex < nodes.length(); nodeIndex++) {
                JSONObject child = nodes.optJSONObject(nodeIndex);
                if (child != null && isDescendantOfWindowRoot(child, rootNodeId, nodes)
                        && (!"com.android.systemui".equals(child.optString("package_name", ""))
                                || !isNonInteractiveVisibleNode(child))) {
                    allDecorative = false;
                    break;
                }
            }
            if (allDecorative) return window;
        }
        return null;
    }

    private static boolean isDecorativeStatusBarWindow(JSONObject window,
            int screenWidth, int screenHeight, int activeLayer) {
        if (window == null || window.optInt("window_type", -1) != 3
                || !"com.android.systemui".equals(window.optString("package_name", ""))
                || window.optBoolean("active", true) || window.optBoolean("focused", true)
                || window.optInt("layer", Integer.MIN_VALUE) <= activeLayer
                || screenWidth <= 0 || screenHeight <= 0) {
            return false;
        }
        JSONObject bounds = window.optJSONObject("bounds");
        if (bounds == null || !hasNonNullFields(bounds, "left", "top", "right", "bottom")) return false;
        int left = bounds.optInt("left", Integer.MIN_VALUE);
        int top = bounds.optInt("top", Integer.MIN_VALUE);
        int right = bounds.optInt("right", Integer.MIN_VALUE);
        int bottom = bounds.optInt("bottom", Integer.MIN_VALUE);
        // Anonymous system windows are eligible only when they are an unchanged, full-width
        // top status strip. Expanded shades, keyboards, and side/edge panels stay strict.
        return left == 0 && top == 0 && right == screenWidth && bottom > 0
                && (long) bottom * 8L <= screenHeight;
    }

    private static boolean isDescendantOfWindowRoot(JSONObject node, String rootNodeId, JSONArray nodes) {
        String parentId = node == null ? "" : node.optString("parent_node_id", "");
        if (parentId.isEmpty()) return false;
        for (int i = 0; i < nodes.length(); i++) {
            if (rootNodeId.equals(parentId)) return true;
            JSONObject parent = uniqueObjectById(nodes, "node_id", parentId);
            if (parent == null) return false;
            parentId = parent.optString("parent_node_id", "");
            if (parentId.isEmpty()) return false;
        }
        return false;
    }

    private static boolean isOnePixelHorizontalTranslation(JSONObject expectedNode,
            JSONObject currentNode, JSONObject expectedObservation, JSONObject currentObservation) {
        int[] expected = nodeBounds(expectedNode);
        int[] current = nodeBounds(currentNode);
        JSONObject expectedWindow = containingDecorativeStatusBarWindow(expectedObservation,
                expectedNode, expectedObservation.optJSONArray("nodes"));
        JSONObject currentWindow = containingDecorativeStatusBarWindow(currentObservation,
                currentNode, currentObservation.optJSONArray("nodes"));
        int[] expectedWindowBounds = windowBounds(expectedWindow);
        int[] currentWindowBounds = windowBounds(currentWindow);
        if (expected == null || current == null || expectedWindowBounds == null || currentWindowBounds == null
                || expectedWindowBounds[0] != currentWindowBounds[0]
                || expectedWindowBounds[1] != currentWindowBounds[1]
                || expectedWindowBounds[2] != currentWindowBounds[2]
                || expectedWindowBounds[3] != currentWindowBounds[3]) {
            return false;
        }
        int deltaLeft = current[0] - expected[0];
        int deltaRight = current[2] - expected[2];
        return deltaLeft != 0 && Math.abs(deltaLeft) <= 1 && deltaLeft == deltaRight
                && current[1] == expected[1] && current[3] == expected[3]
                && expected[0] >= expectedWindowBounds[0] && expected[2] <= expectedWindowBounds[2]
                && expected[1] >= expectedWindowBounds[1] && expected[3] <= expectedWindowBounds[3]
                && current[0] >= currentWindowBounds[0] && current[2] <= currentWindowBounds[2]
                && current[1] >= currentWindowBounds[1] && current[3] <= currentWindowBounds[3];
    }

    private static int[] nodeBounds(JSONObject node) {
        JSONObject bounds = node == null ? null : node.optJSONObject("bounds");
        if (bounds == null || !hasNonNullFields(bounds, "left", "top", "right", "bottom")) return null;
        Object left = bounds.opt("left");
        Object top = bounds.opt("top");
        Object right = bounds.opt("right");
        Object bottom = bounds.opt("bottom");
        if (!(left instanceof Number) || !(top instanceof Number)
                || !(right instanceof Number) || !(bottom instanceof Number)) return null;
        return new int[] {((Number) left).intValue(), ((Number) top).intValue(),
                ((Number) right).intValue(), ((Number) bottom).intValue()};
    }

    private static int[] windowBounds(JSONObject window) {
        JSONObject bounds = window == null ? null : window.optJSONObject("bounds");
        if (bounds == null || !hasNonNullFields(bounds, "left", "top", "right", "bottom")) return null;
        return new int[] {bounds.optInt("left", Integer.MIN_VALUE), bounds.optInt("top", Integer.MIN_VALUE),
                bounds.optInt("right", Integer.MIN_VALUE), bounds.optInt("bottom", Integer.MIN_VALUE)};
    }

    private static boolean sameObjectExceptBounds(JSONObject expected, JSONObject current) {
        return canonicalJsonObjectExcept(expected, "bounds")
                .equals(canonicalJsonObjectExcept(current, "bounds"));
    }

    private static String canonicalJsonObjectExcept(JSONObject object, String excludedField) {
        if (object == null) return "null";
        ArrayList<String> keys = new ArrayList<>();
        for (java.util.Iterator<String> iterator = object.keys(); iterator.hasNext();) {
            String key = iterator.next();
            if (!excludedField.equals(key)) keys.add(key);
        }
        Collections.sort(keys);
        StringBuilder canonical = new StringBuilder("{");
        for (String key : keys) canonical.append(key).append(':')
                .append(canonicalJsonValue(object.opt(key))).append(';');
        return canonical.append('}').toString();
    }

    private static boolean hasNonNullFields(JSONObject object, String... fields) {
        if (object == null) return false;
        for (String field : fields) {
            if (!object.has(field) || object.opt(field) == null || object.opt(field) == JSONObject.NULL) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameObjectArrayById(JSONArray expected, JSONArray current,
            String idField, String[] comparedFields) {
        if (expected == null || current == null || expected.length() != current.length()) return false;
        for (int i = 0; i < expected.length(); i++) {
            JSONObject expectedObject = expected.optJSONObject(i);
            if (expectedObject == null || !expectedObject.has(idField)) return false;
            String expectedId = expectedObject.optString(idField, "");
            if (expectedId.isEmpty()) return false;
            JSONObject currentObject = uniqueObjectById(current, idField, expectedId);
            if (currentObject == null || !sameFields(expectedObject, currentObject, comparedFields)) return false;
        }
        return true;
    }

    private static JSONObject uniqueObjectById(JSONArray objects, String idField, String id) {
        JSONObject found = null;
        for (int i = 0; objects != null && i < objects.length(); i++) {
            JSONObject candidate = objects.optJSONObject(i);
            if (candidate == null || !id.equals(candidate.optString(idField, ""))) continue;
            if (found != null) return null;
            found = candidate;
        }
        return found;
    }

    private static boolean sameFields(JSONObject expected, JSONObject current, String... fields) {
        if (expected == null || current == null) return expected == current;
        for (String field : fields) {
            if (expected.has(field) != current.has(field)
                    || !canonicalJsonValue(expected.opt(field)).equals(canonicalJsonValue(current.opt(field)))) {
                return false;
            }
        }
        return true;
    }

    private static String canonicalJsonValue(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            ArrayList<String> keys = new ArrayList<>();
            for (java.util.Iterator<String> iterator = object.keys(); iterator.hasNext();) {
                keys.add(iterator.next());
            }
            Collections.sort(keys);
            StringBuilder canonical = new StringBuilder("{");
            for (String key : keys) {
                canonical.append(key).append(':').append(canonicalJsonValue(object.opt(key))).append(';');
            }
            return canonical.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder canonical = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                canonical.append(canonicalJsonValue(array.opt(i))).append(';');
            }
            return canonical.append(']').toString();
        }
        return value.toString();
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
