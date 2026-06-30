package com.bantu.droid;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Hosting Dashboard - manages tunnel connections and public IP access.
 *
 * Provides UI for:
 * - Viewing local/public IP and NAT status
 * - Starting Direct Public IP mode (UPnP port forwarding)
 * - Starting SSH tunnels (Pinggy, Serveo, localhost.run)
 * - Starting Cloudflare tunnels (via Termux cloudflared)
 * - Viewing tunnel status and public URL
 * - Copying/opening the public URL
 */
public class HostingDashboardActivity extends AppCompatActivity {

    private TunnelManager tunnelManager;

    // IP info
    private TextView tvLocalIp;
    private TextView tvPublicIp;
    private TextView tvNatStatus;
    private Button btnDetectIp;

    // Direct mode
    private EditText etDirectPort;
    private Button btnStartDirect;

    // SSH
    private Button btnPinggy;
    private Button btnServeo;
    private Button btnLocalhostRun;

    // Cloudflare
    private Button btnStartCloudflare;

    // Status
    private View cardStatus;
    private View statusDot;
    private TextView tvStatus;
    private Button btnStopTunnel;
    private TextView tvPublicUrl;
    private Button btnCopyUrl;
    private Button btnOpenUrl;

    // Log
    private TextView tvLog;

    // State
    private String localIp;
    private String publicIp;
    private boolean behindNat;
    private boolean cgnat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hosting_dashboard);

        // Initialize TunnelManager
        tunnelManager = new TunnelManager(this);
        tunnelManager.setListener(new TunnelListener());

        // Find views
        tvLocalIp = findViewById(R.id.tv_local_ip);
        tvPublicIp = findViewById(R.id.tv_public_ip);
        tvNatStatus = findViewById(R.id.tv_nat_status);
        btnDetectIp = findViewById(R.id.btn_detect_ip);

        etDirectPort = findViewById(R.id.et_direct_port);
        btnStartDirect = findViewById(R.id.btn_start_direct);

        btnPinggy = findViewById(R.id.btn_pinggy);
        btnServeo = findViewById(R.id.btn_serveo);
        btnLocalhostRun = findViewById(R.id.btn_localhost_run);

        btnStartCloudflare = findViewById(R.id.btn_start_cloudflare);

        cardStatus = findViewById(R.id.card_status);
        statusDot = findViewById(R.id.status_dot);
        tvStatus = findViewById(R.id.tv_status);
        btnStopTunnel = findViewById(R.id.btn_stop_tunnel);
        tvPublicUrl = findViewById(R.id.tv_public_url);
        btnCopyUrl = findViewById(R.id.btn_copy_url);
        btnOpenUrl = findViewById(R.id.btn_open_url);

        tvLog = findViewById(R.id.tv_log);

        // Setup click handlers
        setupClickHandlers();

        // Detect IPs on launch
        detectIps();
    }

    private void setupClickHandlers() {
        // IP detection
        btnDetectIp.setOnClickListener(v -> detectIps());

        // Direct Public IP
        btnStartDirect.setOnClickListener(v -> {
            int port = getPortFromInput(etDirectPort, 8080);
            tunnelManager.setLocalPort(port);
            tunnelManager.setExternalPort(port);
            log("Starting Direct Public IP mode on port " + port + "...");
            setButtonsEnabled(false);
            tunnelManager.startDirectTunnel();
        });

        // SSH providers
        btnPinggy.setOnClickListener(v -> startSshTunnel(TunnelManager.SSH_PINGGY));
        btnServeo.setOnClickListener(v -> startSshTunnel(TunnelManager.SSH_SERVEO));
        btnLocalhostRun.setOnClickListener(v -> startSshTunnel(TunnelManager.SSH_LOCALHOST_RUN));

        // Cloudflare
        btnStartCloudflare.setOnClickListener(v -> {
            int port = getPortFromInput(etDirectPort, 8080);
            tunnelManager.setLocalPort(port);
            log("Starting Cloudflare tunnel on port " + port + "...");
            setButtonsEnabled(false);
            tunnelManager.startCloudflareTunnel();
        });

        // Stop
        btnStopTunnel.setOnClickListener(v -> {
            log("Stopping tunnel...");
            tunnelManager.stopTunnel();
        });

        // Copy URL
        btnCopyUrl.setOnClickListener(v -> {
            String url = tunnelManager.getPublicUrl();
            if (url != null) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Tunnel URL", url);
                clipboard.setPrimaryClip(clip);
                log("URL copied to clipboard: " + url);
            }
        });

        // Open URL
        btnOpenUrl.setOnClickListener(v -> {
            String url = tunnelManager.getPublicUrl();
            if (url != null) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            }
        });
    }

    private void startSshTunnel(String provider) {
        int port = getPortFromInput(etDirectPort, 8080);
        tunnelManager.setLocalPort(port);
        String name;
        switch (provider) {
            case TunnelManager.SSH_PINGGY: name = "Pinggy"; break;
            case TunnelManager.SSH_SERVEO: name = "Serveo"; break;
            case TunnelManager.SSH_LOCALHOST_RUN: name = "localhost.run"; break;
            default: name = provider;
        }
        log("Starting SSH tunnel via " + name + " on port " + port + "...");
        setButtonsEnabled(false);
        tunnelManager.startSshTunnel(provider);
    }

    private void detectIps() {
        btnDetectIp.setEnabled(false);
        tvLocalIp.setText("Detecting...");
        tvPublicIp.setText("Detecting...");
        tvNatStatus.setText("Checking...");

        // Get local IP on background thread
        new Thread(() -> {
            localIp = UpnpPortMapper.getLocalIpAddress();
            runOnUiThread(() -> {
                if (localIp != null) {
                    tvLocalIp.setText(localIp);
                } else {
                    tvLocalIp.setText("Not found");
                    tvLocalIp.setTextColor(getColor(R.color.text_error));
                }
            });
        }).start();

        // Detect public IP via TunnelManager
        tunnelManager.detectPublicIp();
    }

    private int getPortFromInput(EditText et, int defaultPort) {
        try {
            String text = et.getText().toString().trim();
            if (text.isEmpty()) return defaultPort;
            int port = Integer.parseInt(text);
            if (port < 1 || port > 65535) return defaultPort;
            return port;
        } catch (NumberFormatException e) {
            return defaultPort;
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        btnStartDirect.setEnabled(enabled);
        btnPinggy.setEnabled(enabled);
        btnServeo.setEnabled(enabled);
        btnLocalhostRun.setEnabled(enabled);
        btnStartCloudflare.setEnabled(enabled);
        etDirectPort.setEnabled(enabled);
    }

    private void showTunnelActive(String url) {
        cardStatus.setVisibility(View.VISIBLE);
        statusDot.setBackgroundColor(getColor(R.color.bantu_green));
        tvStatus.setText("Active — " + tunnelManager.getActiveMode().toUpperCase());
        tvStatus.setTextColor(getColor(R.color.text_success));
        tvPublicUrl.setText(url);
        setButtonsEnabled(false);
        btnStopTunnel.setEnabled(true);
    }

    private void showTunnelError(String error) {
        cardStatus.setVisibility(View.VISIBLE);
        statusDot.setBackgroundColor(getColor(R.color.text_error));
        tvStatus.setText("Error — " + tunnelManager.getActiveMode());
        tvStatus.setTextColor(getColor(R.color.text_error));
        tvPublicUrl.setText(error);
        tvPublicUrl.setTextColor(getColor(R.color.text_error));
        setButtonsEnabled(true);
    }

    private void showTunnelStopped() {
        statusDot.setBackgroundColor(getColor(R.color.text_secondary));
        tvStatus.setText("Stopped");
        tvStatus.setTextColor(getColor(R.color.text_secondary));
        tvPublicUrl.setText("—");
        tvPublicUrl.setTextColor(getColor(R.color.bantu_green));
        setButtonsEnabled(true);
    }

    private void log(String message) {
        runOnUiThread(() -> {
            String current = tvLog.getText().toString();
            String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
            String newLine = "[" + timestamp + "] " + message + "\n";
            // Keep last 50 lines
            String[] lines = (current + newLine).split("\n");
            StringBuilder sb = new StringBuilder();
            int start = Math.max(0, lines.length - 50);
            for (int i = start; i < lines.length; i++) {
                sb.append(lines[i]);
                if (i < lines.length - 1) sb.append("\n");
            }
            tvLog.setText(sb.toString());
        });
    }

    @Override
    protected void onDestroy() {
        // Don't stop tunnel when activity is destroyed - let it run in background
        super.onDestroy();
    }

    // ──────────────────────────────────────────────────────────────
    // Tunnel Listener
    // ──────────────────────────────────────────────────────────────

    private class TunnelListener implements TunnelManager.TunnelListener {

        @Override
        public void onTunnelStarted(String mode, String url) {
            runOnUiThread(() -> {
                log("Tunnel ACTIVE: " + url);
                showTunnelActive(url);
            });
        }

        @Override
        public void onTunnelStopped(String mode) {
            runOnUiThread(() -> {
                log("Tunnel stopped (" + mode + ")");
                showTunnelStopped();
            });
        }

        @Override
        public void onTunnelError(String mode, String error) {
            runOnUiThread(() -> {
                log("ERROR (" + mode + "): " + error);
                showTunnelError(error);
            });
        }

        @Override
        public void onTunnelProgress(String mode, String message) {
            log("[" + mode.toUpperCase() + "] " + message);
        }

        @Override
        public void onPublicIpDetected(String ip, boolean isBehindNat, boolean isCgnat) {
            runOnUiThread(() -> {
                publicIp = ip;
                behindNat = isBehindNat;
                cgnat = isCgnat;

                tvPublicIp.setText(ip);
                tvPublicIp.setTextColor(getColor(R.color.bantu_green));
                btnDetectIp.setEnabled(true);

                if (cgnat) {
                    tvNatStatus.setText("CGNAT (Carrier-Grade NAT)");
                    tvNatStatus.setTextColor(getColor(R.color.text_error));
                    log("WARNING: You are behind CGNAT. Direct Public IP mode may not work. Use SSH or Cloudflare tunnel instead.");
                } else if (isBehindNat) {
                    tvNatStatus.setText("Behind NAT (UPnP may help)");
                    tvNatStatus.setTextColor(getColor(R.color.text_warning));
                    log("You are behind NAT. UPnP port forwarding will be attempted for Direct mode.");
                } else {
                    tvNatStatus.setText("Direct (No NAT)");
                    tvNatStatus.setTextColor(getColor(R.color.text_success));
                    log("You have a direct public IP! Direct mode will work perfectly.");
                }
            });
        }
    }
}
