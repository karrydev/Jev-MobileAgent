package com.jev.mobileagent;

import org.json.JSONArray;
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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Direct, bounded HTTPS client for OpenAI-compatible chat completions APIs. */
public final class VlmApiClient {
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 120_000;
    private static final int MAX_REQUEST_BYTES = 20 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int CANCELLATION_POLL_MILLIS = 25;

    private static final ConnectionFactory DEFAULT_CONNECTION_FACTORY =
            url -> (HttpURLConnection) url.openConnection();

    private VlmApiClient() {
    }

    /** Returns completion text and token counts, leaving unreported counts null. */
    public static Response complete(
            String endpoint,
            String model,
            String apiKey,
            JSONArray messages,
            int maxTokens,
            CancellationToken cancellationToken) throws VlmApiException {
        return complete(endpoint, model, apiKey, messages, maxTokens,
                cancellationToken, DEFAULT_CONNECTION_FACTORY);
    }

    static Response complete(
            String endpoint,
            String model,
            String apiKey,
            JSONArray messages,
            int maxTokens,
            CancellationToken cancellationToken,
            ConnectionFactory connectionFactory) throws VlmApiException {
        checkCancelled(cancellationToken, 0);
        URL requestUrl = endpointUrl(endpoint);
        if (model == null || model.trim().isEmpty()
                || apiKey == null || apiKey.isEmpty()
                || messages == null || maxTokens <= 0
                || connectionFactory == null) {
            throw failure(ErrorCategory.INVALID_ARGUMENT, 0);
        }

        final byte[] requestBytes;
        try {
            JSONObject request = new JSONObject();
            request.put("model", model);
            request.put("messages", messages);
            request.put("max_tokens", maxTokens);
            request.put("enable_thinking", false);
            requestBytes = request.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException | RuntimeException exception) {
            // Do not include JSON-library messages: they may contain request content.
            throw failure(ErrorCategory.INVALID_ARGUMENT, 0);
        }
        if (requestBytes.length > MAX_REQUEST_BYTES) {
            throw failure(ErrorCategory.INVALID_ARGUMENT, 0);
        }
        checkCancelled(cancellationToken, 0);

        HttpURLConnection connection = null;
        CancellationWatchdog watchdog = null;
        int status = 0;
        try {
            connection = connectionFactory.open(requestUrl);
            if (connection == null) {
                throw failure(ErrorCategory.NETWORK_ERROR, 0);
            }
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestMethod("POST");
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setFixedLengthStreamingMode(requestBytes.length);

            watchdog = new CancellationWatchdog(connection, cancellationToken);
            watchdog.start();
            checkCancelled(cancellationToken, status);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
            }
            checkCancelled(cancellationToken, status);

            status = connection.getResponseCode();
            if (status == 401) {
                throw failure(ErrorCategory.UNAUTHORIZED, status);
            }
            if (status == 429) {
                throw failure(ErrorCategory.RATE_LIMITED, status);
            }
            if (status < 200 || status >= 300) {
                throw failure(ErrorCategory.HTTP_ERROR, status);
            }

            checkCancelled(cancellationToken, status);
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw failure(ErrorCategory.INVALID_RESPONSE, status);
            }
            byte[] responseBytes;
            try (InputStream input = connection.getInputStream()) {
                responseBytes = readBounded(input, cancellationToken, status);
            }
            checkCancelled(cancellationToken, status);
            Response response = parseResponse(new String(responseBytes, StandardCharsets.UTF_8), status);
            checkCancelled(cancellationToken, status);
            return response;
        } catch (VlmApiException exception) {
            throw exception;
        } catch (SocketTimeoutException exception) {
            if (isCancelled(cancellationToken)) {
                throw failure(ErrorCategory.CANCELLED, status);
            }
            throw failure(ErrorCategory.TIMEOUT, status);
        } catch (IOException | RuntimeException exception) {
            if (isCancelled(cancellationToken)) {
                throw failure(ErrorCategory.CANCELLED, status);
            }
            throw failure(ErrorCategory.NETWORK_ERROR, status);
        } finally {
            if (watchdog != null) {
                watchdog.stop();
            }
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (RuntimeException ignored) {
                    // Cleanup must not replace the safe result or safe error.
                }
            }
        }
    }

    private static URL endpointUrl(String endpoint) throws VlmApiException {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw failure(ErrorCategory.INVALID_ARGUMENT, 0);
        }
        try {
            URI input = new URI(endpoint.trim());
            if (!"https".equalsIgnoreCase(input.getScheme())
                    || input.getHost() == null
                    || input.getRawUserInfo() != null
                    || input.getRawFragment() != null
                    || input.getPort() == 0) {
                throw failure(ErrorCategory.INVALID_ARGUMENT, 0);
            }
            String path = input.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "";
            }
            while (!path.isEmpty() && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (!path.endsWith("/chat/completions")) {
                path += "/chat/completions";
            }

            // Use raw URI components to preserve percent-encoded base paths.
            StringBuilder value = new StringBuilder("https://")
                    .append(input.getRawAuthority())
                    .append(path);
            if (input.getRawQuery() != null) {
                value.append('?').append(input.getRawQuery());
            }
            return new URI(value.toString()).toURL();
        } catch (URISyntaxException | IOException exception) {
            throw failure(ErrorCategory.INVALID_ARGUMENT, 0);
        }
    }

    private static byte[] readBounded(
            InputStream input,
            CancellationToken cancellationToken,
            int status) throws IOException, VlmApiException {
        if (input == null) {
            throw failure(ErrorCategory.INVALID_RESPONSE, status);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkCancelled(cancellationToken, status);
            if (count > MAX_RESPONSE_BYTES - total) {
                throw failure(ErrorCategory.INVALID_RESPONSE, status);
            }
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    private static Response parseResponse(String raw, int status) throws VlmApiException {
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw failure(ErrorCategory.INVALID_RESPONSE, status);
            }
            JSONObject choice = choices.optJSONObject(0);
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            Object rawContent = message == null ? null : message.opt("content");
            if (!(rawContent instanceof String) || ((String) rawContent).isEmpty()) {
                throw failure(ErrorCategory.INVALID_RESPONSE, status);
            }

            JSONObject usage = null;
            Object rawUsage = root.opt("usage");
            if (rawUsage != null && rawUsage != JSONObject.NULL) {
                if (!(rawUsage instanceof JSONObject)) {
                    throw failure(ErrorCategory.INVALID_RESPONSE, status);
                }
                usage = (JSONObject) rawUsage;
            }
            Long promptTokens = optionalCount(usage, "prompt_tokens", status);
            Long completionTokens = optionalCount(usage, "completion_tokens", status);
            Long totalTokens = optionalCount(usage, "total_tokens", status);
            return new Response((String) rawContent, promptTokens, completionTokens, totalTokens);
        } catch (VlmApiException exception) {
            throw exception;
        } catch (JSONException | RuntimeException exception) {
            throw failure(ErrorCategory.INVALID_RESPONSE, status);
        }
    }

    private static Long optionalCount(JSONObject usage, String field, int status) throws VlmApiException {
        if (usage == null || !usage.has(field) || usage.isNull(field)) {
            return null;
        }
        Object value = usage.opt(field);
        if (!(value instanceof Number)) {
            throw failure(ErrorCategory.INVALID_RESPONSE, status);
        }
        double numericValue = ((Number) value).doubleValue();
        long count = ((Number) value).longValue();
        if (numericValue != (double) count || count < 0) {
            throw failure(ErrorCategory.INVALID_RESPONSE, status);
        }
        return count;
    }

    private static void checkCancelled(CancellationToken token, int status) throws VlmApiException {
        if (isCancelled(token)) {
            throw failure(ErrorCategory.CANCELLED, status);
        }
    }

    private static boolean isCancelled(CancellationToken token) {
        if (token == null) {
            return false;
        }
        try {
            return token.isCancelled();
        } catch (RuntimeException exception) {
            // Fail closed if the caller's cancellation source is no longer usable.
            return true;
        }
    }

    private static VlmApiException failure(ErrorCategory category, int status) {
        return new VlmApiException(category, status);
    }

    public interface CancellationToken {
        boolean isCancelled();
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

    /** Contains only safe error metadata; never includes server or request text. */
    public static final class VlmApiException extends Exception {
        private final ErrorCategory category;
        private final int httpStatus;

        private VlmApiException(ErrorCategory category, int httpStatus) {
            super(category.name().toLowerCase(Locale.US));
            this.category = category;
            this.httpStatus = httpStatus;
        }

        public ErrorCategory getCategory() {
            return category;
        }

        /** Zero means no HTTP response status was received. */
        public int getHttpStatus() {
            return httpStatus;
        }
    }

    public static final class Response {
        public final String content;
        public final Long promptTokens;
        public final Long completionTokens;
        public final Long totalTokens;

        private Response(String content, Long promptTokens, Long completionTokens, Long totalTokens) {
            this.content = content;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }
    }

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    private static final class CancellationWatchdog {
        private final HttpURLConnection connection;
        private final CancellationToken cancellationToken;
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private Thread thread;

        CancellationWatchdog(HttpURLConnection connection, CancellationToken cancellationToken) {
            this.connection = connection;
            this.cancellationToken = cancellationToken;
        }

        void start() {
            if (cancellationToken == null) {
                return;
            }
            thread = new Thread(() -> {
                while (!stopped.get()) {
                    if (isCancelled(cancellationToken)) {
                        try {
                            connection.disconnect();
                        } catch (RuntimeException ignored) {
                            // The calling thread maps the interrupted request to CANCELLED.
                        }
                        return;
                    }
                    try {
                        Thread.sleep(CANCELLATION_POLL_MILLIS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "jev-vlm-cancel-watchdog");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            stopped.set(true);
            if (thread != null) {
                thread.interrupt();
            }
        }
    }
}
