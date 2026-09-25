package com.jev.mobileagent;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class JevApiClientTest {
    private static final String KEY = "secret-jev-key-test-only";

    @Test
    public void postsChineseChoiceDirectlyToSystemOneAndParsesChoiceConfidenceAndUsage() throws Exception {
        FakeConnection connection = new FakeConnection(
                new URL("https://api.typesafe.ai/v1/systemone"), 200, validResponse("probe_click_continue"));
        AtomicReference<URL> openedUrl = new AtomicReference<>();

        JevApiClient.CallResult result = JevApiClient.choose(
                "https://api.typesafe.ai/v1/systemone", "jev-1.13.0", KEY,
                JevApiClient.validProtocolProbeState(), "next_action", JevApiClient.validProtocolProbeCriteria(),
                () -> false, url -> { openedUrl.set(url); return connection; });

        assertTrue(result.isValidChoice());
        assertEquals("probe_click_continue", result.choiceId);
        assertEquals(Double.valueOf(0.92), result.confidence);
        assertEquals(Long.valueOf(378), result.inputTokens);
        assertEquals(Long.valueOf(35), result.outputTokens);
        assertEquals("https://api.typesafe.ai/v1/systemone", openedUrl.get().toExternalForm());
        assertEquals("POST", connection.getRequestMethod());
        assertEquals("Bearer " + KEY, connection.getRequestProperty("Authorization"));
        assertFalse(connection.getInstanceFollowRedirects());
        String body = connection.requestBody.toString(StandardCharsets.UTF_8.name());
        assertTrue(body.contains("next_action"));
        assertTrue(body.contains("jev-1.13.0"));
        assertEquals("点击“继续”按钮", new JSONObject(body).getJSONObject("questions")
                .getJSONObject("next_action").getJSONObject("criteria").getString("probe_click_continue"));
        assertFalse(body.contains(KEY));
    }

    @Test
    public void rejectsUnknownChoiceAndMissingUsageAsProtocolErrors() throws Exception {
        JevApiClient.CallResult unknown = call(200, validResponse("not-a-candidate"));
        JevApiClient.CallResult missingUsage = call(200,
                validResponse("probe_click_continue").replace(",\"usage\":{\"input_tokens\":378,\"output_tokens\":35}", ""));

        assertEquals("protocol_error", unknown.protocolStatus);
        assertEquals("choice_unknown", unknown.errorCode);
        assertNull(unknown.choiceId);
        assertEquals("usage_missing", missingUsage.errorCode);
        assertFalse(missingUsage.isValidChoice());
    }

    @Test
    public void acceptsExtraProviderProbabilityKeysWhenEveryCandidateIsPresent() throws Exception {
        String response = validResponse("probe_click_continue").replace(
                "\"probe_click_cancel\":0.08", "\"probe_click_cancel\":0.08,\"provider_extra\":0.0");

        assertTrue(call(200, response).isValidChoice());
    }

    @Test
    public void classifiesHttpErrorsWithoutReadingOrReflectingProviderBodies() throws Exception {
        for (int status : new int[] {400, 401, 429}) {
            FakeConnection connection = new FakeConnection(new URL("https://api.typesafe.ai/v1/systemone"),
                    status, "private response body " + KEY);
            JevApiClient.CallResult result = JevApiClient.choose(
                    "https://api.typesafe.ai/v1/systemone", "jev-1.13.0", KEY,
                    "测试", "next_action", JevApiClient.validProtocolProbeCriteria(),
                    () -> false, url -> connection);
            assertEquals(status, result.httpStatus);
            assertEquals("not_evaluated", result.protocolStatus);
            assertFalse(result.isValidChoice());
            assertFalse(result.errorCode.contains(KEY));
            assertFalse(connection.requestBody.toString(StandardCharsets.UTF_8.name()).contains(KEY));
        }
    }

    @Test
    public void explicitInvalidProtocolProbeRecordsExpectedHttp400WithoutRetry() throws Exception {
        FakeConnection connection = new FakeConnection(
                new URL("https://api.typesafe.ai/v1/systemone"), 400, "provider error must not be stored");
        AtomicReference<URL> openedUrl = new AtomicReference<>();

        JevApiClient.CallResult result = JevApiClient.invalidProtocolProbe(
                "https://api.typesafe.ai/v1/systemone", "jev-1.13.0", KEY,
                () -> false, url -> { openedUrl.set(url); return connection; });

        assertEquals(1, connection.responseCalls);
        assertEquals("https://api.typesafe.ai/v1/systemone", openedUrl.get().toExternalForm());
        assertEquals("invalid_request_rejected", result.protocolStatus);
        assertEquals("invalid_request_http_400", result.errorCode);
        String body = connection.requestBody.toString(StandardCharsets.UTF_8.name());
        assertTrue(body.contains("unsupported_probe_type"));
        assertFalse(body.contains(KEY));
    }

    @Test
    public void cancellationBeforeOpeningConnectionMakesNoNetworkCall() throws Exception {
        AtomicBoolean opened = new AtomicBoolean(false);
        try {
            JevApiClient.choose("https://api.typesafe.ai/v1/systemone", "jev-1.13.0", KEY,
                    "测试", "next_action", JevApiClient.validProtocolProbeCriteria(),
                    () -> true, url -> { opened.set(true); return new FakeConnection(url, 200, validResponse("probe_click_continue")); });
            fail("cancelled request unexpectedly returned");
        } catch (JevApiClient.JevApiException exception) {
            assertEquals(JevApiClient.ErrorCategory.CANCELLED, exception.getCategory());
            assertFalse(exception.requestMayHaveBeenSent());
            assertFalse(exception.getMessage().contains(KEY));
        }
        assertFalse(opened.get());
    }

    @Test
    public void rejectsHttpEndpointsAndOutOfRangeConfidenceBeforeNetwork() throws Exception {
        AtomicBoolean opened = new AtomicBoolean(false);
        try {
            JevApiClient.choose("http://api.typesafe.ai/v1/systemone", "jev-1.13.0", KEY,
                    "测试", "next_action", JevApiClient.validProtocolProbeCriteria(),
                    () -> false, url -> { opened.set(true); return new FakeConnection(url, 200, validResponse("probe_click_continue")); });
            fail("insecure endpoint unexpectedly accepted");
        } catch (JevApiClient.JevApiException exception) {
            assertEquals(JevApiClient.ErrorCategory.INVALID_ARGUMENT, exception.getCategory());
        }
        assertFalse(opened.get());

        JevApiClient.CallResult invalidConfidence = call(200,
                validResponse("probe_click_continue").replace("\"confidence\":0.92", "\"confidence\":1.2"));
        assertEquals("confidence_invalid", invalidConfidence.errorCode);
    }

    private static JevApiClient.CallResult call(int status, String response) throws Exception {
        FakeConnection connection = new FakeConnection(
                new URL("https://api.typesafe.ai/v1/systemone"), status, response);
        return JevApiClient.choose("https://api.typesafe.ai/v1/systemone", "jev-1.13.0", KEY,
                "当前任务是继续", "next_action", JevApiClient.validProtocolProbeCriteria(),
                () -> false, url -> connection);
    }

    private static String validResponse(String choice) {
        return "{\"model\":\"jev-1.13.0\",\"answers\":{\"next_action\":{" +
                "\"type\":\"choice\",\"choice\":" + JSONObject.quote(choice) + "," +
                "\"probabilities\":{\"probe_click_continue\":0.92,\"probe_click_cancel\":0.08}," +
                "\"confidence\":0.92}},\"usage\":{\"input_tokens\":378,\"output_tokens\":35}}";
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final int responseCode;
        private final byte[] responseBody;
        private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
        private int responseCalls;

        FakeConnection(URL url, int responseCode, String responseBody) {
            super(url);
            this.responseCode = responseCode;
            this.responseBody = responseBody.getBytes(StandardCharsets.UTF_8);
        }

        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public OutputStream getOutputStream() { return requestBody; }
        @Override public int getResponseCode() { responseCalls++; return responseCode; }
        @Override public InputStream getInputStream() throws IOException { return new ByteArrayInputStream(responseBody); }
    }
}
