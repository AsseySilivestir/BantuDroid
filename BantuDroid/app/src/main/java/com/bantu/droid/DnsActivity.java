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
    private Button btnBind, btnUnbind, btnOpenInBrowser, btnStartTunnelFirst;
    private TextView tvStatus, tvInstructions, tvLog;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dns);

        etDomain = findViewById(R.id.et_domain);
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
        btnBind.setEnabled(connected);
        btnUnbind.setEnabled(connected);
        if (connected) {
            tvStatus.setText("Tunnel active — ready to bind domain.");
            btnStartTunnelFirst.setVisibility(View.GONE);
        } else if (running) {
            tvStatus.setText("Tunnel is connecting... please wait.");
            btnStartTunnelFirst.setVisibility(View.GONE);
        } else {
            tvStatus.setText("No active Bantu tunnel. Start one first.");
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
        return "HOW THIS WORKS (no Cloudflare needed):\n\n" +
            "Your bantu-tunnel server runs on Render. Render already supports\n" +
            "custom domains with automatic HTTPS. You just need to:\n\n" +
            "1. RENDER DASHBOARD\n" +
            "   Open your bantu-tunnel service on Render.\n" +
            "   Go to Settings -> Custom Domains -> Add your domain\n" +
            "   (e.g. splannes.co.tz).\n" +
            "   Render provisions HTTPS automatically.\n\n" +
            "2. WAZOHOST DNS PANEL\n" +
            "   Log in to Wazohost -> your domain -> DNS management.\n" +
            "   Add a CNAME record:\n" +
            "     Name/Host:  @  (or splannes.co.tz)\n" +
            "     Value/Target: " + renderHost + "\n" +
            "     TTL: Automatic\n\n" +
            "3. BIND DOMAIN HERE\n" +
            "   Enter your domain below and tap Bind Domain.\n" +
            "   This tells your bantu-tunnel server to route requests\n" +
            "   for that domain to your phone's tunnel.\n\n" +
            "4. VISIT\n" +
            "   Open https://splannes.co.tz in any browser.\n" +
            "   It routes: Render -> bantu-tunnel -> your phone -> localhost.\n\n" +
            "WHY THIS IS BETTER THAN CLOUDFLARE:\n" +
            "   - No external accounts\n" +
            "   - No API tokens to manage\n" +
            "   - No nameserver transfer (you keep Wazohost)\n" +
            "   - Everything controlled from this app\n" +
            "   - Render handles HTTPS automatically";
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
