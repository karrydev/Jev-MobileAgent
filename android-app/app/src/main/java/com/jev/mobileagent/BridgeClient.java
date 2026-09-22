package com.jev.mobileagent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Dependency-free HTTP client for the Android bridge contract. */
public final class BridgeClient {
    private BridgeClient() {
    }

    public static JSONObject pair(BridgeConfig config) throws BridgeException {
        JSONObject body = new JSONObject();
        try {
            body.put("schema_version", "1.0");
            body.put("android_schema_version", "1.0");
            body.put("task_id", config.taskId);
            body.put("device_id", config.deviceId);
            body.put("client_name", "Jev Android observation app");
            body.put("capabilities", new JSONArray()
                    .put("accessibility_tree")
                    .put("windows")
                    .put("screen_metrics"));
        } catch (JSONException exception) {
            throw new BridgeException(0, "invalid_client_payload", exception.getMessage(), exception);
        }
        return request(config, "POST", "/v1/android/pair", body);
    }

    public static JSONObject postObservation(BridgeConfig config, JSONObject observation) throws BridgeException {
        return request(config, "POST", "/v1/android/observations", observation);
    }

    public static JSONObject latest(BridgeConfig config) throws BridgeException {
        try {
            String query = "?device_id=" + URLEncoder.encode(config.deviceId, "UTF-8");
            return request(config, "GET", "/v1/android/observations/latest" + query, null);
        } catch (IOException exception) {
            throw new BridgeException(0, "invalid_device_id", exception.getMessage(), exception);
        }
    }

    public static JSONObject status(BridgeConfig config) throws BridgeException {
        try {
            String query = "?device_id=" + URLEncoder.encode(config.deviceId, "UTF-8");
            return request(config, "GET", "/v1/android/status" + query, null);
        } catch (IOException exception) {
            throw new BridgeException(0, "invalid_device_id", exception.getMessage(), exception);
        }
    }

    public static JSONObject submitTask(BridgeConfig config, String goal) throws BridgeException {
        JSONObject body = new JSONObject();
        try {
            body.put("schema_version", "1.0");
            body.put("android_schema_version", "1.0");
            body.put("task_id", config.taskId);
            body.put("device_id", config.deviceId);
            body.put("goal", goal == null ? "" : goal);
            body.put("source", "android-app-user");
        } catch (JSONException exception) {
            throw new BridgeException(0, "invalid_client_payload", exception.getMessage(), exception);
        }
        return request(config, "POST", "/v1/android/tasks", body);
    }

    public static JSONObject taskStatus(BridgeConfig config) throws BridgeException {
        return request(config, "GET", taskPath(config, ""), null);
    }

    public static JSONObject controlTask(BridgeConfig config, String command) throws BridgeException {
        JSONObject body = new JSONObject();
        try {
            body.put("command", command);
            body.put("reason", "user_" + command);
        } catch (JSONException exception) {
            throw new BridgeException(0, "invalid_client_payload", exception.getMessage(), exception);
        }
        return request(config, "POST", taskPath(config, "/" + command), body);
    }

    public static JSONObject postReceipt(BridgeConfig config, JSONObject receipt) throws BridgeException {
        return request(config, "POST", taskPath(config, "/receipt"), receipt);
    }

    private static String taskPath(BridgeConfig config, String suffix) throws BridgeException {
        try {
            return "/v1/android/tasks/" + URLEncoder.encode(config.taskId, "UTF-8") + suffix;
        } catch (IOException exception) {
            throw new BridgeException(0, "invalid_task_id", exception.getMessage(), exception);
        }
    }

    private static JSONObject request(
            BridgeConfig config,
            String method,
            String path,
            JSONObject body) throws BridgeException {
        if (config.endpoint.length() == 0 || config.token.length() == 0
                || config.deviceId.length() == 0 || config.taskId.length() == 0) {
            throw new BridgeException(0, "invalid_configuration", "endpoint, token, device_id and task_id are required");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(config.endpoint + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setDoInput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + config.token);
            connection.setRequestProperty("X-JEV-Protocol-Version", "1");
            connection.setRequestProperty("X-JEV-Device-Id", config.deviceId);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }

            int status = connection.getResponseCode();
            String raw = readFully(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            JSONObject response;
            try {
                response = new JSONObject(raw);
            } catch (JSONException exception) {
                throw new BridgeException(status, "invalid_bridge_response", "bridge returned malformed JSON", exception);
            }
            if (status < 200 || status >= 300) {
                JSONObject error = response.optJSONObject("error");
                String code = error == null ? "bridge_http_error" : error.optString("code", "bridge_http_error");
                String message = error == null ? "bridge returned HTTP " + status : error.optString("message", "bridge request failed");
                throw new BridgeException(status, code, message);
            }
            return response;
        } catch (IOException exception) {
            throw new BridgeException(0, "bridge_unreachable", "bridge connection failed: " + exception.getMessage(), exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readFully(InputStream stream) throws IOException {
        if (stream == null) {
            return "{}";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    public static final class BridgeException extends Exception {
        public final int status;
        public final String code;

        public BridgeException(int status, String code, String message) {
            super(message == null ? code : message);
            this.status = status;
            this.code = code;
        }

        public BridgeException(int status, String code, String message, Throwable cause) {
            super(message == null ? code : message, cause);
            this.status = status;
            this.code = code;
        }
    }
}
