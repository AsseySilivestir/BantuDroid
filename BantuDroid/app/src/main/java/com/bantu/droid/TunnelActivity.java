package com.bantu.droid;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Hosting & Tunnel management activity.
 *
 * Provides:
 * - Public IP detection (via web APIs)
 * - Local IP & gateway display
 * - CGNAT detection
 * - UPnP port forwarding (auto port map via router)
 * - SSH reverse tunnel (Pinggy/Serveo/localhost.run)
 * - Cloudflare quick tunnel (via cloudflared binary)
 *
 * This activity does NOT replace the Dashboard — it's a separate
 * tool focused on network tunneling and port forwarding.
 */
public class TunnelActivity extends AppCompatActivity {

    // Views
    private TextView tvPublicIp, tvLocalIp, tvGateway, tvNatType;
    private EditText etPort;
    private Button btnDetectIp, btnUpnpStart, btnUpnpStop;
    private Button btnSshStart, btnSshStop, btnCfStart, btnCfStop;
    private TextView tvUpnpStatus, tvSshStatus, tvSshUrl, tvCfStatus, tvCfUrl;
    private Spinner spinnerSshProvider;
    private TextView tvLog;
    private ScrollView logScroll;

    // Managers
    private UpnpPortMapper upnp;
    private TunnelManager tunnelMgr;

    // State
    private String publicIp;
    private String localIp;
    private String gateway;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tunnel);

        // Initialize managers
        upnp = new UpnpPortMapper();
        tunnelMgr = new TunnelManager();

        // Bind views
        tvPublicIp = findViewById(R.id.tv_public_ip);
        tvLocalIp = findViewById(R.id.tv_local_ip);
        tvGateway = findViewById(R.id.tv_gateway);
        tvNatType = findViewById(R.id.tv_nat_type);
        etPort = findViewById(R.id.et_port);

        btnDetectIp = findViewById(R.id.btn_detect_ip);
        btnUpnpStart = findViewById(R.id.btn_upnp_start);
        btnUpnpStop = findViewById(R.id.btn_upnp_stop);
        btnSshStart = findViewById(R.id.btn_ssh_start);
        btnSshStop = findViewById(R.id.btn_ssh_stop);
        btnCfStart = findViewById(R.id.btn_cf_start);
        btnCfStop = findViewById(R.id.btn_cf_stop);

        tvUpnpStatus = findViewById(R.id.tv_upnp_status);
        tvSshStatus = findViewById(R.id.tv_ssh_status);
        tvSshUrl = findViewById(R.id.tv_ssh_url);
        tvCfStatus = findViewById(R.id.tv_cf_status);
        tvCfUrl = findViewById(R.id.tv_cf_url);

        spinnerSshProvider = findViewById(R.id.spinner_ssh_provider);
        tvLog = findViewById(R.id.tv_log);
        logScroll = findViewById(R.id.log_scroll);

        // Set up SSH provider spinner
        String[] providerNames = new String[TunnelManager.SSH_PROVIDERS.length];
        for (int i = 0; i < TunnelManager.SSH_PROVIDERS.length; i++) {
            providerNames[i] = TunnelManager.SSH_PROVIDERS[i][0];
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, providerNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSshProvider.setAdapter(adapter);

        // Detect local IP immediately
        detectLocalNetwork();

        // Button handlers
        btnDetectIp.setOnClickListener(v -> detectPublicIp());

        btnUpnpStart.setOnClickListener(v -> startUpnp());
        btnUpnpStop.setOnClickListener(v -> stopUpnp());

        btnSshStart.setOnClickListener(v -> startSshTunnel());
        btnSshStop.setOnClickListener(v -> stopSshTunnel());

        btnCfStart.setOnClickListener(v -> startCloudflareTunnel());
        btnCfStop.setOnClickListener(v -> stopCloudflareTunnel());
    }

    // ──────────────────────────────────────────────────────────────
    // Network detection
    // ──────────────────────────────────────────────────────────────

    private void detectLocalNetwork() {
        localIp = UpnpPortMapper.getLocalIp();
        gateway = UpnpPortMapper.getGateway();

        tvLocalIp.setText(localIp != null ? localIp : "—");
        tvGateway.setText(gateway != null ? gateway : "—");
    }

    private void detectPublicIp() {
        btnDetectIp.setEnabled(false);
        tvPublicIp.setText("Detecting...");
        tvNatType.setText("Checking...");

        new Thread(() -> {
            publicIp = UpnpPortMapper.detectPublicIp();
            runOnUiThread(() -> {
                btnDetectIp.setEnabled(true);
                if (publicIp != null) {
                    tvPublicIp.setText(publicIp);
                    boolean cgnat = UpnpPortMapper.isCgnat(publicIp);
                    if (cgnat) {
                        tvNatType.setText("CGNAT (Carrier-Grade NAT)");
                        tvNatType.setTextColor(0xFFF44336); // Red
                        log("WARNING: CGNAT detected! Direct access may not work.");
                        log("Use SSH Tunnel or Cloudflare Tunnel instead.");
                    } else {
                        tvNatType.setText("Public IP (No CGNAT)");
                        tvNatType.setTextColor(0xFF4CAF50); // Green
                        log("Public IP detected: " + publicIp + " — no CGNAT, UPnP should work.");
                    }
                } else {
                    tvPublicIp.setText("Failed");
                    tvNatType.setText("Unknown");
                    log("Failed to detect public IP. Check internet connection.");
                }
            });
        }).start();
    }

    // ──────────────────────────────────────────────────────────────
    // UPnP Port Forwarding
    // ──────────────────────────────────────────────────────────────

    private void startUpnp() {
        int port = getPort();
        btnUpnpStart.setEnabled(false);
        tvUpnpStatus.setText("Discovering...");

        new Thread(() -> {
            UpnpPortMapper.Callback cb = new UpnpPortMapper.Callback() {
                @Override
                public void onMessage(String msg) {
                    runOnUiThread(() -> {
                        log("[UPnP] " + msg);
                        tvUpnpStatus.setText(msg);
                    });
                }
                @Override
                public void onError(String err) {
                    runOnUiThread(() -> {
                        log("[UPnP] ERROR: " + err);
                        tvUpnpStatus.setText("Error: " + err);
                        btnUpnpStart.setEnabled(true);
                        btnUpnpStop.setEnabled(false);
                    });
                }
            };

            if (upnp.discover(cb)) {
                boolean ok = upnp.addPortMapping(port, port, "TCP",
                    "BantuDroid", cb);
                runOnUiThread(() -> {
                    if (ok) {
                        btnUpnpStart.setEnabled(false);
                        btnUpnpStop.setEnabled(true);
                        String extIp = upnp.getExternalIp();
                        if (extIp != null) {
                            tvPublicIp.setText(extIp);
                        }
                    } else {
                        btnUpnpStart.setEnabled(true);
                        btnUpnpStop.setEnabled(false);
                    }
                });
            } else {
                runOnUiThread(() -> {
                    btnUpnpStart.setEnabled(true);
                    btnUpnpStop.setEnabled(false);
                });
            }
        }).start();
    }

    private void stopUpnp() {
        if (upnp.isMapped()) {
            new Thread(() -> {
                upnp.deletePortMapping(upnp.getMappedExternalPort(), "TCP",
                    new UpnpPortMapper.Callback() {
                        @Override
                        public void onMessage(String msg) {
                            runOnUiThread(() -> {
                                log("[UPnP] " + msg);
                                tvUpnpStatus.setText("Idle");
                            });
                        }
                        @Override
                        public void onError(String err) {
                            runOnUiThread(() -> {
                                log("[UPnP] ERROR: " + err);
                                tvUpnpStatus.setText("Error: " + err);
                            });
                        }
                    });
                runOnUiThread(() -> {
                    btnUpnpStart.setEnabled(true);
                    btnUpnpStop.setEnabled(false);
                });
            }).start();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // SSH Reverse Tunnel
    // ──────────────────────────────────────────────────────────────

    private void startSshTunnel() {
        int port = getPort();
        int providerIdx = spinnerSshProvider.getSelectedItemPosition();
        String providerName = TunnelManager.SSH_PROVIDERS[providerIdx][0];

        btnSshStart.setEnabled(false);
        tvSshStatus.setText("Connecting to " + providerName + "...");
        tvSshUrl.setVisibility(View.GONE);

        tunnelMgr.startSshTunnel(providerIdx, port, new TunnelManager.TunnelCallback() {
            @Override
            public void onMessage(String msg) {
                runOnUiThread(() -> {
                    log("[SSH] " + msg);
                    tvSshStatus.setText(msg);
                });
            }
            @Override
            public void onError(String err) {
                runOnUiThread(() -> {
                    log("[SSH] ERROR: " + err);
                    tvSshStatus.setText("Error: " + err);
                    btnSshStart.setEnabled(true);
                    btnSshStop.setEnabled(false);
                });
            }
            @Override
            public void onConnected(String url) {
                runOnUiThread(() -> {
                    tvSshUrl.setText(url);
                    tvSshUrl.setVisibility(View.VISIBLE);
                    btnSshStart.setEnabled(false);
                    btnSshStop.setEnabled(true);
                    log("[SSH] Public URL: " + url);

                    // Make URL clickable to copy
                    tvSshUrl.setOnClickListener(v -> {
                        android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        android.content.ClipData clip =
                            android.content.ClipData.newPlainText("Tunnel URL", url);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(TunnelActivity.this,
                            "URL copied to clipboard", Toast.LENGTH_SHORT).show();
                    });
                });
            }
            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    tvSshStatus.setText("Disconnected");
                    btnSshStart.setEnabled(true);
                    btnSshStop.setEnabled(false);
                    tvSshUrl.setVisibility(View.GONE);
                });
            }
        });
    }

    private void stopSshTunnel() {
        tunnelMgr.stopSshTunnel();
        btnSshStart.setEnabled(true);
        btnSshStop.setEnabled(false);
        tvSshStatus.setText("Stopping...");
        tvSshUrl.setVisibility(View.GONE);
    }

    // ──────────────────────────────────────────────────────────────
    // Cloudflare Tunnel
    // ──────────────────────────────────────────────────────────────

    private void startCloudflareTunnel() {
        int port = getPort();

        btnCfStart.setEnabled(false);
        tvCfStatus.setText("Starting cloudflared...");
        tvCfUrl.setVisibility(View.GONE);

        tunnelMgr.startCloudflareTunnel(port, new TunnelManager.TunnelCallback() {
            @Override
            public void onMessage(String msg) {
                runOnUiThread(() -> {
                    log("[CF] " + msg);
                    tvCfStatus.setText(msg);
                });
            }
            @Override
            public void onError(String err) {
                runOnUiThread(() -> {
                    log("[CF] ERROR: " + err);
                    tvCfStatus.setText("Error: " + err);
                    btnCfStart.setEnabled(true);
                    btnCfStop.setEnabled(false);
                });
            }
            @Override
            public void onConnected(String url) {
                runOnUiThread(() -> {
                    tvCfUrl.setText(url);
                    tvCfUrl.setVisibility(View.VISIBLE);
                    btnCfStart.setEnabled(false);
                    btnCfStop.setEnabled(true);
                    log("[CF] Public URL: " + url);

                    tvCfUrl.setOnClickListener(v -> {
                        android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        android.content.ClipData clip =
                            android.content.ClipData.newPlainText("Tunnel URL", url);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(TunnelActivity.this,
                            "URL copied to clipboard", Toast.LENGTH_SHORT).show();
                    });
                });
            }
            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    tvCfStatus.setText("Disconnected");
                    btnCfStart.setEnabled(true);
                    btnCfStop.setEnabled(false);
                    tvCfUrl.setVisibility(View.GONE);
                });
            }
        });
    }

    private void stopCloudflareTunnel() {
        tunnelMgr.stopCloudflareTunnel();
        btnCfStart.setEnabled(true);
        btnCfStop.setEnabled(false);
        tvCfStatus.setText("Stopping...");
        tvCfUrl.setVisibility(View.GONE);
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private int getPort() {
        try {
            return Integer.parseInt(etPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            return 8080;
        }
    }

    private void log(String msg) {
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss",
            java.util.Locale.getDefault()).format(new java.util.Date());
        tvLog.append("[" + timestamp + "] " + msg + "\n");
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onDestroy() {
        tunnelMgr.stopSshTunnel();
        tunnelMgr.stopCloudflareTunnel();
        super.onDestroy();
    }
}
