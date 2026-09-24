package com.jev.mobileagent;

import org.json.JSONArray;
import org.json.JSONObject;

/** Safety rules for releasing app-local tasks after a control request. */
final class LocalTaskControlPolicy {
    private LocalTaskControlPolicy() {
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
                    && !"not_dispatched".equals(phase)) {
                return true;
            }
        }
        return false;
    }
}
