package com.jev.mobileagent;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Prevents a visual model from asserting an event succeeded when the observed app did not change. */
final class VisualChangeGuard {
    static final String POLICY_ID = "jev-android-visual-no-change-guard-v1";

    private static final String LOCAL_APP_PACKAGE = "com.jev.mobileagent";
    private static final String LOCAL_TASK_STATUS_DESCRIPTION = "Local VLM task status";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String SYSTEM_STATUS_BAR_VIEW_ID = "com.android.systemui:id/status_bar";

    private VisualChangeGuard() {
    }

    enum Change {
        CHANGED,
        UNCHANGED,
        INDETERMINATE
    }

    interface PixelRows {
        void readRow(int x, int y, int[] destination);
    }

    static final class Assessment {
        final Change change;
        final String reason;
        final JSONObject evidence;

        private Assessment(Change change, String reason, JSONObject evidence) {
            this.change = change;
            this.reason = reason;
            this.evidence = evidence == null ? new JSONObject() : evidence;
        }
    }

    static final class FrameContext {
        final boolean comparable;
        final String reason;
        final String activePackage;
        final int imageWidth;
        final int imageHeight;
        final int[] appBounds;
        final int[] ignoredStatusBounds;
        final String appBoundsSource;
        final JSONObject evidence;

        private FrameContext(boolean comparable, String reason, String activePackage,
                int imageWidth, int imageHeight, int[] appBounds, int[] ignoredStatusBounds,
                String appBoundsSource, JSONObject evidence) {
            this.comparable = comparable;
            this.reason = reason;
            this.activePackage = activePackage;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.appBounds = appBounds;
            this.ignoredStatusBounds = ignoredStatusBounds;
            this.appBoundsSource = appBoundsSource;
            this.evidence = evidence == null ? new JSONObject() : evidence;
        }
    }

    /** Compare trusted app-content frames. Pixel equality is exact; no visual threshold is used. */
    static Assessment inspect(JSONObject before, JSONObject after,
            JSONObject beforeShot, JSONObject afterShot) {
        FrameContext context = frameContext(before, after, beforeShot, afterShot);
        if (!context.comparable) {
            return assessment(Change.INDETERMINATE, context.reason, context.evidence);
        }

        byte[] beforeDigest = screenshotFingerprint(beforeShot, context);
        byte[] afterDigest = screenshotFingerprint(afterShot, context);
        if (beforeDigest == null || afterDigest == null) {
            return assessment(Change.INDETERMINATE, "screenshot_pixels_unavailable", context.evidence);
        }

        boolean equal = MessageDigest.isEqual(beforeDigest, afterDigest);
        JSONObject evidence = copy(context.evidence);
        put(evidence, "before_app_pixels_sha256", hex(beforeDigest));
        put(evidence, "after_app_pixels_sha256", hex(afterDigest));
        put(evidence, "comparison", equal ? "exact_pixel_match" : "pixel_change");
        put(evidence, "compared_pixel_count", comparedPixelCount(
                context.appBounds, context.ignoredStatusBounds));
        return assessment(equal ? Change.UNCHANGED : Change.CHANGED,
                equal ? "target_app_content_pixels_unchanged" : "target_app_content_pixels_changed",
                evidence);
    }

    /**
     * Apply the static no-change rule to an already parsed VLM result. A changed frame still needs
     * the model's semantic judgment; only an exact no-change observation can veto SUCCESS.
     */
    static TreeActionVerifier.Result guardVisualDecision(
            TreeActionVerifier.Result modelDecision, JSONObject action,
            JSONObject before, JSONObject after, JSONObject beforeShot, JSONObject afterShot) {
        if (modelDecision == null || modelDecision.status != TreeActionVerifier.Status.SUCCESS
                || !isEventAction(action)) {
            return modelDecision;
        }
        return applyAssessment(modelDecision, action, inspect(before, after, beforeShot, afterShot));
    }

    static TreeActionVerifier.Result applyAssessment(
            TreeActionVerifier.Result modelDecision, JSONObject action, Assessment assessment) {
        if (modelDecision == null || modelDecision.status != TreeActionVerifier.Status.SUCCESS
                || !isEventAction(action) || assessment == null
                || assessment.change != Change.UNCHANGED) {
            return modelDecision;
        }
        JSONObject evidence = copy(assessment.evidence);
        put(evidence, "model_status", modelDecision.status.name());
        put(evidence, "model_reason", modelDecision.reason);
        put(evidence, "visual_change_policy", POLICY_ID);
        return TreeActionVerifier.decision(TreeActionVerifier.Status.UNKNOWN,
                "event_action_success_without_visible_app_change", evidence);
    }

    /** Pure pixel-row policy used by Android decoding and JVM behavior tests. Bounds are right/bottom exclusive. */
    static Assessment comparePixelRows(PixelRows before, PixelRows after,
            int imageWidth, int imageHeight, int[] appBounds, int[] ignoredStatusBounds) {
        if (before == null || after == null || imageWidth <= 0 || imageHeight <= 0
                || !validRect(appBounds, imageWidth, imageHeight)
                || (ignoredStatusBounds != null
                        && !containedRect(ignoredStatusBounds, appBounds))) {
            return assessment(Change.INDETERMINATE, "pixel_frame_or_bounds_invalid", null);
        }

        byte[] beforeDigest = pixelFingerprint(before, imageWidth, imageHeight,
                appBounds, ignoredStatusBounds);
        byte[] afterDigest = pixelFingerprint(after, imageWidth, imageHeight,
                appBounds, ignoredStatusBounds);
        if (beforeDigest == null || afterDigest == null) {
            return assessment(Change.INDETERMINATE, "pixel_rows_unavailable", null);
        }
        boolean equal = MessageDigest.isEqual(beforeDigest, afterDigest);
        JSONObject evidence = new JSONObject();
        put(evidence, "app_bounds_px", rectJson(appBounds));
        put(evidence, "ignored_own_status_bounds_px",
                ignoredStatusBounds == null ? JSONObject.NULL : rectJson(ignoredStatusBounds));
        put(evidence, "before_app_pixels_sha256", hex(beforeDigest));
        put(evidence, "after_app_pixels_sha256", hex(afterDigest));
        put(evidence, "comparison", equal ? "exact_pixel_match" : "pixel_change");
        put(evidence, "compared_pixel_count", comparedPixelCount(appBounds, ignoredStatusBounds));
        return assessment(equal ? Change.UNCHANGED : Change.CHANGED,
                equal ? "target_app_content_pixels_unchanged" : "target_app_content_pixels_changed",
                evidence);
    }

    /** Checks screenshot, observation, app-window and known self-status relationships before comparing pixels. */
    static FrameContext frameContext(JSONObject before, JSONObject after,
            JSONObject beforeShot, JSONObject afterShot) {
        if (before == null || after == null || beforeShot == null || afterShot == null) {
            return invalidContext("observation_or_screenshot_missing");
        }
        if (!"AVAILABLE".equals(before.optString("availability", ""))
                || !"AVAILABLE".equals(after.optString("availability", ""))
                || !"observed".equalsIgnoreCase(before.optString("page_state", ""))
                || !"observed".equalsIgnoreCase(after.optString("page_state", ""))) {
            return invalidContext("observation_unavailable");
        }

        String beforeId = before.optString("observation_id", "");
        String afterId = after.optString("observation_id", "");
        if (beforeId.isEmpty() || afterId.isEmpty() || beforeId.equals(afterId)) {
            return invalidContext("observation_identity_invalid");
        }
        JSONObject beforeScreen = before.optJSONObject("screen");
        JSONObject afterScreen = after.optJSONObject("screen");
        if (!sameScreenContext(beforeScreen, afterScreen)) {
            return invalidContext("active_screen_or_window_changed");
        }
        String activePackage = LocalTaskControlPolicy.activeApplicationPackage(before);
        if (activePackage.isEmpty()
                || !activePackage.equals(LocalTaskControlPolicy.activeApplicationPackage(after))) {
            return invalidContext("active_application_changed");
        }
        int screenWidth = beforeScreen.optInt("width_px", 0);
        int screenHeight = beforeScreen.optInt("height_px", 0);
        Viewport viewport = trustworthyAppViewport(before, after, activePackage,
                screenWidth, screenHeight);
        if (viewport == null) return invalidContext("target_app_content_bounds_untrusted");
        if (!validScreenshot(beforeShot, beforeId, "BEFORE", screenWidth, screenHeight)
                || !validScreenshot(afterShot, afterId, "AFTER", screenWidth, screenHeight)
                || beforeShot.optString("screenshot_id", "")
                        .equals(afterShot.optString("screenshot_id", ""))) {
            return invalidContext("screenshot_observation_or_frame_mismatch");
        }

        StatusMask mask = statusMask(before, after, activePackage,
                viewport.beforeWindowRootId, viewport.afterWindowRootId, viewport.bounds);
        if (!mask.valid) return invalidContext(mask.reason);
        JSONObject evidence = new JSONObject();
        put(evidence, "policy", POLICY_ID);
        put(evidence, "active_application_package", activePackage);
        put(evidence, "before_observation_id", beforeId);
        put(evidence, "after_observation_id", afterId);
        put(evidence, "before_screenshot_id", beforeShot.optString("screenshot_id", ""));
        put(evidence, "after_screenshot_id", afterShot.optString("screenshot_id", ""));
        JSONObject dimensions = new JSONObject();
        put(dimensions, "width_px", screenWidth);
        put(dimensions, "height_px", screenHeight);
        put(evidence, "source_screen_size_px", dimensions);
        put(evidence, "target_app_content_bounds_px", rectJson(viewport.bounds));
        put(evidence, "target_app_content_bounds_source", viewport.source);
        put(evidence, "ignored_own_status_bounds_px",
                mask.bounds == null ? JSONObject.NULL : rectJson(mask.bounds));
        return new FrameContext(true, "trusted_matching_app_frame", activePackage,
                screenWidth, screenHeight, viewport.bounds, mask.bounds, viewport.source, evidence);
    }

    static boolean isOwnStatusNode(String activePackage, JSONObject node) {
        return LOCAL_APP_PACKAGE.equals(activePackage)
                && node != null
                && LOCAL_APP_PACKAGE.equals(node.optString("package_name", ""))
                && LOCAL_TASK_STATUS_DESCRIPTION.equals(node.optString("content_description", ""));
    }

    private static boolean isEventAction(JSONObject action) {
        String kind = action == null ? "" : action.optString("kind", "");
        return "tap".equals(kind) || "coordinate_tap".equals(kind)
                || "long_press".equals(kind) || "coordinate_long_press".equals(kind)
                || "swipe".equals(kind) || "coordinate_swipe".equals(kind)
                || "scroll".equals(kind) || "system_back".equals(kind) || "back".equals(kind);
    }

    private static boolean sameScreenContext(JSONObject before, JSONObject after) {
        if (before == null || after == null) return false;
        int width = before.optInt("width_px", 0);
        int height = before.optInt("height_px", 0);
        int windowId = before.optInt("active_window_id", -1);
        int rotation = before.optInt("rotation", -1);
        JSONObject bounds = before.optJSONObject("active_window_bounds");
        JSONObject afterBounds = after.optJSONObject("active_window_bounds");
        return width > 0 && height > 0 && windowId > 0 && rotation >= 0
                && width == after.optInt("width_px", -1)
                && height == after.optInt("height_px", -1)
                && windowId == after.optInt("active_window_id", -1)
                && rotation == after.optInt("rotation", -1)
                && sameRect(bounds, afterBounds);
    }

    private static Viewport trustworthyAppViewport(JSONObject before, JSONObject after,
            String activePackage, int width, int height) {
        JSONObject beforeWindow = activeApplicationWindow(before, activePackage);
        JSONObject afterWindow = activeApplicationWindow(after, activePackage);
        if (beforeWindow == null || afterWindow == null) return null;
        int[] beforeActiveBounds = nodeBoundsObject(beforeWindow.optJSONObject("bounds"), width, height);
        int[] afterActiveBounds = nodeBoundsObject(afterWindow.optJSONObject("bounds"), width, height);
        if (beforeActiveBounds == null || !Arrays.equals(beforeActiveBounds, afterActiveBounds)) return null;

        int[] beforePolicyBounds = LocalTaskControlPolicy.targetScreenshotBounds(before);
        int[] afterPolicyBounds = LocalTaskControlPolicy.targetScreenshotBounds(after);
        if (beforePolicyBounds != null && afterPolicyBounds != null
                && Arrays.equals(beforePolicyBounds, afterPolicyBounds)
                && containedRect(beforePolicyBounds, beforeActiveBounds)) {
            return new Viewport(beforePolicyBounds, "local_task_control_policy",
                    beforeWindow.optString("root_node_id", ""), afterWindow.optString("root_node_id", ""));
        }
        if (beforePolicyBounds != null || afterPolicyBounds != null) return null;

        int[] beforeBar = knownSystemStatusBarBounds(before, width, height);
        int[] afterBar = knownSystemStatusBarBounds(after, width, height);
        if (beforeBar == null || !Arrays.equals(beforeBar, afterBar)) return null;
        int[] bounds = {beforeActiveBounds[0], Math.max(beforeActiveBounds[1], beforeBar[3]),
                beforeActiveBounds[2], beforeActiveBounds[3]};
        if (!validRect(bounds, width, height)) return null;
        return new Viewport(bounds, "unique_systemui_status_bar_node",
                beforeWindow.optString("root_node_id", ""), afterWindow.optString("root_node_id", ""));
    }

    private static JSONObject activeApplicationWindow(JSONObject observation, String activePackage) {
        JSONObject screen = observation == null ? null : observation.optJSONObject("screen");
        JSONArray windows = observation == null ? null : observation.optJSONArray("windows");
        if (screen == null || windows == null || activePackage == null || activePackage.isEmpty()) return null;
        int expectedId = screen.optInt("active_window_id", -1);
        JSONObject found = null;
        int matches = 0;
        for (int i = 0; i < windows.length(); i++) {
            JSONObject window = windows.optJSONObject(i);
            if (window == null || window.optInt("window_id", -2) != expectedId
                    || window.optInt("window_type", -1) != 1
                    || !window.optBoolean("active", false) || !window.optBoolean("focused", false)
                    || !activePackage.equals(window.optString("package_name", ""))) continue;
            found = window;
            matches++;
        }
        JSONObject screenBounds = screen.optJSONObject("active_window_bounds");
        String rootId = found == null ? "" : found.optString("root_node_id", "");
        return matches == 1 && !rootId.isEmpty()
                && sameRect(found.optJSONObject("bounds"), screenBounds) ? found : null;
    }

    private static int[] knownSystemStatusBarBounds(JSONObject observation, int width, int height) {
        JSONArray windows = observation == null ? null : observation.optJSONArray("windows");
        JSONArray nodes = observation == null ? null : observation.optJSONArray("nodes");
        if (windows == null || nodes == null) return null;
        Set<String> systemWindowRoots = new HashSet<>();
        for (int i = 0; i < windows.length(); i++) {
            JSONObject window = windows.optJSONObject(i);
            if (window != null && window.optInt("window_type", -1) == 3
                    && SYSTEM_UI_PACKAGE.equals(window.optString("package_name", ""))) {
                String rootId = window.optString("root_node_id", "");
                if (!rootId.isEmpty()) systemWindowRoots.add(rootId);
            }
        }
        int[] result = null;
        int matches = 0;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null || !SYSTEM_UI_PACKAGE.equals(node.optString("package_name", ""))
                    || !SYSTEM_STATUS_BAR_VIEW_ID.equals(node.optString("view_id_resource_name", ""))) continue;
            String parentId = node.optString("parent_node_id", "");
            JSONObject parent = nodeById(nodes, parentId);
            int[] bounds = nodeBounds(node);
            if (!systemWindowRoots.contains(parentId) || parent == null
                    || !SYSTEM_UI_PACKAGE.equals(parent.optString("package_name", ""))
                    || node.optBoolean("visible_to_user", true) == false
                    || bounds == null || bounds[0] != 0 || bounds[1] != 0
                    || bounds[2] != width || bounds[3] <= 0 || bounds[3] >= height) {
                return null;
            }
            result = bounds;
            matches++;
        }
        return matches == 1 ? result : null;
    }

    private static JSONObject nodeById(JSONArray nodes, String nodeId) {
        if (nodes == null || nodeId == null || nodeId.isEmpty()) return null;
        JSONObject result = null;
        int matches = 0;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node != null && nodeId.equals(node.optString("node_id", ""))) {
                result = node;
                matches++;
            }
        }
        return matches == 1 ? result : null;
    }

    private static boolean validScreenshot(JSONObject screenshot, String observationId,
            String captureType, int screenWidth, int screenHeight) {
        return screenshot != null
                && !screenshot.optString("png_base64", "").isEmpty()
                && !screenshot.optString("screenshot_id", "").isEmpty()
                && observationId.equals(screenshot.optString("observation_id", ""))
                && captureType.equals(screenshot.optString("capture_type", ""))
                && screenshot.optInt("width_px", 0) == screenWidth
                && screenshot.optInt("height_px", 0) == screenHeight;
    }

    private static StatusMask statusMask(JSONObject before, JSONObject after,
            String activePackage, String beforeWindowRootId, String afterWindowRootId,
            int[] appBounds) {
        if (!LOCAL_APP_PACKAGE.equals(activePackage)) return new StatusMask(true, null, "");
        JSONArray beforeNodes = before.optJSONArray("nodes");
        JSONArray afterNodes = after.optJSONArray("nodes");
        if (beforeNodes == null || afterNodes == null) {
            return new StatusMask(false, null, "own_status_node_tree_unavailable");
        }
        JSONObject beforeNode = uniqueOwnStatusNode(beforeNodes, activePackage, beforeWindowRootId);
        JSONObject afterNode = uniqueOwnStatusNode(afterNodes, activePackage, afterWindowRootId);
        int beforeCount = ownStatusNodeCount(beforeNodes, activePackage, beforeWindowRootId);
        int afterCount = ownStatusNodeCount(afterNodes, activePackage, afterWindowRootId);
        if (beforeCount == 0 && afterCount == 0) return new StatusMask(true, null, "");
        if (beforeCount != 1 || afterCount != 1 || beforeNode == null || afterNode == null) {
            return new StatusMask(false, null, "own_status_node_not_unique");
        }
        int[] beforeBounds = nodeBounds(beforeNode);
        int[] afterBounds = nodeBounds(afterNode);
        if (!stableStatusNode(beforeNode, afterNode) || beforeBounds == null
                || !Arrays.equals(beforeBounds, afterBounds)
                || !containedRect(beforeBounds, appBounds)) {
            return new StatusMask(false, null, "own_status_node_bounds_or_identity_changed");
        }
        return new StatusMask(true, beforeBounds, "");
    }

    private static int ownStatusNodeCount(JSONArray nodes, String activePackage, String windowRootId) {
        int count = 0;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (isOwnStatusNode(activePackage, node)
                    && windowRootId.equals(node.optString("parent_node_id", ""))) count++;
        }
        return count;
    }

    private static JSONObject uniqueOwnStatusNode(JSONArray nodes, String activePackage,
            String windowRootId) {
        JSONObject result = null;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (!isOwnStatusNode(activePackage, node)
                    || !windowRootId.equals(node.optString("parent_node_id", ""))) continue;
            if (result != null) return null;
            result = node;
        }
        return result;
    }

    private static boolean stableStatusNode(JSONObject before, JSONObject after) {
        String[] fields = {"class_name", "package_name", "content_description",
                "view_id_resource_name", "enabled", "visible_to_user", "clickable",
                "focusable", "focused", "selected", "scrollable", "editable"};
        for (String field : fields) {
            if (!sameJsonValue(before, after, field)) return false;
        }
        return sameRect(before.optJSONObject("bounds"), after.optJSONObject("bounds"));
    }

    private static boolean sameJsonValue(JSONObject left, JSONObject right, String key) {
        Object leftValue = left == null ? null : left.opt(key);
        Object rightValue = right == null ? null : right.opt(key);
        if (leftValue == null || leftValue == JSONObject.NULL) {
            return rightValue == null || rightValue == JSONObject.NULL;
        }
        return leftValue.equals(rightValue);
    }

    private static int[] nodeBounds(JSONObject node) {
        JSONObject bounds = node == null ? null : node.optJSONObject("bounds");
        if (bounds == null) return null;
        int[] result = {bounds.optInt("left", Integer.MIN_VALUE),
                bounds.optInt("top", Integer.MIN_VALUE), bounds.optInt("right", Integer.MIN_VALUE),
                bounds.optInt("bottom", Integer.MIN_VALUE)};
        return validRect(result, Integer.MAX_VALUE, Integer.MAX_VALUE) ? result : null;
    }

    private static byte[] screenshotFingerprint(JSONObject screenshot, FrameContext context) {
        byte[] encoded;
        try {
            encoded = Base64.decode(screenshot.optString("png_base64", ""), Base64.DEFAULT);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
        if (bitmap == null) return null;
        try {
            if (bitmap.getWidth() != context.imageWidth || bitmap.getHeight() != context.imageHeight) {
                return null;
            }
            return pixelFingerprint(new BitmapPixelRows(bitmap), bitmap.getWidth(), bitmap.getHeight(),
                    context.appBounds, context.ignoredStatusBounds);
        } catch (RuntimeException exception) {
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    private static byte[] pixelFingerprint(PixelRows rows, int imageWidth, int imageHeight,
            int[] bounds, int[] ignoredBounds) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateInt(digest, imageWidth);
            updateInt(digest, imageHeight);
            for (int edge : bounds) updateInt(digest, edge);
            if (ignoredBounds == null) {
                updateInt(digest, -1);
            } else {
                for (int edge : ignoredBounds) updateInt(digest, edge);
            }

            int width = bounds[2] - bounds[0];
            int[] row = new int[width];
            byte[] rowBytes = new byte[width * 4];
            for (int y = bounds[1]; y < bounds[3]; y++) {
                rows.readRow(bounds[0], y, row);
                int offset = 0;
                for (int x = 0; x < width; x++) {
                    int screenX = bounds[0] + x;
                    int screenY = y;
                    if (ignoredBounds != null
                            && screenX >= ignoredBounds[0] && screenX < ignoredBounds[2]
                            && screenY >= ignoredBounds[1] && screenY < ignoredBounds[3]) continue;
                    int pixel = row[x];
                    rowBytes[offset++] = (byte) (pixel >>> 24);
                    rowBytes[offset++] = (byte) (pixel >>> 16);
                    rowBytes[offset++] = (byte) (pixel >>> 8);
                    rowBytes[offset++] = (byte) pixel;
                }
                digest.update(rowBytes, 0, offset);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static int comparedPixelCount(int[] bounds, int[] ignoredBounds) {
        long total = (long) (bounds[2] - bounds[0]) * (bounds[3] - bounds[1]);
        if (ignoredBounds != null) {
            total -= (long) (ignoredBounds[2] - ignoredBounds[0])
                    * (ignoredBounds[3] - ignoredBounds[1]);
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, total);
    }

    private static boolean validRect(int[] rect, int width, int height) {
        return rect != null && rect.length == 4
                && rect[0] >= 0 && rect[1] >= 0 && rect[2] <= width && rect[3] <= height
                && rect[2] > rect[0] && rect[3] > rect[1];
    }

    private static int[] nodeBoundsObject(JSONObject bounds, int width, int height) {
        if (bounds == null) return null;
        int[] result = {bounds.optInt("left", Integer.MIN_VALUE),
                bounds.optInt("top", Integer.MIN_VALUE), bounds.optInt("right", Integer.MIN_VALUE),
                bounds.optInt("bottom", Integer.MIN_VALUE)};
        return validRect(result, width, height) ? result : null;
    }

    private static boolean containedRect(int[] inner, int[] outer) {
        return validRect(inner, Integer.MAX_VALUE, Integer.MAX_VALUE)
                && validRect(outer, Integer.MAX_VALUE, Integer.MAX_VALUE)
                && inner[0] >= outer[0] && inner[1] >= outer[1]
                && inner[2] <= outer[2] && inner[3] <= outer[3];
    }

    private static boolean sameRect(JSONObject left, JSONObject right) {
        if (left == null || right == null) return false;
        String[] keys = {"left", "top", "right", "bottom"};
        for (String key : keys) {
            Object a = left.opt(key);
            Object b = right.opt(key);
            if (!(a instanceof Number) || !(b instanceof Number)
                    || ((Number) a).intValue() != ((Number) b).intValue()) return false;
        }
        return true;
    }

    private static JSONObject rectJson(int[] rect) {
        if (rect == null) return new JSONObject();
        JSONObject result = new JSONObject();
        put(result, "left", rect[0]);
        put(result, "top", rect[1]);
        put(result, "right", rect[2]);
        put(result, "bottom", rect[3]);
        return result;
    }

    private static Assessment assessment(Change change, String reason, JSONObject evidence) {
        return new Assessment(change, reason, evidence);
    }

    private static FrameContext invalidContext(String reason) {
        JSONObject evidence = new JSONObject();
        put(evidence, "policy", POLICY_ID);
        put(evidence, "comparison", "indeterminate");
        put(evidence, "reason", reason);
        return new FrameContext(false, reason, "", 0, 0, null, null, "", evidence);
    }

    private static JSONObject copy(JSONObject source) {
        if (source == null) return new JSONObject();
        try {
            return new JSONObject(source.toString());
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException ignored) {
            // Evidence fields have fixed JSON-safe values.
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return result.toString();
    }

    private static final class BitmapPixelRows implements PixelRows {
        private final Bitmap bitmap;

        BitmapPixelRows(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        public void readRow(int x, int y, int[] destination) {
            bitmap.getPixels(destination, 0, destination.length, x, y, destination.length, 1);
        }
    }

    private static final class StatusMask {
        final boolean valid;
        final int[] bounds;
        final String reason;

        StatusMask(boolean valid, int[] bounds, String reason) {
            this.valid = valid;
            this.bounds = bounds;
            this.reason = reason;
        }
    }

    private static final class Viewport {
        final int[] bounds;
        final String source;
        final String beforeWindowRootId;
        final String afterWindowRootId;

        Viewport(int[] bounds, String source, String beforeWindowRootId, String afterWindowRootId) {
            this.bounds = bounds;
            this.source = source;
            this.beforeWindowRootId = beforeWindowRootId;
            this.afterWindowRootId = afterWindowRootId;
        }
    }
}
