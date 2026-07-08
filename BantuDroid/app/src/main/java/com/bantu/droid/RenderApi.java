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

    // Hardcoded for the user's bantu-tunnel Render service
    public static final String HARDCODED_SERVICE_ID = "srv-d95v5onaqgkc73ej3b70";
    public static final String HARDCODED_SERVICE_NAME = "bantu-tunnel";
    public static final String HARDCODED_RENDER_HOST = "bantu-tunnel.onrender.com";

    public interface Callback {
        void onSuccess(String message);
        void onError(String error);
    }

    /** Callback that includes the raw HTTP status code for diagnostics. */
    public interface VerboseCallback {
        void onSuccess(int httpStatus, String message);
        void onError(int httpStatus, String error);
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
     * Logs every service found for diagnostics.
     */
    public static void findServiceByName(String apiKey, String nameQuery, ServiceCallback cb) {
        new Thread(() -> {
            try {
                // GET /v1/services?limit=100  — returns bare array
                HttpResult result = apiRequestVerbose("GET",
                    API_BASE + "/services?limit=100",
                    apiKey, null);

                Log.i(TAG, "findServiceByName HTTP " + result.status + " bodyLen=" + result.body.length());

                if (result.status >= 400) {
                    String errMsg = extractErrorMessage(result.body, result.status);
                    Log.e(TAG, "API error: " + errMsg);
                    cb.onError("HTTP " + result.status + ": " + errMsg
                        + (result.status == 401 ? " (API key invalid or missing Read scope)" : ""));
                    return;
                }

                if (result.body == null || result.body.trim().isEmpty()) {
                    cb.onError("Render returned an empty response. Check your API key.");
                    return;
                }

                JSONArray services = parseArray(result.body);
                if (services == null) {
                    cb.onError("Could not parse Render response: "
                        + result.body.substring(0, Math.min(200, result.body.length())));
                    return;
                }
                if (services.length() == 0) {
                    cb.onError("No Render services found on your account. "
                        + "Make sure your API key has Read access.");
                    return;
                }

                Log.i(TAG, "Found " + services.length() + " service(s) on account");

                // Find a service whose name contains the query (case-insensitive)
                String lowerQuery = nameQuery.toLowerCase();
                JSONObject matched = null;
                for (int i = 0; i < services.length(); i++) {
                    JSONObject svc = services.getJSONObject(i);
                    String svcName = svc.optString("name", "");
                    String svcId = svc.optString("id", "");
                    String svcType = svc.optString("type", "");
                    Log.i(TAG, "  service[" + i + "]: name='" + svcName + "' id='" + svcId + "' type=" + svcType);

                    if (matched == null && (svcName.toLowerCase().contains(lowerQuery) ||
                        lowerQuery.contains(svcName.toLowerCase()))) {
                        matched = svc;
                        Log.i(TAG, "  ^^^ MATCHED");
                    }
                }

                if (matched != null) {
                    String id = matched.optString("id", "");
                    String name = matched.optString("name", "(unnamed)");
                    if (id.isEmpty()) {
                        cb.onError("Matched service '" + name + "' but it has no ID. "
                            + "Raw: " + matched.toString());
                        return;
                    }
                    cb.onServiceFound(id, name);
                } else {
                    // No match — return the first service that has a non-empty ID
                    for (int i = 0; i < services.length(); i++) {
                        JSONObject svc = services.getJSONObject(i);
                        String id = svc.optString("id", "");
                        if (!id.isEmpty()) {
                            String name = svc.optString("name", "(unnamed)");
                            Log.w(TAG, "No name match for '" + nameQuery + "'. Using first service with ID: " + name);
                            cb.onServiceFound(id, name);
                            return;
                        }
                    }
                    cb.onError("Found " + services.length() + " services but none have a valid ID. "
                        + "Raw response: " + result.body.substring(0, Math.min(300, result.body.length())));
                }
            } catch (RenderApiException e) {
                cb.onError(e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "findServiceByName error", e);
                cb.onError("Failed: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Diagnostic: return the raw list of services as a string. Used by the
     * 'Test API Connection' button so the user can see exactly what Render
     * returns when we can't auto-detect.
     */
    public static void testConnection(String apiKey, Callback cb) {
        new Thread(() -> {
            try {
                HttpResult result = apiRequestVerbose("GET",
                    API_BASE + "/services?limit=20",
                    apiKey, null);

                StringBuilder sb = new StringBuilder();
                sb.append("HTTP ").append(result.status).append("\n");
                sb.append("Response body (" ).append(result.body.length()).append(" bytes):\n");

                if (result.status >= 400) {
                    sb.append(result.body.isEmpty() ? "(empty error body)" : result.body);
                    cb.onError(sb.toString());
                    return;
                }

                JSONArray services = parseArray(result.body);
                if (services == null) {
                    sb.append("Could not parse as array. Raw:\n");
                    sb.append(result.body.isEmpty() ? "(empty)" : result.body);
                    cb.onSuccess(sb.toString());
                    return;
                }

                sb.append(services.length()).append(" service(s) found:\n\n");
                for (int i = 0; i < services.length(); i++) {
                    JSONObject svc = services.getJSONObject(i);
                    String name = svc.optString("name", "(no name)");
                    String id = svc.optString("id", "(no id)");
                    String type = svc.optString("type", "?");
                    sb.append("  [").append(i).append("] ").append(name)
                      .append("  id=").append(id)
                      .append("  type=").append(type).append("\n");
                }
                if (services.length() == 0) {
                    sb.append("(no services — your API key may be read-only or scoped to nothing)");
                }
                cb.onSuccess(sb.toString());
            } catch (Exception e) {
                cb.onError("Exception: " + e.getMessage());
            }
        }).start();
    }

    /** Sanitize a domain name per RFC 1034. */
    public static String sanitizeDomain(String input) {
        if (input == null) return null;
        String d = input.trim().toLowerCase();
        if (d.startsWith("https://")) d = d.substring(8);
        else if (d.startsWith("http://")) d = d.substring(7);
        int slash = d.indexOf('/');
        if (slash > 0) d = d.substring(0, slash);
        int colon = d.indexOf(':');
        if (colon > 0) d = d.substring(0, colon);
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        d = d.replaceAll("[^a-z0-9.-]", "");
        if (d.length() < 4 || !d.contains(".")) return null;
        if (d.length() > 253) return null;
        for (String label : d.split("\\.")) {
            if (label.isEmpty() || label.length() > 63) return null;
            if (label.startsWith("-") || label.endsWith("-")) return null;
        }
        return d;
    }

    public static void addCustomDomain(String apiKey, String serviceId,
                                         String domain, Callback cb) {
        new Thread(() -> {
            try {
                String cleanDomain = sanitizeDomain(domain);
                if (cleanDomain == null) {
                    cb.onError("Invalid domain: '" + domain + "'. Must be like www.splannes.co.tz");
                    return;
                }
                String effectiveServiceId = (serviceId == null || serviceId.isEmpty())
                    ? HARDCODED_SERVICE_ID : serviceId;

                // Use 'name' field per Render's official curl docs
                JSONObject body = new JSONObject();
                body.put("name", cleanDomain);

                HttpResult result = apiRequestVerbose("POST",
                    API_BASE + "/services/" + effectiveServiceId + "/custom-domains",
                    apiKey, body.toString());

                Log.i(TAG, "addCustomDomain HTTP " + result.status + " body=" + result.body);

                // If 400 mentioning customDomain, try that field name
                if (result.status == 400 && result.body != null &&
                    result.body.toLowerCase().contains("customdomain")) {
                    JSONObject altBody = new JSONObject();
                    altBody.put("customDomain", cleanDomain);
                    result = apiRequestVerbose("POST",
                        API_BASE + "/services/" + effectiveServiceId + "/custom-domains",
                        apiKey, altBody.toString());
                }

                if (result.status >= 400) {
                    String errMsg = extractErrorMessage(result.body, result.status);
                    cb.onError("HTTP " + result.status + ": " + errMsg + "\n\nRaw: " + result.body);
                    return;
                }

                // Verify
                Thread.sleep(500);
                HttpResult listResult = apiRequestVerbose("GET",
                    API_BASE + "/services/" + effectiveServiceId + "/custom-domains?limit=100",
                    apiKey, null);
                JSONArray domains = parseArray(listResult.body);
                if (domains != null) {
                    for (int i = 0; i < domains.length(); i++) {
                        JSONObject d = domains.getJSONObject(i);
                        String dName = d.optString("customDomain", "").toLowerCase();
                        if (dName.isEmpty()) dName = d.optString("name", "").toLowerCase();
                        if (dName.equals(cleanDomain)) {
                            String ssl = d.optString("sslStatus", "pending");
                            cb.onSuccess("Domain verified on Render: " + cleanDomain + "\nSSL: " + ssl);
                            return;
                        }
                    }
                }
                cb.onError("Render accepted (HTTP " + result.status + ") but domain not in list.\nPOST: " + result.body + "\nLIST: " + listResult.body);
            } catch (RenderApiException e) {
                String msg = e.getMessage();
                if (msg != null && msg.toLowerCase().contains("already")) {
                    cb.onSuccess("Domain already added to Render");
                } else {
                    cb.onError(msg != null ? msg : "API error");
                }
            } catch (Exception e) {
                cb.onError("Failed: " + e.getMessage());
            }
        }).start();
    }

    /** Fetch DNS records Render requires and return as formatted string. */
    public static void getDnsInstructions(String apiKey, String serviceId,
                                            String domain, Callback cb) {
        new Thread(() -> {
            try {
                String effectiveServiceId = (serviceId == null || serviceId.isEmpty())
                    ? HARDCODED_SERVICE_ID : serviceId;
                String cleanDomain = sanitizeDomain(domain);
                if (cleanDomain == null) { cb.onError("Invalid domain"); return; }

                HttpResult listResult = apiRequestVerbose("GET",
                    API_BASE + "/services/" + effectiveServiceId + "/custom-domains?limit=100",
                    apiKey, null);

                JSONObject domainObj = null;
                JSONArray domains = parseArray(listResult.body);
                if (domains != null) {
                    for (int i = 0; i < domains.length(); i++) {
                        JSONObject d = domains.getJSONObject(i);
                        String dName = d.optString("customDomain", "").toLowerCase();
                        if (dName.isEmpty()) dName = d.optString("name", "").toLowerCase();
                        if (dName.equals(cleanDomain)) { domainObj = d; break; }
                    }
                }
                if (domainObj == null) {
                    cb.onError("Domain " + cleanDomain + " not found on Render. Add it first.");
                    return;
                }

                StringBuilder sb = new StringBuilder();
                String ssl = domainObj.optString("sslStatus", "unknown");
                String verif = domainObj.optString("verificationStatus", "unknown");
                sb.append("DNS RECORDS FOR: ").append(cleanDomain).append("\n");
                sb.append("Status: ssl=").append(ssl).append(" verification=").append(verif).append("\n\n");

                // Try verification records from API
                JSONArray verifRecords = domainObj.optJSONArray("verificationRecords");
                if (verifRecords != null && verifRecords.length() > 0) {
                    sb.append("RECORDS RENDER REQUIRES:\n\n");
                    for (int i = 0; i < verifRecords.length(); i++) {
                        JSONObject r = verifRecords.getJSONObject(i);
                        sb.append("[").append(i+1).append("] ").append(r.optString("type","CNAME")).append(" Record\n");
                        sb.append("  Host:   ").append(r.optString("name","@")).append("\n");
                        sb.append("  Value:  ").append(r.optString("value","")).append("\n\n");
                    }
                } else {
                    // Compute defaults
                    String hostLabel = "www";
                    if (cleanDomain.startsWith("www.")) hostLabel = "www";
                    else if (cleanDomain.startsWith("api.")) hostLabel = "api";
                    else if (cleanDomain.startsWith("app.")) hostLabel = "app";
                    else { int dot = cleanDomain.indexOf('.'); hostLabel = dot > 0 ? cleanDomain.substring(0, dot) : "@"; }

                    sb.append("DNS RECORDS TO ADD AT YOUR REGISTRAR:\n\n");
                    sb.append("[1] CNAME Record\n");
                    sb.append("  Host/Name:  ").append(hostLabel).append("\n");
                    sb.append("  Target:     ").append(HARDCODED_RENDER_HOST).append("\n");
                    sb.append("  TTL:        Automatic\n\n");
                    sb.append("[2] A Record (for apex/root domain)\n");
                    sb.append("  Host/Name:  @\n");
                    sb.append("  Value/IP:   216.24.57.1\n");
                    sb.append("  TTL:        Automatic\n\n");
                }

                if ("issued".equals(ssl) && "verified".equals(verif)) {
                    sb.append("VERIFIED - SSL issued! Visit https://").append(cleanDomain).append("\n");
                } else if ("issuing".equals(ssl) || "pending".equals(ssl)) {
                    sb.append("SSL provisioning... check again in 1-2 min.\n");
                } else if ("failed".equals(ssl)) {
                    sb.append("SSL FAILED - check DNS records are correct.\n");
                }
                cb.onSuccess(sb.toString());
            } catch (Exception e) {
                cb.onError("Failed: " + e.getMessage());
            }
        }).start();
    }

    /**
     * List all custom domains on a service — used for diagnostics and verification.
     */
    public static void listCustomDomains(String apiKey, String serviceId, Callback cb) {
        new Thread(() -> {
            try {
                HttpResult result = apiRequestVerbose("GET",
                    API_BASE + "/services/" + serviceId + "/custom-domains?limit=100",
                    apiKey, null);

                if (result.status >= 400) {
                    cb.onError("HTTP " + result.status + ": " + extractErrorMessage(result.body, result.status));
                    return;
                }

                JSONArray domains = parseArray(result.body);
                if (domains == null || domains.length() == 0) {
                    cb.onSuccess("No custom domains on this service yet.");
                    return;
                }

                StringBuilder sb = new StringBuilder();
                sb.append(domains.length()).append(" custom domain(s) on this service:\n");
                for (int i = 0; i < domains.length(); i++) {
                    JSONObject d = domains.getJSONObject(i);
                    String name = d.optString("customDomain", "?");
                    String ssl = d.optString("sslStatus", "?");
                    String verif = d.optString("verificationStatus", "?");
                    sb.append("  - ").append(name)
                      .append(" (ssl=").append(ssl)
                      .append(", verification=").append(verif).append(")\n");
                }
                cb.onSuccess(sb.toString().trim());
            } catch (Exception e) {
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
    // Low-level HTTP helpers
    // ──────────────────────────────────────────────────────────────

    /** Holds both the HTTP status code and response body for diagnostics. */
    private static class HttpResult {
        int status;
        String body;
        HttpResult(int s, String b) { status = s; body = b; }
    }

    /** Extract a human-readable error message from a Render error response. */
    private static String extractErrorMessage(String body, int defaultStatus) {
        if (body == null || body.trim().isEmpty()) return "HTTP " + defaultStatus;
        try {
            String trimmed = body.trim();
            if (trimmed.startsWith("{")) {
                JSONObject errJson = new JSONObject(trimmed);
                JSONArray errors = errJson.optJSONArray("errors");
                if (errors != null && errors.length() > 0) {
                    return errors.getJSONObject(0).optString("message", "HTTP " + defaultStatus);
                }
                String msg = errJson.optString("message", null);
                if (msg != null) return msg;
            } else if (trimmed.startsWith("[")) {
                JSONArray errArr = new JSONArray(trimmed);
                if (errArr.length() > 0) {
                    return errArr.getJSONObject(0).optString("message", "HTTP " + defaultStatus);
                }
            }
        } catch (Exception ignored) {}
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    /** Verbose version of apiRequest that returns both status and body. */
    private static HttpResult apiRequestVerbose(String method, String urlStr, String apiKey, String body) throws Exception {
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
            java.io.InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = "";
            if (is != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                responseBody = sb.toString();
            }
            Log.d(TAG, method + " " + urlStr + " -> HTTP " + status + " (" + responseBody.length() + " bytes)");
            return new HttpResult(status, responseBody);
        } finally {
            conn.disconnect();
        }
    }

    /** Original apiRequest kept for backwards-compat — delegates to apiRequestVerbose. */
    private static String apiRequest(String method, String urlStr, String apiKey, String body) throws Exception {
        HttpResult result = apiRequestVerbose(method, urlStr, apiKey, body);
        if (result.status < 200 || result.status >= 300) {
            throw new RenderApiException(extractErrorMessage(result.body, result.status));
        }
        return result.body.isEmpty() ? null : result.body;
    }

    private static class RenderApiException extends Exception {
        RenderApiException(String msg) { super(msg); }
    }
}
