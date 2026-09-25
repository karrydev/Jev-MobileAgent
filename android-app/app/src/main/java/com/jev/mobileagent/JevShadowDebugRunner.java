package com.jev.mobileagent;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

/** Debug-build-only input/report path for one isolated Jev shadow case. */
public final class JevShadowDebugRunner {
    public static final int MAX_CASE_BYTES = 64 * 1024;
    public static final String CASE_INPUT_NAME = "jev-shadow-debug/input.json";
    public static final String REPORT_NAME = "jev-shadow-debug/report.json";
    private static final Set<String> ALLOWED_CASE_KEYS = new HashSet<>(Arrays.asList(
            "case_id", "split", "instruction", "observation", "known_parameters"));
    private static final Set<String> FORBIDDEN_TRUTH_KEYS = new HashSet<>(Arrays.asList(
            "truth", "execution", "effect_rules", "completion_rule", "answer_key",
            "future_frame", "future_observation", "reference_answer", "expected_choice",
            "expected_id", "expected_candidate_id", "expected_action", "expected_result"));
    private static final Set<String> ALLOWED_PARAMETER_KEYS = new HashSet<>(Arrays.asList(
            "text", "query", "direction", "duration_ms"));

    private JevShadowDebugRunner() {
    }

    public static File caseInputFile(Context context) {
        return new File(context.getFilesDir(), CASE_INPUT_NAME);
    }

    public static File reportFile(Context context) {
        return new File(context.getFilesDir(), REPORT_NAME);
    }

    public static JSONObject readCaseInput(Context context) throws IOException, JSONException {
        if (!BuildConfig.DEBUG) throw new IOException("debug-only input is unavailable in release builds");
        File file = caseInputFile(context);
        if (!file.isFile() || file.length() < 1 || file.length() > MAX_CASE_BYTES) {
            throw new IOException("private case input is missing or exceeds 64 KiB");
        }
        byte[] bytes = readBounded(file, MAX_CASE_BYTES);
        String json = new String(bytes, StandardCharsets.UTF_8);
        Arrays.fill(bytes, (byte) 0);
        JSONObject input = new JSONObject(json);
        validateCase(input);
        return new JSONObject(input.toString());
    }

    public static JSONObject runPrivateCase(Context context, JevApiClient.CancellationToken cancellationToken)
            throws IOException, JSONException {
        if (!BuildConfig.DEBUG) throw new IOException("debug-only runner is unavailable in release builds");
        JSONObject input = readCaseInput(context);
        ModelProfileStore.Profile profile = ModelProfileStore.load(context, ModelProfileStore.Type.JEV);
        if (!profile.isConfigured()) throw new IOException("Jev profile is not configured in the app");
        if (!JevShadowBudgetPolicy.isReviewedProfile(profile)) {
            throw new IOException("configured Jev provider, model, or endpoint has no reviewed reserve");
        }
        JSONObject task = LocalTaskStore.create(context, "Jev shadow debug case", profile.provider, profile.model, false);
        if (task == null) throw new IOException("another local task is still active");
        String taskId = task.optString("task_id", "");
        JSONObject attempt = JevShadow.runDebugCase(context, taskId, input, cancellationToken);
        boolean budgetDenied = "budget_denied".equals(attempt.optString("status", ""));
        boolean cancelled = "cancelled".equals(attempt.optString("status", ""));
        LocalTaskStore.updateState(context, taskId,
                budgetDenied || cancelled ? "PAUSED" : "SUCCEEDED",
                budgetDenied ? attempt.optJSONObject("error").optString("code", "budget_denied")
                        : cancelled ? "debug_case_cancelled" : "debug_case_completed");
        JSONObject report = report("private_case", taskId, attempt, input.optString("case_id", ""),
                input.optString("split", "unspecified"));
        writeReport(context, report);
        return report;
    }

    public static JSONObject runProtocolProbe(Context context, boolean invalidRequest,
            JevApiClient.CancellationToken cancellationToken) throws IOException, JSONException {
        if (!BuildConfig.DEBUG) throw new IOException("debug-only probe is unavailable in release builds");
        ModelProfileStore.Profile profile = ModelProfileStore.load(context, ModelProfileStore.Type.JEV);
        if (!profile.isConfigured()) throw new IOException("Jev profile is not configured in the app");
        if (!JevShadowBudgetPolicy.isReviewedProfile(profile)) {
            throw new IOException("configured Jev provider, model, or endpoint has no reviewed reserve");
        }
        JSONObject task = LocalTaskStore.create(context,
                invalidRequest ? "Jev explicit invalid protocol probe" : "Jev Chinese protocol probe",
                profile.provider, profile.model, false);
        if (task == null) throw new IOException("another local task is still active");
        String taskId = task.optString("task_id", "");
        JSONObject attempt = JevShadow.runProtocolProbe(context, taskId, profile, 0,
                invalidRequest, cancellationToken);
        boolean budgetDenied = "budget_denied".equals(attempt.optString("status", ""));
        boolean cancelled = "cancelled".equals(attempt.optString("status", ""));
        LocalTaskStore.updateState(context, taskId,
                budgetDenied || cancelled ? "PAUSED" : "SUCCEEDED",
                budgetDenied ? attempt.optJSONObject("error").optString("code", "budget_denied")
                        : cancelled ? "protocol_probe_cancelled" : "protocol_probe_completed");
        JSONObject report = report(invalidRequest ? "explicit_invalid_protocol_probe" : "valid_chinese_protocol_probe",
                taskId, attempt, null, null);
        writeReport(context, report);
        return report;
    }

    static void validateCase(JSONObject input) throws IOException {
        if (input == null || input.length() == 0) throw new IOException("case JSON must be an object");
        Iterator<String> keys = input.keys();
        while (keys.hasNext()) {
            if (!ALLOWED_CASE_KEYS.contains(keys.next())) {
                throw new IOException("case JSON contains an unsupported field");
            }
        }
        String caseId = input.optString("case_id", "");
        String split = input.optString("split", "unspecified");
        String instruction = input.optString("instruction", "");
        if (!caseId.matches("[A-Za-z0-9_.-]{1,80}")
                || !("dev".equals(split) || "holdout".equals(split) || "unspecified".equals(split))
                || instruction.trim().isEmpty()
                || instruction.length() > JevCandidateBuilder.MAX_INSTRUCTION_CHARS) {
            throw new IOException("case identity, split, or instruction is invalid");
        }
        JSONObject observation = input.optJSONObject("observation");
        if (observation == null || observation.toString().length() > MAX_CASE_BYTES) {
            throw new IOException("case observation is missing or too large");
        }
        JSONArrayNodeLimit.check(observation);
        if (containsForbiddenTruth(input)) throw new IOException("case input must not contain truth or future data");
        JSONObject parameters = input.optJSONObject("known_parameters");
        if (parameters != null) validateParameters(parameters);
    }

    private static void validateParameters(JSONObject parameters) throws IOException {
        if (parameters.length() > 8) throw new IOException("known_parameters has too many values");
        Iterator<String> keys = parameters.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!ALLOWED_PARAMETER_KEYS.contains(key)) throw new IOException("known_parameters key is unsupported");
            Object value = parameters.opt(key);
            if (value instanceof String) {
                if (((String) value).isEmpty() || ((String) value).length() > JevCandidateBuilder.MAX_KNOWN_TEXT_CHARS) {
                    throw new IOException("known text is empty or exceeds the limit");
                }
            } else if ("duration_ms".equals(key) && value instanceof Number) {
                long duration = ((Number) value).longValue();
                if (duration < 1L || duration > 5_000L) throw new IOException("known duration is out of range");
            } else {
                throw new IOException("known parameter value type is unsupported");
            }
        }
    }

    private static boolean containsForbiddenTruth(Object value) throws IOException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String normalized = key.toLowerCase(Locale.ROOT);
                if (FORBIDDEN_TRUTH_KEYS.contains(normalized) || normalized.startsWith("expected_")
                        || normalized.startsWith("future_") || normalized.startsWith("reference_")) return true;
                if (containsForbiddenTruth(object.opt(key))) return true;
            }
        } else if (value instanceof org.json.JSONArray) {
            org.json.JSONArray array = (org.json.JSONArray) value;
            for (int i = 0; i < array.length(); i++) if (containsForbiddenTruth(array.opt(i))) return true;
        } else if (value instanceof String) {
            String keyLike = ((String) value).toLowerCase(Locale.ROOT);
            if (keyLike.startsWith("data:image/") || keyLike.startsWith("file://")) {
                throw new IOException("case input must not embed screenshots or private file paths");
            }
        }
        return false;
    }

    private static byte[] readBounded(File file, int limit) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4_096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > limit - total) throw new IOException("case input exceeds 64 KiB");
                output.write(buffer, 0, count);
                total += count;
            }
            return output.toByteArray();
        }
    }

    private static JSONObject report(String mode, String taskId, JSONObject attempt,
            String caseId, String split) throws JSONException {
        JSONObject report = new JSONObject().put("report_version", "jev-shadow-debug-report/android-v1")
                .put("created_at", Instant.now().toString())
                .put("mode", mode).put("task_id", taskId).put("attempt", attempt)
                .put("action_dispatched", false);
        if (caseId != null) report.put("case_id", caseId);
        if (split != null) report.put("split", split);
        return report;
    }

    private static void writeReport(Context context, JSONObject report) throws IOException {
        File file = reportFile(context);
        File parent = file.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            throw new IOException("could not create private Jev debug directory");
        }
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        } catch (JSONException exception) {
            throw new IOException("could not serialize Jev debug report", exception);
        }
    }

    private static final class JSONArrayNodeLimit {
        static void check(JSONObject observation) throws IOException {
            org.json.JSONArray nodes = observation.optJSONArray("nodes");
            if (nodes == null || nodes.length() > 150) {
                throw new IOException("observation nodes are missing or exceed the 150 node limit");
            }
        }
    }
}
