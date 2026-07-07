package com.bantu.droid;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * RenderApi — HTTP client for Render's REST API (v1).
 *
 * Lets BantuDroid programmatically add custom domains to the bantu-tunnel
 * Render service, so the user doesn't have to visit the Render dashboard.
 *
 * Required setup (one-time):
 *   1. User creates a Render API key at https://dashboard.render.com/users/me/api-keys
 *      (Needs "Write" scope for services)
 *   2. User enters the API key in BantuDroid Settings
 *   3. App auto-finds the bantu-tunnel service by name (or user enters service ID)
 *
 * API reference: https://render.com/docs/api
 *
 * IMPORTANT (response format):
 *   The Render API returns BARE JSON arrays for list endpoints, NOT objects
 *   wrapping an array. For example:
 *     GET /v1/services             -> [ {id, name, ...}, ... ]
 *     GET /v1/services/{id}/custom-domains -> [ {id, customDomain, ...}, ... ]
 *   We use a parseArray() helper that handles both forms defensively.
 */
public class RenderApi {

    private static final String TAG = "RenderApi";
    private static final String API_BASE = "https://api.render.com/v1";

    public interface Callback {
        void onSuccess(String message);
        void onError(String error);
    }

    public interface ServiceCallback {
        void onServiceFound(String serviceId, String serviceName);
        void onError(String error);
    }

    public interface DomainStatusCallback {
        void onStatus(String domainId, String verificationStatus, String sslStatus, String message);
        void onError(String error);
    }

    /**
     * Parse a JSON response into a JSONArray, handling BOTH bare-array form
     * (which is what Render actually returns) and object-wrapped form
     * (defensive fallback).
     *
     *   "[...]"              -> parse as bare array
     *   "{services:[...]}"   -> extract .services array
     *   "{customDomains:[...]}" -> extract .customDomains array
     */
    private static JSONArray parseArray(String response) {
        if (response == null) return null;
        String trimmed = response.trim();
        if (trimmed.isEmpty()) return null;

        // Bare array form — this is what Render returns
        if (trimmed.startsWith("[")) {
            try { return new JSONArray(trimmed); }
            catch (Exception e) { Log.e(TAG, "parseArray JSON array failed: " + e.getMessage()); return null; }
        }

        // Object-wrapped form (defensive — in case Render changes their API later)
        if (trimmed.startsWith("{")) {
            try {
                JSONObject obj = new JSONObject(trimmed);
                // Try common wrapper keys
                String[] keys = {"services", "customDomains", "data", "items", "results"};
                for (String k : keys) {
                    JSONArray arr = obj.optJSONArray(k);
                    if (arr != null) return arr;
                }
                // No known wrapper key — log what we got so the user can report it
                Log.w(TAG, "parseArray: object response had no recognized array key: " + trimmed.substring(0, Math.min(200, trimmed.length())));
                return null;
            } catch (Exception e) { Log.e(TAG, "parseArray JSON object failed: " + e.getMessage()); return null; }
        }
        return null;
    }

    /**
     * Find a service by name (e.g. "bantu-tunnel"). Iterates through paginated
     * service list looking for a name match (case-insensitive, partial).
     */
    public static void findServiceByName(String apiKey, String nameQuery, ServiceCallback cb) {
        new Thread(() -> {
            try {
                // GET /v1/services?limit=100  — returns bare array
                String response = apiRequest("GET",
                    API_BASE + "/services?limit=100",
                    apiKey, null);
                if (response == null) {
                    cb.onError("No response from Render API. Check your API key.");
                    return;
                }

                JSONArray services = parseArray(response);
                if (services == null || services.length() == 0) {
                    cb.onError("No Render services found on your account. Make sure your API key has access.");
                    return;
                }

                Log.i(TAG, "Found " + services.length() + " service(s) on account");

                // Find a service whose name contains the query (case-insensitive)
                String lowerQuery = nameQuery.toLowerCase();
                for (int i = 0; i < services.length(); i++) {
                    JSONObject svc = services.getJSONObject(i);
                    String svcName = svc.optString("name", "");
                    String svcId = svc.optString("id", "");
                    String svcType = svc.optString("type", "");
                    Log.d(TAG, "  service[" + i + "]: " + svcName + " (id=" + svcId + ", type=" + svcType + ")");

                    if (svcName.toLowerCase().contains(lowerQuery) ||
                        lowerQuery.contains(svcName.toLowerCase())) {
                        Log.i(TAG, "Matched service: " + svcName + " (id=" + svcId + ")");
                        cb.onServiceFound(svcId, svcName);
                        return;
                    }
                }

                // No match — return the first service as a fallback (so the user can still proceed)
                JSONObject first = services.getJSONObject(0);
                String firstName = first.optString("name", "(unnamed)");
                String firstId = first.optString("id", "");
                Log.w(TAG, "No name match for '" + nameQuery + "'. Falling back to first service: " + firstName);
                cb.onServiceFound(firstId, firstName);
            } catch (RenderApiException e) {
                cb.onError(e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "findServiceByName error", e);
                cb.onError("Failed: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Add a custom domain to a Render service.
     * Render starts provisioning SSL automatically (takes ~1-2 min).
     */
    public static void addCustomDomain(String apiKey, String serviceId,
                                         String domain, Callback cb) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("customDomain", domain.trim().toLowerCase());

                String response = apiRequest("POST",
                    API_BASE + "/services/" + serviceId + "/custom-domains",
                    apiKey, body.toString());

                if (response == null) {
                    // 204 No Content sometimes — treat as success
                    cb.onSuccess("Domain added to Render.");
                    return;
                }

                // Response is a single object (not array) — the created domain resource
                JSONObject json = new JSONObject(response);
                String sslStatus = json.optString("sslStatus", "pending");
                String verificationStatus = json.optString("verificationStatus", "pending");

                Log.i(TAG, "Custom domain added: " + domain + " (ssl=" + sslStatus + ", verification=" + verificationStatus + ")");
                cb.onSuccess("Domain added to Render. SSL provisioning: " + sslStatus
                    + ". Add the CNAME at your registrar now.");
            } catch (RenderApiException e) {
                // Check for "already exists" — that's not a fatal error
                String msg = e.getMessage();
                if (msg != null && msg.toLowerCase().contains("already")) {
                    cb.onSuccess("Domain already added to Render (this is OK)");
                } else {
                    cb.onError(msg != null ? msg : "API error");
                }
            } catch (Exception e) {
                Log.e(TAG, "addCustomDomain error", e);
                cb.onError("Failed: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Get the status of a custom domain (verification + SSL provisioning).
     */
    public static void getDomainStatus(String apiKey, String serviceId,
                                         String domain, DomainStatusCallback cb) {
        new Thread(() -> {
            try {
                String response = apiRequest("GET",
                    API_BASE + "/services/" + serviceId + "/custom-domains?limit=100",
                    apiKey, null);
                if (response == null) { cb.onError("No response from Render API"); return; }

                JSONArray domains = parseArray(response);
                if (domains == null || domains.length() == 0) {
                    cb.onError("No custom domains on this service yet. Tap 'Add Domain to Render' first.");
                    return;
                }

                String lowerDomain = domain.trim().toLowerCase();
                for (int i = 0; i < domains.length(); i++) {
                    JSONObject d = domains.getJSONObject(i);
                    String dName = d.optString("customDomain", "").toLowerCase();
                    if (dName.equals(lowerDomain)) {
                        String domainId = d.optString("id", "");
                        String ssl = d.optString("sslStatus", "unknown");
                        String verif = d.optString("verificationStatus", "unknown");
                        String msg;
                        if ("issued".equals(ssl) && "verified".equals(verif)) {
                            msg = "READY — SSL certificate issued, domain verified. Visit https://" + domain;
                        } else if ("issuing".equals(ssl) || "pending".equals(ssl)) {
                            msg = "SSL provisioning in progress... (usually 1-2 minutes)";
                        } else if ("failed".equals(ssl)) {
                            msg = "SSL provisioning FAILED. Check that the CNAME is correctly pointing at the Render service.";
                        } else {
                            msg = "Status: verification=" + verif + ", ssl=" + ssl;
                        }
                        cb.onStatus(domainId, verif, ssl, msg);
                        return;
                    }
                }
                cb.onError("Domain " + domain + " not found on this service. Add it first.");
            } catch (RenderApiException e) {
                cb.onError(e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "getDomainStatus error", e);
                cb.onError("Failed: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Remove a custom domain from a Render service.
     */
    public static void removeCustomDomain(String apiKey, String serviceId,
                                            String domain, Callback cb) {
        new Thread(() -> {
            try {
                String response = apiRequest("GET",
                    API_BASE + "/services/" + serviceId + "/custom-domains?limit=100",
                    apiKey, null);
                if (response == null) { cb.onError("No response"); return; }

                JSONArray domains = parseArray(response);
                if (domains == null) { cb.onError("No custom domains"); return; }

                String lowerDomain = domain.trim().toLowerCase();
                String domainId = null;
                for (int i = 0; i < domains.length(); i++) {
                    JSONObject d = domains.getJSONObject(i);
                    if (d.optString("customDomain", "").toLowerCase().equals(lowerDomain)) {
                        domainId = d.optString("id", "");
                        break;
                    }
                }
                if (domainId == null) {
                    cb.onError("Domain not found on this service");
                    return;
                }

                apiRequest("DELETE",
                    API_BASE + "/services/" + serviceId + "/custom-domains/" + domainId,
                    apiKey, null);
                cb.onSuccess("Domain removed from Render");
            } catch (Exception e) {
                cb.onError("Failed: " + e.getMessage());
            }
        }).start();
    }

    // ──────────────────────────────────────────────────────────────
    // Low-level HTTP helper
    // ──────────────────────────────────────────────────────────────

    private static String apiRequest(String method, String urlStr, String apiKey, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            if (body != null && (method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
            }
            int status = conn.getResponseCode();
            java.io.InputStream is;
            if (status >= 200 && status < 300) {
                is = conn.getInputStream();
            } else {
                is = conn.getErrorStream();
            }
            if (is == null) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            String response = sb.toString();
            if (status < 200 || status >= 300) {
                // Try to extract a useful error message from JSON
                String errMsg = "HTTP " + status;
                try {
                    // Response might be an object with "message" or "errors"
                    if (response.trim().startsWith("{")) {
                        JSONObject errJson = new JSONObject(response);
                        JSONArray errors = errJson.optJSONArray("errors");
                        if (errors != null && errors.length() > 0) {
                            errMsg = errors.getJSONObject(0).optString("message", errMsg);
                        } else {
                            errMsg = errJson.optString("message", errMsg);
                        }
                    } else if (response.trim().startsWith("[")) {
                        JSONArray errArr = new JSONArray(response);
                        if (errArr.length() > 0) {
                            errMsg = errArr.getJSONObject(0).optString("message", errMsg);
                        }
                    }
                } catch (Exception ignored) {}
                throw new RenderApiException(errMsg);
            }
            return response.isEmpty() ? null : response;
        } finally {
            conn.disconnect();
        }
    }

    private static class RenderApiException extends Exception {
        RenderApiException(String msg) { super(msg); }
    }
}
