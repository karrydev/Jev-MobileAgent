package com.jev.mobileagent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class VlmApiClientTest {
    private static final String API_KEY = "test-key-never-in-body";
    private static final String PROMPT_SECRET = "private-prompt-sentinel";

    @Test
    public void sendsChineseMultiImageMessagesAndParsesUsage() throws Exception {
        FakeConnection connection = new FakeConnection(
                new URL("https://api.example.test/v1/chat/completions"),
                200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"已看到两张图片\"}}],\"usage\":{"
                        + "\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15}}");
        JSONArray messages = multimodalMessages();

        VlmApiClient.Response response = complete(
                "https://api.example.test/v1/", messages, url -> connection);

        assertEquals("已看到两张图片", response.content);
        assertEquals(Long.valueOf(12), response.promptTokens);
        assertEquals(Long.valueOf(3), response.completionTokens);
        assertEquals(Long.valueOf(15), response.totalTokens);
        assertEquals("https://api.example.test/v1/chat/completions",
                connection.getURL().toExternalForm());
        assertEquals("Bearer " + API_KEY, connection.getRequestProperty("Authorization"));
        assertFalse(connection.getInstanceFollowRedirects());

        String requestBody = connection.requestBody.toString(StandardCharsets.UTF_8.name());
        assertTrue(requestBody.contains("请比较这两张图片中的中文路牌"));
        assertTrue(requestBody.contains("data:image/jpeg;base64,IMAGE_ONE"));
        assertTrue(requestBody.contains("data:image/png;base64,IMAGE_TWO"));
        assertTrue(requestBody.contains("\"max_tokens\":128"));
        assertTrue(requestBody.contains("\"enable_thinking\":false"));
        assertFalse(requestBody.contains(API_KEY));
    }

    @Test
    public void acceptsExplicitChatCompletionsUrlAndLeavesMissingUsageUnknown() throws Exception {
        FakeConnection connection = new FakeConnection(
                new URL("https://api.example.test/custom/chat/completions"),
                200,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");

        VlmApiClient.Response response = complete(
                "https://api.example.test/custom/chat/completions",
                new JSONArray(), url -> connection);

        assertEquals("https://api.example.test/custom/chat/completions",
                connection.getURL().toExternalForm());
        assertEquals("ok", response.content);
        assertNull(response.promptTokens);
        assertNull(response.completionTokens);
        assertNull(response.totalTokens);
    }

    @Test
    public void normalizesRootPathBeforeAppendingChatCompletions() throws Exception {
        AtomicReference<URL> requestedUrl = new AtomicReference<>();

        complete("https://api.example.test/", new JSONArray(), url -> {
            requestedUrl.set(url);
            return new FakeConnection(url, 200,
                    "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        });

        assertEquals("https://api.example.test/chat/completions",
                requestedUrl.get().toExternalForm());
    }

    @Test
    public void classifiesMalformedResponseWithoutExposingBody() throws Exception {
        String privateBody = "malformed " + API_KEY + " " + PROMPT_SECRET
                + " https://private.example.test/signed";
        FakeConnection connection = new FakeConnection(
                new URL("https://api.example.test/v1/chat/completions"), 200, privateBody);

        VlmApiClient.VlmApiException exception = expectFailure(
                "https://api.example.test/v1", url -> connection);

        assertSafeFailure(exception, VlmApiClient.ErrorCategory.INVALID_RESPONSE, 200,
                privateBody, "https://api.example.test");
    }

    @Test
    public void classifiesEmptyChoicesAsInvalidResponse() throws Exception {
        FakeConnection connection = new FakeConnection(
                new URL("https://api.example.test/v1/chat/completions"),
                200,
                "{\"choices\":[]}");

        VlmApiClient.VlmApiException exception = expectFailure(
                "https://api.example.test/v1", url -> connection);

        assertSafeFailure(exception, VlmApiClient.ErrorCategory.INVALID_RESPONSE, 200,
                "choices", "https://api.example.test");
    }

    @Test
    public void classifiesUnauthorizedAndRateLimitByStatusOnly() throws Exception {
        assertHttpFailure(401, VlmApiClient.ErrorCategory.UNAUTHORIZED);
        assertHttpFailure(429, VlmApiClient.ErrorCategory.RATE_LIMITED);
    }

    @Test
    public void mapsNetworkFailureToSafeCategory() throws Exception {
        FakeConnection connection = new FakeConnection(
                new URL("https://api.example.test/v1/chat/completions"), 200, "{}");
        connection.outputFailure = new IOException(
                "private endpoint https://api.example.test " + API_KEY + " " + PROMPT_SECRET);

        VlmApiClient.VlmApiException exception = expectFailure(
                "https://api.example.test/v1", url -> connection);

        assertSafeFailure(exception, VlmApiClient.ErrorCategory.NETWORK_ERROR, 0,
                API_KEY, PROMPT_SECRET, "https://api.example.test");
    }

    @Test
    public void mapsSocketTimeoutToSafeCategory() throws Exception {
        FakeConnection connection = new FakeConnection(
                new URL("https://api.example.test/v1/chat/completions"), 200, "{}");
        connection.responseFailure = new SocketTimeoutException(
                "private endpoint " + API_KEY + " " + PROMPT_SECRET);

        VlmApiClient.VlmApiException exception = expectFailure(
                "https://api.example.test/v1", url -> connection);

        assertSafeFailure(exception, VlmApiClient.ErrorCategory.TIMEOUT, 0,
                API_KEY, PROMPT_SECRET, "https://api.example.test");
    }

    @Test(timeout = 5000)
    public void cancellationDisconnectsInFlightRequest() throws Exception {
        FakeConnection connection = new FakeConnection(
                new URL("https://api.example.test/v1/chat/completions"), 200, "{}");
        connection.blockForCancellation = true;
        AtomicBoolean cancelled = new AtomicBoolean(false);
        FutureTask<VlmApiClient.Response> task = new FutureTask<>(() -> VlmApiClient.complete(
                "https://api.example.test/v1",
                "vision-model",
                API_KEY,
                new JSONArray(),
                128,
                cancelled::get,
                url -> connection));
        Thread worker = new Thread(task, "vlm-api-client-test");
        worker.start();

        assertTrue("request never reached response wait",
                connection.responseEntered.await(1, TimeUnit.SECONDS));
        cancelled.set(true);
        try {
            task.get(2, TimeUnit.SECONDS);
            fail("cancelled request unexpectedly succeeded");
        } catch (ExecutionException exception) {
            assertTrue(exception.getCause() instanceof VlmApiClient.VlmApiException);
            VlmApiClient.VlmApiException apiException =
                    (VlmApiClient.VlmApiException) exception.getCause();
            assertEquals(VlmApiClient.ErrorCategory.CANCELLED, apiException.getCategory());
            assertEquals(0, apiException.getHttpStatus());
            assertFalse(apiException.getMessage().contains(API_KEY));
        }
        assertTrue("cancellation did not disconnect the active connection", connection.disconnected);
    }

    private static void assertHttpFailure(
            int status,
            VlmApiClient.ErrorCategory expectedCategory) throws Exception {
        String privateBody = "error body " + API_KEY + " " + PROMPT_SECRET;
        FakeConnection connection = new FakeConnection(
                new URL("https://api.example.test/v1/chat/completions"), status, privateBody);

        VlmApiClient.VlmApiException exception = expectFailure(
                "https://api.example.test/v1", url -> connection);

        assertSafeFailure(exception, expectedCategory, status,
                privateBody, API_KEY, PROMPT_SECRET, "https://api.example.test");
    }

    private static VlmApiClient.Response complete(
            String endpoint,
            JSONArray messages,
            VlmApiClient.ConnectionFactory connectionFactory) throws Exception {
        return VlmApiClient.complete(endpoint, "vision-model", API_KEY, messages,
                128, () -> false, connectionFactory);
    }

    private static VlmApiClient.VlmApiException expectFailure(
            String endpoint,
            VlmApiClient.ConnectionFactory connectionFactory) throws Exception {
        try {
            complete(endpoint, multimodalMessages(), connectionFactory);
            fail("request unexpectedly succeeded");
            throw new AssertionError("unreachable");
        } catch (VlmApiClient.VlmApiException exception) {
            return exception;
        }
    }

    private static void assertSafeFailure(
            VlmApiClient.VlmApiException exception,
            VlmApiClient.ErrorCategory category,
            int status,
            String... secrets) {
        assertNotNull(exception);
        assertEquals(category, exception.getCategory());
        assertEquals(status, exception.getHttpStatus());
        assertNotNull(exception.getMessage());
        for (String secret : secrets) {
            assertFalse("exception exposed sensitive text", exception.getMessage().contains(secret));
        }
    }

    private static JSONArray multimodalMessages() throws Exception {
        JSONArray content = new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", "请比较这两张图片中的中文路牌"))
                .put(new JSONObject().put("type", "image_url").put("image_url",
                        new JSONObject().put("url", "data:image/jpeg;base64,IMAGE_ONE")))
                .put(new JSONObject().put("type", "image_url").put("image_url",
                        new JSONObject().put("url", "data:image/png;base64,IMAGE_TWO")));
        return new JSONArray().put(new JSONObject().put("role", "user").put("content", content));
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final int responseCode;
        private final byte[] responseBody;
        private final CountDownLatch responseEntered = new CountDownLatch(1);
        private final CountDownLatch disconnectedLatch = new CountDownLatch(1);
        private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
        private volatile boolean disconnected;
        private boolean blockForCancellation;
        private IOException outputFailure;
        private IOException responseFailure;

        FakeConnection(URL url, int responseCode, String responseBody) {
            super(url);
            this.responseCode = responseCode;
            this.responseBody = responseBody.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void disconnect() {
            disconnected = true;
            disconnectedLatch.countDown();
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            if (outputFailure != null) {
                throw outputFailure;
            }
            return requestBody;
        }

        @Override
        public int getResponseCode() throws IOException {
            responseEntered.countDown();
            if (blockForCancellation) {
                try {
                    if (!disconnectedLatch.await(3, TimeUnit.SECONDS)) {
                        throw new SocketTimeoutException("test response wait timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("test response wait interrupted");
                }
                throw new IOException("cancelled test transport");
            }
            if (responseFailure != null) {
                throw responseFailure;
            }
            return responseCode;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(responseBody);
        }
    }
}
