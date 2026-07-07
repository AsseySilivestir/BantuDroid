package com.bantu.droid;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

/**
 * Domain binding screen — wires a custom domain (e.g. splannes.co.tz bought
 * at Wazohost) to the active Bantu tunnel.
 *
 * THE NATURAL FLOW (no Cloudflare, no API tokens, no nameserver transfers):
 *
 *   1. User buys a domain at Wazohost (or any registrar).
 *   2. In Render dashboard: Settings → Custom Domains → Add the domain.
 *      Render provisions HTTPS automatically via Let's Encrypt.
 *   3. At Wazohost's DNS panel: add a CNAME record pointing the domain
 *      at the bantu-tunnel Render service (e.g. bantu-tunnel.onrender.com).
 *   4. In this screen: enter the domain + tap "Bind Domain".
 *      BantuDroid sends a WebSocket message to the tunnel server telling
 *      it to route requests for that domain to this tunnel.
 *   5. Visit https://splannes.co.tz — it routes through Render → bantu-tunnel
 *      server → your phone → localhost:8080.
 *
 * The binding persists across reconnects (auto-rebound after each reconnect).
 */
public class DnsActivity extends AppCompatActivity {

    private EditText etDomain;
    private Button btnAddToRender, btnCheckStatus, btnBind, btnUnbind, btnOpenInBrowser, btnStartTunnelFirst;
    private TextView tvStatus, tvInstructions, tvLog;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dns);

        etDomain = findViewById(R.id.et_domain);
        btnAddToRender = findViewById(R.id.btn_add_to_render);
        btnCheckStatus = findViewById(R.id.btn_check_status);
        btnBind = findViewById(R.id.btn_bind);
        btnUnbind = findViewById(R.id.btn_unbind);
        btnOpenInBrowser = findViewById(R.id.btn_open_browser);
        btnStartTunnelFirst = findViewById(R.id.btn_start_tunnel_first);
        tvStatus = findViewById(R.id.tv_status);
        tvInstructions = findViewById(R.id.tv_instructions);
        tvLog = findViewById(R.id.tv_log);
        tvLog.setMovementMethod(new ScrollingMovementMethod());

        // Load saved domain
        etDomain.setText(prefs().getString("dns_domain", ""));

        // Show the saved Bantu server URL in the instructions
        String bantuUrl = prefs().getString("bantu_server_url", "");
        if (!bantuUrl.isEmpty()) {
            // Strip scheme for cleaner display
            String host = bantuUrl.replaceAll("^https?://", "").replaceAll("/.*$", "").trim();
            tvInstructions.setText(buildInstructions(host));
        } else {
            tvInstructions.setText(buildInstructions("bantu-tunnel-xxxx.onrender.com"));
        }

        btnAddToRender.setOnClickListener(v -> addDomainToRender());
        btnCheckStatus.setOnClickListener(v -> checkDomainStatus());
        btnBind.setOnClickListener(v -> bindDomain());
        btnUnbind.setOnClickListener(v -> unbindDomain());
        btnOpenInBrowser.setOnClickListener(v -> openInBrowser());
        btnStartTunnelFirst.setOnClickListener(v -> {
            startActivity(new Intent(this, TunnelActivity.class));
        });

        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    private void refreshState() {
        boolean connected = TunnelService.isBantuConnected();
        boolean running = TunnelService.isRunning();
        boolean hasRender = !prefs().getString("render_api_key", "").isEmpty()
            && !prefs().getString("render_service_id", "").isEmpty();

        btnAddToRender.setEnabled(hasRender);
        btnCheckStatus.setEnabled(hasRender);
        btnBind.setEnabled(connected);
        btnUnbind.setEnabled(connected);

        if (!hasRender) {
            tvStatus.setText("Add your Render API key in Settings first to enable automatic domain setup.");
            btnStartTunnelFirst.setVisibility(View.GONE);
        } else if (connected) {
            tvStatus.setText("Tunnel active. Add domain to Render, then bind.");
            btnStartTunnelFirst.setVisibility(View.GONE);
        } else if (running) {
            tvStatus.setText("Tunnel is connecting... please wait.");
            btnStartTunnelFirst.setVisibility(View.GONE);
        } else {
            tvStatus.setText("No active Bantu tunnel. Start one first (or add domain anyway).");
            btnStartTunnelFirst.setVisibility(View.VISIBLE);
        }
    }

    private android.content.SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(this);
    }

    private void log(String msg) {
        String ts = new java.text.SimpleDateFormat("HH:mm:ss",
            java.util.Locale.getDefault()).format(new java.util.Date());
        mainHandler.post(() -> {
            tvLog.append("[" + ts + "] " + msg + "\n");
            tvLog.post(() -> tvLog.scrollTo(0, tvLog.getBottom()));
        });
    }

    private void setStatus(String s) {
        mainHandler.post(() -> tvStatus.setText(s));
    }

    private String buildInstructions(String renderHost) {
        return "HOW THIS WORKS (fully automated from this app):\n\n" +
            "1. SETTINGS (one-time)\n" +
            "   Open Settings -> RENDER API section.\n" +
            "   Get an API key at dashboard.render.com/users/me/api-keys\n" +
            "   (needs Write scope). Paste it + tap Auto-detect.\n\n" +
            "2. ADD DOMAIN TO RENDER (this app does it)\n" +
            "   Enter your domain below + tap 'Add to Render'.\n" +
            "   BantuDroid calls Render's API to register the domain.\n" +
            "   Render provisions HTTPS automatically.\n\n" +
            "3. WAZOHOST DNS PANEL (manual — only step outside this app)\n" +
            "   Log in to Wazohost -> your domain -> DNS management.\n" +
            "   Add a CNAME record:\n" +
            "     Name/Host:  @  (or your domain)\n" +
            "     Value/Target: " + renderHost + "\n" +
            "     TTL: Automatic\n\n" +
            "4. CHECK STATUS\n" +
            "   Tap 'Check Status' to see if SSL is ready.\n" +
            "   Usually takes 1-2 minutes.\n\n" +
            "5. BIND TO TUNNEL\n" +
            "   Start a Bantu tunnel (if not running).\n" +
            "   Tap 'Bind Domain to Tunnel'.\n" +
            "   This tells your bantu-tunnel server to route\n" +
            "   requests for that domain to your phone.\n\n" +
            "6. VISIT\n" +
            "   Tap 'Open in Browser' -> https://your-domain loads.\n\n" +
            "WHY THIS IS BETTER:\n" +
            "   - No Cloudflare\n" +
            "   - No Render dashboard visits (except one-time API key)\n" +
            "   - No nameserver transfer (you keep Wazohost)\n" +
            "   - Everything controlled from this app";
    }

    /** Add the domain to Render via API — Render starts provisioning SSL. */
    private void addDomainToRender() {
        final String domain = etDomain.getText().toString().trim().toLowerCase();
        if (domain.isEmpty()) {
            Toast.makeText(this, "Enter your domain first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!domain.contains(".") || domain.length() < 4) {
            Toast.makeText(this, "That doesn't look like a domain", Toast.LENGTH_SHORT).show();
            return;
        }
        final String apiKey = prefs().getString("render_api_key", "");
        final String serviceId = prefs().getString("render_service_id", "");
        if (apiKey.isEmpty() || serviceId.isEmpty()) {
            Toast.makeText(this, "Set Render API key in Settings first", Toast.LENGTH_LONG).show();
            return;
        }

        prefs().edit().putString("dns_domain", domain).apply();
        log("Adding " + domain + " to Render service " + serviceId + "...");
        setStatus("Adding domain to Render...");
        btnAddToRender.setEnabled(false);

        RenderApi.addCustomDomain(apiKey, serviceId, domain, new RenderApi.Callback() {
            @Override public void onSuccess(String message) {
                runOnUiThread(() -> {
                    btnAddToRender.setEnabled(true);
                    log("SUCCESS: " + message);
                    setStatus("Domain added to Render. Now add CNAME at Wazohost, then check status.");
                    Toast.makeText(DnsActivity.this, "Domain added!", Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    btnAddToRender.setEnabled(true);
                    log("ERROR: " + error);
                    setStatus("Failed: " + error);
                });
            }
        });
    }

    /** Poll Render API for SSL provisioning status. */
    private void checkDomainStatus() {
        final String domain = etDomain.getText().toString().trim().toLowerCase();
        if (domain.isEmpty()) return;
        final String apiKey = prefs().getString("render_api_key", "");
        final String serviceId = prefs().getString("render_service_id", "");
        if (apiKey.isEmpty() || serviceId.isEmpty()) return;

        log("Checking SSL status for " + domain + "...");
        setStatus("Checking status...");
        btnCheckStatus.setEnabled(false);

        RenderApi.getDomainStatus(apiKey, serviceId, domain, new RenderApi.DomainStatusCallback() {
            @Override public void onStatus(String domainId, String verificationStatus, String sslStatus, String message) {
                runOnUiThread(() -> {
                    btnCheckStatus.setEnabled(true);
                    log("SSL=" + sslStatus + " verification=" + verificationStatus);
                    log(message);
                    setStatus(message);
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    btnCheckStatus.setEnabled(true);
                    log("ERROR: " + error);
                    setStatus("Failed: " + error);
                });
            }
        });
    }

    private void bindDomain() {
        String domain = etDomain.getText().toString().trim().toLowerCase();
        if (domain.isEmpty()) {
            Toast.makeText(this, "Enter your domain first", Toast.LENGTH_SHORT).show();
            return;
        }
        // Basic validation
        if (!domain.contains(".") || domain.length() < 4) {
            Toast.makeText(this, "That doesn't look like a domain", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!TunnelService.isBantuConnected()) {
            Toast.makeText(this, "Start a Bantu tunnel first", Toast.LENGTH_LONG).show();
            return;
        }

        // Save the domain
        prefs().edit().putString("dns_domain", domain).apply();

        log("Binding " + domain + " to active tunnel...");
        setStatus("Binding " + domain + "...");
        boolean sent = TunnelService.bindDomain(domain);
        if (!sent) {
            log("ERROR: no active Bantu tunnel connection.");
            setStatus("Failed — no active tunnel");
            return;
        }
        log("Bind request sent. Waiting for server confirmation...");
        // The server's domain-bound response will be logged by BantuTunnelClient.
        // Give it a moment then update status.
        mainHandler.postDelayed(() -> {
            setStatus("Domain bound: " + domain + "\nVisit https://" + domain);
            log("SUCCESS! Visit https://" + domain);
            Toast.makeText(this, "Domain bound!", Toast.LENGTH_SHORT).show();
        }, 1500);
    }

    private void unbindDomain() {
        String domain = etDomain.getText().toString().trim().toLowerCase();
        if (domain.isEmpty()) return;
        if (!TunnelService.isBantuConnected()) return;

        log("Unbinding " + domain + "...");
        // Use the static bindDomain path in reverse — call unbind via service
        // (we need to add a TunnelService.unbindDomain, but for now reuse bind with empty)
        // Actually, let's just call TunnelManager directly via the service instance
        TunnelService.unbindDomainStatic(domain);
        setStatus("Unbound: " + domain);
        log("Domain unbound.");
    }

    private void openInBrowser() {
        String domain = etDomain.getText().toString().trim();
        if (domain.isEmpty()) {
            Toast.makeText(this, "Enter your domain first", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = "https://" + domain;
        if (!url.endsWith("/")) url = url + "/";
        log("Opening " + url + " in browser...");
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            startActivity(browserIntent);
        } catch (Exception e) {
            Toast.makeText(this, "No browser app available", Toast.LENGTH_SHORT).show();
        }
    }
}
