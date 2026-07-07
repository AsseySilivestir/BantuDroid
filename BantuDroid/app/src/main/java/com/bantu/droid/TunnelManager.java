package com.bantu.droid;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages SSH reverse tunnels and Cloudflare tunnel processes.
 *
 * SSH Tunnel providers:
 *   - Serveo (serveo.net) — no intermediate page
 *   - localhost.run (localhost.run) — no intermediate page
 *   - Pinggy (a.pinggy.io) — NOTE: free tier shows an intermediate "Enter" page
 *     that cannot be disabled. See SSH_PROVIDER_NOTES for details.
 *
 * Cloudflare tunnel execution strategy (critical for Android 10+):
 *   - PRIMARY: JNI fork+execv via BantuBridge (bypasses SELinux exec restrictions)
 *   - FALLBACK: ProcessBuilder from codeCacheDir (may work on some devices)
 *   - The old approach of using ProcessBuilder from Termux paths fails because
 *     /data/data/com.termux/files/usr/bin/ is mounted NOEXEC on Android 10+.
 *
 * Cloudflared binary management:
 *   - Downloads cloudflared from official GitHub releases if not found
 *   - Stores in app's codeCacheDir (writable, possibly executable)
 *   - Uses JNI bridge for execution to bypass SELinux
 */
public class TunnelManager {

    private static final String TAG = "TunnelManager";

    // Cloudflared download URLs (official GitHub releases)
    private static final String CF_DOWNLOAD_BASE =
        "https://github.com/cloudflare/cloudflared/releases/latest/download/";
    private static final String CF_VERSION_API =
        "https://api.github.com/repos/cloudflare/cloudflared/releases/latest";

    public interface TunnelCallback {
        void onMessage(String msg);
        void onError(String err);
        void onConnected(String url);
        void onDisconnected();
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onMessage(String msg);
        void onSuccess(String path);
        void onError(String err);
    }

    // ──────────────────────────────────────────────────────────────
    // SSH Tunnel providers
    // ──────────────────────────────────────────────────────────────
    //
    // IMPORTANT: Provider ordering matters. Serveo and localhost.run
    // are listed first because they provide direct access to the
    // tunneled content without an intermediate page. Pinggy is listed
    // last because its free tier injects an intermediate "Enter" page
    // before visitors can reach the hosted website. This page is a
    // Pinggy server-side behavior that cannot be disabled through
    // SSH options, HTTP headers, or any client-side configuration.
    // It is a limitation of Pinggy's free tier business model.

    public static final String[][] SSH_PROVIDERS = {
        {"Bantu", "bantu-tunnel", "443", "bantu"},
        {"Serveo", "serveo.net", "443", "serveo"},
        {"localhost.run", "localhost.run", "443", "localhostrun"},
        {"Pinggy", "a.pinggy.io", "443", "pinggy"}
    };

    /**
     * Human-readable notes about each SSH provider's behavior.
     * Index matches SSH_PROVIDERS array.
     */
    public static final String[] SSH_PROVIDER_NOTES = {
        // Bantu
        "Bantu is YOUR self-hosted tunnel server (github.com/bantugateway/bantu-tunnel). "
        + "Deploy it free on Render, then enter its URL in Settings. "
        + "URLs look like https://your-app.onrender.com/t/<id>/  (path mode) "
        + "or https://<id>.<your-domain> (subdomain mode). "
        + "You can request a specific subdomain via Settings.",

        // Serveo
        "Serveo provides direct access with no intermediate page. "
        + " URLs are assigned randomly (e.g., https://xyz.serveo.net). "
        + " You can request a subdomain: ssh -R mysite:80:localhost:8080 serveo.net",

        // localhost.run
        "localhost.run provides direct access with no intermediate page. "
        + " URLs include a random token (e.g., https://abc123.lhr.life). "
        + " The tunnel is stable and suitable for development use.",

        // Pinggy
        "LIMITATION: Pinggy's free tier shows an intermediate 'Enter' page "
        + "before visitors reach your website. This is a Pinggy server-side "
        + "behavior that CANNOT be disabled through SSH options, HTTP headers, "
        + "or any client-side configuration. It is a platform limitation of "
        + "Pinggy's free tier. To avoid this, use Bantu or Serveo instead. "
        + "The 'Enter' page is injected by Pinggy's reverse proxy before forwarding "
        + "the request to your SSH tunnel."
    };

    private Session sshSession;
    private Thread sshThread;
    private volatile boolean sshRunning = false;
    private String sshUrl;

    // Bantu tunnel state (self-hosted WebSocket tunnel)
    private BantuTunnelClient bantuClient;
    private volatile boolean bantuRunning = false;

    private Process cfProcess;
    private Thread cfThread;
    private volatile boolean cfRunning = false;
    private String cfUrl;

    // Custom domain state
    private String customDomain;
    private String cfApiKey;
    private String cfZoneId;
    private String cfTunnelName;
    private boolean cfNamedTunnel = false;
    private String cfNamedTunnelUrl;

    // Callback reference for safe use inside executeCloudflared
    private volatile TunnelCallback cfCallback;

    // Context for file operations and BantuBridge access
    private final Context appContext;

    public TunnelManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Start SSH reverse tunnel.
     *
     * IMPLEMENTATION STRATEGY:
     * Each provider requires a DIFFERENT SSH approach to get the tunnel URL:
     *
     *   - Serveo:       JSch setPortForwardingR() + shell channel polling
     *                   URL appears in shell stdout: "Forwarding HTTP traffic from https://..."
     *
     *   - localhost.run: exec channel with "none" command + stderr URL capture
     *                   Must use: ssh -R 80:localhost:PORT none
     *                   URL printed on connect: "https://abc123.lhr.life"
     *
     *   - Pinggy:       exec channel with "none" command + stderr URL capture
     *                   Must use: ssh -R 80:localhost:PORT a.pinggy.io none
     *                   URL in stderr: "https://xyz.a.pinggy.io"
     *
     * KEY INSIGHT: localhost.run and Pinggy print the URL as part of the
     * SSH session/banner output BEFORE any shell or exec channel is opened.
     * Using setPortForwardingR() works for Serveo (which prints the URL
     * after the forwarding request) but NOT for localhost.run/Pinggy
     * (which print the URL during the SSH command execution).
     *
     * @param providerIndex index into SSH_PROVIDERS
     * @param localPort the local server port to expose
     */
    public void startSshTunnel(int providerIndex, int localPort, TunnelCallback cb) {
        if (sshRunning || bantuRunning) {
            cb.onError("A tunnel is already running");
            return;
        }

        String[] provider = SSH_PROVIDERS[providerIndex];
        String name = provider[0];
        String host = provider[1];
        int port = Integer.parseInt(provider[2]);
        String providerKey = provider[3];

        // Bantu is a WebSocket-based self-hosted tunnel, not SSH — delegate.
        if ("bantu".equals(providerKey)) {
            startBantuTunnel(localPort, cb);
            return;
        }

        sshThread = new Thread(() -> {
            com.jcraft.jsch.Channel channel = null;
            try {
                cb.onMessage("[SSH-" + providerKey + "] Connecting to " + name + " (" + host + ":" + port + ")...");
                Log.i(TAG, "[SSH-" + providerKey + "] Starting tunnel for localhost:" + localPort);

                JSch jsch = new JSch();
                jsch.setKnownHosts("/dev/null");

                // Create session
                sshSession = jsch.getSession("", host, port);
                sshSession.setPassword("");
                sshSession.setConfig("StrictHostKeyChecking", "no");
                sshSession.setConfig("UserKnownHostsFile", "/dev/null");
                sshSession.setConfig("PreferredAuthentications", "password");
                sshSession.setConfig("server_alive_interval", "15");
                sshSession.setConfig("server_alive_count_max", "3");

                sshSession.connect(30000);
                sshRunning = true;
                cb.onMessage("[SSH-" + providerKey + "] SSH session established");
                Log.i(TAG, "[SSH-" + providerKey + "] SSH connected successfully");

                String extractedUrl = null;

                if ("serveo".equals(providerKey)) {
                    // ── SERVEO: Use JSch port forwarding + shell channel polling ──
                    // Serveo prints the URL after setPortForwardingR() in the
                    // shell channel stdout. This is the original approach.
                    channel = sshSession.openChannel("shell");
                    java.io.ByteArrayOutputStream outBuf = new java.io.ByteArrayOutputStream();
                    java.io.ByteArrayOutputStream errBuf = new java.io.ByteArrayOutputStream();
                    channel.setOutputStream(outBuf);
                    channel.setExtOutputStream(errBuf);
                    channel.connect(5000);

                    sshSession.setPortForwardingR(0, "127.0.0.1", localPort);
                    cb.onMessage("[SSH-serveo] Reverse port forward: R:0 -> localhost:" + localPort);
                    Log.i(TAG, "[SSH-serveo] Port forwarding established");

                    extractedUrl = pollForUrl(outBuf, errBuf, providerKey, 15000, cb);
                    if (extractedUrl == null) {
                        // Dump what we got for debugging
                        dumpChannelOutput(outBuf, errBuf, cb);
                    }

                } else if ("localhostrun".equals(providerKey)) {
                    // ── LOCALHOST.RUN: Use exec channel with specific command ──
                    // localhost.run requires: ssh -R 80:localhost:PORT none
                    // The URL is printed to stderr immediately on connect.
                    // Using setPortForwardingR() does NOT trigger URL output.
                    cb.onMessage("[SSH-localhostrun] Opening exec channel: -R 80:localhost:" + localPort + " none");
                    Log.i(TAG, "[SSH-localhostrun] Using exec channel approach");

                    channel = sshSession.openChannel("exec");
                    ((ChannelExec) channel).setCommand("none");
                    java.io.ByteArrayOutputStream outBuf = new java.io.ByteArrayOutputStream();
                    java.io.ByteArrayOutputStream errBuf = new java.io.ByteArrayOutputStream();
                    channel.setOutputStream(outBuf);
                    channel.setExtOutputStream(errBuf);

                    // Set up the reverse tunnel BEFORE connecting the channel
                    // localhost.run uses port 80 on the remote side
                    sshSession.setPortForwardingR(80, "127.0.0.1", localPort);
                    cb.onMessage("[SSH-localhostrun] Port forward R:80 -> localhost:" + localPort);
                    Log.i(TAG, "[SSH-localhostrun] Forwarding established");

                    channel.connect(10000);

                    // localhost.run prints the URL very quickly in stderr
                    extractedUrl = pollForUrl(outBuf, errBuf, providerKey, 20000, cb);
                    if (extractedUrl == null) {
                        dumpChannelOutput(outBuf, errBuf, cb);
                        // Try broader pattern on all output
                        String allText = outBuf.toString() + "\n" + errBuf.toString();
                        extractedUrl = extractBroadUrl(allText, "lhr.life");
                    }

                } else if ("pinggy".equals(providerKey)) {
                    // ── PINGGY: Use exec channel with specific command ──
                    // Pinggy requires: ssh -R 80:localhost:PORT a.pinggy.io none
                    // The URL is printed to stderr (extended data stream).
                    // IMPORTANT: Pinggy uses ANSI color codes in output that
                    // can be embedded INSIDE the URL. pollForUrl now strips
                    // ANSI codes before regex matching.
                    cb.onMessage("[SSH-pinggy] Opening exec channel: -R 80:localhost:" + localPort + " a.pinggy.io none");
                    Log.i(TAG, "[SSH-pinggy] Using exec channel approach");

                    channel = sshSession.openChannel("exec");
                    ((ChannelExec) channel).setCommand("none");
                    java.io.ByteArrayOutputStream outBuf = new java.io.ByteArrayOutputStream();
                    java.io.ByteArrayOutputStream errBuf = new java.io.ByteArrayOutputStream();
                    channel.setOutputStream(outBuf);
                    channel.setExtOutputStream(errBuf);

                    // Set up the reverse tunnel
                    sshSession.setPortForwardingR(80, "127.0.0.1", localPort);
                    cb.onMessage("[SSH-pinggy] Port forward R:80 -> localhost:" + localPort);
                    Log.i(TAG, "[SSH-pinggy] Forwarding established");

                    channel.connect(10000);

                    // Pinggy prints URL quickly — poll for up to 25 seconds
                    extractedUrl = pollForUrl(outBuf, errBuf, providerKey, 25000, cb);
                    if (extractedUrl == null) {
                        dumpChannelOutput(outBuf, errBuf, cb);
                        // Extra fallback: try ANY https:// URL in the combined output
                        String allText = stripAnsiCodes(outBuf.toString()) + "\n" + stripAnsiCodes(errBuf.toString());
                        extractedUrl = extractBroadUrl(allText, "pinggy");
                        if (extractedUrl == null) {
                            // Last resort: any https:// URL at all
                            extractedUrl = extractBroadUrl(allText, "https://");
                        }
                    }
                }

                if (extractedUrl != null) {
                    sshUrl = extractedUrl;
                    cb.onMessage("[SSH-" + providerKey + "] Tunnel URL: " + sshUrl);
                    Log.i(TAG, "[SSH-" + providerKey + "] URL extracted: " + sshUrl);
                } else {
                    sshUrl = "Connected via " + name + " (check log for URL)";
                    cb.onMessage("[SSH-" + providerKey + "] Could not auto-extract URL. The tunnel IS active.");
                    cb.onMessage("[SSH-" + providerKey + "] Check the output above — the URL may be there.");
                    Log.w(TAG, "[SSH-" + providerKey + "] Failed to extract URL from channel output");
                }

                cb.onConnected(sshUrl);

                // Keep the session alive
                while (sshRunning && sshSession.isConnected()) {
                    if (channel != null && !channel.isConnected()) {
                        Log.i(TAG, "[SSH-" + providerKey + "] Channel disconnected, ending tunnel");
                        break;
                    }
                    Thread.sleep(1000);
                }

            } catch (JSchException e) {
                String errMsg = "[SSH-" + providerKey + "] " + e.getMessage();
                Log.e(TAG, errMsg, e);
                cb.onError(errMsg);
            } catch (Exception e) {
                String errMsg = "[SSH-" + providerKey + "] " + e.getClass().getSimpleName() + ": " + e.getMessage();
                Log.e(TAG, errMsg, e);
                cb.onError(errMsg);
            } finally {
                sshRunning = false;
                if (channel != null) {
                    try { channel.disconnect(); } catch (Exception ignored) {}
                }
                disconnectSsh();
                cb.onDisconnected();
            }
        }, "ssh-tunnel-" + providerKey);
        sshThread.setDaemon(true);
        sshThread.start();
    }

    /**
     * Poll channel output buffers for a tunnel URL with timeout.
     *
     * CRITICAL: ANSI escape codes are stripped BEFORE URL extraction.
     * Pinggy (and some other servers) embed ANSI color codes INSIDE
     * the URL text (e.g., \033[32mhttps://\033[0m...). Without stripping,
     * the regex fails because \033 is not a valid URL character.
     *
     * @param outBuf      stdout buffer
     * @param errBuf      stderr buffer
     * @param providerKey provider key for URL pattern matching
     * @param timeoutMs   maximum time to wait
     * @param cb          callback for debug messages
     * @return extracted URL or null
     */
    private String pollForUrl(java.io.ByteArrayOutputStream outBuf,
                               java.io.ByteArrayOutputStream errBuf,
                               String providerKey, long timeoutMs,
                               TunnelCallback cb) {
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline && sshRunning) {
                Thread.sleep(500);

                // ALWAYS strip ANSI codes first — critical for Pinggy
                // Pinggy embeds color codes inside the URL text itself
                String outText = stripAnsiCodes(outBuf.toString());
                String errText = stripAnsiCodes(errBuf.toString());

                // Check stdout
                String url = extractTunnelUrl(outText, providerKey);
                if (url != null) {
                    Log.i(TAG, "[SSH-" + providerKey + "] URL found in stdout: " + url);
                    return url;
                }

                // Check stderr
                url = extractTunnelUrl(errText, providerKey);
                if (url != null) {
                    Log.i(TAG, "[SSH-" + providerKey + "] URL found in stderr: " + url);
                    return url;
                }

                // Debug: report if we're getting output but no URL yet
                if (outText.length() > 0 || errText.length() > 0) {
                    Log.d(TAG, "[SSH-" + providerKey + "] Waiting... stdout=" + outText.length()
                        + " bytes, stderr=" + errText.length() + " bytes");
                    // Log first 300 chars of each for debugging
                    if (outText.length() > 0) {
                        Log.d(TAG, "[SSH-" + providerKey + "] stdout preview: "
                            + outText.substring(0, Math.min(300, outText.length())));
                    }
                    if (errText.length() > 0) {
                        Log.d(TAG, "[SSH-" + providerKey + "] stderr preview: "
                            + errText.substring(0, Math.min(300, errText.length())));
                    }
                    // For Pinggy: also log RAW (unstripped) output to diagnose ANSI issues
                    if ("pinggy".equals(providerKey)) {
                        String rawOut = outBuf.toString();
                        String rawErr = errBuf.toString();
                        if (rawOut.length() > 0) {
                            Log.d(TAG, "[SSH-pinggy] RAW stdout (first 300): "
                                + rawOut.substring(0, Math.min(300, rawOut.length())));
                        }
                        if (rawErr.length() > 0) {
                            Log.d(TAG, "[SSH-pinggy] RAW stderr (first 300): "
                                + rawErr.substring(0, Math.min(300, rawErr.length())));
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /**
     * Dump channel output for debugging when URL extraction fails.
     */
    private void dumpChannelOutput(java.io.ByteArrayOutputStream outBuf,
                                    java.io.ByteArrayOutputStream errBuf,
                                    TunnelCallback cb) {
        String outText = outBuf.toString().trim();
        String errText = errBuf.toString().trim();

        if (!outText.isEmpty()) {
            String display = outText.length() > 500 ? outText.substring(0, 500) + "..." : outText;
            cb.onMessage("[SSH] stdout: " + display);
            Log.d(TAG, "[SSH] stdout dump: " + outText);
        }
        if (!errText.isEmpty()) {
            String display = errText.length() > 500 ? errText.substring(0, 500) + "..." : errText;
            cb.onMessage("[SSH] stderr: " + display);
            Log.d(TAG, "[SSH] stderr dump: " + errText);
        }
        if (outText.isEmpty() && errText.isEmpty()) {
            cb.onMessage("[SSH] No output received from server");
            Log.w(TAG, "[SSH] Empty channel output — server sent nothing");
        }
    }

    /**
     * Strip ANSI escape codes from text.
     * Some SSH servers (Pinggy) include color codes in their output.
     */
    private String stripAnsiCodes(String text) {
        if (text == null) return "";
        return text.replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "")
                    .replaceAll("\u001B\\].*?\u0007", "")  // OSC sequences
                    .replaceAll("\\x1b\\[[0-9;]*[a-zA-Z]", "");
    }

    /**
     * Broader URL extraction — find any HTTPS URL containing the given domain.
     * Falls back to this when the provider-specific pattern fails.
     */
    private String extractBroadUrl(String text, String domainHint) {
        if (text == null || text.isEmpty()) return null;
        Pattern p = Pattern.compile("https://[^\\s\"'<>]+");
        Matcher m = p.matcher(text);
        while (m.find()) {
            String found = m.group();
            if (found.contains(domainHint)) {
                // Clean trailing punctuation
                found = found.replaceAll("[.,;:!?)>]+$", "");
                return found;
            }
        }
        return null;
    }

    /**
     * Extract a tunnel URL from SSH server output text.
     *
     * Each provider has a known URL pattern:
     *   - Serveo:       "https://<random>.serveo.net"
     *   - localhost.run: "https://<random>.lhr.life"
     *   - Pinggy:       "https://<random>.a.pinggy.io"
     *
     * @param text        the server output to search
     * @param providerKey the provider key from SSH_PROVIDERS ("serveo", "localhostrun", "pinggy")
     * @return extracted URL, or null if not found
     */
    private String extractTunnelUrl(String text, String providerKey) {
        if (text == null || text.isEmpty()) return null;

        // More permissive URL pattern — matches any https:// URL with
        // domain containing at least one dot. Handles ANSI artifacts,
        // trailing punctuation, etc.
        Pattern urlPattern = Pattern.compile("https?://[a-zA-Z0-9._-]+\\.[a-zA-Z]{2,}[a-zA-Z0-9./_-]*");
        Matcher m = urlPattern.matcher(text);

        while (m.find()) {
            String found = m.group();

            switch (providerKey) {
                case "serveo":
                    // Serveo URLs: https://xyz.serveo.net
                    if (found.contains("serveo.net")) return found;
                    break;

                case "localhostrun":
                    // localhost.run URLs: https://abc123.lhr.life
                    if (found.contains("lhr.life")) return found;
                    // Also accept the direct localhost.run domain
                    if (found.contains("localhost.run")) return found;
                    break;

                case "pinggy":
                    // Pinggy URLs: https://xyz.a.pinggy.io
                    // IMPORTANT: exclude dashboard.pinggy.io which appears in
                    // server output but is NOT the tunnel URL
                    if (found.contains("pinggy.io") && !found.contains("dashboard")) {
                        return found;
                    }
                    break;

                default:
                    // Generic: return first HTTPS URL that isn't a known non-tunnel domain
                    if (!found.contains("github.com")
                        && !found.contains("cloudflare.com")
                        && !found.contains("example.com")) {
                        return found;
                    }
                    break;
            }
        }
        return null;
    }

    /**
     * Stop the SSH tunnel.
     */
    public void stopSshTunnel() {
        sshRunning = false;
        disconnectSsh();
        // Also stop a Bantu tunnel if it's running
        if (bantuClient != null) {
            bantuRunning = false;
            try { bantuClient.stop(); } catch (Exception ignored) {}
            bantuClient = null;
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Bantu Tunnel (self-hosted, WebSocket-based — like ngrok)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Start a Bantu tunnel — connects to the user's self-hosted bantu-tunnel
     * server (deployable on Render) over WebSocket and exposes the local port.
     */
    public void startBantuTunnel(int localPort, TunnelCallback cb) {
        if (bantuRunning) {
            cb.onError("Bantu tunnel already running");
            return;
        }
        bantuClient = new BantuTunnelClient(appContext);
        if (!bantuClient.isConfigured()) {
            cb.onError("Bantu server URL is not configured. Open Settings and set it first.");
            bantuClient = null;
            return;
        }

        bantuRunning = true;
        cb.onMessage("[Bantu] Starting tunnel to " + bantuClient.getServerUrl()
            + " for localhost:" + localPort
            + (bantuClient.getSubdomain().isEmpty() ? "" : " (subdomain: " + bantuClient.getSubdomain() + ")"));
        Log.i(TAG, "[Bantu] Starting tunnel — server=" + bantuClient.getServerUrl()
            + " port=" + localPort + " subdomain=" + bantuClient.getSubdomain());

        bantuClient.start(localPort, new BantuTunnelClient.Callback() {
            @Override public void onMessage(String msg) { cb.onMessage("[Bantu] " + msg); }
            @Override public void onError(String err) { Log.e(TAG, "[Bantu] error: " + err); cb.onError("[Bantu] " + err); }
            @Override public void onConnected(String publicUrl) { sshUrl = publicUrl; cb.onConnected(publicUrl); }
            @Override public void onDisconnected() { bantuRunning = false; cb.onDisconnected(); }
        });
    }

    public boolean isBantuRunning() { return bantuRunning; }

    /** Bind a custom domain to the active Bantu tunnel (passes through to BantuTunnelClient). */
    public void bindBantuDomain(String domain) {
        if (bantuClient != null && bantuClient.isRunning()) {
            bantuClient.bindDomain(domain);
            bantuClient.setAutoRebindDomain(domain);
        }
    }

    public void unbindBantuDomain(String domain) {
        if (bantuClient != null) {
            bantuClient.unbindDomain(domain);
            bantuClient.setAutoRebindDomain("");
        }
    }

    public boolean isBantuConnected() {
        return bantuClient != null && bantuClient.isRunning();
    }

    // ──────────────────────────────────────────────────────────────
    // Cloudflare Tunnel (Quick Tunnel)
    // ──────────────────────────────────────────────────────────────

    /**
     * Start Cloudflare quick tunnel via cloudflared binary.
     *
     * EXECUTION STRATEGY (fixes the "Access Denied" issue):
     * On Android 10+, SELinux blocks exec() from app-writable directories.
     * The old code used ProcessBuilder directly on cloudflared from Termux
     * paths (/data/data/com.termux/files/usr/bin/), which is a NOEXEC mount.
     *
     * The fix:
     * 1. Download cloudflared to app's codeCacheDir if not found
     * 2. Execute via JNI fork+execv (BantuBridge) which bypasses SELinux
     * 3. Fall back to ProcessBuilder from codeCacheDir
     */
    public void startCloudflareTunnel(int localPort, TunnelCallback cb) {
        if (cfRunning) {
            cb.onError("Cloudflare tunnel already running");
            return;
        }

        // Store callback reference for use inside executeCloudflared
        this.cfCallback = cb;

        cfThread = new Thread(() -> {
            try {
                // Find or download cloudflared binary
                String cloudflared = findOrDownloadCloudflared(cb);
                if (cloudflared == null) {
                    return; // Error already reported via callback
                }

                cb.onMessage("Found cloudflared at: " + cloudflared);
                cb.onMessage("Starting cloudflared tunnel...");
                Log.i(TAG, "[CF] Executing: " + cloudflared + " tunnel --url http://localhost:" + localPort);

                // Execute cloudflared using the proper Android execution strategy
                cfProcess = executeCloudflared(cloudflared, localPort);
                cfRunning = true;
                cb.onMessage("cloudflared process started (pid=" + getPid(cfProcess) + ")");

                // Read output for the URL.
                // cloudflared writes ALL log output (including the tunnel URL)
                // to stderr. With JNI mode, stderr is merged into stdout,
                // so getInputStream() contains everything. With ProcessBuilder
                // fallback, redirectErrorStream(true) also merges them.
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(cfProcess.getInputStream()));

                // Drain stderr in a side thread to prevent pipe blocking
                final java.io.ByteArrayOutputStream stderrCapture = new java.io.ByteArrayOutputStream();
                Thread stderrDrainer = new Thread(() -> {
                    try {
                        java.io.InputStream es = cfProcess.getErrorStream();
                        if (es != null) {
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = es.read(buf)) != -1) {
                                stderrCapture.write(buf, 0, n);
                            }
                        }
                    } catch (Exception ignored) {}
                }, "cf-stderr-drain");
                stderrDrainer.setDaemon(true);
                stderrDrainer.start();

                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    // Log every line for debugging
                    Log.i(TAG, "[CF] " + line);
                    // Only forward first 50 lines to UI to prevent TextView bloat
                    if (lineCount <= 50) {
                        cb.onMessage(line);
                    }
                    String urlMatch = extractUrl(line);
                    if (urlMatch != null && line.contains("https://")) {
                        cfUrl = urlMatch;
                        cb.onMessage("Tunnel URL: " + cfUrl);
                        cb.onConnected(cfUrl);
                    }
                }

                // Also check stderr capture for any URL we might have missed
                if (cfUrl == null) {
                    String stderrText = stderrCapture.toString();
                    for (String stderrLine : stderrText.split("\n")) {
                        String urlMatch = extractUrl(stderrLine);
                        if (urlMatch != null && stderrLine.contains("https://")) {
                            cfUrl = urlMatch;
                            cb.onMessage("URL from stderr: " + cfUrl);
                            cb.onConnected(cfUrl);
                            break;
                        }
                    }
                }

                int exit = cfProcess.waitFor();
                Log.i(TAG, "[CF] cloudflared exited with code " + exit);
                if (cfRunning) {
                    cb.onMessage("cloudflared exited with code " + exit);
                    if (exit != 0) {
                        cb.onError("cloudflared exited with code " + exit
                            + ". Check if port " + localPort + " has a server running.");
                    }
                }

            } catch (Throwable t) {
                // Catch Throwable (not just Exception) to prevent app crashes
                // from NoClassDefFoundError, OutOfMemoryError, etc.
                String errMsg = "Cloudflare error: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage();
                Log.e(TAG, errMsg, t);
                // Send short error to callback — avoid very long messages
                if (t.getMessage() != null && t.getMessage().length() > 200) {
                    cb.onError(t.getClass().getSimpleName() + ": "
                        + t.getMessage().substring(0, 200));
                } else {
                    cb.onError(errMsg);
                }
            } finally {
                cfRunning = false;
                cfUrl = null;
                if (cfProcess != null) {
                    try { cfProcess.destroy(); } catch (Exception ignored) {}
                }
                try { cb.onDisconnected(); } catch (Exception ignored) {}
            }
        }, "cf-tunnel");
        cfThread.setDaemon(true);
        cfThread.start();
    }

    /**
     * Start Cloudflare named tunnel with a custom domain.
     *
     * This creates a persistent named tunnel via Cloudflare API and
     * configures a CNAME DNS record pointing the custom domain to the
     * tunnel. Requires a Cloudflare API token with Zone:DNS:Edit and
     * Account:Tunnel permissions.
     *
     * @param localPort local server port
     * @param domain custom domain (e.g., "mysite.example.com")
     * @param apiToken Cloudflare API token
     * @param zoneId Cloudflare Zone ID for the domain
     * @param cb tunnel callbacks
     */
    public void startCloudflareNamedTunnel(int localPort, String domain,
                                            String apiToken, String zoneId,
                                            TunnelCallback cb) {
        if (cfRunning) {
            cb.onError("A tunnel is already running. Stop it first.");
            return;
        }

        this.customDomain = domain;
        this.cfApiKey = apiToken;
        this.cfZoneId = zoneId;
        this.cfTunnelName = "bantudroid-" + domain.replace(".", "-");

        cfThread = new Thread(() -> {
            try {
                String cloudflared = findOrDownloadCloudflared(cb);
                if (cloudflared == null) {
                    return;
                }

                cb.onMessage("Creating named tunnel '" + cfTunnelName + "'...");

                // Step 1: Create tunnel via Cloudflare API
                String tunnelId = CloudflareApi.createTunnel(
                    apiToken, cfTunnelName, cb);
                if (tunnelId == null) {
                    cb.onError("Failed to create tunnel via Cloudflare API");
                    return;
                }

                cb.onMessage("Tunnel created: " + tunnelId);

                // Step 2: Configure DNS CNAME record
                cb.onMessage("Configuring DNS for " + domain + "...");
                boolean dnsOk = CloudflareApi.createDnsCname(
                    apiToken, zoneId, domain, tunnelId + ".cfargotunnel.com", cb);
                if (!dnsOk) {
                    cb.onMessage("WARNING: DNS setup may have failed. "
                        + "Check your Cloudflare dashboard.");
                }

                // Step 3: Run the tunnel with the tunnel secret and ingress rule
                cb.onMessage("Starting tunnel with custom domain...");
                cfProcess = executeNamedTunnel(cloudflared, localPort,
                    tunnelId, domain, apiToken, cb);
                cfRunning = true;
                cfNamedTunnel = true;

                // Read output (stderr is merged into stdout by both JNI and ProcessBuilder)
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(cfProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    cb.onMessage(line);
                    // For named tunnels, the URL is the custom domain
                    if (line.contains("Connection registered") ||
                        line.contains("Registered tunnel connection")) {
                        cfNamedTunnelUrl = "https://" + domain;
                        cfUrl = cfNamedTunnelUrl;
                        cb.onConnected(cfUrl);
                    }
                }

                int exit = cfProcess.waitFor();
                if (cfRunning) {
                    cb.onMessage("cloudflared exited with code " + exit);
                }

            } catch (Exception e) {
                String errMsg = "Named tunnel error: " + e.getMessage();
                Log.e(TAG, errMsg, e);
                cb.onError(errMsg);
            } finally {
                cfRunning = false;
                cfNamedTunnel = false;
                cfUrl = null;
                cfNamedTunnelUrl = null;
                cb.onDisconnected();
            }
        }, "cf-named-tunnel");
        cfThread.setDaemon(true);
        cfThread.start();
    }

    /**
     * Stop the Cloudflare tunnel.
     */
    public void stopCloudflareTunnel() {
        cfRunning = false;
        if (cfProcess != null) {
            cfProcess.destroy();
            try {
                if (!cfProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    cfProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                cfProcess.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            cfProcess = null;
        }
        cfUrl = null;
        cfNamedTunnelUrl = null;
        cfNamedTunnel = false;
    }

    public boolean isSshRunning() { return sshRunning; }
    public boolean isCfRunning() { return cfRunning; }
    public boolean isNamedTunnel() { return cfNamedTunnel; }
    public String getSshUrl() { return sshUrl; }
    public String getCfUrl() { return cfUrl; }

    // ──────────────────────────────────────────────────────────────
    // Cloudflared Binary Management
    // ──────────────────────────────────────────────────────────────

    /**
     * Find cloudflared binary on the device. Checks multiple locations
     * including Termux paths, system paths, and the app's codeCacheDir.
     */
    private String findCloudflared() {
        // PRIORITY 0: Bundled in nativeLibraryDir (SELinux-safe)
        // Files in jniLibs/ are extracted by Android into nativeLibraryDir
        // with app_lib_file SELinux label which PERMITS execution.
        try {
            String nativePath = appContext.getApplicationInfo().nativeLibraryDir
                + "/libcloudflared.so";
            File f = new File(nativePath);
            if (f.exists() && f.length() > 100000) {
                Log.i(TAG, "Found BUNDLED cloudflared: " + nativePath + " (" + f.length() + " bytes)");
                return nativePath;
            }
        } catch (Exception ignored) {}

        // PRIORITY 1: Check app's storage directories
        String cachedPath = getCachedCloudflaredPath();
        if (cachedPath != null) return cachedPath;

        // Check common external locations
        String[] paths = {
            "/data/data/com.termux/files/usr/bin/cloudflared",
            "/usr/bin/cloudflared",
            "/system/bin/cloudflared"
        };
        for (String path : paths) {
            File f = new File(path);
            if (f.exists() && f.canRead()) return path;
        }

        // Try to find via PATH
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"which", "cloudflared"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            if (line != null && !line.isEmpty()) return line.trim();
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Get the path to cloudflared in the app's storage.
     * Tries multiple locations in order of reliability:
     * 1. filesDir — most reliable, survives app updates
     * 2. /data/local/tmp/ — often writable and NOT NOEXEC on many devices
     * 3. codeCacheDir — may be cleared by Android but works on some OEMs
     * 4. externalFilesDir — external storage, usually accessible
     */
    private String getCachedCloudflaredPath() {
        String[] candidates = {
            new File(appContext.getFilesDir(), "cloudflared").getAbsolutePath(),
            "/data/local/tmp/cloudflared",
            new File(appContext.getCodeCacheDir(), "cloudflared").getAbsolutePath(),
        };
        // Try external files dir if available
        File extDir = appContext.getExternalFilesDir(null);
        if (extDir != null) {
            candidates = new String[]{
                candidates[0], candidates[1], candidates[2],
                new File(extDir, "cloudflared").getAbsolutePath()
            };
        }
        for (String path : candidates) {
            try {
                File f = new File(path);
                if (f.exists() && f.canRead() && f.length() > 100000) {
                    Log.i(TAG, "Found cloudflared at: " + path + " (" + f.length() + " bytes)");
                    return path;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Find cloudflared, or download it if not found.
     * Reports progress via the callback.
     *
     * OPTIMIZATION: If found in nativeLibraryDir (bundled .so), return
     * that path directly WITHOUT copying. The copy was unnecessary and
     * slow (37MB) and the executeCloudflared method reads from
     * nativeLibraryDir directly in strategies S0/S1.
     */
    private String findOrDownloadCloudflared(TunnelCallback cb) {
        String cloudflared = findCloudflared();
        if (cloudflared != null) {
            cb.onMessage("Found cloudflared: " + cloudflared);
            Log.i(TAG, "Using cloudflared at: " + cloudflared);
            return cloudflared;
        }

        // Not found — download it
        cb.onMessage("cloudflared not found. Downloading...");
        String downloadedPath = downloadCloudflared(new DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                cb.onMessage("Downloading cloudflared... " + percent + "%");
            }
            @Override
            public void onMessage(String msg) {
                cb.onMessage(msg);
            }
            @Override
            public void onSuccess(String path) {
                cb.onMessage("cloudflared downloaded: " + path);
            }
            @Override
            public void onError(String err) {
                cb.onError("cloudflared download failed: " + err);
            }
        });

        return downloadedPath;
    }

    /**
     * Copy a binary to the app's filesDir and make it executable.
     * Tries multiple directories for best compatibility.
     */
    private String copyToCache(String sourcePath, TunnelCallback cb) {
        // Try multiple target directories in order of reliability
        File[] targetDirs = {
            appContext.getFilesDir(),
            new File("/data/local/tmp"),
            appContext.getCodeCacheDir(),
        };
        File extDir = appContext.getExternalFilesDir(null);
        if (extDir != null) {
            targetDirs = new File[]{
                targetDirs[0], targetDirs[1], targetDirs[2], extDir
            };
        }

        for (File dir : targetDirs) {
            try {
                if (!dir.exists()) dir.mkdirs();
                File target = new File(dir, "cloudflared");

                try (InputStream is = new java.io.FileInputStream(sourcePath);
                     FileOutputStream os = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        os.write(buffer, 0, len);
                    }
                }

                // Make executable via native chmod (more reliable than Java API)
                target.setExecutable(true, false);
                target.setReadable(true, false);
                try {
                    Process p = new ProcessBuilder("/system/bin/chmod", "755",
                        target.getAbsolutePath()).start();
                    p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {}

                // Validate: check ELF header
                if (validateBinary(target)) {
                    Log.i(TAG, "Cloudflared cached at: " + target.getAbsolutePath());
                    return target.getAbsolutePath();
                } else {
                    Log.w(TAG, "Binary validation failed at: " + target.getAbsolutePath());
                    target.delete();
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to cache cloudflared in " + dir, e);
            }
        }
        Log.e(TAG, "Failed to cache cloudflared in any directory");
        return null;
    }

    /**
     * Validate that a file is a proper ELF binary (not corrupted download).
     */
    private boolean validateBinary(File f) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] header = new byte[4];
            int n = fis.read(header);
            if (n < 4) return false;
            // ELF magic: 0x7F 'E' 'L' 'F'
            return header[0] == 0x7F && header[1] == 'E'
                && header[2] == 'L' && header[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Download the cloudflared binary for the current device architecture.
     * Downloads the ARM64 build (which most modern Android devices use)
     * from Cloudflare's official GitHub releases.
     */
    private String downloadCloudflared(DownloadCallback cb) {
        try {
            // Determine the correct binary for this device
            String arch = Build.SUPPORTED_ABIS[0];
            String downloadFile;

            if (arch.contains("arm64") || arch.contains("aarch64")) {
                downloadFile = "cloudflared-linux-arm64";
            } else if (arch.contains("arm")) {
                downloadFile = "cloudflared-linux-arm";
            } else if (arch.contains("x86_64") || arch.contains("x86")) {
                downloadFile = "cloudflared-linux-amd64";
            } else {
                // Default to arm64
                downloadFile = "cloudflared-linux-arm64";
            }

            String downloadUrl = CF_DOWNLOAD_BASE + downloadFile;
            // Primary: filesDir (survives app updates, not cleared by Android)
            File primaryDir = appContext.getFilesDir();
            File target = new File(primaryDir, "cloudflared");

            cb.onMessage("Device architecture: " + arch);
            cb.onMessage("Downloading: " + downloadUrl);

            // Download with progress
            URL url = new URL(downloadUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            // Follow redirects (GitHub releases redirect)
            conn.setInstanceFollowRedirects(true);
            // Handle GitHub's redirect manually
            int status = conn.getResponseCode();
            if (status == 302 || status == 301) {
                String redirectUrl = conn.getHeaderField("Location");
                if (redirectUrl != null) {
                    conn.disconnect();
                    conn = (HttpURLConnection) new URL(redirectUrl).openConnection();
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(120000);
                }
            }

            int fileSize = conn.getContentLength();
            try (InputStream is = conn.getInputStream();
                 FileOutputStream os = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int len;
                long total = 0;
                int lastPercent = -1;

                while ((len = is.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                    total += len;
                    if (fileSize > 0) {
                        int percent = (int) ((total * 100) / fileSize);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            cb.onProgress(percent);
                        }
                    }
                }
            }
            conn.disconnect();

            // Make executable
            target.setExecutable(true, false);
            target.setReadable(true, false);
            try {
                Process p = new ProcessBuilder("/system/bin/chmod", "755",
                    target.getAbsolutePath()).start();
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {}

            // Validate binary (check ELF header)
            if (!validateBinary(target)) {
                String msg = "Downloaded file is not a valid ELF binary (corrupted?)";
                cb.onError(msg);
                target.delete();
                return null;
            }

            cb.onMessage("Binary validated (" + target.length() + " bytes)");
            cb.onSuccess(target.getAbsolutePath());
            return target.getAbsolutePath();

        } catch (Exception e) {
            Log.e(TAG, "Failed to download cloudflared", e);
            cb.onError(e.getMessage());
            return null;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Cloudflared Execution (Android SELinux-aware)
    // ──────────────────────────────────────────────────────────────

    /**
     * Execute cloudflared binary using the proper strategy for Android.
     *
     * ROOT CAUSE OF "Access Denied":
     * On Android 10+, the kernel enforces SELinux policies that block
     * exec() from directories mounted with the NOEXEC flag. This includes:
     *   - /data/data/com.termux/files/usr/bin/ (Termux)
     *   - /data/data/com.bantu.droid/files/ (app's files dir)
     *
     * SOLUTION (ordered by reliability):
     * 1. JNI fork+execv via BantuBridge: The native code runs in a
     *    different SELinux context that allows exec from writable dirs.
     * 2. ProcessBuilder from codeCacheDir: On some OEMs/devices,
     *    codeCacheDir is not NOEXEC and allows binary execution.
     * 3. ProcessBuilder from the original path: As a last resort,
     *    try the path where cloudflared was found (may fail on
     *    strict SELinux devices).
     */
    private Process executeCloudflared(String binaryPath, int localPort) throws Exception {
        Exception lastException = null;
        String[] args = {"tunnel", "--url", "http://localhost:" + localPort};
        // Wait time to check if process is alive after launch
        final long WAIT_MS = 500;

        String nativeLibDir = appContext.getApplicationInfo().nativeLibraryDir;
        String bundledSoPath = nativeLibDir + "/libcloudflared.so";
        File bundledSo = new File(bundledSoPath);
        Log.i(TAG, "[CF] nativeLibDir=" + nativeLibDir);
        Log.i(TAG, "[CF] bundled .so exists=" + bundledSo.exists()
            + " size=" + (bundledSo.exists() ? bundledSo.length() : 0));

        // STRATEGY 0: JNI fork+execv (PROMOTED — safest on Android 10+)
        // JNI runs fork+execv in native code with different SELinux context,
        // bypassing W^X restrictions. Most reliable on modern devices.
        if (BantuBridge.isJniAvailable()) {
            try {
                // Prefer bundled .so from nativeLibraryDir (already extracted by Android)
                String jniPath = bundledSo.exists() && bundledSo.length() > 100000
                    ? bundledSoPath : binaryPath;
                Log.i(TAG, "[CF-S0] Attempting JNI fork+execv: " + jniPath);
                Process p = executeViaJni(jniPath, args);
                Thread.sleep(WAIT_MS);
                if (p != null && p.isAlive()) {
                    Log.i(TAG, "[CF-S0] SUCCESS via JNI fork+execv");
                    return p;
                }
                String output = (p != null) ? drainProcessOutput(p) : "null process";
                Log.w(TAG, "[CF-S0] Failed output: " + truncate(output, 300));
                lastException = new Exception("[CF-S0] JNI failed: " + truncate(output, 150));
            } catch (Throwable e) {
                Log.w(TAG, "[CF-S0] Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                lastException = (e instanceof Exception) ? (Exception) e : new Exception(e);
            }
        } else {
            Log.i(TAG, "[CF-S0] SKIP (JNI not available)");
        }

        // STRATEGY 1: Direct ProcessBuilder from nativeLibraryDir
        // This works when SELinux allows exec from app_lib_file context.
        try {
            if (bundledSo.exists() && bundledSo.length() > 100000) {
                Log.i(TAG, "[CF-S1] Attempting direct ProcessBuilder: " + bundledSoPath);
                Process p = executeViaProcessBuilder(bundledSoPath, args);
                Thread.sleep(WAIT_MS);
                if (p.isAlive()) {
                    Log.i(TAG, "[CF-S1] SUCCESS via direct ProcessBuilder");
                    return p;
                }
                String output = drainProcessOutput(p);
                int exitCode = p.exitValue();
                Log.w(TAG, "[CF-S1] Failed exit=" + exitCode + " output: " + truncate(output, 300));
                lastException = new Exception("[CF-S1] direct exec failed (exit " + exitCode + "): " + truncate(output, 150));
            }
        } catch (Exception e) {
            Log.w(TAG, "[CF-S1] Exception: " + e.getMessage());
            lastException = e;
        }

        // STRATEGY 2: ProcessBuilder from cached/downloaded path
        // Uses filesDir which is writable but may be NOEXEC on some devices.
        String cachedPath = getCachedCloudflaredPath();
        if (cachedPath != null) {
            try {
                Log.i(TAG, "[CF-S2] Attempting cached path: " + cachedPath);
                Process p = executeViaProcessBuilder(cachedPath, args);
                Thread.sleep(WAIT_MS);
                if (p.isAlive()) {
                    Log.i(TAG, "[CF-S2] SUCCESS via cached path");
                    return p;
                }
                String output = drainProcessOutput(p);
                Log.w(TAG, "[CF-S2] Failed output: " + truncate(output, 300));
                lastException = new Exception("[CF-S2] cached failed: " + truncate(output, 150));
            } catch (Exception e) {
                lastException = e;
            }
        }

        // STRATEGY 3: /data/local/tmp (root-writable, often not NOEXEC)
        // NOTE: This will fail silently on non-rooted devices — that's expected.
        try {
            File tmpCf = new File("/data/local/tmp/cloudflared");
            if (!tmpCf.exists() && new File(binaryPath).exists()) {
                Log.i(TAG, "[CF-S3] Copying to /data/local/tmp...");
                copyBinaryToFile(binaryPath, tmpCf);
            }
            if (tmpCf.exists() && tmpCf.length() > 100000) {
                tmpCf.setExecutable(true, false);
                try {
                    Runtime.getRuntime().exec(new String[]{"/system/bin/chmod", "755", tmpCf.getAbsolutePath()}).waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {}
                Log.i(TAG, "[CF-S3] Attempting /data/local/tmp");
                Process p = executeViaProcessBuilder(tmpCf.getAbsolutePath(), args);
                Thread.sleep(WAIT_MS);
                if (p.isAlive()) {
                    Log.i(TAG, "[CF-S3] SUCCESS via /data/local/tmp");
                    return p;
                }
                String output = drainProcessOutput(p);
                Log.w(TAG, "[CF-S3] Failed output: " + truncate(output, 300));
                lastException = new Exception("[CF-S3] /data/local/tmp failed: " + truncate(output, 150));
            }
        } catch (Exception e) {
            Log.w(TAG, "[CF-S3] Exception: " + e.getMessage());
            lastException = e;
        }

        // STRATEGY 4: Execute from nativeLibraryDir via Android linker (LAST RESORT)
        // WARNING: linker64 execution can cause native crashes on some devices.
        // Only try this as a last resort because if it crashes, it takes down
        // the entire app process (cannot be caught by Java try/catch).
        try {
            if (bundledSo.exists() && bundledSo.length() > 100000) {
                Log.i(TAG, "[CF-S4] LAST RESORT: Attempting linker64 exec: " + bundledSoPath);
                cb_onMessage_Safe("Trying linker64 execution (last resort)...");
                Process p = executeViaLinker(bundledSoPath, args);
                Thread.sleep(WAIT_MS);
                if (p.isAlive()) {
                    Log.i(TAG, "[CF-S4] SUCCESS via linker64");
                    return p;
                }
                String output = drainProcessOutput(p);
                int exitCode = p.exitValue();
                Log.w(TAG, "[CF-S4] Failed exit=" + exitCode + " output: " + truncate(output, 300));
                lastException = new Exception("[CF-S4] linker64 failed (exit " + exitCode + "): " + truncate(output, 150));
            }
        } catch (Exception e) {
            Log.w(TAG, "[CF-S4] Exception: " + e.getMessage());
            lastException = e;
        }

        // All strategies failed — throw a SHORT, clear error
        String diag = "ABI=" + Build.SUPPORTED_ABIS[0]
            + " API=" + Build.VERSION.SDK_INT
            + " JNI=" + BantuBridge.isJniAvailable();
        diag += " bundled=" + bundledSo.exists() + "(" + (bundledSo.exists() ? bundledSo.length() : 0) + ")";

        Log.e(TAG, "All CF strategies failed. " + diag);
        throw new Exception(
            "All execution strategies failed. " + diag
            + ". Last: " + (lastException != null ? lastException.getMessage() : "unknown"),
            lastException);
    }

    /** Safe callback helper — used inside executeCloudflared where cb is not directly available. */
    private void cb_onMessage_Safe(String msg) {
        if (cfCallback != null) {
            try { cfCallback.onMessage(msg); } catch (Exception ignored) {}
        }
    }

    /**
     * Drain all available output from a process that has already exited.
     * Used to capture error messages when a strategy fails quickly.
     */
    private String drainProcessOutput(Process p) {
        StringBuilder sb = new StringBuilder();
        try {
            java.io.InputStream is = p.getInputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
        } catch (Exception ignored) {}
        try {
            java.io.InputStream es = p.getErrorStream();
            if (es != null) {
                byte[] buf = new byte[2048];
                int n;
                while ((n = es.read(buf)) != -1) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                }
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    /** Truncate a string to maxLen characters. */
    private String truncate(String s, int maxLen) {
        if (s == null) return "(null)";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /** Get PID from a Process (works for both regular and JniProcess). */
    private String getPid(Process p) {
        try {
            if (p instanceof BantuBridge.JniProcess) {
                return String.valueOf(((BantuBridge.JniProcess) p).getPid());
            }
            // For regular Process, use reflection (API 26+)
            try {
                java.lang.reflect.Field pidField = p.getClass().getDeclaredField("pid");
                pidField.setAccessible(true);
                return String.valueOf(pidField.getInt(p));
            } catch (Exception e) {
                return "?";
            }
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * Execute a binary via the Android dynamic linker.
     *
     * This bypasses SELinux exec restrictions because:
     * 1. /system/bin/linker64 runs as a system process (u:r:linker:s0)
     * 2. The linker CAN execute files with app_lib_file SELinux label
     * 3. We pass the binary path as the first argument to the linker
     *
     * Equivalent to: /system/bin/linker64 /path/to/libcloudflared.so tunnel --url ...
     *
     * @param binaryPath path to the binary (e.g., nativeLibraryDir/libcloudflared.so)
     * @param args       arguments to pass to the binary
     * @return running Process
     */
    private Process executeViaLinker(String binaryPath, String[] args) throws Exception {
        // Build command: linker64 <binary> <arg1> <arg2> ...
        String[] cmd = new String[args.length + 2];
        cmd[0] = "/system/bin/linker64";
        cmd[1] = binaryPath;
        System.arraycopy(args, 0, cmd, 2, args.length);

        Log.i(TAG, "Linker exec: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(appContext.getFilesDir());

        // Set environment for proper dynamic linking
        String nativeLibDir = appContext.getApplicationInfo().nativeLibraryDir;
        if (nativeLibDir != null) {
            String existing = System.getenv("LD_LIBRARY_PATH");
            if (existing != null && !existing.isEmpty()) {
                pb.environment().put("LD_LIBRARY_PATH", nativeLibDir + ":" + existing);
            } else {
                pb.environment().put("LD_LIBRARY_PATH", nativeLibDir);
            }
        }
        pb.environment().put("HOME", appContext.getFilesDir().getAbsolutePath());

        return pb.start();
    }

    /**
     * Execute cloudflared for a named tunnel with custom domain.
     */
    private Process executeNamedTunnel(String binaryPath, int localPort,
                                        String tunnelId, String domain,
                                        String apiToken, TunnelCallback cb) throws Exception {
        Exception lastException = null;

        File configFile = createTunnelConfig(tunnelId, domain, localPort, apiToken, cb);
        if (configFile == null) {
            throw new Exception("Failed to create tunnel config file");
        }

        String[] args = {"tunnel", "--config", configFile.getAbsolutePath(), "run"};

        // STRATEGY 0: Execute via linker64 from nativeLibraryDir
        try {
            String np = appContext.getApplicationInfo().nativeLibraryDir + "/libcloudflared.so";
            File nb = new File(np);
            if (nb.exists() && nb.length() > 100000) {
                Log.i(TAG, "[CF-NAMED-S0] linker64: " + np);
                Process p = executeViaLinker(np, args);
                Thread.sleep(800);
                if (p.isAlive()) return p;
                lastException = new Exception("[CF-NAMED-S0] linker64 exec failed");
            }
        } catch (Exception e) { lastException = e; }

        // STRATEGY 1: Direct ProcessBuilder from nativeLibraryDir
        try {
            String np = appContext.getApplicationInfo().nativeLibraryDir + "/libcloudflared.so";
            File nb = new File(np);
            if (nb.exists() && nb.length() > 100000) {
                Process p = executeViaProcessBuilder(np, args);
                Thread.sleep(800);
                if (p.isAlive()) return p;
                lastException = new Exception("[CF-NAMED-S1] direct exec failed");
            }
        } catch (Exception e) { lastException = e; }

        // STRATEGY 2: /data/local/tmp
        File tmpCf = new File("/data/local/tmp/cloudflared");
        if (!tmpCf.exists() && new File(binaryPath).exists()) copyBinaryToFile(binaryPath, tmpCf);
        if (tmpCf.exists() && tmpCf.length() > 100000) {
            try {
                tmpCf.setExecutable(true, false);
                Runtime.getRuntime().exec(new String[]{"/system/bin/chmod", "755", tmpCf.getAbsolutePath()}).waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                Process p = executeViaProcessBuilder(tmpCf.getAbsolutePath(), args);
                Thread.sleep(800);
                if (p.isAlive()) return p;
            } catch (Exception e) { lastException = e; }
        }

        // STRATEGY 3: JNI fork+execv
        if (BantuBridge.isJniAvailable()) {
            try {
                Process p = executeViaJni(binaryPath, args);
                Thread.sleep(800);
                if (p != null && p.isAlive()) return p;
            } catch (Exception e) { lastException = e; }
        }

        // STRATEGY 4: ProcessBuilder from cached path
        String cachedPath = getCachedCloudflaredPath();
        if (cachedPath != null) {
            try {
                return executeViaProcessBuilder(cachedPath, args);
            } catch (Exception e) { lastException = e; }
        }

        throw new Exception(
            "Cannot execute cloudflared named tunnel. All strategies failed.\n" +
            "Last error: " + (lastException != null ? lastException.getMessage() : "unknown"),
            lastException);
    }

    /**
     * Copy a binary file to a target location.
     */
    private void copyBinaryToFile(String sourcePath, File target) {
        try {
            try (InputStream is = new java.io.FileInputStream(sourcePath);
                 FileOutputStream os = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
            }
            target.setExecutable(true, false);
            target.setReadable(true, false);
            try {
                Runtime.getRuntime().exec(new String[]{"/system/bin/chmod", "755",
                    target.getAbsolutePath()}).waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.w(TAG, "Failed to copy binary to " + target.getAbsolutePath(), e);
        }
    }

    /**
     * Create a cloudflared tunnel config file for a named tunnel.
     */
    private File createTunnelConfig(String tunnelId, String domain,
                                     int localPort, String apiToken,
                                     TunnelCallback cb) {
        try {
            File configDir = new File(appContext.getFilesDir(), "tunnel_configs");
            configDir.mkdirs();
            File configFile = new File(configDir, "tunnel-" + domain + ".yml");

            String config =
                "tunnel: " + tunnelId + "\n" +
                "credentials-file: " + configDir.getAbsolutePath() + "/tunnel-creds-" + domain + ".json\n" +
                "\n" +
                "ingress:\n" +
                "  - hostname: " + domain + "\n" +
                "    service: http://localhost:" + localPort + "\n" +
                "  - service: http_status:404\n";

            try (java.io.FileWriter writer = new java.io.FileWriter(configFile)) {
                writer.write(config);
            }

            // Write credentials file
            File credsFile = new File(configDir, "tunnel-creds-" + domain + ".json");
            String creds = "{\"AccountTag\":\"\",\"TunnelID\":\"" + tunnelId +
                "\",\"TunnelSecret\":\"\"}";
            try (java.io.FileWriter writer = new java.io.FileWriter(credsFile)) {
                writer.write(creds);
            }

            cb.onMessage("Tunnel config written to: " + configFile.getAbsolutePath());
            return configFile;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create tunnel config", e);
            return null;
        }
    }

    /**
     * Execute a binary via JNI fork+execv using BantuBridge.
     * This bypasses Android SELinux exec restrictions.
     *
     * BantuBridge.executeBinary() uses nativeForkExec() which runs
     * fork()+execv() in native code. The native code runs in a different
     * SELinux context than the JVM, allowing execution of binaries from
     * directories that would otherwise be blocked (NOEXEC mounts).
     *
     * @return a Process object (JniProcess) that can be used like any Process
     */
    private Process executeViaJni(String binaryPath, String[] args) throws Exception {
        Log.i(TAG, "Executing via JNI fork+execv: " + binaryPath);
        return BantuBridge.executeBinary(binaryPath, args,
            appContext.getFilesDir().getAbsolutePath());
    }

    /**
     * Execute a binary via ProcessBuilder with proper environment setup.
     * Sets LD_LIBRARY_PATH and other env vars needed for Android execution.
     */
    private Process executeViaProcessBuilder(String binaryPath, String[] args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = binaryPath;
        System.arraycopy(args, 0, cmd, 1, args.length);

        Log.i(TAG, "ProcessBuilder: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(appContext.getFilesDir());

        // Set up environment for proper execution
        String nativeLibDir = appContext.getApplicationInfo().nativeLibraryDir;
        if (nativeLibDir != null) {
            String existing = System.getenv("LD_LIBRARY_PATH");
            if (existing != null && !existing.isEmpty()) {
                pb.environment().put("LD_LIBRARY_PATH", nativeLibDir + ":" + existing);
            } else {
                pb.environment().put("LD_LIBRARY_PATH", nativeLibDir);
            }
        }
        pb.environment().put("HOME", appContext.getFilesDir().getAbsolutePath());
        pb.environment().put("PATH", new File(binaryPath).getParent() +
            ":" + System.getenv("PATH"));

        return pb.start();
    }

    // ──────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────

    private void disconnectSsh() {
        try {
            if (sshSession != null && sshSession.isConnected()) {
                sshSession.disconnect();
            }
        } catch (Exception ignored) {}
        sshSession = null;
        sshUrl = null;
    }

    private String resolveHost(String hostname) {
        try {
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(hostname);
            if (addrs.length > 0) return addrs[0].getHostAddress();
        } catch (Exception ignored) {}
        return null;
    }

    private String extractUrl(String text) {
        // Find HTTPS URL in text — case-insensitive TLD matching
        Pattern p = Pattern.compile("https?://[a-zA-Z0-9._-]+\\.[a-zA-Z]{2,}(?:[:/][^\\s]*)?",
            Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        if (m.find()) return m.group();
        return null;
    }

    /**
     * Connect SSH session and wait for connection, with optional port forwarding.
     * Shared helper used by localhost.run and Pinggy which need to establish
     * the port forward BEFORE opening the exec channel.
     */
    private Session connectSshSession(String host, int port, String providerKey,
                                        TunnelCallback cb) throws JSchException {
        JSch jsch = new JSch();
        jsch.setKnownHosts("/dev/null");

        sshSession = jsch.getSession("", host, port);
        sshSession.setPassword("");
        sshSession.setConfig("StrictHostKeyChecking", "no");
        sshSession.setConfig("UserKnownHostsFile", "/dev/null");
        sshSession.setConfig("PreferredAuthentications", "password");
        sshSession.setConfig("server_alive_interval", "15");
        sshSession.setConfig("server_alive_count_max", "3");

        sshSession.connect(30000);
        sshRunning = true;
        cb.onMessage("[SSH-" + providerKey + "] SSH session established");
        Log.i(TAG, "[SSH-" + providerKey + "] SSH connected successfully");
        return sshSession;
    }

    /**
     * Set up reverse port forwarding to remote port 80.
     */
    private void setupPortForwardR(String providerKey, int localPort, TunnelCallback cb)
            throws JSchException {
        sshSession.setPortForwardingR(80, "127.0.0.1", localPort);
        cb.onMessage("[SSH-" + providerKey + "] Port forward R:80 -> localhost:" + localPort);
        Log.i(TAG, "[SSH-" + providerKey + "] Forwarding established");
    }
}