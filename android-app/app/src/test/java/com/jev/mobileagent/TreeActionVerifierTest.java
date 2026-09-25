package com.jev.mobileagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class TreeActionVerifierTest {
    @Test
    public void exactUniqueInputPostconditionCanSucceedWithRealBeforeAndAfterImages() throws Exception {
        JSONObject target = inputNode("before", "");
        JSONObject before = observation("before-observation", target);
        JSONObject after = observation("after-observation", inputNode("after", "独立手机测试成功"));

        TreeActionVerifier.Result result = TreeActionVerifier.verify(
                before, after, setTextAction("before", "独立手机测试成功"), image("before"), image("after"));

        assertEquals(TreeActionVerifier.Status.SUCCESS, result.status);
        assertEquals("exact_text_postcondition_met", result.reason);
    }

    @Test
    public void similarOrUnchangedTextFailsTheExactTextPostcondition() throws Exception {
        JSONObject before = observation("before-observation", inputNode("before", ""));
        JSONObject after = observation("after-observation", inputNode("after", "独立手机测试成"));

        TreeActionVerifier.Result result = TreeActionVerifier.verify(
                before, after, setTextAction("before", "独立手机测试成功"), image("before"), image("after"));

        assertEquals(TreeActionVerifier.Status.FAILURE, result.status);
        assertEquals("exact_text_postcondition_not_met", result.reason);
    }

    @Test
    public void unrelatedTextChangeCannotProveSetTextPostcondition() throws Exception {
        JSONObject beforeTarget = inputNode("before", "");
        JSONObject afterTarget = inputNode("after", "");
        JSONObject before = observation("before-observation", beforeTarget);
        JSONObject after = observation("after-observation", afterTarget);
        before.getJSONArray("nodes").put(new JSONObject().put("node_id", "status")
                .put("class_name", "android.widget.TextView").put("text", "Ready"));
        after.getJSONArray("nodes").put(new JSONObject().put("node_id", "status")
                .put("class_name", "android.widget.TextView").put("text", "Updated status"));

        TreeActionVerifier.Result result = TreeActionVerifier.verify(
                before, after, setTextAction("before", "Expected text"), image("before"), image("after"));

        assertEquals(TreeActionVerifier.Status.FAILURE, result.status);
        assertEquals("exact_text_postcondition_not_met", result.reason);
    }

    @Test
    public void visualActionWithChangingStatusTextStillHasNoTreePostcondition() throws Exception {
        JSONObject before = observation("before-observation", inputNode("before", ""));
        JSONObject after = observation("after-observation", inputNode("after", ""));
        before.getJSONArray("nodes").put(new JSONObject().put("node_id", "gesture-state")
                .put("class_name", "android.widget.TextView").put("text", "Visual gesture state: ready"));
        after.getJSONArray("nodes").put(new JSONObject().put("node_id", "gesture-state")
                .put("class_name", "android.widget.TextView")
                .put("text", "Visual gesture state: coordinate tap completed"));

        TreeActionVerifier.Result result = TreeActionVerifier.verify(before, after,
                new JSONObject().put("kind", "coordinate_tap").put("target_node_id", "gesture-state"),
                image("before"), image("after"));

        assertEquals(TreeActionVerifier.Status.UNKNOWN, result.status);
        assertEquals("no_supported_unique_tree_postcondition", result.reason);
    }

    @Test
    public void ambiguousOrSimilarLookingTargetsNeverSupplyTextTruth() throws Exception {
        JSONObject before = observation("before-observation", inputNode("before", ""));
        JSONObject after = observation("after-observation", inputNode("after", "独立手机测试成功"));
        after.getJSONArray("nodes").put(inputNode("another-after", "独立手机测试成"));

        TreeActionVerifier.Result result = TreeActionVerifier.verify(
                before, after, setTextAction("before", "独立手机测试成功"), image("before"), image("after"));

        assertEquals(TreeActionVerifier.Status.UNKNOWN, result.status);
        assertEquals("after_target_identity_not_unique", result.reason);
    }

    @Test
    public void visibleLoadingStateIsPendingAndCannotBePromotedByAReceipt() throws Exception {
        JSONObject before = observation("before-observation", inputNode("before", ""));
        JSONObject after = observation("after-observation", inputNode("after", ""));
        after.getJSONArray("nodes").put(new JSONObject().put("node_id", "loading")
                .put("text", "加载中").put("visible_to_user", true));

        TreeActionVerifier.Result result = TreeActionVerifier.verify(
                before, after, setTextAction("before", "独立手机测试成功"), image("before"), image("after"));

        assertEquals(TreeActionVerifier.Status.PENDING, result.status);
    }

    @Test
    public void noTreePostconditionAndScreenshotLossRemainUnknown() throws Exception {
        JSONObject before = observation("before-observation", inputNode("before", ""));
        JSONObject after = observation("after-observation", inputNode("after", ""));

        TreeActionVerifier.Result noEffect = TreeActionVerifier.verify(
                before, after, new JSONObject().put("kind", "tap").put("target_node_id", "before"),
                image("before"), image("after"));
        TreeActionVerifier.Result noAfterImage = TreeActionVerifier.verify(
                before, observation("after-observation", inputNode("after", "独立手机测试成功")),
                setTextAction("before", "独立手机测试成功"), image("before"),
                new JSONObject().put("error_code", "screenshot_unavailable"));

        assertEquals(TreeActionVerifier.Status.UNKNOWN, noEffect.status);
        assertEquals(TreeActionVerifier.Status.UNKNOWN, noAfterImage.status);
        assertEquals("after_screenshot_missing", noAfterImage.reason);
    }

    @Test
    public void missingBeforeImageCannotProveSuccess() throws Exception {
        JSONObject before = observation("before-observation", inputNode("before", ""));
        JSONObject after = observation("after-observation", inputNode("after", "独立手机测试成功"));

        TreeActionVerifier.Result result = TreeActionVerifier.verify(
                before, after, setTextAction("before", "独立手机测试成功"), null, image("after"));

        assertEquals(TreeActionVerifier.Status.UNKNOWN, result.status);
        assertEquals("before_screenshot_missing", result.reason);
    }

    @Test
    public void pendingWaitBoundAndFrozenPolicyDigestAreExplicit() throws Exception {
        assertEquals(600L, TreeActionVerifier.WAIT_INTERVAL_MILLIS);
        assertEquals(2, TreeActionVerifier.MAX_WAIT_ATTEMPTS);
        assertEquals(TreeActionVerifier.policySha256(), TreeActionVerifier.POLICY_SHA256);
        assertFalse(TreeActionVerifier.POLICY_SHA256.contains("PENDING"));
        assertEquals(TreeActionVerifier.Status.UNKNOWN,
                TreeActionVerifier.parseModelStatus("SUCCESS maybe"));
        assertEquals(TreeActionVerifier.Status.UNKNOWN,
                TreeActionVerifier.parseModelStatus(""));
        assertEquals(TreeActionVerifier.Status.FAILURE,
                TreeActionVerifier.parseModelStatus("failure: no effect"));
        assertTrue(TreeActionVerifier.hasImage(image("png")));
    }

    private static JSONObject observation(String id, JSONObject node) throws Exception {
        return new JSONObject().put("availability", "AVAILABLE").put("observation_id", id)
                .put("nodes", new JSONArray().put(node));
    }

    private static JSONObject inputNode(String nodeId, String text) throws Exception {
        return new JSONObject().put("node_id", nodeId).put("class_name", "android.widget.EditText")
                .put("package_name", "com.jev.mobileagent").put("content_description", "中文输入框")
                .put("editable", true).put("visible_to_user", true).put("text", text);
    }

    private static JSONObject setTextAction(String targetId, String text) throws Exception {
        return new JSONObject().put("kind", "set_text").put("target_node_id", targetId)
                .put("parameters", new JSONObject().put("text", text));
    }

    private static JSONObject image(String id) throws Exception {
        return new JSONObject().put("screenshot_id", id).put("png_base64", "cG5n");
    }
}
