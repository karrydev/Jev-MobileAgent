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
import java.util.HashSet;
import java.util.Set;
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
    private static final String TASK_PREFIX = "task.";
    private static final int MAX_RECOVERY_DIAGNOSTIC_WINDOWS = 6;
    static final String DEBUG_FAULT_BEFORE_DISPATCH = "BEFORE_DISPATCH";
    static final String DEBUG_FAULT_AFTER_SIDE_EFFECT = "AFTER_SIDE_EFFECT_BEFORE_RECEIPT";
    static final String DEBUG_FAULT_AFTER_RECEIPT = "AFTER_RECEIPT_BEFORE_VERIFICATION";

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
                record.put("tree_verification_policy_id", TreeActionVerifier.POLICY_ID);
                record.put("tree_verification_policy_sha256", TreeActionVerifier.POLICY_SHA256);
                record.put("jev_shadow_call_count", 0);
                record.put("jev_shadow_reserved_cny", 0.0);
                record.put("requests", new JSONArray());
                record.put("screenshot_captures", new JSONArray());
                record.put("jev_shadow_attempts", new JSONArray());
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

    /** Store only allowlisted, compact window metadata in Debug task records. */
    public static boolean recordDebugRecoveryWindowDiagnostic(Context context, String taskId,
            JSONObject source) {
        if (!BuildConfig.DEBUG || context == null || taskId == null || taskId.isEmpty() || source == null) {
            return false;
        }
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            if (task == null) return false;
            try {
                JSONObject diagnostic = new JSONObject()
                        .put("expectedTargetPackage", source.optString("expectedTargetPackage", ""))
                        .put("phase", source.optString("phase", ""))
                        .put("error", source.optString("error", ""))
                        .put("readinessError", source.optString("readinessError", ""))
                        .put("elapsedMs", Math.max(0L, source.optLong("elapsedMs", 0L)))
                        .put("screenInteractive", source.optBoolean("screenInteractive", false))
                        .put("keyguardLocked", source.optBoolean("keyguardLocked", false))
                        .put("capturedAt", Instant.now().toString());
                JSONObject observation = source.optJSONObject("observation");
                if (observation != null) {
                    diagnostic.put("availability", observation.optString("availability", ""));
                    JSONObject sourceScreen = observation.optJSONObject("screen");
                    if (sourceScreen != null) {
                        JSONObject screen = new JSONObject()
                                .put("widthPx", Math.max(0, sourceScreen.optInt("width_px", 0)))
                                .put("heightPx", Math.max(0, sourceScreen.optInt("height_px", 0)))
                                .put("rotation", sourceScreen.optInt("rotation", -1))
                                .put("activeWindowId", sourceScreen.optInt("active_window_id", -1));
                        JSONObject bounds = debugBounds(sourceScreen.optJSONObject("active_window_bounds"));
                        if (bounds != null) screen.put("activeWindowBounds", bounds);
                        JSONObject sourceInsets = sourceScreen.optJSONObject("recovery_system_bar_insets");
                        if (sourceInsets != null) {
                            JSONObject safeInsets = new JSONObject()
                                    .put("available", sourceInsets.optBoolean("available", false))
                                    .put("source", sourceInsets.optString("source", ""))
                                    .put("reason", sourceInsets.optString("reason", ""))
                                    .put("statusBarsVisible", sourceInsets.optBoolean("status_bars_visible", false))
                                    .put("navigationBarsVisible", sourceInsets.optBoolean("navigation_bars_visible", false))
                                    .put("left", Math.max(0, sourceInsets.optInt("left", 0)))
                                    .put("top", Math.max(0, sourceInsets.optInt("top", 0)))
                                    .put("right", Math.max(0, sourceInsets.optInt("right", 0)))
                                    .put("bottom", Math.max(0, sourceInsets.optInt("bottom", 0)));
                            JSONObject metricsBounds = debugBounds(sourceInsets.optJSONObject("metrics_bounds_px"));
                            if (metricsBounds != null) safeInsets.put("metricsBoundsPx", metricsBounds);
                            screen.put("recoverySystemBarInsets", safeInsets);
                        }
                        diagnostic.put("screen", screen);
                    }
                    JSONArray sourceWindows = observation.optJSONArray("windows");
                    JSONArray windows = new JSONArray();
                    if (sourceWindows != null) {
                        for (int i = 0; i < Math.min(sourceWindows.length(), MAX_RECOVERY_DIAGNOSTIC_WINDOWS); i++) {
                            JSONObject window = sourceWindows.optJSONObject(i);
                            if (window == null) continue;
                            JSONObject safeWindow = new JSONObject()
                                    .put("id", window.optInt("window_id", -1))
                                    .put("type", window.optInt("window_type", -1))
                                    .put("packageName", window.optString("package_name", ""))
                                    .put("className", window.optString("class_name", ""))
                                    .put("active", window.optBoolean("active", false))
                                    .put("focused", window.optBoolean("focused", false))
                                    .put("layer", window.optInt("layer", -1));
                            JSONObject bounds = debugBounds(window.optJSONObject("bounds"));
                            if (bounds != null) safeWindow.put("bounds", bounds);
                            windows.put(safeWindow);
                        }
                    }
                    diagnostic.put("windowCount", sourceWindows == null ? 0 : sourceWindows.length())
                            .put("windows", windows);
                }
                task.put("debug_recovery_window", diagnostic);
                touch(task);
                return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    private static JSONObject debugBounds(JSONObject source) throws JSONException {
        if (source == null) return null;
        return new JSONObject()
                .put("left", source.optInt("left", 0))
                .put("top", source.optInt("top", 0))
                .put("right", source.optInt("right", 0))
                .put("bottom", source.optInt("bottom", 0));
    }

    /** Resolve the most recent trustworthy application seen while the task was running. */
    static String recoveryTargetPackage(Context context, String taskId) {
        synchronized (LOCK) {
            return recoveryTargetPackage(task(context, taskId));
        }
    }

    static String recoveryTargetPackage(JSONObject task) {
        if (task == null) return "";
        String latestRunning = task.optString("last_running_target_application_package", "");
        if (!latestRunning.isEmpty()) return latestRunning;
        LegacyRecoveryTarget legacy = resolveLegacyRecoveryTarget(task);
        return legacy.ambiguous ? "" : legacy.packageName;
    }

    static boolean recoveryTargetIsAmbiguous(JSONObject task) {
        if (task == null || !task.optString("last_running_target_application_package", "").isEmpty()) {
            return false;
        }
        return resolveLegacyRecoveryTarget(task).ambiguous;
    }

    private static LegacyRecoveryTarget resolveLegacyRecoveryTarget(JSONObject task) {
        JSONArray observations = task.optJSONArray("observations");
        if (observations == null) observations = new JSONArray();
        Set<String> recoveryObservationIds = recoveryObservationIds(task);
        String candidatePackage = "";
        long candidateVersion = -1L;
        boolean ambiguousAtCandidate = false;

        // Newer records carry an explicit running-target marker. Older records can
        // prove the same fact when a BEFORE/AFTER screenshot attempt links to the
        // observation captured by the task loop. RECOVERY captures are never target evidence.
        for (int i = 0; i < observations.length(); i++) {
            JSONObject entry = observations.optJSONObject(i);
            if (entry == null || recoveryObservationIds.contains(entry.optString("observation_id", ""))) continue;
            String trusted = entry.optString("trusted_running_target_application_package", "");
            if (!trusted.isEmpty()) {
                long version = entry.optLong("observation_version", 0L);
                if (version > candidateVersion) {
                    candidatePackage = trusted;
                    candidateVersion = version;
                    ambiguousAtCandidate = false;
                } else if (version == candidateVersion && !trusted.equals(candidatePackage)) {
                    ambiguousAtCandidate = true;
                }
            }
        }

        JSONArray captures = task.optJSONArray("screenshot_captures");
        if (captures != null) {
            for (int i = 0; i < captures.length(); i++) {
                JSONObject capture = captures.optJSONObject(i);
                if (capture == null) continue;
                String type = capture.optString("capture_type", "");
                String observationId = capture.optString("observation_id", "");
                if ("RECOVERY".equals(type)) {
                    if (!observationId.isEmpty()) recoveryObservationIds.add(observationId);
                    continue;
                }
                if (!"BEFORE".equals(type) && !"AFTER".equals(type)) continue;
                JSONObject entry = observationById(observations, observationId);
                if (entry == null || recoveryObservationIds.contains(observationId)) continue;
                String packageName = entry.optString("active_application_package", "");
                long version = Math.max(entry.optLong("observation_version", 0L),
                        capture.optLong("observation_version", 0L));
                if (packageName.isEmpty()) continue;
                if (version > candidateVersion) {
                    candidatePackage = packageName;
                    candidateVersion = version;
                    ambiguousAtCandidate = false;
                } else if (version == candidateVersion && !packageName.equals(candidatePackage)) {
                    ambiguousAtCandidate = true;
                }
            }
        }

        if (ambiguousAtCandidate) return new LegacyRecoveryTarget("", true);
        if (!candidatePackage.isEmpty()) {
            // An unlinked, later observation could be the process-death window
            // between saving the tree and recording its screenshot attempt. If it
            // names another app, the old running scene cannot be reconstructed.
            for (int i = 0; i < observations.length(); i++) {
                JSONObject entry = observations.optJSONObject(i);
                if (entry == null || recoveryObservationIds.contains(entry.optString("observation_id", ""))) continue;
                if (entry.optLong("observation_version", 0L) <= candidateVersion) continue;
                String packageName = entry.optString("active_application_package", "");
                if (!packageName.isEmpty() && !packageName.equals(candidatePackage)) {
                    return new LegacyRecoveryTarget("", true);
                }
            }
            return new LegacyRecoveryTarget(candidatePackage, false);
        }

        String stored = task.optString("target_application_package", "");
        if (!stored.isEmpty()) {
            // Preserve ordinary single-app legacy review while rejecting a known
            // cross-app history whose running phase has no screenshot linkage.
            for (int i = 0; i < observations.length(); i++) {
                JSONObject entry = observations.optJSONObject(i);
                if (entry == null || recoveryObservationIds.contains(entry.optString("observation_id", ""))) continue;
                String packageName = entry.optString("active_application_package", "");
                if (!packageName.isEmpty() && !packageName.equals(stored)) {
                    return new LegacyRecoveryTarget("", true);
                }
            }
            return new LegacyRecoveryTarget(stored, false);
        }
        return new LegacyRecoveryTarget("", false);
    }

    private static Set<String> recoveryObservationIds(JSONObject task) {
        Set<String> ids = new HashSet<>();
        JSONObject review = task.optJSONObject("recovery_review");
        if (review != null) addRecoveryObservationId(ids, review);
        JSONArray reviews = task.optJSONArray("recovery_reviews");
        if (reviews != null) {
            for (int i = 0; i < reviews.length(); i++) {
                addRecoveryObservationId(ids, reviews.optJSONObject(i));
            }
        }
        return ids;
    }

    private static void addRecoveryObservationId(Set<String> ids, JSONObject review) {
        if (review == null) return;
        String id = review.optString("observation_id", "");
        if (!id.isEmpty()) ids.add(id);
    }

    private static JSONObject observationById(JSONArray observations, String observationId) {
        if (observationId == null || observationId.isEmpty()) return null;
        for (int i = 0; i < observations.length(); i++) {
            JSONObject entry = observations.optJSONObject(i);
            if (entry != null && observationId.equals(entry.optString("observation_id", ""))) return entry;
        }
        return null;
    }

    private static final class LegacyRecoveryTarget {
        final String packageName;
        final boolean ambiguous;

        LegacyRecoveryTarget(String packageName, boolean ambiguous) {
            this.packageName = packageName;
            this.ambiguous = ambiguous;
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

    /** Arm a single recovery interruption from the Debug controlled-page UI before task start. */
    public static boolean armDebugRecoveryFault(Context context, String taskId, String point) {
        return armDebugRecoveryFault(context, taskId, point, "");
    }

    /** Arm a single recovery interruption, optionally waiting for one durable action kind. */
    public static boolean armDebugRecoveryFault(Context context, String taskId, String point,
            String actionKind) {
        if (!BuildConfig.DEBUG || !isDebugRecoveryFaultPoint(point)) return false;
        String requiredActionKind = actionKind == null ? "" : actionKind.trim();
        if (!requiredActionKind.isEmpty()
                && (!isAfterActionDebugRecoveryFaultPoint(point) || !"set_text".equals(requiredActionKind))) {
            return false;
        }
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            if (task == null || !"ARMED".equals(task.optString("state", ""))) return false;
            try {
                JSONObject fault = new JSONObject()
                        .put("point", point)
                        .put("status", "armed")
                        .put("configured_before_task_start", true)
                        .put("armed_at", Instant.now().toString());
                if (!requiredActionKind.isEmpty()) fault.put("action_kind", requiredActionKind);
                task.put("debug_recovery_fault", fault);
                touch(task);
                return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    /** Persist a one-shot fault marker before the Debug build terminates its process. */
    public static boolean fireDebugRecoveryFault(Context context, String taskId, String point, String actionId) {
        if (!BuildConfig.DEBUG || !isDebugRecoveryFaultPoint(point)) return false;
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            if (!shouldFireDebugRecoveryFault(task, point, actionId)) return false;
            JSONObject fault = task.optJSONObject("debug_recovery_fault");
            try {
                fault.put("status", "fired")
                        .put("action_id", actionId == null ? "" : actionId)
                        .put("fired_at", Instant.now().toString());
                task.put("debug_recovery_fault", fault)
                        .put("runtime_status", "Debug 中断夹具已触发：" + point);
                touch(task);
                return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    static boolean shouldFireDebugRecoveryFault(JSONObject task, String point, String actionId) {
        JSONObject fault = task == null ? null : task.optJSONObject("debug_recovery_fault");
        if (fault == null || !"armed".equals(fault.optString("status", ""))
                || point == null || !point.equals(fault.optString("point", ""))) return false;
        String requiredActionKind = fault.optString("action_kind", "");
        return requiredActionKind.isEmpty()
                || hasDurableActionKind(task, actionId, requiredActionKind);
    }

    private static boolean hasDurableActionKind(JSONObject task, String actionId, String actionKind) {
        if (task == null || actionId == null || actionId.isEmpty() || actionKind == null || actionKind.isEmpty()) {
            return false;
        }
        JSONArray actions = task.optJSONArray("actions");
        if (actions == null) return false;
        for (int i = 0; i < actions.length(); i++) {
            JSONObject entry = actions.optJSONObject(i);
            if (entry == null || !actionId.equals(entry.optString("action_id", ""))) continue;
            JSONObject action = entry.optJSONObject("action");
            return action != null && actionKind.equals(action.optString("kind", ""));
        }
        return false;
    }

    private static boolean isAfterActionDebugRecoveryFaultPoint(String point) {
        return DEBUG_FAULT_AFTER_SIDE_EFFECT.equals(point) || DEBUG_FAULT_AFTER_RECEIPT.equals(point);
    }

    private static boolean isDebugRecoveryFaultPoint(String point) {
        return DEBUG_FAULT_BEFORE_DISPATCH.equals(point)
                || DEBUG_FAULT_AFTER_SIDE_EFFECT.equals(point)
                || DEBUG_FAULT_AFTER_RECEIPT.equals(point);
    }

    /** Save a fresh review tree and a stable scene identity without making a model request. */
    public static boolean recordRecoveryObservation(Context context, String taskId,
            JSONObject observation, String screenshotFingerprint, String goalOutcome) throws IOException {
        String activePackage = LocalTaskControlPolicy.activeApplicationPackage(observation);
        if (observation == null || activePackage.isEmpty()
                || screenshotFingerprint == null || screenshotFingerprint.isEmpty()) {
            throw new IOException("recovery observation or local screenshot fingerprint missing");
        }
        saveObservation(context, taskId, observation);
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            if (task == null || !isReviewableState(task.optString("state", ""))) return false;
            try {
                JSONObject review = new JSONObject()
                        .put("observation_id", observation.optString("observation_id", ""))
                        .put("observation_version", observation.optLong("observation_version", 0L))
                        .put("captured_at", observation.optString("captured_at", ""))
                        .put("scene_fingerprint", LocalTaskControlPolicy.sceneFingerprint(
                                observation, screenshotFingerprint))
                        .put("semantic_fingerprint", LocalTaskControlPolicy.sceneFingerprint(
                                observation, ""))
                        .put("target_application_package", activePackage)
                        .put("goal", task.optString("goal", ""))
                        .put("goal_outcome", goalOutcome == null ? "UNKNOWN" : goalOutcome)
                        .put("valid", true);
                JSONArray facts = new JSONArray();
                JSONArray actions = task.optJSONArray("actions");
                if (actions != null) {
                    for (int i = 0; i < actions.length(); i++) {
                        JSONObject action = actions.optJSONObject(i);
                        if (action == null) continue;
                        JSONObject verification = action.optJSONObject("verification");
                        JSONObject receipt = action.optJSONObject("result");
                        facts.put(new JSONObject()
                                .put("action_id", action.optString("action_id", ""))
                                .put("execution_fact", LocalTaskControlPolicy.executionFact(task, action).name())
                                .put("phase", action.optString("phase", "pending"))
                                .put("receipt_success", receipt == null ? JSONObject.NULL
                                        : receipt.has("success") ? receipt.optBoolean("success") : JSONObject.NULL)
                                .put("verification_status", verification == null
                                        ? verificationStatusFromPhase(action.optString("phase", ""))
                                        : verification.optString("status", "UNKNOWN")));
                    }
                }
                review.put("action_facts", facts);
                task.put("recovery_review", review);
                task.put("runtime_status", "已重新观察；用户确认前不会恢复任务");
                JSONArray history = task.optJSONArray("recovery_reviews");
                if (history == null) history = new JSONArray();
                history.put(new JSONObject(review.toString()));
                task.put("recovery_reviews", history);
                touch(task);
                return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
            } catch (JSONException exception) {
                throw new IOException("could not save recovery review", exception);
            }
        }
    }

    /** Revalidate the exact reviewed target and fresh scene, then record a user-only decision. */
    public static boolean applyRecoveryDecision(Context context, String taskId, String decision,
            String expectedReviewObservationId, JSONObject confirmationObservation,
            String screenshotFingerprint, String confirmationGoalOutcome, int confirmationSampleCount) {
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            JSONObject review = task == null ? null : task.optJSONObject("recovery_review");
            if (task == null || review == null || !isReviewableState(task.optString("state", ""))
                    || !review.optBoolean("valid", false)
                    || expectedReviewObservationId == null
                    || !expectedReviewObservationId.equals(review.optString("observation_id", ""))
                    || !task.optString("goal", "").equals(review.optString("goal", ""))
                    || screenshotFingerprint == null || screenshotFingerprint.isEmpty()
                    || !review.optString("scene_fingerprint", "").equals(
                            LocalTaskControlPolicy.sceneFingerprint(confirmationObservation, screenshotFingerprint))) {
                return false;
            }
            boolean factsKnown = LocalTaskControlPolicy.allExecutionFactsKnown(task);
            boolean unresolvedPostcondition = LocalTaskControlPolicy.hasUnresolvedDeviceAction(task);
            if (!LocalTaskControlPolicy.allowsRecoveryDecision(
                    decision, task, review, confirmationGoalOutcome)) return false;
            try {
                JSONArray actions = task.optJSONArray("actions");
                int nextStep = task.optInt("step_count", 0);
                if (actions != null) {
                    for (int i = 0; i < actions.length(); i++) {
                        JSONObject entry = actions.optJSONObject(i);
                        if (LocalTaskControlPolicy.executionFact(task, entry)
                                != LocalTaskControlPolicy.ExecutionFact.UNKNOWN) {
                            JSONObject action = entry == null ? null : entry.optJSONObject("action");
                            if (action != null) nextStep = Math.max(nextStep, action.optInt("sequence", 0));
                        }
                    }
                }
                JSONObject decisionRecord = new JSONObject()
                        .put("decision", decision)
                        .put("decision_actor", "user")
                        .put("review_observation_id", review.optString("observation_id", ""))
                        .put("confirmation_observation_id", confirmationObservation.optString("observation_id", ""))
                        .put("confirmed_at", Instant.now().toString())
                        .put("execution_facts_known", factsKnown)
                        .put("unresolved_postcondition_retained", unresolvedPostcondition)
                        .put("review_goal_outcome", review.optString("goal_outcome", "UNKNOWN"))
                        .put("confirmation_goal_outcome", confirmationGoalOutcome == null
                                ? "UNKNOWN" : confirmationGoalOutcome)
                        .put("confirmation_sample_count", Math.max(1, confirmationSampleCount))
                        .put("step_count_before", task.optInt("step_count", 0))
                        .put("request_count_at_confirmation", task.optInt("request_count", 0))
                        .put("accounted_cost_cny_at_confirmation", task.optDouble("accounted_cost_cny", 0.0));
                if ("complete_goal".equals(decision)) {
                    decisionRecord.put("completion_attribution", "observed_only")
                            .put("execution_actor", JSONObject.NULL);
                }
                JSONArray decisions = task.optJSONArray("recovery_decisions");
                if (decisions == null) decisions = new JSONArray();
                decisions.put(decisionRecord);
                task.put("recovery_decisions", decisions);
                review.put("valid", false).put("invalidated_reason", "confirmation_consumed");
                task.put("recovery_review", review);
                if ("resume".equals(decision)) {
                    task.put("step_count", nextStep);
                    task.put("state", "RUNNING");
                    task.put("state_reason", "user_confirmed_resume_after_fresh_reconciliation");
                    task.put("runtime_status", "用户已确认；从新观察继续，不重放已记录动作");
                } else if ("complete_goal".equals(decision)) {
                    task.put("state", "COMPLETED_ON_REVIEW");
                    task.put("state_reason", "user_confirmed_goal_visible_in_two_fresh_observations");
                    task.put("runtime_status", "用户确认当前页面目标已满足；完成归因仅为观察到，不归因给人或 Agent");
                } else {
                    task.put("state", unresolvedPostcondition ? "ENDED_WITH_UNRESOLVED" : "CANCELLED");
                    task.put("state_reason", unresolvedPostcondition
                            ? "user_ended_after_review_acknowledging_unresolved_postcondition"
                            : "user_ended_after_fresh_reconciliation");
                    task.put("runtime_status", unresolvedPostcondition
                            ? "用户已结束任务；执行记录保留，动作后置条件仍未决"
                            : "用户已结束任务");
                }
                touch(task);
                SharedPreferences.Editor editor = preferences(context).edit()
                        .putString(taskKey(taskId), task.toString());
                if (("end".equals(decision) || "complete_goal".equals(decision))
                        && taskId.equals(preferences(context).getString(ACTIVE_TASK_ID, ""))) {
                    editor.remove(ACTIVE_TASK_ID);
                }
                return editor.commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    public static boolean invalidateRecoveryReview(Context context, String taskId,
            String expectedReviewObservationId, String reason) {
        synchronized (LOCK) {
            JSONObject task = task(context, taskId);
            JSONObject review = task == null ? null : task.optJSONObject("recovery_review");
            if (review == null || expectedReviewObservationId == null || !review.optBoolean("valid", false)
                    || !expectedReviewObservationId.equals(review.optString("observation_id", ""))) {
                return false;
            }
            try {
                review.put("valid", false).put("invalidated_reason", reason == null ? "" : reason);
                task.put("recovery_review", review)
                        .put("runtime_status", "现场或目标已变化；请重新观察后再确认");
                touch(task);
                return preferences(context).edit().putString(taskKey(taskId), task.toString()).commit();
            } catch (JSONException exception) {
                return false;
            }
        }
    }

    private static boolean isReviewableState(String state) {
        return "PAUSED".equals(state) || "NEEDS_REVIEW".equals(state);
    }

    private static String verificationStatusFromPhase(String phase) {
        if (phase != null && phase.startsWith("verification_")) {
            return phase.substring("verification_".length()).toUpperCase(java.util.Locale.ROOT);
        }
        return "UNKNOWN";
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
        return saveObservation(context, taskId, observation, false);
    }

    /** Save an observation produced by the active task loop and advance its trusted recovery target. */
    static String saveRunningObservation(Context context, String taskId, JSONObject observation) throws IOException {
        return saveObservation(context, taskId, observation, true);
    }

    private static String saveObservation(Context context, String taskId, JSONObject observation,
            boolean fromRunningTaskLoop) throws IOException {
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
                String activePackage = LocalTaskControlPolicy.activeApplicationPackage(observation);
                String trustedTargetPackage = fromRunningTaskLoop
                        ? LocalTaskControlPolicy.rememberRunningTargetPackage(task, observation) : "";
                JSONObject entry = new JSONObject()
                        .put("observation_id", observation.optString("observation_id", ""))
                        .put("observation_version", version)
                        .put("active_application_package", activePackage)
                        .put("page_state", observation.optString("page_state", ""))
                        .put("private_file", file.getName())
                        .put("captured_at", observation.optString("captured_at", ""));
                if (!trustedTargetPackage.isEmpty()) {
                    entry.put("trusted_running_target_application_package", trustedTargetPackage);
                }
                observations.put(entry);
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
        String normalized = "AFTER".equals(captureType) ? "after"
                : "RECOVERY".equals(captureType) ? "recovery" : "before";
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
