package com.jev.mobileagent;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/** Bounded direct HTTPS client for TypeSafe Jev's system-one choice protocol. */
public final class JevApiClient {
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 25_000;
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int CANCEL_POLL_MILLIS = 25;
    private static final String QUESTION_ID = "next_action";

    private static final ConnectionFactory DEFAULT_CONNECTION_FACTORY =
            url -> (HttpURLConnection) url.openConnection();

    private JevApiClient() {
    }

    public static CallResult choose(
            ModelProfileStore.Profile profile,
            String state,
            String questionId,
            JSONObject criteria,
            CancellationToken cancellationToken) throws JevApiException {
        return choose(profile, state, questionId, criteria, cancellationToken, DEFAULT_CONNECTION_FACTORY);
    }

    static CallResult choose(
            ModelProfileStore.Profile profile,
            String state,
            String questionId,
            JSONObject criteria,
            CancellationToken cancellationToken,
            ConnectionFactory connectionFactory) throws JevApiException {
        if (profile == null) throw invalidArgument();
        return choose(profile.endpoint, profile.model, profile.apiKey, state, questionId,
                criteria, cancellationToken, connectionFactory);
    }

    static CallResult choose(
            String endpoint, String model, String apiKey, String state, String questionId,
            JSONObject criteria, CancellationToken cancellationToken,
            ConnectionFactory connectionFactory) throws JevApiException {
        JSONObject payload = payload(endpoint, model, apiKey, state, questionId, criteria);
        return post(endpoint, model, apiKey, payload,
                questionId, criteria, false, cancellationToken, connectionFactory);
    }

    /** One opt-in invalid-protocol request, used only by the debug acceptance button. */
    static CallResult invalidProtocolProbe(
            ModelProfileStore.Profile profile,
            CancellationToken cancellationToken,
            ConnectionFactory connectionFactory) throws JevApiException {
        if (profile == null) throw invalidArgument();
        return invalidProtocolProbe(profile.endpoint, profile.model, profile.apiKey,
                cancellationToken, connectionFactory);
    }

    static CallResult invalidProtocolProbe(String endpoint, String model, String apiKey,
            CancellationToken cancellationToken, ConnectionFactory connectionFactory) throws JevApiException {
        validateProfile(endpoint, model, apiKey);
        JSONObject questions;
        JSONObject payload;
        try {
            questions = new JSONObject().put("invalid_probe", new JSONObject()
                    .put("type", "unsupported_probe_type"));
            payload = new JSONObject().put("model", model)
                    .put("state", "显式无效协议探针；不得选择或执行手机动作。")
                    .put("questions", questions);
        } catch (JSONException exception) {
            throw invalidArgument();
        }
        return post(endpoint, model, apiKey, payload,
                "invalid_probe", new JSONObject(), true, cancellationToken,
                connectionFactory == null ? DEFAULT_CONNECTION_FACTORY : connectionFactory);
    }

    public static JSONObject validProtocolProbeCriteria() throws JSONException {
        return new JSONObject()
                .put("probe_click_continue", "点击“继续”按钮")
                .put("probe_click_cancel", "点击“取消”按钮");
    }

    public static String validProtocolProbeState() {
        return "这是一个中文手机操作选择测试。当前页面有“继续”和“取消”两个按钮，用户希望继续。请从候选动作中选择下一步。";
    }

    private static JSONObject payload(String endpoint, String model, String apiKey, String state,
            String questionId, JSONObject criteria) throws JevApiException {
        validateProfile(endpoint, model, apiKey);
        if (state == null || state.trim().isEmpty() || state.length() > 8_000
                || questionId == null || !questionId.matches("[A-Za-z0-9_.-]{1,80}")
                || criteria == null || criteria.length() < 1
                || criteria.length() > JevCandidateBuilder.MAX_CANDIDATES) {
            throw invalidArgument();
        }
        try {
            JSONObject copiedCriteria = new JSONObject(criteria.toString());
            Iterator<String> ids = copiedCriteria.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                Object description = copiedCriteria.opt(id);
                if (!isSafeCandidateId(id) || !(description instanceof String)
                        || ((String) description).trim().isEmpty()
                        || ((String) description).length() > JevCandidateBuilder.MAX_KNOWN_TEXT_CHARS + 160) {
                    throw invalidArgument();
                }
            }
            JSONObject question = new JSONObject()
                    .put("type", "choice")
                    .put("instructions", "只从给定候选中选择最符合任务和当前页面的一项；不要生成新动作或文本。")
                    .put("criteria", copiedCriteria);
            return new JSONObject().put("model", model)
                    .put("state", state).put("questions", new JSONObject().put(questionId, question));
        } catch (JSONException exception) {
            throw invalidArgument();
        }
    }

    private static void validateProfile(String endpoint, String model, String apiKey) throws JevApiException {
        if (endpoint == null || endpoint.trim().isEmpty() || model == null || model.trim().isEmpty()
                || apiKey == null || apiKey.isEmpty()) throw invalidArgument();
        endpointUrl(endpoint);
        if (model.length() > 200 || apiKey.length() > 4_096) throw invalidArgument();
    }

    private static CallResult post(String endpoint, String model, String apiKey, JSONObject payload,
            String questionId, JSONObject criteria, boolean invalidProbe,
            CancellationToken cancellationToken, ConnectionFactory connectionFactory) throws JevApiException {
        checkCancelled(cancellationToken, 0, false);
        URL url = endpointUrl(endpoint);
        final byte[] requestBytes;
        try {
            requestBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            throw invalidArgument();
        }
        if (requestBytes.length == 0 || requestBytes.length > MAX_REQUEST_BYTES || connectionFactory == null) {
            throw invalidArgument();
        }

        long started = System.nanoTime();
        HttpURLConnection connection = null;
        CancellationWatchdog watchdog = null;
        boolean requestMayHaveBeenSent = false;
        int status = 0;
        try {
            connection = connectionFactory.open(url);
            if (connection == null) throw failure(ErrorCategory.NETWORK_ERROR, 0, false);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestMethod("POST");
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setFixedLengthStreamingMode(requestBytes.length);

            watchdog = new CancellationWatchdog(connection, cancellationToken);
            watchdog.start();
            checkCancelled(cancellationToken, status, false);
            requestMayHaveBeenSent = true;
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
            }
            checkCancelled(cancellationToken, status, true);
            status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                if (invalidProbe && (status == 400 || status == 422)) {
                    return new CallResult(status, elapsedMillis(started), true,
                            "invalid_request_rejected", null, null, null, null,
                            "http_error", "invalid_request_http_" + status);
                }
                return new CallResult(status, elapsedMillis(started), true,
                        "not_evaluated", null, null, null, null,
                        httpErrorClass(status), httpErrorCode(status));
            }
            if (invalidProbe) {
                return new CallResult(status, elapsedMillis(started), true,
                        "invalid_request_accepted", null, null, null, null,
                        "protocol_error", "invalid_request_accepted");
            }

            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_RESPONSE_BYTES) {
                return protocolFailure(status, started, "response_too_large", null, null);
            }
            byte[] responseBytes;
            try (InputStream input = connection.getInputStream()) {
                responseBytes = readBounded(input, cancellationToken, status);
            }
            checkCancelled(cancellationToken, status, true);
            return parseChoice(new String(responseBytes, StandardCharsets.UTF_8), status,
                    elapsedMillis(started), questionId, criteria);
        } catch (JevApiException exception) {
            throw exception;
        } catch (SocketTimeoutException exception) {
            if (isCancelled(cancellationToken)) {
                throw failure(ErrorCategory.CANCELLED, status, requestMayHaveBeenSent);
            }
            throw failure(ErrorCategory.TIMEOUT, status, requestMayHaveBeenSent);
        } catch (IOException | RuntimeException exception) {
            if (isCancelled(cancellationToken)) {
                throw failure(ErrorCategory.CANCELLED, status, requestMayHaveBeenSent);
            }
            throw failure(ErrorCategory.NETWORK_ERROR, status, requestMayHaveBeenSent);
        } finally {
            if (watchdog != null) watchdog.stop();
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (RuntimeException ignored) {
                    // Cleanup must not replace the safe result or error.
                }
            }
        }
    }

    private static CallResult parseChoice(String raw, int status, long elapsed,
            String questionId, JSONObject criteria) {
        JSONObject root;
        try {
            root = new JSONObject(raw);
        } catch (JSONException | RuntimeException exception) {
            return protocolFailure(status, elapsed, "response_not_json", null, null);
        }
        String model = root.optString("model", "");
        if (model.trim().isEmpty()) return protocolFailure(status, elapsed, "model_missing", null, null);
        Usage usage = parseUsage(root.opt("usage"));
        if (usage.error != null) {
            return protocolFailure(status, elapsed, usage.error, usage.inputTokens, usage.outputTokens);
        }
        JSONObject answers = root.optJSONObject("answers");
        JSONObject answer = answers == null ? null : answers.optJSONObject(questionId);
        if (answer == null || !"choice".equals(answer.optString("type", ""))) {
            return protocolFailure(status, elapsed, "choice_answer_invalid", usage.inputTokens, usage.outputTokens);
        }
        String choice = answer.optString("choice", "");
        if (!criteria.has(choice)) {
            return protocolFailure(status, elapsed, "choice_unknown", usage.inputTokens, usage.outputTokens);
        }
        JSONObject probabilities = answer.optJSONObject("probabilities");
        if (probabilities == null) {
            return protocolFailure(status, elapsed, "probabilities_missing", usage.inputTokens, usage.outputTokens);
        }
        Set<String> expected = keys(criteria);
        Set<String> actual = keys(probabilities);
        if (!actual.containsAll(expected)) {
            return protocolFailure(status, elapsed, "probabilities_invalid", usage.inputTokens, usage.outputTokens);
        }
        for (String id : expected) {
            if (!validProbability(probabilities.opt(id))) {
                return protocolFailure(status, elapsed, "probabilities_invalid", usage.inputTokens, usage.outputTokens);
            }
        }
        Object rawConfidence = answer.opt("confidence");
        if (!validProbability(rawConfidence)) {
            return protocolFailure(status, elapsed, "confidence_invalid", usage.inputTokens, usage.outputTokens);
        }
        return new CallResult(status, elapsed, true, "valid", choice,
                ((Number) rawConfidence).doubleValue(), usage.inputTokens, usage.outputTokens,
                null, null);
    }

    private static Usage parseUsage(Object raw) {
        if (!(raw instanceof JSONObject)) return new Usage(null, null, "usage_missing");
        JSONObject usage = (JSONObject) raw;
        Long input = integerCount(usage.opt("input_tokens"));
        Long output = integerCount(usage.opt("output_tokens"));
        if (input == null || output == null) return new Usage(null, null, "usage_invalid");
        return new Usage(input, output, null);
    }

    private static Long integerCount(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            return null;
        }
        long count = ((Number) value).longValue();
        return count < 0L ? null : count;
    }

    private static boolean validProbability(Object value) {
        if (!(value instanceof Number) || value instanceof Boolean) return false;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && number >= 0.0 && number <= 1.0;
    }

    private static Set<String> keys(JSONObject object) {
        Set<String> result = new HashSet<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) result.add(iterator.next());
        return result;
    }

    private static byte[] readBounded(InputStream input, CancellationToken token, int status)
            throws IOException, JevApiException {
        if (input == null) throw failure(ErrorCategory.INVALID_RESPONSE, status, true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4_096];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkCancelled(token, status, true);
            if (count > MAX_RESPONSE_BYTES - total) {
                throw failure(ErrorCategory.INVALID_RESPONSE, status, true);
            }
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    private static URL endpointUrl(String endpoint) throws JevApiException {
        if (endpoint == null || endpoint.trim().isEmpty()) throw invalidArgument();
        try {
            URI uri = new URI(endpoint.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getPort() == 0) {
                throw invalidArgument();
            }
            return uri.toURL();
        } catch (URISyntaxException | IOException exception) {
            throw invalidArgument();
        }
    }

    private static boolean isSafeCandidateId(String value) {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,240}");
    }

    private static String httpErrorClass(int status) {
        if (status == 401 || status == 403) return "authentication_error";
        if (status == 429) return "rate_limit_error";
        return "http_error";
    }

    private static String httpErrorCode(int status) {
        if (status == 401 || status == 403) return "authentication_failed_http_" + status;
        if (status == 429) return "rate_limited";
        return "http_" + (status > 0 ? status : "unknown");
    }

    private static CallResult protocolFailure(int status, long elapsed, String code, Long input, Long output) {
        return new CallResult(status, elapsed, true, "protocol_error", null, null,
                input, output, "protocol_error", code);
    }

    private static void checkCancelled(CancellationToken token, int status, boolean mayHaveBeenSent)
            throws JevApiException {
        if (isCancelled(token)) throw failure(ErrorCategory.CANCELLED, status, mayHaveBeenSent);
    }

    private static boolean isCancelled(CancellationToken token) {
        if (token == null) return false;
        try {
            return token.isCancelled();
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static JevApiException invalidArgument() {
        return failure(ErrorCategory.INVALID_ARGUMENT, 0, false);
    }

    private static JevApiException failure(ErrorCategory category, int status, boolean mayHaveBeenSent) {
        return new JevApiException(category, status, mayHaveBeenSent);
    }

    public interface CancellationToken {
        boolean isCancelled();
    }

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    public enum ErrorCategory {
        INVALID_ARGUMENT,
        CANCELLED,
        INVALID_RESPONSE,
        UNAUTHORIZED,
        RATE_LIMITED,
        HTTP_ERROR,
        TIMEOUT,
        NETWORK_ERROR
    }

    public static final class JevApiException extends Exception {
        private final ErrorCategory category;
        private final int httpStatus;
        private final boolean requestMayHaveBeenSent;

        private JevApiException(ErrorCategory category, int httpStatus, boolean requestMayHaveBeenSent) {
            super(category.name().toLowerCase(Locale.ROOT));
            this.category = category;
            this.httpStatus = httpStatus;
            this.requestMayHaveBeenSent = requestMayHaveBeenSent;
        }

        public ErrorCategory getCategory() { return category; }
        public int getHttpStatus() { return httpStatus; }
        public boolean requestMayHaveBeenSent() { return requestMayHaveBeenSent; }
    }

    public static final class CallResult {
        public final int httpStatus;
        public final long elapsedMillis;
        public final boolean requestSent;
        public final String protocolStatus;
        public final String choiceId;
        public final Double confidence;
        public final Long inputTokens;
        public final Long outputTokens;
        public final String errorClass;
        public final String errorCode;
        private CallResult(int httpStatus, long elapsedMillis, boolean requestSent,
                String protocolStatus, String choiceId, Double confidence, Long inputTokens,
                Long outputTokens, String errorClass, String errorCode) {
            this.httpStatus = httpStatus;
            this.elapsedMillis = elapsedMillis;
            this.requestSent = requestSent;
            this.protocolStatus = protocolStatus;
            this.choiceId = choiceId;
            this.confidence = confidence;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.errorClass = errorClass;
            this.errorCode = errorCode;
        }

        public boolean isValidChoice() {
            return "valid".equals(protocolStatus) && choiceId != null;
        }

        public JSONObject usageJson() throws JSONException {
            boolean known = inputTokens != null && outputTokens != null;
            return new JSONObject().put("status", known ? "known" : "unknown")
                    .put("input_tokens", known ? inputTokens : JSONObject.NULL)
                    .put("output_tokens", known ? outputTokens : JSONObject.NULL);
        }
    }

    private static final class Usage {
        final Long inputTokens;
        final Long outputTokens;
        final String error;

        Usage(Long inputTokens, Long outputTokens, String error) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.error = error;
        }
    }

    private static final class CancellationWatchdog {
        private final HttpURLConnection connection;
        private final CancellationToken token;
        private volatile boolean stopped;
        private Thread thread;

        CancellationWatchdog(HttpURLConnection connection, CancellationToken token) {
            this.connection = connection;
            this.token = token;
        }

        void start() {
            if (token == null) return;
            thread = new Thread(() -> {
                while (!stopped) {
                    if (isCancelled(token)) {
                        try { connection.disconnect(); } catch (RuntimeException ignored) { }
                        return;
                    }
                    try {
                        Thread.sleep(CANCEL_POLL_MILLIS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "jev-api-cancellation-watchdog");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            stopped = true;
            if (thread != null) thread.interrupt();
        }
    }
}
