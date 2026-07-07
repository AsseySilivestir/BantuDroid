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
 * Endpoints used:
 *   GET    /v1/services                        — list services (find bantu-tunnel)
 *   POST   /v1/services/{id}/custom-domains    — add a custom domain
 *   GET    /v1/services/{id}/custom-domains    — list custom domains
 *   GET    /v1/services/{id}/custom-domains/{domainId} — check status (SSL provisioning)
 *   DELETE /v1/services/{id}/custom-domains/{domainId} — remove
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
     * Find a service by name (e.g. "bantu-tunnel"). Iterates through paginated
     * service list looking for a name match (case-insensitive, partial).
     */
    public static void findServiceByName(String apiKey, String nameQuery, ServiceCallback cb) {
        new Thread(() -> {
            try {
                // GET /v1/services?limit=100
                String response = apiRequest("GET",
                    API_BASE + "/services?limit=100&name=" + URLEncoder.encode(nameQuery, "UTF-8"),
                    apiKey, null);
                if (response == null) {
                    cb.onError("No response from Render API");
                    return;
                }
                JSONObject json = new JSONObject(response);
                JSONArray services = json.optJSONArray("services");
                if (services == null || services.length() == 0) {
                    // Try without the name filter — sometimes the filter is strict
                    response = apiRequest("GET", API_BASE + "/services?limit=100", apiKey, null);
                    if (response == null) { cb.onError("No services found"); return; }
                    json = new JSONObject(response);
                    services = json.optJSONArray("services");
                }
                if (services == null || services.length() == 0) {
                    cb.onError("No Render services found on your account");
                    return;
                }
                // Find a service whose name contains the query (case-insensitive)
                String lowerQuery = nameQuery.toLowerCase();
                for (int i = 0; i < services.length(); i++) {
                    JSONObject svc = services.getJSONObject(i);
                    String svcName = svc.optString("name", "");
                    String svcId = svc.optString("id", "");
                    if (svcName.toLowerCase().contains(lowerQuery) ||
                        lowerQuery.contains(svcName.toLowerCase())) {
                        Log.i(TAG, "Found service: " + svcName + " (id=" + svcId + ")");
                        cb.onServiceFound(svcId, svcName);
                        return;
                    }
                }
                // No match — return the first service as a fallback
                JSONObject first = services.getJSONObject(0);
                cb.onServiceFound(first.optString("id", ""), first.optString("name", "(unnamed)"));
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
                    cb.onError("No response from Render API");
                    return;
                }

                // 201 = created, 200 = already exists
                JSONObject json = new JSONObject(response);
                String domainId = json.optString("id", "");
                String sslStatus = json.optString("sslStatus", "pending");
                String verificationStatus = json.optString("verificationStatus", "pending");

                Log.i(TAG, "Custom domain added: " + domain + " (id=" + domainId
                    + ", ssl=" + sslStatus + ", verification=" + verificationStatus + ")");
                cb.onSuccess("Domain added to Render. SSL provisioning: " + sslStatus
                    + ". Add the CNAME at your registrar now.");
            } catch (RenderApiException e) {
                // API returned an error — check for "already exists"
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("already")) {
                    cb.onSuccess("Domain already added to Render (this is OK)");
                } else {
                    cb.onError(e.getMessage());
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
                // First, list domains to find the ID for this domain
                String response = apiRequest("GET",
                    API_BASE + "/services/" + serviceId + "/custom-domains?limit=100",
                    apiKey, null);
                if (response == null) { cb.onError("No response"); return; }

                JSONObject json = new JSONObject(response);
                JSONArray domains = json.optJSONArray("customDomains");
                if (domains == null) {
                    cb.onError("No custom domains on this service");
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
                // Find the domain ID first
                String response = apiRequest("GET",
                    API_BASE + "/services/" + serviceId + "/custom-domains?limit=100",
                    apiKey, null);
                if (response == null) { cb.onError("No response"); return; }

                JSONObject json = new JSONObject(response);
                JSONArray domains = json.optJSONArray("customDomains");
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
                // Try to extract error message from JSON
                try {
                    JSONObject errJson = new JSONObject(response);
                    JSONArray errors = errJson.optJSONArray("errors");
                    if (errors != null && errors.length() > 0) {
                        throw new RenderApiException(errors.getJSONObject(0).optString("message",
                            "HTTP " + status));
                    }
                    String msg = errJson.optString("message", "HTTP " + status);
                    throw new RenderApiException(msg);
                } catch (RenderApiException e) { throw e; }
                catch (Exception e) { throw new RenderApiException("HTTP " + status); }
            }
            return response;
        } finally {
            conn.disconnect();
        }
    }

    private static class RenderApiException extends Exception {
        RenderApiException(String msg) { super(msg); }
    }
}
