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
        observation.put("capabilities", new JSONArray()
                .put("accessibility_tree")
                .put("windows")
                .put("screen_metrics")
                .put("node_text")
                .put("node_state"));
        return observation;
    }

    public static JSONObject screen(Context context) throws JSONException {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        display.getRealMetrics(metrics);
        return new JSONObject()
                .put("width_px", metrics.widthPixels)
                .put("height_px", metrics.heightPixels)
                .put("rotation", display.getRotation());
    }

    public static JSONObject bounds(Rect bounds) throws JSONException {
        return new JSONObject()
                .put("left", bounds.left)
                .put("top", bounds.top)
                .put("right", bounds.right)
                .put("bottom", bounds.bottom);
    }
}
