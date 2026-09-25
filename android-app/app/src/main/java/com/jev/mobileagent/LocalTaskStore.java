package com.jev.mobileagent;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Private, durable journal for the standalone VLM loop; separate from the old bridge recovery journal. */
public final class LocalTaskStore {
    private static final Object LOCK = new Object();
    private static final String PREFS = "jev_standalone_vlm_tasks_v1";
    private static final String ACTIVE_TASK_ID = "active_task_id";
    private static final String LATEST_TASK_ID = "latest_task_id";
    private static final String GLOBAL_ACCOUNTED_CNY = "global_accounted_cny";
    private static final String GLOBAL_JEV_SHADOW_CALLS = "global_jev_shadow_calls";
    private static final String JEV_SHADOW_ENABLED = "jev_shadow_enabled";
    private static final String JEV_SELECTION_ENABLED = "jev_selection_enabled";
    private static final String TREE_VERIFICATION_ENABLED = "tree_verification_enabled";
    private static final String ON_DEMAND_PLANNING_ENABLED = "on_demand_planning_enabled";
    private static final String TASK_PREFIX = "task.";

    public static final int MAX_STEPS = 5;
    public static final int MAX_REQUESTS = 25;
    public static final int MAX_OUTPUT_TOKENS = LocalVlmBudgetPolicy.OUTPUT_TOKEN_LIMIT;
    public static final double TASK_BUDGET_CNY = 1.0;
    public static final double GLOBAL_BUDGET_CNY = 10.0;
    /** Price reservation estimate based on the existing transport's 8192-token input allowance. */
    public static final int MAX_INPUT_TOKENS_RESERVED = LocalVlmBudgetPolicy.INPUT_TOKEN_RESERVATION_ESTIMATE;
    public static final double INPUT_PRICE_PER_MILLION = LocalVlmBudgetPolicy.INPUT_CNY_PER_MILLION;
    public static final double OUTPUT_PRICE_PER_MILLION = LocalVlmBudgetPolicy.OUTPUT_CNY_PER_MILLION;
    public static final double REQUEST_RESERVATION_CNY = LocalVlmBudgetPolicy.requestReservationCny();

    private LocalTaskStore() {
    }

    public static JSONObject create(Context context, String goal, String provider, String model, boolean armed) {
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            String activeId = preferences.getString(ACTIVE_TASK_ID, "");
            JSONObject active = task(context, activeId);
            if (active != null && isActiveState(active.optString("state", ""))) {
                return null;
            }
            String id = UUID.randomUUID().toString();
            JSONObject record = new JSONObject();
            try {
                record.put("schema_version", "standalone-vlm-task-v1");
                record.put("task_id", id);
                record.put("goal", goal == null ? "" : goal.trim());
                record.put("provider", provider == null ? "" : provider);
                record.put("model", model == null ? "" : model);
                record.put("state", armed ? "ARMED" : "RUNNING");
                record.put("state_reason", armed ? "ready_for_target_app" : "");
                record.put("created_at", Instant.now().toString());
                record.put("updated_at", Instant.now().toString());
                record.put("max_steps", MAX_STEPS);
                record.put("max_requests", MAX_REQUESTS);
                record.put("max_jev_shadow_calls", JevShadowBudgetPolicy.MAX_CALLS_PER_TASK);
                record.put("max_output_tokens", MAX_OUTPUT_TOKENS);
                record.put("budget_cny", TASK_BUDGET_CNY);
                record.put("request_count", 0);
                record.put("step_count", 0);
                record.put("accounted_cost_cny", 0.0);
                record.put("cost_status", "known_so_far");
                record.put("usage_missing", false);
                record.put("jev_shadow_enabled", preferences.getBoolean(JEV_SHADOW_ENABLED, false));
                record.put("jev_selection_enabled", preferences.getBoolean(JEV_SELECTION_ENABLED, false));
                record.put("tree_verification_enabled", preferences.getBoolean(TREE_VERIFICATION_ENABLED, false));
                record.put("on_demand_planning_enabled", preferences.getBoolean(ON_DEMAND_PLANNING_ENABLED, false));
                record.put("planning_plan_step", -1);
                record.put("tree_verification_policy_id", TreeActionVerifier.POLICY_ID);
                record.put("tree_verification_policy_sha256", TreeActionVerifier.POLICY_SHA256);
                record.put("jev_shadow_call_count", 0);
                record.put("jev_shadow_reserved_cny", 0.0);
                record.put("requests", new JSONArray());
                record.put("screenshot_captures", new JSONArray());
                record.put("jev_shadow_attempts", new JSONArray());
                record.put("planning_events", new JSONArray());
                record.put("actions", new JSONArray());
                record.put("observations", new JSONArray());
                record.put("history", new JSONObject());
            } catch (JSONException exception) {
                return null;
            }
            boolean saved = preferences.edit()
                    .putString(taskKey(id), record.toString())
                    .putString(ACTIVE_TASK_ID, id)
                    .putString(LATEST_TASK_ID, id)
                    .commit();
            return saved ? record : null;
        }
    }

    public static JSONObject activeTask(Context context) {
        synchronized (LOCK) {
            return task(context, preferences(context).getString(ACTIVE_TASK_ID, ""));
        }
    }

    public static JSONObject latestTask(Context context) {
        synchronized (LOCK) {
            return task(context, preferences(context).getString(LATEST_TASK_ID, ""));
        }
    }

    public static JSONObject task(Context context, String taskId) {
        if (context == null || taskId == null || taskId.isEmpty()) {
            return null;
        }
        String value = preferences(context).getString(taskKey(taskId), null);
        if (value == null) {
            return null;
        }
        try {
            JSONObject task = new JSONObject(value);
            return taskId.equals(task.optString("task_id", "")) ? task : null;
        } catch (JSONException exception) {
            return null;
        }
    }

    public static boolean updateState(Context context, String taskId, String state, String reason) {
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            if (task == null) {
                return false;
            }
            try {
                task.put("state", state);
                task.put("state_reason", reason == null ? "" : reason);
                touch(task);
                SharedPreferences.Editor editor = preferences(context).edit().putString(taskKey(taskId), task.toString());
                if (!isActiveState(state)
                        && taskId.equals(preferences(context).getString(ACTIVE_TASK_ID, ""))) {
                    editor.remove(ACTIVE_TASK_ID);
                }
                return editor.commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    /** Mark an orphaned app-local task paused; never consults or edits the bridge task journal. */
    public static boolean markInterrupted(Context context) {
        JSONObject active = activeTask(context);
        if (active == null) {
            return false;
        }
        String state = active.optString("state", "");
        if (!"ARMED".equals(state) && !"RUNNING".equals(state) && !"PAUSING".equals(state)) {
            return false;
        }
        boolean unresolvedAction = LocalTaskControlPolicy.hasUnresolvedDeviceAction(active);
        return updateState(context, active.optString("task_id", ""),
                unresolvedAction ? "NEEDS_REVIEW" : "PAUSED",
                unresolvedAction ? "app_or_runtime_interrupted_with_unresolved_action"
                        : "app_or_runtime_interrupted; manual review required");
    }

    public static boolean setStep(Context context, String taskId, int step) {
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            if (task == null || step < 0 || step > MAX_STEPS) {
                return false;
            }
            try {
                task.put("step_count", step);
                touch(task);
                return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    public static boolean updateRuntimeStatus(Context context, String taskId, String status) {
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            if (task == null) {
                return false;
            }
            try {
                task.put("runtime_status", status == null ? "" : status);
                touch(task);
                return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    /** Shadow suggestions are task-scoped: settings are snapshotted when a task is created. */
    public static boolean setJevShadowEnabled(Context context, boolean enabled) {
        synchronized (LOCK) {
            return preferences(context).edit().putBoolean(JEV_SHADOW_ENABLED, enabled).commit();
        }
    }

    public static boolean isJevShadowEnabled(Context context) {
        synchronized (LOCK) {
            return preferences(context).getBoolean(JEV_SHADOW_ENABLED, false);
        }
    }

    /** Controlled selection is opt-in and snapshotted when a task is created. */
    public static boolean setJevSelectionEnabled(Context context, boolean enabled) {
        synchronized (LOCK) {
            return preferences(context).edit().putBoolean(JEV_SELECTION_ENABLED, enabled).commit();
        }
    }

    public static boolean isJevSelectionEnabled(Context context) {
        synchronized (LOCK) {
            return preferences(context).getBoolean(JEV_SELECTION_ENABLED, false);
        }
    }

    /** Tree action verification is opt-in and snapshotted separately from Jev selection. */
    public static boolean setTreeVerificationEnabled(Context context, boolean enabled) {
        synchronized (LOCK) {
            return preferences(context).edit().putBoolean(TREE_VERIFICATION_ENABLED, enabled).commit();
        }
    }

    public static boolean isTreeVerificationEnabled(Context context) {
        synchronized (LOCK) {
            return preferences(context).getBoolean(TREE_VERIFICATION_ENABLED, false);
        }
    }

    /** On-demand planning is opt-in and snapshotted when each task is created. */
    public static boolean setOnDemandPlanningEnabled(Context context, boolean enabled) {
        synchronized (LOCK) {
            return preferences(context).edit().putBoolean(ON_DEMAND_PLANNING_ENABLED, enabled).commit();
        }
    }

    public static boolean isOnDemandPlanningEnabled(Context context) {
        synchronized (LOCK) {
            return preferences(context).getBoolean(ON_DEMAND_PLANNING_ENABLED, false);
        }
    }

    public static boolean setPlanningPlanStep(Context context, String taskId, int step) {
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            if (task == null || step < 0 || step >= MAX_STEPS) return false;
            try {
                task.put("planning_plan_step", step);
                touch(task);
                return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    /** Append planner scheduling and outcome reasons to the same private task journal as role calls. */
    public static boolean recordPlanningEvent(Context context, String taskId, JSONObject event) {
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            if (task == null || event == null) return false;
            JSONArray events = task.optJSONArray("planning_events");
            if (events == null) events = new JSONArray();
            events.put(event);
            try {
                task.put("planning_events", events);
                touch(task);
                return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    /** Keep the final controlled-selection outcome beside its reserved attempt. */
    public static boolean annotateJevSelectionAttempt(Context context, String taskId, int zeroBasedStep,
            JSONObject annotations) {
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            JSONArray attempts = task == null ? null : task.optJSONArray("jev_shadow_attempts");
            if (attempts == null || annotations == null) return false;
            for (int i = attempts.length() - 1; i >= 0; i--) {
                JSONObject attempt = attempts.optJSONObject(i);
                if (attempt == null || attempt.optInt("step", -1) != zeroBasedStep + 1
                        || !"task_controlled".equals(attempt.optString("source", ""))) {
                    continue;
                }
                try {
                    java.util.Iterator<String> keys = annotations.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        attempt.put(key, annotations.opt(key));
                    }
                    attempts.put(i, attempt);
                    task.put("jev_shadow_attempts", attempts);
                    touch(task);
                    return preferences(context).edit()
                            .putString(taskKey(taskId), task.toString()).commit();
                } catch (JSONException exception) {
                    return false;
                }
            }
            return false;
        }
    }

    /** Reserve the frozen CNY hold before sending one Jev request. */
    public static Reservation reserveJevShadowCall(Context context, String taskId, int step,
            JSONObject attemptBase) {
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            JSONObject task = task(context, taskId);
            if (task == null || !"RUNNING".equals(task.optString("state", ""))) {
                return Reservation.denied("task_not_running");
            }
            if (step < 0 || step >= MAX_STEPS) {
                return Reservation.denied("max_steps");
            }
            JSONArray attempts = task.optJSONArray("jev_shadow_attempts");
            if (attempts == null || attemptBase == null) {
                return Reservation.denied("attempt_journal_invalid");
            }
            if (hasSentJevRequestForPurpose(attempts, step + 1, jevRequestPurpose(attemptBase))) {
                return Reservation.denied("jev_step_already_requested");
            }
            int taskCalls = task.optInt("jev_shadow_call_count", 0);
            int globalCalls = shadowCallCount(preferences);
            if (taskCalls >= JevShadowBudgetPolicy.MAX_CALLS_PER_TASK) {
                return Reservation.denied("task_jev_call_limit");
            }
            if (globalCalls >= JevShadowBudgetPolicy.MAX_TOTAL_CALLS) {
                return Reservation.denied("global_jev_call_limit");
            }
            double reserve = JevShadowBudgetPolicy.RESERVATION_CNY_PER_CALL;
            double taskCost = task.optDouble("accounted_cost_cny", 0.0);
            double globalCost = accountedTotal(preferences);
            if (LocalVlmBudgetPolicy.exceeds(taskCost + reserve, TASK_BUDGET_CNY)) {
                return Reservation.denied("task_budget_reserved");
            }
            if (LocalVlmBudgetPolicy.exceeds(globalCost + reserve, GLOBAL_BUDGET_CNY)) {
                return Reservation.denied("global_budget_reserved");
            }
            JSONObject attempt;
            try {
                attempt = new JSONObject(attemptBase.toString())
                        .put("attempt", attempts.length() + 1)
                        .put("step", step + 1)
                        .put("status", "reserved")
                        .put("request_sent", false)
                        .put("http_status", JSONObject.NULL)
                        .put("cost", new JSONObject()
                                .put("status", "reserved_unknown_actual")
                                .put("currency", "USD")
                                .put("amount_usd", JSONObject.NULL)
                                .put("fx_status", JevShadowBudgetPolicy.FX_STATUS)
                                .put("actual_bill_status", JevShadowBudgetPolicy.ACTUAL_BILL_STATUS)
                                .put("reserved_cny", reserve));
                attempts.put(attempt);
                task.put("jev_shadow_attempts", attempts);
                task.put("jev_shadow_call_count", taskCalls + 1);
                task.put("jev_shadow_reserved_cny",
                        task.optDouble("jev_shadow_reserved_cny", 0.0) + reserve);
                task.put("accounted_cost_cny", taskCost + reserve);
                task.put("cost_status", "reserved_or_partial");
                touch(task);
                boolean saved = preferences.edit()
                        .putString(taskKey(taskId), task.toString())
                        .putString(GLOBAL_ACCOUNTED_CNY, Double.toString(globalCost + reserve))
                        .putInt(GLOBAL_JEV_SHADOW_CALLS, globalCalls + 1)
                        .commit();
                return saved ? Reservation.allowed(attempts.length() - 1, reserve)
                        : Reservation.denied("jev_reservation_not_saved");
            } catch (JSONException exception) {
                return Reservation.denied("attempt_journal_invalid");
            }
        }
    }

    static boolean hasSentJevRequestForPurpose(JSONArray attempts, int oneBasedStep,
            String requestedPurpose) {
        if (attempts == null) return false;
        String requested = requestedPurpose == null ? "" : requestedPurpose.trim();
        for (int i = 0; i < attempts.length(); i++) {
            JSONObject prior = attempts.optJSONObject(i);
            if (prior == null || prior.optInt("step", -1) != oneBasedStep
                    || !prior.optBoolean("request_sent", false)) {
                continue;
            }
            String priorPurpose = jevRequestPurpose(prior);
            // Legacy records without a recognizable purpose conservatively block every replay.
            if (requested.isEmpty() || priorPurpose.isEmpty() || requested.equals(priorPurpose)) {
                return true;
            }
        }
        return false;
    }

    private static String jevRequestPurpose(JSONObject attempt) {
        if (attempt == null) return "";
        String purpose = attempt.optString("request_purpose", "").trim();
        if (!purpose.isEmpty()) return purpose;
        switch (attempt.optString("source", "").trim()) {
            case "task_controlled": return "selection";
            case "task_verification": return "verification";
            case "task_shadow": return "shadow";
            case "debug_case":
            case "debug_protocol_probe": return "debug";
            default: return "";
        }
    }

    /** Complete a reserved Jev attempt without converting its USD usage to CNY. */
    public static boolean completeJevShadowCall(Context context, String taskId, int attemptIndex,
            JSONObject completion) {
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            JSONObject task = task(context, taskId);
            JSONArray attempts = task == null ? null : task.optJSONArray("jev_shadow_attempts");
            if (attempts == null || completion == null || attemptIndex < 0 || attemptIndex >= attempts.length()) {
                return false;
            }
            JSONObject reserved = attempts.optJSONObject(attemptIndex);
            if (reserved == null || !"reserved".equals(reserved.optString("status", ""))) {
                return false;
            }
            try {
                JSONObject finished = new JSONObject(completion.toString())
                        .put("attempt", reserved.optInt("attempt", attemptIndex + 1))
                        .put("step", reserved.optInt("step", 0));
                double reserve = reserved.optJSONObject("cost") == null ? 0.0
                        : reserved.getJSONObject("cost").optDouble("reserved_cny", 0.0);
                boolean requestSent = finished.optBoolean("request_sent", false);
                if (!requestSent) {
                    JSONObject cost = finished.optJSONObject("cost");
                    if (cost == null) cost = new JSONObject();
                    cost.put("reserved_cny", 0.0).put("status", "released_before_send");
                    finished.put("cost", cost);
                    double taskCost = Math.max(0.0,
                            task.optDouble("accounted_cost_cny", 0.0) - reserve);
                    double globalCost = Math.max(0.0, accountedTotal(preferences) - reserve);
                    double taskReserve = Math.max(0.0,
                            task.optDouble("jev_shadow_reserved_cny", 0.0) - reserve);
                    int taskCalls = Math.max(0, task.optInt("jev_shadow_call_count", 0) - 1);
                    int globalCalls = Math.max(0, shadowCallCount(preferences) - 1);
                    task.put("accounted_cost_cny", taskCost);
                    task.put("jev_shadow_reserved_cny", taskReserve);
                    task.put("jev_shadow_call_count", taskCalls);
                    attempts.put(attemptIndex, finished);
                    task.put("jev_shadow_attempts", attempts);
                    touch(task);
                    return preferences.edit().putString(taskKey(taskId), task.toString())
                            .putString(GLOBAL_ACCOUNTED_CNY, Double.toString(globalCost))
                            .putInt(GLOBAL_JEV_SHADOW_CALLS, globalCalls).commit();
                }
                JSONObject cost = finished.optJSONObject("cost");
                if (cost == null) cost = new JSONObject();
                cost.put("reserved_cny", reserve)
                        .put("status", "reserved_actual_unknown")
                        .put("currency", "USD")
                        .put("amount_usd", JSONObject.NULL)
                        .put("fx_status", JevShadowBudgetPolicy.FX_STATUS)
                        .put("actual_bill_status", JevShadowBudgetPolicy.ACTUAL_BILL_STATUS);
                finished.put("cost", cost);
                attempts.put(attemptIndex, finished);
                task.put("jev_shadow_attempts", attempts);
                touch(task);
                return preferences.edit().putString(taskKey(taskId), task.toString()).commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    /** Save a fallback or gate result that did not reserve or send a Jev request. */
    public static boolean recordJevShadowAttempt(Context context, String taskId, JSONObject attempt) {
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            if (task == null || attempt == null) return false;
            JSONArray attempts = task.optJSONArray("jev_shadow_attempts");
            if (attempts == null) attempts = new JSONArray();
            try {
                JSONObject saved = new JSONObject(attempt.toString())
                        .put("attempt", attempts.length() + 1)
                        .put("request_sent", false);
                attempts.put(saved);
                task.put("jev_shadow_attempts", attempts);
                touch(task);
                return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    /** Record which local screenshots are included in this already-reserved request. */
    public static boolean recordRequestImages(Context context, String taskId, int attemptIndex,
            JSONArray screenshotIds) {
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            JSONArray requests = task == null ? null : task.optJSONArray("requests");
            if (requests == null || attemptIndex < 0 || attemptIndex >= requests.length()) {
                return false;
            }
            JSONObject request = requests.optJSONObject(attemptIndex);
            if (request == null || !"reserved".equals(request.optString("status", ""))) {
                return false;
            }
            try {
                JSONArray safeIds = screenshotIds == null ? new JSONArray() : new JSONArray(screenshotIds.toString());
                request.put("image_count", safeIds.length());
                request.put("screenshot_ids", safeIds);
                touch(task);
                return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    /** Keep a durable count of screenshot attempts, including captures Android refused. */
    public static boolean recordScreenshotCapture(Context context, String taskId, JSONObject capture) {
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            if (task == null || capture == null) return false;
            JSONArray captures = task.optJSONArray("screenshot_captures");
            if (captures == null) captures = new JSONArray();
            try {
                captures.put(new JSONObject(capture.toString()).put("attempt", captures.length() + 1));
                task.put("screenshot_captures", captures);
                touch(task);
                return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    /** Refund a reservation only when the caller proves no request was opened. */
    public static boolean releaseUnsentRequest(Context context, String taskId, int attemptIndex,
            String reason) {
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            if (!preferences.contains(GLOBAL_ACCOUNTED_CNY)) {
                return false;
            }
            JSONObject task = task(context, taskId);
            JSONArray requests = task == null ? null : task.optJSONArray("requests");
            if (requests == null || attemptIndex < 0 || attemptIndex >= requests.length()) {
                return false;
            }
            JSONObject request = requests.optJSONObject(attemptIndex);
            if (request == null || !"reserved".equals(request.optString("status", ""))) {
                return false;
            }
            try {
                double reservation = request.optDouble("reserved_cost_cny", 0.0);
                request.put("status", "cancelled_before_send");
                request.put("error_code", reason == null ? "request_not_started" : reason);
                request.put("reserved_cost_cny", 0.0);
                double taskTotal = Math.max(0.0, task.optDouble("accounted_cost_cny", 0.0) - reservation);
                double globalTotal = Math.max(0.0, accountedTotal(preferences) - reservation);
                task.put("accounted_cost_cny", taskTotal);
                task.put("cost_status", containsUnknownUsage(requests) ? "unknown" : "known_so_far");
                task.put("requests", requests);
                touch(task);
                return preferences.edit()
                        .putString(taskKey(taskId), task.toString())
                        .putString(GLOBAL_ACCOUNTED_CNY, Double.toString(globalTotal))
                        .commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    /** Reserve the reviewed pricing estimate before a paid request leaves the phone. */
    public static Reservation reserveRequest(Context context, String taskId, String role, int step) {
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            JSONObject task = task(context, taskId);
            if (task == null || !"RUNNING".equals(task.optString("state", ""))) {
                return Reservation.denied("task_not_running");
            }
            JSONArray requests = task.optJSONArray("requests");
            if (requests == null) {
                return Reservation.denied("request_journal_invalid");
            }
            if (requests.length() >= MAX_REQUESTS) {
                return Reservation.denied("max_requests");
            }
            if (step < 0 || step >= MAX_STEPS) {
                return Reservation.denied("max_steps");
            }
            double taskCost = task.optDouble("accounted_cost_cny", 0.0);
            double globalCost = accountedTotal(preferences);
            if (LocalVlmBudgetPolicy.exceeds(taskCost + REQUEST_RESERVATION_CNY, TASK_BUDGET_CNY)) {
                return Reservation.denied("task_budget_reserved");
            }
            if (LocalVlmBudgetPolicy.exceeds(globalCost + REQUEST_RESERVATION_CNY, GLOBAL_BUDGET_CNY)) {
                return Reservation.denied("global_budget_reserved");
            }
            int index = requests.length();
            JSONObject attempt = new JSONObject();
            try {
                attempt.put("attempt", index + 1);
                attempt.put("role", role == null ? "unknown" : role);
                attempt.put("step", step + 1);
                attempt.put("status", "reserved");
                attempt.put("usage", JSONObject.NULL);
                attempt.put("usage_present", false);
                attempt.put("estimated_cost_cny", JSONObject.NULL);
                attempt.put("reserved_cost_cny", REQUEST_RESERVATION_CNY);
                attempt.put("http_status", JSONObject.NULL);
                attempt.put("error_code", JSONObject.NULL);
                requests.put(attempt);
                task.put("request_count", requests.length());
                task.put("accounted_cost_cny", taskCost + REQUEST_RESERVATION_CNY);
                task.put("cost_status", "reserved_or_partial");
                touch(task);
                boolean saved = preferences.edit()
                        .putString(taskKey(taskId), task.toString())
                        .putString(GLOBAL_ACCOUNTED_CNY, Double.toString(globalCost + REQUEST_RESERVATION_CNY))
                        .commit();
                return saved ? Reservation.allowed(index, REQUEST_RESERVATION_CNY)
                        : Reservation.denied("request_reservation_not_saved");
            } catch (JSONException exception) {
                return Reservation.denied("request_journal_invalid");
            }
        }
    }

    /** Usage missing/partial retains its reservation; actual usage above either cap pauses future calls. */
    public static Settlement finishRequest(Context context, String taskId, int attemptIndex,
            Long inputTokens, Long outputTokens, Long totalTokens,
            int httpStatus, String errorCode) {
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            if (!preferences.contains(GLOBAL_ACCOUNTED_CNY)) {
                return Settlement.invalid();
            }
            JSONObject task = task(context, taskId);
            JSONArray requests = task == null ? null : task.optJSONArray("requests");
            if (requests == null || attemptIndex < 0 || attemptIndex >= requests.length()) {
                return Settlement.invalid();
            }
            JSONObject attempt = requests.optJSONObject(attemptIndex);
            if (attempt == null || !"reserved".equals(attempt.optString("status", ""))) {
                return Settlement.invalid();
            }
            try {
                double reservation = attempt.optDouble("reserved_cost_cny", 0.0);
                boolean usageKnown = validTokenCount(inputTokens) && validTokenCount(outputTokens);
                JSONObject usage = new JSONObject();
                usage.put("input_tokens", usageKnown ? inputTokens : JSONObject.NULL);
                usage.put("output_tokens", usageKnown ? outputTokens : JSONObject.NULL);
                usage.put("total_tokens", validTokenCount(totalTokens) ? totalTokens : JSONObject.NULL);
                attempt.put("usage_present", inputTokens != null || outputTokens != null || totalTokens != null);
                attempt.put("usage", attempt.optBoolean("usage_present", false) ? usage : JSONObject.NULL);
                attempt.put("http_status", httpStatus > 0 ? httpStatus : JSONObject.NULL);
                attempt.put("error_code", errorCode == null ? JSONObject.NULL : errorCode);
                double taskCost = task.optDouble("accounted_cost_cny", 0.0);
                double globalCost = accountedTotal(preferences);
                if (usageKnown) {
                    double actual = LocalVlmBudgetPolicy.actualCostCny(inputTokens, outputTokens);
                    attempt.put("status", "usage_known");
                    attempt.put("estimated_cost_cny", actual);
                    taskCost = taskCost - reservation + actual;
                    globalCost = globalCost - reservation + actual;
                    attempt.put("reserved_cost_cny", 0.0);
                } else {
                    attempt.put("status", "usage_unknown");
                    attempt.put("estimated_cost_cny", JSONObject.NULL);
                    task.put("usage_missing", true);
                }
                if (httpStatus > 0) {
                    attempt.put("http_status", httpStatus);
                }
                task.put("accounted_cost_cny", Math.max(0.0, taskCost));
                task.put("cost_status", containsUnknownUsage(requests) ? "unknown" : "known_so_far");
                boolean exceeded = LocalVlmBudgetPolicy.exceeds(taskCost, TASK_BUDGET_CNY)
                        || LocalVlmBudgetPolicy.exceeds(globalCost, GLOBAL_BUDGET_CNY);
                task.put("budget_exceeded", exceeded);
                if (exceeded) {
                    task.put("state", "PAUSED");
                    task.put("state_reason", "actual_model_usage_exceeded_budget");
                }
                task.put("requests", requests);
                touch(task);
                SharedPreferences.Editor editor = preferences.edit()
                        .putString(taskKey(taskId), task.toString())
                        .putString(GLOBAL_ACCOUNTED_CNY, Double.toString(Math.max(0.0, globalCost)));
                if (exceeded && taskId.equals(preferences.getString(ACTIVE_TASK_ID, ""))) {
                    editor.remove(ACTIVE_TASK_ID);
                }
                boolean saved = editor.commit();
                return new Settlement(saved && usageKnown, saved && exceeded, saved);
            } catch (JSONException exception) {
                return Settlement.invalid();
            }
        }
    }

    public static boolean saveHistory(Context context, String taskId, JSONObject history) {
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            if (task == null) {
                return false;
            }
            try {
                task.put("history", history == null ? new JSONObject() : history);
                touch(task);
                return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    /** Persist a prepared action before its Accessibility side effect can be dispatched. */
    public static boolean recordActionIntent(Context context, String taskId, JSONObject action) {
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            if (task == null || action == null || !"RUNNING".equals(task.optString("state", ""))) {
                return false;
            }
            JSONArray actions = task.optJSONArray("actions");
            if (actions == null) {
                return false;
            }
            String actionId = action.optString("action_id", "");
            if (actionId.isEmpty()) {
                return false;
            }
            for (int i = 0; i < actions.length(); i++) {
                JSONObject existing = actions.optJSONObject(i);
                if (existing != null && actionId.equals(existing.optString("action_id", ""))) {
                    return false;
                }
            }
            JSONObject entry = new JSONObject();
            try {
                entry.put("action_id", actionId);
                entry.put("phase", "pending");
                entry.put("action", action);
                entry.put("result", JSONObject.NULL);
                entry.put("created_at", Instant.now().toString());
                actions.put(entry);
                task.put("actions", actions);
                touch(task);
                return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    public static boolean recordActionResult(Context context, String taskId, String actionId,
            String phase, JSONObject result) {
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            JSONArray actions = task == null ? null : task.optJSONArray("actions");
            if (actions == null) {
                return false;
            }
            try {
                for (int i = 0; i < actions.length(); i++) {
                    JSONObject entry = actions.optJSONObject(i);
                    if (entry != null && actionId.equals(entry.optString("action_id", ""))) {
                        entry.put("phase", phase == null ? "unknown" : phase);
                        entry.put("result", result == null ? JSONObject.NULL : result);
                        entry.put("completed_at", Instant.now().toString());
                        task.put("actions", actions);
                        touch(task);
                        return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
                    }
                }
                return false;
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    /** Persist the reflector's result alongside, never in place of, the device receipt. */
    public static boolean recordActionReflection(Context context, String taskId, String actionId,
            String outcome, String errorDescription) {
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            JSONArray actions = task == null ? null : task.optJSONArray("actions");
            if (actions == null) {
                return false;
            }
            for (int i = 0; i < actions.length(); i++) {
                JSONObject entry = actions.optJSONObject(i);
                if (entry != null && actionId.equals(entry.optString("action_id", ""))) {
                    if (!applyActionReflection(entry, outcome, errorDescription)) {
                        return false;
                    }
                    try {
                        task.put("actions", actions);
                        touch(task);
                        return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
                    } catch (JSONException exception) {
                        return false;
                    }
                }
            }
            return false;
        }
    }

    static boolean applyActionReflection(JSONObject entry, String outcome, String errorDescription) {
        JSONObject receipt = entry == null ? null : entry.optJSONObject("result");
        if (entry == null || !"executed".equals(entry.optString("phase", ""))
                || receipt == null || !receipt.optBoolean("success", false)
                || !("A".equals(outcome) || "B".equals(outcome) || "C".equals(outcome))) {
            return false;
        }
        try {
            entry.put("reflection", new JSONObject()
                    .put("outcome", outcome)
                    .put("error_description", errorDescription == null ? "" : errorDescription)
                    .put("recorded_at", Instant.now().toString()));
            entry.put("phase", "A".equals(outcome) ? "verified" : "reflected_failure");
            entry.put("reflection_recorded_at", Instant.now().toString());
            return true;
        } catch (JSONException exception) {
            return false;
        }
    }

    /** Persist four-state page evidence separately from the device execution result. */
    public static boolean recordActionVerification(Context context, String taskId, String actionId,
            JSONObject verification) {
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            JSONArray actions = task == null ? null : task.optJSONArray("actions");
            if (actions == null || verification == null) return false;
            for (int i = 0; i < actions.length(); i++) {
                JSONObject entry = actions.optJSONObject(i);
                if (entry == null || !actionId.equals(entry.optString("action_id", ""))) continue;
                if (!applyActionVerification(entry, verification)) return false;
                try {
                    task.put("actions", actions);
                    touch(task);
                    return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
                } catch (JSONException exception) {
                    return false;
                }
            }
            return false;
        }
    }

    static boolean applyActionVerification(JSONObject entry, JSONObject verification) {
        String phase = entry == null ? "" : entry.optString("phase", "");
        JSONObject receipt = entry == null ? null : entry.optJSONObject("result");
        if (entry == null || !("executed".equals(phase)
                || "verification_unknown".equals(phase) || "verification_pending".equals(phase))
                || receipt == null || !receipt.optBoolean("success", false) || verification == null) {
            return false;
        }
        String status = verification.optString("status", "");
        if (!TreeActionVerifier.Status.SUCCESS.name().equals(status)
                && !TreeActionVerifier.Status.FAILURE.name().equals(status)
                && !TreeActionVerifier.Status.PENDING.name().equals(status)
                && !TreeActionVerifier.Status.UNKNOWN.name().equals(status)) {
            return false;
        }
        try {
            entry.put("verification", new JSONObject(verification.toString()))
                    .put("verification_recorded_at", Instant.now().toString());
            if (TreeActionVerifier.Status.SUCCESS.name().equals(status)) {
                entry.put("phase", "verified");
            } else if (TreeActionVerifier.Status.FAILURE.name().equals(status)) {
                entry.put("phase", "reflected_failure");
            } else {
                entry.put("phase", "verification_" + status.toLowerCase(java.util.Locale.ROOT));
            }
            return true;
        } catch (JSONException exception) {
            return false;
        }
    }

    public static boolean hasUnresolvedDeviceAction(Context context, String taskId) {
        return LocalTaskControlPolicy.hasUnresolvedDeviceAction(task(context, taskId));
    }

    /** Raw trees and PNGs are kept under the app-private files directory and are never automatically exported. */
    public static String saveObservation(Context context, String taskId, JSONObject observation) throws IOException {
        if (observation == null) {
            throw new IOException("observation missing");
        }
        long version = observation.optLong("observation_version", 0L);
        if (version < 1L) {
            throw new IOException("observation version missing");
        }
        File directory = taskDirectory(context, taskId);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("could not create private task evidence directory");
        }
        File file = new File(directory, "observation-" + version + ".json");
        writeSync(file, observation.toString().getBytes(StandardCharsets.UTF_8));
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            if (task == null) {
                throw new IOException("task record missing");
            }
            JSONArray observations = task.optJSONArray("observations");
            if (observations == null) {
                observations = new JSONArray();
            }
            try {
                observations.put(new JSONObject()
                        .put("observation_id", observation.optString("observation_id", ""))
                        .put("observation_version", version)
                        .put("page_state", observation.optString("page_state", ""))
                        .put("private_file", file.getName())
                        .put("captured_at", observation.optString("captured_at", "")));
                task.put("observations", observations);
                touch(task);
                if (!preferences(context).edit().putString(taskKey(taskId), task.toString()).commit()) {
                    throw new IOException("could not save observation metadata");
                }
            } catch (JSONException exception) {
                throw new IOException("could not save observation metadata", exception);
            }
        }
        return file.getAbsolutePath();
    }

    public static String saveScreenshot(Context context, String taskId, long observationVersion,
            String captureType, String pngBase64) throws IOException {
        if (observationVersion < 1L || pngBase64 == null || pngBase64.isEmpty()) {
            throw new IOException("screenshot evidence is missing");
        }
        final byte[] bytes;
        try {
            bytes = Base64.decode(pngBase64, Base64.DEFAULT);
        } catch (IllegalArgumentException exception) {
            throw new IOException("screenshot evidence is invalid", exception);
        }
        if (bytes.length == 0 || bytes.length > 8 * 1024 * 1024) {
            throw new IOException("screenshot evidence exceeds the local limit");
        }
        File directory = taskDirectory(context, taskId);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("could not create private task evidence directory");
        }
        String normalized = "AFTER".equals(captureType) ? "after" : "before";
        File file = new File(directory, "screenshot-" + observationVersion + "-" + normalized + ".png");
        try {
            writeSync(file, bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
        return file.getAbsolutePath();
    }

    public static BudgetSnapshot budgetSnapshot(Context context, String taskId) {
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            if (!preferences.contains(GLOBAL_ACCOUNTED_CNY)) {
                preferences.edit().putString(GLOBAL_ACCOUNTED_CNY, "0.0").commit();
            }
            JSONObject task = task(context, taskId);
            return new BudgetSnapshot(
                    task == null ? 0.0 : task.optDouble("accounted_cost_cny", 0.0),
                    accountedTotal(preferences),
                    GLOBAL_BUDGET_CNY,
                    REQUEST_RESERVATION_CNY);
        }
    }

    private static boolean validTokenCount(Long value) {
        return value != null && value >= 0;
    }

    private static boolean containsUnknownUsage(JSONArray requests) {
        for (int i = 0; i < requests.length(); i++) {
            JSONObject request = requests.optJSONObject(i);
            if (request != null && "usage_unknown".equals(request.optString("status", ""))) {
                return true;
            }
        }
        return false;
    }

    private static double accountedTotal(SharedPreferences preferences) {
        if (!preferences.contains(GLOBAL_ACCOUNTED_CNY)) {
            return 0.0;
        }
        try {
            double parsed = Double.parseDouble(preferences.getString(GLOBAL_ACCOUNTED_CNY, ""));
            return Double.isFinite(parsed) && parsed >= 0.0 ? parsed : GLOBAL_BUDGET_CNY;
        } catch (ClassCastException | NumberFormatException exception) {
            try {
                float legacyValue = preferences.getFloat(GLOBAL_ACCOUNTED_CNY, (float) GLOBAL_BUDGET_CNY);
                return Float.isFinite(legacyValue) && legacyValue >= 0.0f
                        ? legacyValue : GLOBAL_BUDGET_CNY;
            } catch (ClassCastException ignored) {
                return GLOBAL_BUDGET_CNY;
            }
        }
    }

    private static int shadowCallCount(SharedPreferences preferences) {
        if (!preferences.contains(GLOBAL_JEV_SHADOW_CALLS)) {
            preferences.edit().putInt(GLOBAL_JEV_SHADOW_CALLS, 0).commit();
            return 0;
        }
        try {
            int count = preferences.getInt(GLOBAL_JEV_SHADOW_CALLS, JevShadowBudgetPolicy.MAX_TOTAL_CALLS);
            return count < 0 ? JevShadowBudgetPolicy.MAX_TOTAL_CALLS : count;
        } catch (ClassCastException exception) {
            return JevShadowBudgetPolicy.MAX_TOTAL_CALLS;
        }
    }

    private static boolean isActiveState(String state) {
        return "ARMED".equals(state) || "RUNNING".equals(state) || "PAUSING".equals(state)
                || "PAUSED".equals(state) || "NEEDS_REVIEW".equals(state);
    }

    private static void touch(JSONObject task) throws JSONException {
        task.put("updated_at", Instant.now().toString());
    }

    private static File taskDirectory(Context context, String taskId) throws IOException {
        if (taskId == null || !taskId.matches("[a-fA-F0-9-]{36}")) {
            throw new IOException("invalid task id");
        }
        return new File(new File(context.getFilesDir(), "standalone_vlm"), taskId);
    }

    private static void writeSync(File file, byte[] bytes) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(file, false)) {
            stream.write(bytes);
            stream.flush();
            stream.getFD().sync();
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String taskKey(String taskId) {
        return TASK_PREFIX + taskId.length() + ":" + taskId;
    }

    public static final class Reservation {
        public final boolean allowed;
        public final int attemptIndex;
        public final double reservedCny;
        public final String reason;

        private Reservation(boolean allowed, int attemptIndex, double reservedCny, String reason) {
            this.allowed = allowed;
            this.attemptIndex = attemptIndex;
            this.reservedCny = reservedCny;
            this.reason = reason;
        }

        static Reservation allowed(int index, double amount) {
            return new Reservation(true, index, amount, "");
        }

        static Reservation denied(String reason) {
            return new Reservation(false, -1, 0.0, reason);
        }
    }

    public static final class BudgetSnapshot {
        public final double taskAccountedCny;
        public final double globalAccountedCny;
        public final double globalBudgetCny;
        public final double nextRequestReservationCny;

        private BudgetSnapshot(double taskAccountedCny, double globalAccountedCny,
                double globalBudgetCny, double nextRequestReservationCny) {
            this.taskAccountedCny = taskAccountedCny;
            this.globalAccountedCny = globalAccountedCny;
            this.globalBudgetCny = globalBudgetCny;
            this.nextRequestReservationCny = nextRequestReservationCny;
        }
    }

    public static final class Settlement {
        public final boolean usageKnown;
        public final boolean budgetExceeded;
        public final boolean saved;

        private Settlement(boolean usageKnown, boolean budgetExceeded, boolean saved) {
            this.usageKnown = usageKnown;
            this.budgetExceeded = budgetExceeded;
            this.saved = saved;
        }

        static Settlement invalid() {
            return new Settlement(false, false, false);
        }
    }
}
