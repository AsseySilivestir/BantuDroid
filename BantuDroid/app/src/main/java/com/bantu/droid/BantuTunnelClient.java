package com.bantu.droid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * BantuTunnelClient
 * -----------------
 * Connects to a self-hosted bantu-tunnel server (deployable on Render) and
 * exposes a local port to the public internet via WebSocket.
 *
 * Protocol (matches the bantu-tunnel server at github.com/bantugateway/bantu-tunnel):
 *
 *   Client connects to:        wss://<server>/ws?subdomain=<id>&token=<secret>
 *   Server replies on connect: { type: "connected", tunnelId, url, mode, timestamp }
 *   Public HTTP request comes: { type: "request", id, method, path, headers, body(base64) }
 *   Client fetches localhost:  http://127.0.0.1:<localPort><path>
 *   Client replies:            { type: "response", id, status, headers, body(base64) }
 *   Keepalive:                 client -> { type: "ping", t }   /   server -> { type: "pong", t }
 *
 * v2.10.1 features:
 *   - Auto-reconnect with exponential backoff (1s, 2s, 4s, ... max 30s)
 *   - Stable subdomain derived from ANDROID_ID so URL doesn't change between reconnects
 *   - HTTP keep-alive pings to /health every 5 min to prevent Render free-tier spin-down
 */
public class BantuTunnelClient {

    private static final String TAG = "BantuTunnelClient";

    public interface Callback {
        void onMessage(String msg);
        void onError(String err);
        void onConnected(String publicUrl);
        void onDisconnected();
    }

    private final Context appContext;
    private final OkHttpClient http;
    private final OkHttpClient httpForPing;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private final Handler keepaliveHandler = new Handler(Looper.getMainLooper());

    private volatile WebSocket ws;
    private volatile boolean running = false;
    private volatile boolean connected = false;
    private volatile int localPort = 8080;
    private volatile Callback callback;
    private volatile String currentPublicUrl = null;
    private volatile int reconnectAttempts = 0;
    private volatile String effectiveSubdomain = null;

    private final ConcurrentHashMap<String, okhttp3.Call> pendingCalls = new ConcurrentHashMap<>();

    private static final long KEEPALIVE_INTERVAL_MS = 5 * 60 * 1000;
    private final Runnable keepaliveRunnable = new Runnable() {
        @Override public void run() {
            if (running) sendKeepalivePing();
            keepaliveHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS);
        }
    };

    public BantuTunnelClient(Context context) {
        this.appContext = context.getApplicationContext();
        this.http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build();
        this.httpForPing = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    }

    // Settings helpers
    public String getServerUrl() {
        return PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString("bantu_server_url", "");
    }
    public String getToken() {
        return PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString("bantu_token", "");
    }
    public String getSubdomain() {
        return PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString("bantu_subdomain", "");
    }
    public void setServerUrl(String url) {
        PreferenceManager.getDefaultSharedPreferences(appContext).edit()
            .putString("bantu_server_url", url == null ? "" : url.trim()).apply();
    }
    public void setToken(String token) {
        PreferenceManager.getDefaultSharedPreferences(appContext).edit()
            .putString("bantu_token", token == null ? "" : token.trim()).apply();
    }
    public void setSubdomain(String sub) {
        PreferenceManager.getDefaultSharedPreferences(appContext).edit()
            .putString("bantu_subdomain", sub == null ? "" : sub.trim()).apply();
    }
    public boolean isConfigured() {
        String s = getServerUrl();
        return s != null && !s.trim().isEmpty()
            && (s.startsWith("http://") || s.startsWith("https://")
                || s.startsWith("ws://") || s.startsWith("wss://"));
    }

    /** Derive a stable subdomain from ANDROID_ID so URL stays same across reconnects. */
    public String getStableSubdomain() {
        String userSet = getSubdomain();
        if (userSet != null && !userSet.trim().isEmpty()) {
            return sanitizeSubdomain(userSet);
        }
        String androidId = android.provider.Settings.Secure.getString(
            appContext.getContentResolver(),
            android.provider.Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.length() < 8) {
            androidId = PreferenceManager.getDefaultSharedPreferences(appContext)
                .getString("bantu_fallback_id", null);
            if (androidId == null) {
                androidId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                PreferenceManager.getDefaultSharedPreferences(appContext).edit()
                    .putString("bantu_fallback_id", androidId).apply();
            }
        }
        String stable = "d" + (androidId.length() >= 7 ? androidId.substring(0, 7) : androidId);
        return sanitizeSubdomain(stable);
    }

    // Lifecycle
    public boolean isRunning() { return connected; }
    public boolean isWantRunning() { return running; }
    public String getCurrentPublicUrl() { return currentPublicUrl; }

    public void start(int port, Callback cb) {
        if (running) { cb.onError("Bantu tunnel already running"); return; }
        if (!isConfigured()) {
            cb.onError("Bantu server URL not configured. Open Settings to set it.");
            return;
        }
        this.localPort = port;
        this.callback = cb;
        this.running = true;
        this.reconnectAttempts = 0;
        this.effectiveSubdomain = getStableSubdomain();
        connect();
        keepaliveHandler.removeCallbacks(keepaliveRunnable);
        keepaliveHandler.postDelayed(keepaliveRunnable, KEEPALIVE_INTERVAL_MS);
    }

    private void connect() {
        if (!running) return;
        String wsUrl = buildWebSocketUrl(getServerUrl(), effectiveSubdomain, getToken());
        if (wsUrl == null) {
            if (callback != null) callback.onError("Invalid Bantu server URL: " + getServerUrl());
            return;
        }
        if (reconnectAttempts == 0) {
            post(msg("Connecting to " + maskUrl(wsUrl) + " ..."));
        } else {
            post(msg("Reconnect attempt #" + reconnectAttempts + "..."));
        }
        Request request = new Request.Builder().url(wsUrl).build();
        http.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                connected = true; ws = webSocket; reconnectAttempts = 0;
                post(msg("WebSocket connected \u2014 waiting for tunnel URL..."));
                Log.i(TAG, "[Bantu] WS open, server=" + response.code());
            }
            @Override public void onMessage(WebSocket webSocket, String text) { handleServerMessage(text); }
            @Override public void onClosing(WebSocket webSocket, int code, String reason) {}
            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                handleDisconnect("closed: " + code + " " + reason);
            }
            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                String err = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                handleDisconnect("failure: " + err);
            }
        });
    }

    private void handleDisconnect(String reason) {
        boolean wasConnected = connected;
        connected = false; ws = null;
        for (okhttp3.Call c : pendingCalls.values()) { try { c.cancel(); } catch (Exception ignored) {} }
        pendingCalls.clear();
        if (wasConnected && callback != null) {
            post(() -> { if (callback != null) callback.onDisconnected(); });
        }
        if (running) {
            reconnectAttempts++;
            long delay = Math.min(1000L * (1L << Math.min(reconnectAttempts - 1, 5)), 30_000L);
            delay = (long) (delay * (0.75 + Math.random() * 0.5));
            reconnectHandler.postDelayed(this::connect, delay);
            post(msg("Disconnected (" + reason + "). Reconnecting in " + (delay / 1000) + "s..."));
        }
    }

    public void stop() {
        running = false; connected = false;
        reconnectHandler.removeCallbacksAndMessages(null);
        keepaliveHandler.removeCallbacksAndMessages(null);
        if (ws != null) { try { ws.close(1000, "client stopped"); } catch (Exception ignored) {} }
        for (okhttp3.Call c : pendingCalls.values()) { try { c.cancel(); } catch (Exception ignored) {} }
        pendingCalls.clear(); ws = null;
    }

    private void sendKeepalivePing() {
        String serverUrl = getServerUrl();
        if (serverUrl == null || serverUrl.isEmpty()) return;
        try {
            String healthUrl = serverUrl.trim();
            while (healthUrl.endsWith("/")) healthUrl = healthUrl.substring(0, healthUrl.length() - 1);
            if (healthUrl.startsWith("ws://")) healthUrl = "http://" + healthUrl.substring(5);
            else if (healthUrl.startsWith("wss://")) healthUrl = "https://" + healthUrl.substring(6);
            healthUrl += "/health";
            Request req = new Request.Builder().url(healthUrl).get().build();
            httpForPing.newCall(req).enqueue(new okhttp3.Callback() {
                @Override public void onFailure(okhttp3.Call c, IOException e) {}
                @Override public void onResponse(okhttp3.Call c, Response r) throws IOException { r.close(); }
            });
        } catch (Exception e) {}
    }

    /**
     * Bind a custom domain (e.g. splannes.co.tz) to this tunnel.
     * After the server acknowledges, requests to https://splannes.co.tz
     * will route to this tunnel's local port.
     *
     * Prerequisites (the user does these manually, one-time):
     *   1. Add the custom domain in Render dashboard (for HTTPS cert)
     *   2. Add a CNAME record at the domain's registrar (e.g. Wazohost)
     *      pointing the domain at the bantu-tunnel Render service
     *
     * The server-side mapping is wiped when the tunnel disconnects, so
     * this needs to be re-sent after each reconnect. Use autoRebindDomain()
     * to make it sticky.
     */
    public void bindDomain(String domain) {
        if (ws == null || !connected) {
            if (callback != null) callback.onError("Cannot bind domain — tunnel not connected");
            return;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "bind-domain");
            msg.put("domain", domain.trim().toLowerCase());
            ws.send(msg.toString());
            Log.i(TAG, "[Bantu] bind-domain sent: " + domain);
        } catch (Exception e) {
            if (callback != null) callback.onError("bind-domain error: " + e.getMessage());
        }
    }

    /** Unbind a previously-bound custom domain. */
    public void unbindDomain(String domain) {
        if (ws == null || !connected) return;
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "unbind-domain");
            msg.put("domain", domain.trim().toLowerCase());
            ws.send(msg.toString());
        } catch (Exception e) {}
    }

    /** Persist a domain so it auto-rebinds after each reconnect. */
    public void setAutoRebindDomain(String domain) {
        PreferenceManager.getDefaultSharedPreferences(appContext).edit()
            .putString("bantu_auto_domain", domain == null ? "" : domain.trim().toLowerCase())
            .apply();
    }

    public String getAutoRebindDomain() {
        return PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString("bantu_auto_domain", "");
    }

    static String buildWebSocketUrl(String serverUrl, String subdomain, String token) {
        if (serverUrl == null) return null;
        String s = serverUrl.trim();
        if (s.isEmpty()) return null;
        try {
            if (s.startsWith("http://")) s = "ws://" + s.substring(7);
            else if (s.startsWith("https://")) s = "wss://" + s.substring(8);
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            if (s.endsWith("/ws")) s = s.substring(0, s.length() - 3);
            StringBuilder q = new StringBuilder();
            if (subdomain != null && !subdomain.isEmpty()) {
                q.append("subdomain=").append(URLEncoder.encode(sanitizeSubdomain(subdomain), StandardCharsets.UTF_8.name()));
            }
            if (token != null && !token.isEmpty()) {
                if (q.length() > 0) q.append("&");
                q.append("token=").append(URLEncoder.encode(token, StandardCharsets.UTF_8.name()));
            }
            return s + "/ws" + (q.length() > 0 ? "?" + q.toString() : "");
        } catch (Exception e) { return null; }
    }

    static String sanitizeSubdomain(String input) {
        if (input == null) return "";
        return input.toLowerCase().replaceAll("[^a-z0-9_-]", "")
            .substring(0, Math.min(32, input.length()));
    }

    static String maskUrl(String url) {
        if (url == null) return "(null)";
        return url.replaceAll("token=[^&]+", "token=***");
    }

    private void handleServerMessage(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.optString("type", "");
            switch (type) {
                case "connected": {
                    final String publicUrl = msg.optString("url", "");
                    final String mode = msg.optString("mode", "path");
                    final String tunnelId = msg.optString("tunnelId", "");
                    boolean urlChanged = !publicUrl.equals(currentPublicUrl);
                    currentPublicUrl = publicUrl;
                    if (urlChanged) {
                        post(msg("Tunnel established \u2014 mode: " + mode + ", id: " + tunnelId));
                        post(msg("Public URL: " + publicUrl));
                    } else {
                        post(msg("Reconnected \u2014 same URL: " + publicUrl));
                    }
                    post(() -> { if (callback != null) callback.onConnected(publicUrl); });

                    // Auto-rebind a previously-bound custom domain after reconnect
                    final String autoDomain = getAutoRebindDomain();
                    if (autoDomain != null && !autoDomain.isEmpty()) {
                        post(msg("Auto-rebinding custom domain: " + autoDomain));
                        // Small delay to let server finish registering the tunnel
                        reconnectHandler.postDelayed(() -> bindDomain(autoDomain), 500);
                    }
                    break;
                }
                case "request": handleRequest(msg); break;
                case "pong": break;
                case "domain-bound": {
                    final String domain = msg.optString("domain", "");
                    final String url = msg.optString("url", "");
                    final String serverMsg = msg.optString("message", "");
                    post(msg("Domain bound: " + domain));
                    post(msg("Live URL: " + url));
                    if (!serverMsg.isEmpty()) post(msg(serverMsg));
                    break;
                }
                case "domain-unbound": {
                    final String domain = msg.optString("domain", "");
                    post(msg("Domain unbound: " + domain));
                    break;
                }
                case "error": {
                    final String serverMsg = msg.optString("message", "unknown error");
                    post(msg("Server error: " + serverMsg));
                    if (serverMsg.toLowerCase().contains("already in use")) {
                        post(() -> { if (callback != null)
                            callback.onError("Subdomain is already in use."); });
                    }
                    break;
                }
            }
        } catch (Exception e) { Log.e(TAG, "parse error: " + e.getMessage()); }
    }

    private void handleRequest(JSONObject req) {
        final String id = req.optString("id", "");
        if (id.isEmpty()) return;
        final String method = req.optString("method", "GET");
        final String path = req.optString("path", "/");
        JSONObject hdrsJson = req.optJSONObject("headers");
        final Map<String, String> headers = new HashMap<>();
        if (hdrsJson != null) {
            for (java.util.Iterator<String> it = hdrsJson.keys(); it.hasNext(); ) {
                String k = it.next();
                headers.put(k, hdrsJson.optString(k, ""));
            }
        }
        String bodyB64 = req.optString("body", "");
        final byte[] body = bodyB64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(bodyB64);

        String localUrl = "http://127.0.0.1:" + localPort + path;
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(localUrl);
        okhttp3.Headers.Builder cleanHeaders = new okhttp3.Headers.Builder();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String k = e.getKey(); String v = e.getValue();
            if (k == null) continue;
            String lk = k.toLowerCase();
            if (lk.equals("host") || lk.equals("connection") || lk.equals("upgrade")
                || lk.equals("proxy-connection") || lk.equals("keep-alive")
                || lk.equals("transfer-encoding") || lk.equals("te")
                || lk.equals("trailer") || lk.startsWith("proxy-")) continue;
            cleanHeaders.add(k, v);
        }
        okhttp3.Headers finalHeaders = cleanHeaders.build();
        String contentType = finalHeaders.get("content-type");
        okhttp3.MediaType mt = contentType != null ? okhttp3.MediaType.parse(contentType) : null;
        okhttp3.RequestBody reqBody = (body.length > 0 || "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method))
            ? okhttp3.RequestBody.create(body, mt) : null;
        try {
            okhttp3.Request okReq;
            if (reqBody == null) { okReq = rb.method(method, null).build(); }
            else { okReq = rb.method(method, reqBody).build(); }
            okReq = okReq.newBuilder().headers(finalHeaders).build();
            okhttp3.Call call = http.newCall(okReq);
            pendingCalls.put(id, call);
            call.enqueue(new okhttp3.Callback() {
                @Override public void onFailure(okhttp3.Call call, IOException e) {
                    pendingCalls.remove(id);
                    sendResponse(id, 502, simpleHeaders("text/plain"),
                        ("Cannot reach local server on port " + localPort + ": " + e.getMessage())
                            .getBytes(StandardCharsets.UTF_8));
                }
                @Override public void onResponse(okhttp3.Call call, Response resp) throws IOException {
                    pendingCalls.remove(id);
                    try (okhttp3.ResponseBody respBody = resp.body()) {
                        byte[] bytes = respBody != null ? respBody.bytes() : new byte[0];
                        Map<String, String> respHeaders = new HashMap<>();
                        for (String name : resp.headers().names()) {
                            String v = resp.header(name);
                            if (v != null) respHeaders.put(name, v);
                        }
                        respHeaders.remove("Content-Length");
                        respHeaders.remove("content-length");
                        respHeaders.remove("Transfer-Encoding");
                        respHeaders.remove("transfer-encoding");
                        sendResponse(id, resp.code(), respHeaders, bytes);
                    }
                }
            });
        } catch (IllegalArgumentException iae) {
            pendingCalls.remove(id);
            sendResponse(id, 400, simpleHeaders("text/plain"),
                ("Bad method: " + method).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void sendResponse(String id, int status, Map<String, String> headers, byte[] body) {
        if (ws == null) return;
        try {
            JSONObject resp = new JSONObject();
            resp.put("type", "response");
            resp.put("id", id);
            resp.put("status", status);
            JSONObject hdrsJson = new JSONObject();
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) hdrsJson.put(e.getKey(), e.getValue());
            }
            resp.put("headers", hdrsJson);
            resp.put("body", body == null || body.length == 0
                ? "" : Base64.getEncoder().encodeToString(body));
            ws.send(resp.toString());
        } catch (Exception e) { Log.e(TAG, "sendResponse error: " + e.getMessage()); }
    }

    private static Map<String, String> simpleHeaders(String contentType) {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", contentType);
        return h;
    }

    private interface MainAction { void run(); }
    private void post(MainAction a) { mainHandler.post(a::run); }
    private MainAction msg(final String text) {
        return () -> { if (callback != null) callback.onMessage(text); };
    }
}
