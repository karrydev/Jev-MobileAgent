package com.jev.mobileagent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class CoordinateObservationFingerprintTest {
    @Test
    public void sameWindowScrollRejectsTheEarlierCoordinateObservation() throws Exception {
        JSONObject before = observation("target.app", new JSONObject()
                .put("node_id", "window-1-0-node-0-0")
                .put("parent_node_id", "window-1-0-node-0")
                .put("package_name", "target.app")
                .put("class_name", "android.widget.TextView")
                .put("text", "Blue target")
                .put("bounds", bounds(100, 300, 300, 500)));
        JSONObject afterScroll = observation("target.app", new JSONObject()
                .put("node_id", "window-1-0-node-0-0")
                .put("parent_node_id", "window-1-0-node-0")
                .put("package_name", "target.app")
                .put("class_name", "android.widget.TextView")
                .put("text", "Blue target")
                .put("bounds", bounds(100, 180, 300, 380)));
        JSONObject frame = coordinateFrame(before);

        assertFalse(CoordinateObservationFingerprint.sameCoordinateContext(before, afterScroll, frame));
    }

    @Test
    public void ownTaskStatusTextRefreshDoesNotInvalidateStableScene() throws Exception {
        JSONObject before = observation("com.jev.mobileagent", new JSONObject()
                .put("node_id", "window-7-0-node-1")
                .put("parent_node_id", "window-7-0-node-0")
                .put("package_name", "com.jev.mobileagent")
                .put("class_name", "android.widget.TextView")
                .put("content_description", "Local VLM task status")
                .put("text", "正在读取当前页面")
                .put("bounds", bounds(10, 100, 400, 150)));
        JSONObject afterRefresh = observation("com.jev.mobileagent", new JSONObject()
                .put("node_id", "window-7-0-node-1")
                .put("parent_node_id", "window-7-0-node-0")
                .put("package_name", "com.jev.mobileagent")
                .put("class_name", "android.widget.TextView")
                .put("content_description", "Local VLM task status")
                .put("text", "正在向 VLM 发送 executor 请求")
                .put("bounds", bounds(10, 100, 400, 150)));

        assertTrue(CoordinateObservationFingerprint.sameCoordinateContext(
                before, afterRefresh, coordinateFrame(before)));
    }

    private static JSONObject observation(String packageName, JSONObject contentNode) throws Exception {
        JSONObject bounds = bounds(0, 0, 1000, 2000);
        JSONObject activeWindow = new JSONObject()
                .put("window_id", 7)
                .put("window_type", 1)
                .put("package_name", packageName)
                .put("active", true)
                .put("focused", true)
                .put("layer", 0)
                .put("bounds", bounds);
        JSONObject root = new JSONObject()
                .put("node_id", "window-7-0-node-0")
                .put("parent_node_id", JSONObject.NULL)
                .put("package_name", packageName)
                .put("class_name", "android.widget.FrameLayout")
                .put("bounds", bounds);
        JSONObject screen = new JSONObject()
                .put("width_px", 1000)
                .put("height_px", 2000)
                .put("rotation", 0)
                .put("content_width_px", 1000)
                .put("content_height_px", 2000)
                .put("active_window_id", 7)
                .put("active_window_bounds", bounds)
                .put("window_offset", new JSONObject().put("x", 0).put("y", 0))
                .put("system_bar_insets", new JSONObject()
                        .put("left", 0).put("top", 0).put("right", 0).put("bottom", 0));
        return new JSONObject()
                .put("availability", "AVAILABLE")
                .put("screen", screen)
                .put("windows", new JSONArray().put(activeWindow))
                .put("nodes", new JSONArray().put(root).put(contentNode));
    }

    private static JSONObject coordinateFrame(JSONObject observation) throws Exception {
        JSONObject screen = observation.getJSONObject("screen");
        return new JSONObject(screen.toString())
                .put("screen_width_px", screen.optInt("width_px"))
                .put("screen_height_px", screen.optInt("height_px"));
    }

    private static JSONObject bounds(int left, int top, int right, int bottom) throws Exception {
        return new JSONObject().put("left", left).put("top", top).put("right", right).put("bottom", bottom);
    }
}
