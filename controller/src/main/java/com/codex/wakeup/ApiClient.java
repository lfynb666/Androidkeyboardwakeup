package com.codex.wakeup;

import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import javax.net.ssl.HttpsURLConnection;

final class ApiClient {
    private static final String BASE_URL = "https://wakeup.666-lufengyuan-nb.top";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    static final class Status {
        final boolean online;
        final String proxy;

        Status(boolean online, String proxy) {
            this.online = online;
            this.proxy = proxy;
        }
    }

    static final class ApiException extends Exception {
        final int statusCode;

        ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    private ApiClient() {
    }

    static String pair(String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("password", password);
        body.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        JSONObject response = new JSONObject(request("POST", "/v1/pair", body, null));
        String token = response.optString("token", "");
        if (token.length() == 0) {
            throw new ApiException(502, "服务器没有返回设备令牌");
        }
        return token;
    }

    static Status status(String token) throws Exception {
        JSONObject response = new JSONObject(request("GET", "/v1/status", null, token));
        return new Status(response.optBoolean("online", false),
                response.optString("proxy", "rdp"));
    }

    static void action(String token, String action) throws Exception {
        if (!"wake".equals(action) && !"sleep".equals(action)) {
            throw new IllegalArgumentException("unsupported action");
        }
        JSONObject body = new JSONObject();
        body.put("action", action);
        request("POST", "/v1/action", body, token);
    }

    private static String request(String method, String path, JSONObject body,
                                  String token) throws Exception {
        URL url = new URL(BASE_URL + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "WindowsWake/0.1");
        if (token != null && token.length() > 0) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
        }

        int statusCode = connection.getResponseCode();
        InputStream stream = statusCode >= 200 && statusCode < 400
                ? connection.getInputStream() : connection.getErrorStream();
        String response = stream == null ? "" : readAll(stream);
        connection.disconnect();
        if (statusCode < 200 || statusCode >= 300) {
            String message = response.length() == 0 ? "HTTP " + statusCode : response;
            throw new ApiException(statusCode, message);
        }
        return response;
    }

    private static String readAll(InputStream input) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
