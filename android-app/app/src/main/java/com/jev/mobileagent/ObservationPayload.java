package com.jev.mobileagent;

import android.content.Context;
import android.graphics.Rect;
import android.view.Display;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.UUID;

/** Builders for the versioned Android observation document. */
public final class ObservationPayload {
    private ObservationPayload() {
    }

    public static JSONObject unavailable(
            Context context,
            BridgeConfig config,
            String availability,
            String reason,
            boolean serviceEnabled,
            boolean canObserve) throws JSONException {
        long version = BridgeConfig.nextObservationVersion(context);
        JSONObject observation = base(context, config, version);
        observation.put("page_state", "unavailable");
        observation.put("availability", availability);
        observation.put("unavailable_reason", reason);
        observation.put("permission", new JSONObject()
                .put("service_enabled", serviceEnabled)
                .put("can_observe", canObserve)
                .put("reason", reason));
        observation.put("windows", new JSONArray());
        observation.put("nodes", new JSONArray());
        observation.put("root_node_ids", new JSONArray());
        return observation;
    }

    public static JSONObject base(Context context, BridgeConfig config, long version) throws JSONException {
        JSONObject observation = new JSONObject();
        observation.put("schema_version", "1.0");
        observation.put("android_schema_version", "1.0");
        observation.put("task_id", config.taskId);
        observation.put("observation_id", config.deviceId + "-obs-" + version + "-" + UUID.randomUUID().toString().replace("-", ""));
        observation.put("device_id", config.deviceId);
        observation.put("observation_version", version);
        observation.put("captured_at", Instant.now().toString());
        observation.put("screen", screen(context));
        observation.put("visual", new JSONObject()
                .put("capture_state", "NOT_REQUESTED")
                .put("capture_count", 0)
                .put("upload_count", 0)
                .put("missing_reason", JSONObject.NULL)
                .put("screenshot_id", JSONObject.NULL));
        observation.put("capabilities", new JSONArray()
                .put("accessibility_tree")
                .put("windows")
                .put("screen_metrics")
                .put("node_text")
                .put("node_state")
                .put("tap")
                .put("set_text")
                .put("long_press")
                .put("swipe")
                .put("coordinate_tap")
                .put("system_back")
                .put("screenshot"));
        return observation;
    }

    public static JSONObject screen(Context context) throws JSONException {
        return screen(context, null);
    }

    public static JSONObject screen(Context context, Rect contentBounds) throws JSONException {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        display.getRealMetrics(metrics);
        Rect content = contentBounds == null
                ? new Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
                : new Rect(contentBounds);
        int leftInset = Math.max(0, content.left);
        int topInset = Math.max(0, content.top);
        int rightInset = Math.max(0, metrics.widthPixels - content.right);
        int bottomInset = Math.max(0, metrics.heightPixels - content.bottom);
        return new JSONObject()
                .put("width_px", metrics.widthPixels)
                .put("height_px", metrics.heightPixels)
                .put("rotation", display.getRotation())
                // The observation frame is in physical display coordinates.
                // A window/content frame adds its own offset when a task is
                // planned; zero here is the full-screen default.
                .put("system_bar_insets", new JSONObject()
                        .put("left", leftInset).put("top", topInset)
                        .put("right", rightInset).put("bottom", bottomInset))
                .put("window_offset", new JSONObject().put("x", content.left).put("y", content.top))
                .put("content_width_px", Math.max(1, content.width()))
                .put("content_height_px", Math.max(1, content.height()));
    }

    public static JSONObject bounds(Rect bounds) throws JSONException {
        return new JSONObject()
                .put("left", bounds.left)
                .put("top", bounds.top)
                .put("right", bounds.right)
                .put("bottom", bounds.bottom);
    }
}
