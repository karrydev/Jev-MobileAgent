package com.jev.mobileagent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A report-only Jev path. The returned choice is never converted to a device action. */
public final class JevShadow {
    public static final String CANDIDATE_POLICY_ID = "jev-android-current-observation-v1";
    public static final String CANDIDATE_POLICY_SHA256 =
            "df2b293ad48f0a77b0cbb4cdb1f520156e65a978569ccfdc0eabb7750605669e";
    public static final double LOW_CONFIDENCE_THRESHOLD = 0.5;
    private static final Pattern QUOTED_TEXT = Pattern.compile("(?:“([^”]{1,500})”|「([^」]{1,500})」|\"([^\"]{1,500})\")");

    private JevShadow() {
    }

    public static JSONObject runTaskAttempt(Context context, String taskId, int zeroBasedStep,
            String instruction, JSONObject observation, JSONObject vlmAction,
            JevApiClient.CancellationToken cancellationToken) throws JSONException {
        TaskKnownParameters known = knownParametersForTask(instruction, observation, vlmAction);
        return runChoice(context, taskId, zeroBasedStep, "task_shadow", null, null,
                instruction, observation, known.parameters, known.source, vlmAction, cancellationToken);
    }

    /** A Jev attempt that may supply the device action only when the VLM intent is covered. */
    public static ControlledAttempt runControlledTaskAttempt(Context context, String taskId,
            int zeroBasedStep, String instruction, JSONObject observation, JSONObject vlmAction,
            JevApiClient.CancellationToken cancellationToken) throws JSONException {
        TaskKnownParameters known = knownParametersForTask(instruction, observation, vlmAction);
        JevCandidateBuilder.CandidateSet candidates = JevCandidateBuilder.build(observation, known.parameters);
        JSONObject report = runChoice(context, taskId, zeroBasedStep, "task_controlled", null, null,
                instruction, observation, known.parameters, known.source, vlmAction,
                cancellationToken, candidates, true);

        String status = report.optString("status", "");
        String choiceId = report.optString("choice_id", "");
        double confidence = report.optDouble("confidence", -1.0);
        JevCandidateBuilder.Candidate selected = candidates.candidateById(choiceId);
        boolean budgetDenied = "budget_denied".equals(status);
        boolean maySelect = mayDispatchControlledChoice(candidates, report);
        JSONObject annotations = new JSONObject();
        if (maySelect) {
            annotations.put("selection_status", "selected")
                    .put("selected_candidate_id", selected.id)
                    .put("dispatch_status", "not_started");
        } else if (budgetDenied) {
            annotations.put("selection_status", "blocked")
                    .put("fallback_reason", "jev_budget_denied")
                    .put("fallback_target", "pause_task")
                    .put("dispatch_status", "paused_before_action");
        } else {
            String reason = controlledFallbackReason(report, selected, confidence);
            annotations.put("selection_status", "fallback")
                    .put("fallback_reason", reason)
                    .put("fallback_target", "existing_vlm_action")
                    .put("dispatch_status", "fallback_vlm");
        }
        if (!LocalTaskStore.annotateJevSelectionAttempt(context, taskId, zeroBasedStep, annotations)) {
            throw new JSONException("controlled Jev selection report could not be finalized");
        }
        copyAnnotations(report, annotations);
        return new ControlledAttempt(report, candidates, maySelect ? selected : null);
    }

    public static boolean markControlledFallback(Context context, String taskId, int zeroBasedStep,
            String reason) {
        try {
            JSONObject annotations = new JSONObject()
                    .put("selection_status", "fallback")
                    .put("fallback_reason", reason == null ? "candidate_action_unavailable" : reason)
                    .put("fallback_target", "existing_vlm_action")
                    .put("dispatch_status", "fallback_vlm");
            return LocalTaskStore.annotateJevSelectionAttempt(context, taskId, zeroBasedStep, annotations);
        } catch (JSONException exception) {
            return false;
        }
    }

    static boolean mayDispatchControlledChoice(JevCandidateBuilder.CandidateSet candidates,
            JSONObject report) {
        if (candidates == null || report == null
                || !"valid_recommendation".equals(report.optString("status", ""))
                || report.optDouble("confidence", -1.0) < LOW_CONFIDENCE_THRESHOLD
                || !"matched".equals(report.optString("candidate_coverage_status", ""))) {
            return false;
        }
        return candidates.candidateById(report.optString("choice_id", "")) != null;
    }

    public static final class ControlledAttempt {
        public final JSONObject report;
        public final JevCandidateBuilder.CandidateSet candidates;
        public final JevCandidateBuilder.Candidate selectedCandidate;

        private ControlledAttempt(JSONObject report, JevCandidateBuilder.CandidateSet candidates,
                JevCandidateBuilder.Candidate selectedCandidate) {
            this.report = report;
            this.candidates = candidates;
            this.selectedCandidate = selectedCandidate;
        }
    }

    public static JSONObject runDebugCase(Context context, String taskId, JSONObject input,
            JevApiClient.CancellationToken cancellationToken) throws JSONException {
        String caseId = input.optString("case_id", "");
        String split = input.optString("split", "unspecified");
        String instruction = input.optString("instruction", "");
        JSONObject observation = input.optJSONObject("observation");
        JSONObject known = input.optJSONObject("known_parameters");
        return runChoice(context, taskId, 0, "debug_case", caseId, split,
                instruction, observation, known, null, null, cancellationToken);
    }

    static JSONObject runProtocolProbe(Context context, String taskId, ModelProfileStore.Profile profile,
            int zeroBasedStep, boolean invalidRequest, JevApiClient.CancellationToken cancellationToken)
            throws JSONException {
        JSONObject base = baseAttempt("debug_protocol_probe", null, "debug", zeroBasedStep,
                "", 0L, JevApiClient.validProtocolProbeCriteria().length());
        try {
            base.put("question_id", invalidRequest ? "invalid_probe" : "next_action")
                    .put("debug_probe_kind", invalidRequest ? "explicit_invalid_protocol" : "valid_chinese_choice")
                    .put("candidate_policy_id", "jev-protocol-probe-v1")
                    .put("candidate_policy_sha256", CANDIDATE_POLICY_SHA256)
                    .put("action_dispatched", false);
            if (!profile.isConfigured()) {
                return recordWithoutNetwork(context, taskId, base, "profile_missing", "jev_profile_missing");
            }
            if (!JevShadowBudgetPolicy.isReviewedProfile(profile)) {
                return recordWithoutNetwork(context, taskId, base,
                        "unsupported_profile", "jev_profile_not_reviewed_for_reserve");
            }
            return makeCall(context, taskId, zeroBasedStep, profile, base,
                    JevApiClient.validProtocolProbeState(), JevApiClient.validProtocolProbeCriteria(),
                    null, invalidRequest, cancellationToken);
        } catch (JSONException exception) {
            throw exception;
        }
    }

    private static JSONObject runChoice(Context context, String taskId, int zeroBasedStep,
            String source, String caseId, String split, String instruction, JSONObject observation,
            JSONObject knownParameters, String knownParameterSource, JSONObject vlmAction,
            JevApiClient.CancellationToken cancellationToken)
            throws JSONException {
        JevCandidateBuilder.CandidateSet candidates = JevCandidateBuilder.build(observation, knownParameters);
        return runChoice(context, taskId, zeroBasedStep, source, caseId, split, instruction,
                observation, knownParameters, knownParameterSource, vlmAction, cancellationToken,
                candidates, false);
    }

    private static JSONObject runChoice(Context context, String taskId, int zeroBasedStep,
            String source, String caseId, String split, String instruction, JSONObject observation,
            JSONObject knownParameters, String knownParameterSource, JSONObject vlmAction,
            JevApiClient.CancellationToken cancellationToken,
            JevCandidateBuilder.CandidateSet candidates, boolean requireVlmCoverage)
            throws JSONException {
        JSONObject base = baseAttempt(source, caseId, split, zeroBasedStep,
                candidates.observationId, candidates.observationVersion, candidates.candidates.size());
        base.put("instruction_language", containsHan(instruction) ? "zh-CN" : "other")
                .put("candidate_policy_id", CANDIDATE_POLICY_ID)
                .put("candidate_policy_sha256", CANDIDATE_POLICY_SHA256)
                .put("confidence_threshold", LOW_CONFIDENCE_THRESHOLD)
                .put("candidate_metadata", candidates.candidateMetadata())
                .put("action_dispatched", false)
                .put("comparison", comparison(candidates, null, vlmAction));
        if (knownParameterSource != null) {
            base.put("known_parameter_source", knownParameterSource);
        }
        if (requireVlmCoverage && candidates.fallbackReason == null) {
            String coverage = candidates.vlmCoverageStatus(vlmAction);
            base.put("candidate_coverage_status", coverage);
            if (!"matched".equals(coverage)) {
                String reason = "candidate_coverage_" + safeReason(coverage);
                base.put("status", "fallback")
                        .put("selection_status", "fallback")
                        .put("fallback_reason", reason)
                        .put("fallback_target", "existing_vlm_action");
                return recordWithoutNetwork(context, taskId, base, "fallback", reason);
            }
        } else if (requireVlmCoverage) {
            base.put("candidate_coverage_status", candidates.fallbackReason);
        }
        if (candidates.fallbackReason != null) {
            base.put("status", "fallback")
                    .put("fallback_reason", candidates.fallbackReason)
                    .put("fallback_target", "existing_vlm_action");
            return recordWithoutNetwork(context, taskId, base, "fallback", candidates.fallbackReason);
        }
        ModelProfileStore.Profile profile = ModelProfileStore.load(context, ModelProfileStore.Type.JEV);
        if (!profile.isConfigured()) {
            return recordWithoutNetwork(context, taskId, base, "profile_missing", "jev_profile_missing");
        }
        if (!JevShadowBudgetPolicy.isReviewedProfile(profile)) {
            return recordWithoutNetwork(context, taskId, base,
                    "unsupported_profile", "jev_profile_not_reviewed_for_reserve");
        }
        if (isCancelled(cancellationToken)) {
            base.put("status", "cancelled_before_reservation");
            return recordWithoutNetwork(context, taskId, base, "cancelled", "cancelled_before_send");
        }
        JSONObject criteria = new JSONObject();
        for (JevCandidateBuilder.Candidate candidate : candidates.candidates) {
            criteria.put(candidate.id, candidate.description);
        }
        String state = stateText(instruction, observation, candidates);
        base.put("provider", profile.provider).put("model", profile.model)
                .put("question_id", "next_action");
        return makeCall(context, taskId, zeroBasedStep, profile, base, state, criteria,
                candidates, false, cancellationToken, vlmAction);
    }

    private static JSONObject makeCall(Context context, String taskId, int zeroBasedStep,
            ModelProfileStore.Profile profile, JSONObject base, String state, JSONObject criteria,
            JevCandidateBuilder.CandidateSet candidates, boolean invalidRequest,
            JevApiClient.CancellationToken cancellationToken) throws JSONException {
        return makeCall(context, taskId, zeroBasedStep, profile, base, state, criteria,
                candidates, invalidRequest, cancellationToken, null);
    }

    private static JSONObject makeCall(Context context, String taskId, int zeroBasedStep,
            ModelProfileStore.Profile profile, JSONObject base, String state, JSONObject criteria,
            JevCandidateBuilder.CandidateSet candidates, boolean invalidRequest,
            JevApiClient.CancellationToken cancellationToken, JSONObject vlmAction) throws JSONException {
        LocalTaskStore.Reservation reservation = LocalTaskStore.reserveJevShadowCall(
                context, taskId, zeroBasedStep, base);
        if (!reservation.allowed) {
            base.put("status", "budget_denied").put("error", safeError("budget_gate", reservation.reason));
            return recordWithoutNetwork(context, taskId, base, "budget_denied", reservation.reason);
        }
        long started = System.nanoTime();
        JevApiClient.CallResult result = null;
        JevApiClient.JevApiException failure = null;
        try {
            result = invalidRequest
                    ? JevApiClient.invalidProtocolProbe(profile, cancellationToken, null)
                    : JevApiClient.choose(profile, state, "next_action", criteria,
                            cancellationToken);
        } catch (JevApiClient.JevApiException exception) {
            failure = exception;
        }
        JSONObject completed = new JSONObject(base.toString());
        completed.put("elapsed_ms", result == null
                ? Math.max(0L, (System.nanoTime() - started) / 1_000_000L) : result.elapsedMillis);
        if (result != null) {
            completed.put("request_sent", result.requestSent)
                    .put("http_status", result.httpStatus > 0 ? result.httpStatus : JSONObject.NULL)
                    .put("protocol_status", result.protocolStatus)
                    .put("usage", result.usageJson());
            if (result.choiceId != null) {
                completed.put("choice_id", result.choiceId).put("confidence", result.confidence);
            }
            if (result.errorCode != null) {
                completed.put("error", safeError(result.errorClass, result.errorCode));
            }
            if (result.isValidChoice()) {
                completed.put("status", result.confidence < LOW_CONFIDENCE_THRESHOLD
                        ? "valid_low_confidence" : "valid_recommendation");
                completed.put("confidence_status", result.confidence < LOW_CONFIDENCE_THRESHOLD
                        ? "below_frozen_threshold" : "meets_frozen_threshold");
                completed.put("comparison", comparison(candidates, result.choiceId, vlmAction));
            } else if ("invalid_request_rejected".equals(result.protocolStatus)) {
                completed.put("status", "invalid_request_rejected");
            } else if ("protocol_error".equals(result.protocolStatus)
                    || "invalid_request_accepted".equals(result.protocolStatus)) {
                completed.put("status", "protocol_error");
            } else {
                completed.put("status", "http_error");
            }
        } else if (failure != null) {
            boolean requestSent = failure.requestMayHaveBeenSent();
            completed.put("request_sent", requestSent)
                    .put("http_status", failure.getHttpStatus() > 0
                            ? failure.getHttpStatus() : JSONObject.NULL)
                    .put("protocol_status", "not_evaluated")
                    .put("usage", new JSONObject().put("status", "unknown")
                            .put("input_tokens", JSONObject.NULL).put("output_tokens", JSONObject.NULL))
                    .put("error", safeError(failure.getCategory().name().toLowerCase(Locale.ROOT),
                            failure.getCategory().name().toLowerCase(Locale.ROOT)))
                    .put("status", failure.getCategory() == JevApiClient.ErrorCategory.CANCELLED
                            ? "cancelled" : "request_error");
        }
        boolean requestSent = result != null ? result.requestSent
                : failure != null && failure.requestMayHaveBeenSent();
        completed.put("cost", new JSONObject()
                .put("status", requestSent ? "reserved_actual_unknown" : "released_before_send")
                .put("currency", "USD")
                .put("amount_usd", JSONObject.NULL)
                .put("fx_status", JevShadowBudgetPolicy.FX_STATUS)
                .put("actual_bill_status", JevShadowBudgetPolicy.ACTUAL_BILL_STATUS)
                .put("reserved_cny", requestSent ? JevShadowBudgetPolicy.RESERVATION_CNY_PER_CALL : 0.0));
        if (!LocalTaskStore.completeJevShadowCall(context, taskId,
                reservation.attemptIndex, completed)) {
            throw new JSONException("Jev shadow report could not be saved");
        }
        return completed;
    }

    private static JSONObject recordWithoutNetwork(Context context, String taskId,
            JSONObject base, String status, String reason) throws JSONException {
        base.put("status", status)
                .put("request_sent", false)
                .put("protocol_status", "not_run")
                .put("usage", new JSONObject().put("status", "not_requested")
                        .put("input_tokens", JSONObject.NULL).put("output_tokens", JSONObject.NULL))
                .put("cost", new JSONObject().put("status", "not_incurred")
                        .put("currency", "USD").put("amount_usd", JSONObject.NULL)
                        .put("fx_status", JevShadowBudgetPolicy.FX_STATUS)
                        .put("actual_bill_status", JevShadowBudgetPolicy.ACTUAL_BILL_STATUS)
                        .put("reserved_cny", 0.0));
        if (reason != null) base.put("fallback_reason", reason);
        if (!LocalTaskStore.recordJevShadowAttempt(context, taskId, base)) {
            throw new JSONException("Jev shadow report could not be saved");
        }
        return base;
    }

    private static JSONObject baseAttempt(String source, String caseId, String split,
            int zeroBasedStep, String observationId, long observationVersion, int candidateCount)
            throws JSONException {
        JSONObject result = new JSONObject()
                .put("report_version", "jev-shadow-attempt/android-v1")
                .put("created_at", Instant.now().toString())
                .put("source", source)
                .put("step", Math.max(0, zeroBasedStep) + 1)
                .put("observation_id", observationId == null ? "" : observationId)
                .put("observation_version", Math.max(0L, observationVersion))
                .put("candidate_count", Math.max(0, candidateCount));
        if (caseId != null) result.put("case_id", caseId);
        if (split != null) result.put("split", split);
        return result;
    }

    private static JSONObject comparison(JevCandidateBuilder.CandidateSet candidates,
            String choiceId, JSONObject vlmAction) throws JSONException {
        String status = candidates == null ? "not_compared"
                : candidates.compareRecommendation(choiceId, vlmAction);
        String vlmCandidateId = candidates == null ? null
                : candidates.compareVlmAction(vlmAction, choiceId);
        JSONObject result = new JSONObject().put("status", status);
        if (choiceId != null) result.put("jev_choice_id", choiceId);
        if (vlmCandidateId != null) {
            result.put("vlm_equivalent_candidate_id", vlmCandidateId);
        }
        return result;
    }

    private static String controlledFallbackReason(JSONObject report,
            JevCandidateBuilder.Candidate selected, double confidence) {
        String existing = report.optString("fallback_reason", "");
        if (!existing.isEmpty()) return existing;
        if ("valid_low_confidence".equals(report.optString("status", ""))
                || (confidence >= 0.0 && confidence < LOW_CONFIDENCE_THRESHOLD)) {
            return "confidence_below_threshold";
        }
        if ("valid_recommendation".equals(report.optString("status", "")) && selected == null) {
            return "jev_choice_not_in_current_candidate_set";
        }
        String status = safeReason(report.optString("status", "jev_choice_unavailable"));
        if (status.isEmpty()) status = "jev_choice_unavailable";
        return "jev_" + status;
    }

    private static String safeReason(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,100}")) {
            return "unclassified";
        }
        return value;
    }

    private static void copyAnnotations(JSONObject target, JSONObject annotations) throws JSONException {
        java.util.Iterator<String> keys = annotations.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            target.put(key, annotations.opt(key));
        }
    }

    static TaskKnownParameters knownParametersForTask(String instruction, JSONObject observation,
            JSONObject vlmAction) throws JSONException {
        String knownText = exactQuotedText(instruction);
        if (knownText != null) {
            return new TaskKnownParameters(new JSONObject().put("text", knownText), "instruction_quote");
        }
        knownText = vlmSetText(vlmAction);
        if (knownText == null) {
            return new TaskKnownParameters(new JSONObject(), "none");
        }
        JSONObject known = new JSONObject().put("text", knownText);
        String targetId = vlmAction.optString("target_node_id", "");
        String parameter = inputTextParameter(observation, targetId);
        if (parameter != null && !parameter.isEmpty() && !"text".equals(parameter)) {
            known.put(parameter, knownText);
        }
        return new TaskKnownParameters(known, "vlm_set_text");
    }

    private static String vlmSetText(JSONObject action) {
        if (action == null || !"set_text".equals(action.optString("kind", ""))) return null;
        JSONObject parameters = action.optJSONObject("parameters");
        if (parameters == null) return null;
        Object rawText = parameters.opt("text");
        if (!(rawText instanceof String)) return null;
        String text = (String) rawText;
        if (text.trim().isEmpty() || text.length() > JevCandidateBuilder.MAX_KNOWN_TEXT_CHARS) return null;
        return text;
    }

    private static String inputTextParameter(JSONObject observation, String targetNodeId) {
        if (observation == null || targetNodeId == null || targetNodeId.isEmpty()) return "text";
        JSONArray nodes = observation.optJSONArray("nodes");
        if (nodes == null) return "text";
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null || !targetNodeId.equals(node.optString("node_id", ""))) continue;
            JSONArray actions = node.optJSONArray("actions");
            if (actions != null) {
                for (int j = 0; j < actions.length(); j++) {
                    Object raw = actions.opt(j);
                    if (raw instanceof String && "input_text".equals(raw)) return "text";
                    if (raw instanceof JSONObject && "input_text".equals(
                            ((JSONObject) raw).optString("kind", ""))) {
                        return ((JSONObject) raw).optString("parameter", "text");
                    }
                }
            }
            return node.optString("parameter", "text");
        }
        return "text";
    }

    static final class TaskKnownParameters {
        final JSONObject parameters;
        final String source;

        TaskKnownParameters(JSONObject parameters, String source) {
            this.parameters = parameters;
            this.source = source;
        }
    }

    private static JSONObject safeError(String category, String code) throws JSONException {
        return new JSONObject().put("class", category == null ? "unknown" : category)
                .put("code", code == null ? "unknown" : code);
    }

    private static String stateText(String instruction, JSONObject observation,
            JevCandidateBuilder.CandidateSet candidates) {
        String safeInstruction = instruction == null ? "" : instruction.trim();
        if (safeInstruction.length() > JevCandidateBuilder.MAX_INSTRUCTION_CHARS) {
            safeInstruction = safeInstruction.substring(0, JevCandidateBuilder.MAX_INSTRUCTION_CHARS);
        }
        String page = observation == null ? "unknown" : observation.optString("page_state", "observed");
        return "任务：" + safeInstruction + "\n当前页面观察状态：" + page
                + "\n候选数量：" + candidates.candidates.size()
                + "。只从 question criteria 的候选 ID 中选择，不要创建新动作或新文本。";
    }

    /** Extract text only when the user supplied one unambiguous, explicitly quoted value. */
    static String exactQuotedText(String instruction) {
        if (instruction == null || instruction.length() > JevCandidateBuilder.MAX_INSTRUCTION_CHARS) return null;
        Matcher matcher = QUOTED_TEXT.matcher(instruction);
        String found = null;
        while (matcher.find()) {
            String value = firstNonNull(matcher.group(1), matcher.group(2), matcher.group(3));
            if (value == null || value.trim().isEmpty() || value.length() > JevCandidateBuilder.MAX_KNOWN_TEXT_CHARS) {
                return null;
            }
            if (found != null && !found.equals(value)) return null;
            found = value;
        }
        return found;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) if (value != null) return value;
        return null;
    }

    private static boolean containsHan(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.UnicodeScript.of(value.charAt(i)) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    private static boolean isCancelled(JevApiClient.CancellationToken token) {
        if (token == null) return false;
        try {
            return token.isCancelled();
        } catch (RuntimeException exception) {
            return true;
        }
    }
}
