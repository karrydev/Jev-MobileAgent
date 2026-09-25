package com.jev.mobileagent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative, task-local rules for proving an Android action's observed postcondition. */
public final class TreeActionVerifier {
    public static final String POLICY_ID = "jev-android-tree-verification-rules-v1";
    public static final String POLICY_SOURCE = "v1|loading-marker=PENDING|set_text:unique-stable-editable-target:exact-value-only:SUCCESS:actual-mismatch=FAILURE|positive-requires-real-before-and-after-images|ambiguous-or-generic-change=UNKNOWN|receipt-is-not-outcome-truth|wait=600msx2";
    public static final String POLICY_SHA256 = "a07ccb149ec2826ace4f5fe692f95ef84a319371b038c41a8eec397a2aac83c4";
    public static final long WAIT_INTERVAL_MILLIS = 600L;
    public static final int MAX_WAIT_ATTEMPTS = 2;

    private static final Pattern MODEL_STATUS = Pattern.compile(
            "^(SUCCESS|FAILURE|PENDING|UNKNOWN)(?:\\s*[:：].*)?$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final String[] LOADING_MARKERS = {
            "loading", "saving", "processing", "pending", "加载中", "正在加载",
            "保存中", "正在保存", "处理中", "请稍候"
    };

    private TreeActionVerifier() {
    }

    public enum Status {
        SUCCESS,
        FAILURE,
        PENDING,
        UNKNOWN
    }

    public static final class Result {
        public final Status status;
        public final String reason;
        public final JSONObject evidence;

        private Result(Status status, String reason, JSONObject evidence) {
            this.status = status;
            this.reason = reason;
            this.evidence = evidence == null ? new JSONObject() : evidence;
        }

        public JSONObject asJson(String source) throws JSONException {
            return new JSONObject()
                    .put("status", status.name())
                    .put("source", source == null ? "tree_rule" : source)
                    .put("reason", reason)
                    .put("evidence", new JSONObject(evidence.toString()));
        }
    }

    public static Result decision(Status status, String reason, JSONObject evidence) {
        return new Result(status == null ? Status.UNKNOWN : status,
                reason == null ? "reason_missing" : reason,
                evidence == null ? new JSONObject() : evidence);
    }

    public static Result verify(JSONObject before, JSONObject after, JSONObject action,
            JSONObject beforeScreenshot, JSONObject afterScreenshot) {
        if (before == null || after == null || action == null) {
            return result(Status.UNKNOWN, "observation_or_action_missing");
        }
        if (!"AVAILABLE".equals(before.optString("availability", ""))
                || !"AVAILABLE".equals(after.optString("availability", ""))) {
            return result(Status.UNKNOWN, "tree_unavailable");
        }
        JSONArray afterNodes = after.optJSONArray("nodes");
        if (afterNodes == null || afterNodes.length() == 0) {
            return result(Status.UNKNOWN, "after_tree_missing");
        }
        if (hasLoadingMarker(afterNodes)) {
            return result(Status.PENDING, "loading_state_visible");
        }

        String kind = action.optString("kind", "");
        if (!"set_text".equals(kind)) {
            return result(Status.UNKNOWN, "no_supported_unique_tree_postcondition");
        }
        JSONObject parameters = action.optJSONObject("parameters");
        Object expectedValue = parameters == null ? null : parameters.opt("text");
        if (!(expectedValue instanceof String)) {
            return result(Status.UNKNOWN, "expected_text_missing");
        }
        String targetId = action.optString("target_node_id", "");
        JSONObject target = uniqueNode(before.optJSONArray("nodes"), targetId);
        if (target == null || !target.optBoolean("editable", false)) {
            return result(Status.UNKNOWN, "before_target_not_unique_editable_node");
        }
        List<JSONObject> matches = matchingTarget(afterNodes, target);
        if (matches.size() != 1) {
            return result(Status.UNKNOWN, "after_target_identity_not_unique");
        }
        JSONObject actualNode = matches.get(0);
        String actualValue = actualNode.optString("text", "");
        JSONObject evidence = new JSONObject();
        try {
            evidence.put("before_observation_id", before.optString("observation_id", ""))
                    .put("after_observation_id", after.optString("observation_id", ""))
                    .put("target_class", target.optString("class_name", ""))
                    .put("target_package", target.optString("package_name", ""))
                    .put("target_view_id", target.optString("view_id_resource_name", ""))
                    .put("target_content_description", target.optString("content_description", ""))
                    .put("target_match_count", matches.size())
                    .put("expected_text", expectedValue)
                    .put("actual_text", actualValue)
                    .put("before_screenshot_available", hasImage(beforeScreenshot))
                    .put("after_screenshot_available", hasImage(afterScreenshot));
        } catch (JSONException ignored) {
            return result(Status.UNKNOWN, "tree_evidence_invalid");
        }

        if (!expectedValue.equals(actualValue)) {
            return new Result(Status.FAILURE, "exact_text_postcondition_not_met", evidence);
        }
        if (!hasImage(beforeScreenshot)) {
            return new Result(Status.UNKNOWN, "before_screenshot_missing", evidence);
        }
        if (!hasImage(afterScreenshot)) {
            return new Result(Status.UNKNOWN, "after_screenshot_missing", evidence);
        }
        return new Result(Status.SUCCESS, "exact_text_postcondition_met", evidence);
    }

    public static Status parseModelStatus(String raw) {
        Matcher matcher = MODEL_STATUS.matcher(raw == null ? "" : raw.trim());
        if (!matcher.matches()) return Status.UNKNOWN;
        try {
            return Status.valueOf(matcher.group(1).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return Status.UNKNOWN;
        }
    }

    public static boolean hasImage(JSONObject screenshot) {
        return screenshot != null && !screenshot.optString("png_base64", "").isEmpty();
    }

    public static String policySha256() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(POLICY_SOURCE.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean hasLoadingMarker(JSONArray nodes) {
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null || !node.optBoolean("visible_to_user", true)) continue;
            if (containsLoadingMarker(node.optString("text", ""))
                    || containsLoadingMarker(node.optString("content_description", ""))
                    || containsLoadingMarker(node.optString("state_description", ""))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsLoadingMarker(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String marker : LOADING_MARKERS) {
            if (normalized.contains(marker.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static JSONObject uniqueNode(JSONArray nodes, String nodeId) {
        if (nodes == null || nodeId == null || nodeId.isEmpty()) return null;
        JSONObject found = null;
        int count = 0;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node != null && nodeId.equals(node.optString("node_id", ""))) {
                found = node;
                count++;
            }
        }
        return count == 1 ? found : null;
    }

    private static List<JSONObject> matchingTarget(JSONArray nodes, JSONObject beforeTarget) {
        List<JSONObject> matches = new ArrayList<>();
        if (nodes == null) return matches;
        String packageName = beforeTarget.optString("package_name", "");
        String className = beforeTarget.optString("class_name", "");
        String viewId = beforeTarget.optString("view_id_resource_name", "");
        String description = beforeTarget.optString("content_description", "");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject candidate = nodes.optJSONObject(i);
            if (candidate == null || !candidate.optBoolean("editable", false)) continue;
            if (!packageName.equals(candidate.optString("package_name", ""))
                    || !className.equals(candidate.optString("class_name", ""))) continue;
            boolean sameIdentity = !viewId.isEmpty()
                    ? viewId.equals(candidate.optString("view_id_resource_name", ""))
                    : !description.isEmpty()
                            && description.equals(candidate.optString("content_description", ""));
            if (sameIdentity) matches.add(candidate);
        }
        return matches;
    }

    private static Result result(Status status, String reason) {
        return new Result(status, reason, new JSONObject());
    }
}
