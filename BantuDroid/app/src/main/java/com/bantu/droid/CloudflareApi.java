package com.bantu.droid;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Manages Cloudflare API interactions for custom domain support.
 *
 * Provides:
 * - Tunnel creation via Cloudflare API (named tunnels)
 * - DNS CNAME record creation (points custom domain to tunnel)
 * - Zone ID lookup from domain name
 * - Tunnel secret/credentials management
 *
 * Required Cloudflare API token permissions:
 * - Account > Cloudflare Tunnel > Edit
 * - Zone > DNS > Edit
 * - Zone > Zone > Read
 *
 * API documentation:
 * - Create tunnel: POST /accounts/{account_id}/cfd_tunnel
 * - Create DNS record: POST /zones/{zone_id}/dns_records
 * - List zones: GET /zones?name={domain}
 */
public class CloudflareApi {

    private static final String TAG = "CloudflareApi";
    private static final String API_BASE = "https://api.cloudflare.com/client/v4";

    /**
     * Create a new Cloudflare named tunnel.
     *
     * @param apiToken  Cloudflare API token
     * @param tunnelName Name for the tunnel (e.g., "bantudroid-mysite")
     * @param cb        Callback for status messages
     * @return tunnel ID if successful, null otherwise
     */
    public static String createTunnel(String apiToken, String tunnelName,
                                       TunnelManager.TunnelCallback cb) {
        try {
            // Step 1: Get account ID
            String accountId = getAccountId(apiToken, cb);
            if (accountId == null) {
                if (cb != null) cb.onError("Failed to get Cloudflare account ID");
                return null;
            }

            if (cb != null) cb.onMessage("Account ID: " + accountId);

            // Step 2: Create the tunnel
            String tunnelSecret = generateTunnelSecret();

            JSONObject body = new JSONObject();
            body.put("name", tunnelName);
            body.put("tunnel_secret", tunnelSecret);
            body.put("config_src", "cloudflare");

            String response = apiRequest("POST",
                API_BASE + "/accounts/" + accountId + "/cfd_tunnel",
                apiToken, body.toString(), cb);

            if (response == null) {
                if (cb != null) cb.onError("Tunnel creation API request failed");
                return null;
            }

            JSONObject json = new JSONObject(response);
            if (!json.optBoolean("success", false)) {
                JSONArray errors = json.optJSONArray("errors");
                String errMsg = "Cloudflare API error";
                if (errors != null && errors.length() > 0) {
                    errMsg = errors.getJSONObject(0).optString("message", errMsg);
                }
                if (cb != null) cb.onError(errMsg);
                return null;
            }

            JSONObject result = json.getJSONObject("result");
            String tunnelId = result.getString("id");

            // Save the tunnel credentials for later use by cloudflared
            saveTunnelCredentials(tunnelId, accountId, tunnelSecret, tunnelName);

            return tunnelId;

        } catch (Exception e) {
            Log.e(TAG, "Failed to create tunnel", e);
            if (cb != null) cb.onError("Tunnel creation error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Create a DNS CNAME record pointing a domain to a Cloudflare tunnel.
     *
     * @param apiToken  Cloudflare API token
     * @param zoneId    Cloudflare Zone ID
     * @param domain    Full domain name (e.g., "mysite.example.com")
     * @param target    CNAME target (e.g., "{tunnel_id}.cfargotunnel.com")
     * @param cb        Callback for status messages
     * @return true if successful
     */
    public static boolean createDnsCname(String apiToken, String zoneId,
                                          String domain, String target,
                                          TunnelManager.TunnelCallback cb) {
        try {
            JSONObject body = new JSONObject();
            body.put("type", "CNAME");
            body.put("name", domain);
            body.put("content", target);
            body.put("ttl", 1);  // Auto TTL
            body.put("proxied", true);

            String response = apiRequest("POST",
                API_BASE + "/zones/" + zoneId + "/dns_records",
                apiToken, body.toString(), cb);

            if (response == null) {
                if (cb != null) cb.onError("DNS record creation request failed");
                return false;
            }

            JSONObject json = new JSONObject(response);
            if (!json.optBoolean("success", false)) {
                JSONArray errors = json.optJSONArray("errors");
                String errMsg = "DNS creation failed";
                if (errors != null && errors.length() > 0) {
                    errMsg = errors.getJSONObject(0).optString("message", errMsg);
                    // Check for "already exists" error - not fatal
                    if (errMsg.contains("already exists")) {
                        if (cb != null) cb.onMessage(
                            "DNS record already exists (may need manual update). "
                            + "Go to Cloudflare Dashboard > DNS to verify.");
                        return true;
                    }
                }
                if (cb != null) cb.onError(errMsg);
                return false;
            }

            if (cb != null) cb.onMessage("DNS CNAME created: " + domain + " -> " + target);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to create DNS record", e);
            if (cb != null) cb.onError("DNS error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Look up a Cloudflare Zone ID by domain name.
     * The domain must be added to the user's Cloudflare account.
     *
     * @param apiToken  Cloudflare API token
     * @param domain    Domain name (e.g., "example.com")
     * @param cb        Callback for status messages
     * @return zone ID if found, null otherwise
     */
    public static String getZoneId(String apiToken, String domain,
                                    TunnelManager.TunnelCallback cb) {
        try {
            // Extract the base domain (remove subdomain if present)
            String baseDomain = extractBaseDomain(domain);

            String response = apiRequest("GET",
                API_BASE + "/zones?name=" + baseDomain,
                apiToken, null, cb);

            if (response == null) {
                return null;
            }

            JSONObject json = new JSONObject(response);
            if (!json.optBoolean("success", false)) {
                JSONArray errors = json.optJSONArray("errors");
                if (errors != null && errors.length() > 0) {
                    String errMsg = errors.getJSONObject(0).optString("message", "API error");
                    if (cb != null) cb.onError("Zone lookup: " + errMsg);
                }
                return null;
            }

            JSONArray result = json.getJSONArray("result");
            if (result.length() == 0) {
                if (cb != null) cb.onError(
                    "Domain '" + baseDomain + "' not found in your Cloudflare account. "
                    + "Add it at https://dash.cloudflare.com/ first.");
                return null;
            }

            String zoneId = result.getJSONObject(0).getString("id");
            if (cb != null) cb.onMessage("Zone ID for " + baseDomain + ": " + zoneId);
            return zoneId;

        } catch (Exception e) {
            Log.e(TAG, "Failed to get zone ID", e);
            if (cb != null) cb.onError("Zone lookup error: " + e.getMessage());
            return null;
        }
    }

    /**
     * List existing tunnels on the Cloudflare account.
     * Used to check if a tunnel already exists before creating a new one.
     */
    public static JSONArray listTunnels(String apiToken,
                                         TunnelManager.TunnelCallback cb) {
        try {
            String accountId = getAccountId(apiToken, cb);
            if (accountId == null) return null;

            String response = apiRequest("GET",
                API_BASE + "/accounts/" + accountId + "/cfd_tunnel",
                apiToken, null, cb);

            if (response == null) return null;

            JSONObject json = new JSONObject(response);
            if (json.optBoolean("success", false)) {
                return json.getJSONArray("result");
            }
            return null;

        } catch (Exception e) {
            Log.e(TAG, "Failed to list tunnels", e);
            return null;
        }
    }

    /**
     * Delete a Cloudflare tunnel by ID.
     */
    public static boolean deleteTunnel(String apiToken, String tunnelId,
                                        TunnelManager.TunnelCallback cb) {
        try {
            String accountId = getAccountId(apiToken, cb);
            if (accountId == null) return false;

            String response = apiRequest("DELETE",
                API_BASE + "/accounts/" + accountId + "/cfd_tunnel/" + tunnelId,
                apiToken, null, cb);

            if (response != null) {
                JSONObject json = new JSONObject(response);
                return json.optBoolean("success", false);
            }
            return false;

        } catch (Exception e) {
            Log.e(TAG, "Failed to delete tunnel", e);
            return false;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────

    /**
     * Get the Cloudflare account ID for the authenticated user.
     */
    private static String getAccountId(String apiToken,
                                        TunnelManager.TunnelCallback cb) {
        try {
            String response = apiRequest("GET",
                API_BASE + "/accounts", apiToken, null, cb);

            if (response == null) return null;

            JSONObject json = new JSONObject(response);
            if (json.optBoolean("success", false)) {
                JSONArray result = json.getJSONArray("result");
                if (result.length() > 0) {
                    return result.getJSONObject(0).getString("id");
                }
            }

            JSONArray errors = json.optJSONArray("errors");
            if (errors != null && errors.length() > 0) {
                String errMsg = errors.getJSONObject(0).optString("message", "unknown");
                if (cb != null) cb.onError("Account lookup failed: " + errMsg);
            }
            return null;

        } catch (Exception e) {
            Log.e(TAG, "Failed to get account ID", e);
            return null;
        }
    }

    /**
     * Make an HTTP request to the Cloudflare API.
     */
    private static String apiRequest(String method, String urlStr,
                                      String apiToken, String bodyJson,
                                      TunnelManager.TunnelCallback cb) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Authorization", "Bearer " + apiToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

            if (bodyJson != null) {
                conn.setDoOutput(true);
                byte[] body = bodyJson.getBytes("UTF-8");
                conn.setRequestProperty("Content-Length", String.valueOf(body.length));
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
            }

            int code = conn.getResponseCode();
            BufferedReader reader;
            if (code >= 400) {
                reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
            } else {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            }

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            conn.disconnect();

            return sb.toString();

        } catch (Exception e) {
            Log.e(TAG, "API request failed: " + method + " " + urlStr, e);
            if (cb != null) {
                cb.onError("API request failed: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Extract the base domain from a potentially fully-qualified domain.
     * e.g., "sub.example.com" -> "example.com"
     *      "example.com"     -> "example.com"
     */
    private static String extractBaseDomain(String domain) {
        if (domain == null || domain.isEmpty()) return domain;
        // Remove any trailing dot
        domain = domain.endsWith(".") ? domain.substring(0, domain.length() - 1) : domain;
        // Split and take last two parts
        String[] parts = domain.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        return domain;
    }

    /**
     * Generate a random tunnel secret (32 bytes, base64-encoded).
     */
    private static String generateTunnelSecret() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
    }

    /**
     * Save tunnel credentials to app storage for later use by cloudflared.
     */
    private static void saveTunnelCredentials(String tunnelId, String accountId,
                                               String secret, String name) {
        try {
            java.io.File dir = new java.io.File(
                BantuEngine.getAppContext().getFilesDir(), "tunnel_configs");
            dir.mkdirs();

            JSONObject creds = new JSONObject();
            creds.put("AccountTag", accountId);
            creds.put("TunnelID", tunnelId);
            creds.put("TunnelSecret", secret);
            creds.put("TunnelName", name);

            java.io.File file = new java.io.File(dir, "credentials-" + tunnelId + ".json");
            try (java.io.FileWriter writer = new java.io.FileWriter(file)) {
                writer.write(creds.toString(2));
            }

            Log.i(TAG, "Tunnel credentials saved: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save tunnel credentials", e);
        }
    }
}