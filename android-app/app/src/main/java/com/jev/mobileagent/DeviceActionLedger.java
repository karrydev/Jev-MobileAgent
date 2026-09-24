package com.jev.mobileagent;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;


/** Minimal durable task/action evidence used only for explicit recovery. */
public final class DeviceActionLedger {
    private static final Object LOCK = new Object();
    private static final String TASK_PREFIX = "device_recovery.task.";
    private static final String ACTION_PREFIX = "device_recovery.action.";
    private static final String ACTIVE_TASK = "device_recovery.active_task_id";
    private static final String PHASE_PENDING = "PENDING";
    private static final String PHASE_RECEIPT_RECORDED = "RECEIPT_RECORDED";

    private DeviceActionLedger() {
    }

    public static final class TaskRecord {
        public final String taskId;
        public final String deviceSessionId;
        public final boolean vlm;

        private TaskRecord(String taskId, String deviceSessionId, boolean vlm) {
            this.taskId = taskId;
            this.deviceSessionId = deviceSessionId;
            this.vlm = vlm;
        }
    }

    /** Start a new durable marker before the task request can reach the bridge. */
    public static boolean beginTask(Context context, BridgeConfig config, boolean vlm) {
        if (context == null || config == null || config.taskId.isEmpty()
                || config.deviceSessionId.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            if (!preferences.getString(ACTIVE_TASK, "").isEmpty()
                    || preferences.contains(taskKey(config.taskId))) {
                return false;
            }
            SharedPreferences.Editor editor = preferences.edit();
            String actionPrefix = actionPrefix(config.taskId);
            for (String key : preferences.getAll().keySet()) {
                if (key.startsWith(actionPrefix)) {
                    editor.remove(key);
                }
            }
            try {
                JSONObject task = new JSONObject();
                task.put("task_id", config.taskId);
                task.put("device_session_id", config.deviceSessionId);
                task.put("vlm", vlm);
                return editor.putString(taskKey(config.taskId), task.toString())
                        .putString(ACTIVE_TASK, config.taskId)
                        .commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    public static TaskRecord task(Context context, String taskId) {
        if (context == null || taskId == null || taskId.isEmpty()) {
            return null;
        }
        synchronized (LOCK) {
            String value = preferences(context).getString(taskKey(taskId), null);
            if (value == null) {
                return null;
            }
            try {
                JSONObject task = new JSONObject(value);
                if (!taskId.equals(task.optString("task_id", ""))) {
                    return null;
                }
                return new TaskRecord(
                        taskId,
                        task.optString("device_session_id", ""),
                        task.optBoolean("vlm", false));
            } catch (JSONException exception) {
                return null;
            }
        }
    }

    /** Find the outstanding local task even if the editable task id changed. */
    public static TaskRecord activeTask(Context context) {
        if (context == null) {
            return null;
        }
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            String activeTaskId = preferences.getString(ACTIVE_TASK, "");
            if (!activeTaskId.isEmpty()) {
                return task(context, activeTaskId);
            }
            // Recover markers written before the active-task pointer existed.
            for (String key : preferences.getAll().keySet()) {
                if (key.startsWith(TASK_PREFIX)) {
                    String value = preferences.getString(key, null);
                    if (value == null) {
                        continue;
                    }
                    try {
                        String taskId = new JSONObject(value).optString("task_id", "");
                        TaskRecord record = task(context, taskId);
                        if (record != null) {
                            preferences.edit().putString(ACTIVE_TASK, taskId).commit();
                            return record;
                        }
                    } catch (JSONException ignored) {
                        // A malformed marker is not treated as safe recovery evidence.
                    }
                }
            }
            return null;
        }
    }

    /** Persist PENDING before sending command-intent; existing action ids are never re-dispatched. */
    public static boolean beginAction(
            Context context,
            BridgeConfig config,
            JSONObject action) {
        if (context == null || config == null || action == null) {
            return false;
        }
        String actionId = action.optString("action_id", "");
        if (actionId.isEmpty() || config.deviceSessionId.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            if (task(context, config.taskId) == null) {
                return false;
            }
            String key = actionKey(config.taskId, actionId);
            if (preferences.contains(key)) {
                return false;
            }
            try {
                JSONObject record = new JSONObject();
                record.put("task_id", config.taskId);
                record.put("action_id", actionId);
                record.put("device_id", config.deviceId);
                record.put("device_session_id", config.deviceSessionId);
                record.put("action_kind", action.optString("kind", ""));
                record.put("phase", PHASE_PENDING);
                record.put("receipt", JSONObject.NULL);
                return preferences.edit().putString(key, record.toString()).commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    /** Persist the full receipt before the Activity is allowed to upload it. */
    public static boolean recordReceipt(
            Context context,
            String taskId,
            String actionId,
            JSONObject receipt) {
        if (context == null || taskId == null || actionId == null || receipt == null) {
            return false;
        }
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            String key = actionKey(taskId, actionId);
            String value = preferences.getString(key, null);
            if (value == null) {
                return false;
            }
            try {
                JSONObject record = new JSONObject(value);
                if (!taskId.equals(record.optString("task_id", ""))
                        || !actionId.equals(record.optString("action_id", ""))
                        || !taskId.equals(receipt.optString("task_id", ""))
                        || !actionId.equals(receipt.optString("action_id", ""))
                        || !record.optString("device_id", "").equals(receipt.optString("device_id", ""))) {
                    return false;
                }
                if (!PHASE_PENDING.equals(record.optString("phase", ""))) {
                    return PHASE_RECEIPT_RECORDED.equals(record.optString("phase", ""))
                            && receipt.toString().equals(record.optJSONObject("receipt") == null
                                    ? "" : record.optJSONObject("receipt").toString());
                }
                record.put("phase", PHASE_RECEIPT_RECORDED);
                record.put("receipt", receipt);
                return preferences.edit().putString(key, record.toString()).commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    /** Return null when this device has no matching durable action record. */
    public static JSONObject deviceRecord(Context context, String taskId, String actionId) {
        if (context == null || taskId == null || taskId.isEmpty()
                || actionId == null || actionId.isEmpty()) {
            return null;
        }
        synchronized (LOCK) {
            String value = preferences(context).getString(actionKey(taskId, actionId), null);
            if (value == null) {
                return null;
            }
            try {
                JSONObject stored = new JSONObject(value);
                if (!taskId.equals(stored.optString("task_id", ""))
                        || !actionId.equals(stored.optString("action_id", ""))) {
                    return null;
                }
                JSONObject result = new JSONObject();
                result.put("action_id", actionId);
                String phase = stored.optString("phase", PHASE_PENDING);
                if (!PHASE_PENDING.equals(phase) && !PHASE_RECEIPT_RECORDED.equals(phase)) {
                    return null;
                }
                result.put("phase", phase);
                JSONObject receipt = stored.optJSONObject("receipt");
                if (PHASE_RECEIPT_RECORDED.equals(phase) && receipt == null) {
                    return null;
                }
                if (receipt != null && (!taskId.equals(receipt.optString("task_id", ""))
                        || !actionId.equals(receipt.optString("action_id", ""))
                        || !stored.optString("device_id", "").equals(receipt.optString("device_id", "")))) {
                    return null;
                }
                result.put("receipt", PHASE_RECEIPT_RECORDED.equals(phase) && receipt != null
                        ? receipt : JSONObject.NULL);
                return result;
            } catch (JSONException exception) {
                return null;
            }
        }
    }

    /** A confirmed server result of NOT_EXECUTED permits a safe retry after explicit resume. */
    public static boolean clearKnownNotExecutedAction(Context context, String taskId, String actionId) {
        if (context == null || taskId == null || actionId == null || actionId.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            return preferences(context).edit().remove(actionKey(taskId, actionId)).commit();
        }
    }

    public static boolean clearTask(Context context, String taskId) {
        if (context == null || taskId == null || taskId.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            SharedPreferences.Editor editor = preferences.edit().remove(taskKey(taskId));
            if (taskId.equals(preferences.getString(ACTIVE_TASK, ""))) {
                editor.remove(ACTIVE_TASK);
            }
            String actionPrefix = actionPrefix(taskId);
            for (String key : preferences.getAll().keySet()) {
                if (key.startsWith(actionPrefix)) {
                    editor.remove(key);
                }
            }
            return editor.commit();
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(BridgeConfig.PREFS, Context.MODE_PRIVATE);
    }

    private static String taskKey(String taskId) {
        return TASK_PREFIX + taskId.length() + ":" + taskId;
    }

    private static String actionPrefix(String taskId) {
        return ACTION_PREFIX + taskId.length() + ":" + taskId + ":";
    }

    private static String actionKey(String taskId, String actionId) {
        return actionPrefix(taskId) + actionId;
    }
}
