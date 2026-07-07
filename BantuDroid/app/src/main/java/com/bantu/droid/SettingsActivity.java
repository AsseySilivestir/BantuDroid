package com.bantu.droid;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

/**
 * Settings screen for BantuDroid.
 * Configures engine, server defaults, DDNS credentials, and auto-start behavior.
 */
public class SettingsActivity extends AppCompatActivity {

    private BantuEngine engine;

    private TextView tvEngineVersion;
    private Button btnReinstall;
    private EditText etDefaultPort;
    private Switch switchAutostart;
    private EditText etDdnsDomain;
    private EditText etDdnsToken;
    private Button btnSaveDdns;

    // Bantu tunnel settings (self-hosted bantu-tunnel server on Render)
    private EditText etBantuServerUrl;
    private EditText etBantuSubdomain;
    private EditText etBantuToken;
    private Button btnSaveBantu;

    // Render API settings — lets the app add custom domains without visiting Render dashboard
    private EditText etRenderApiKey;
    private Button btnSaveRender;
    private Button btnAutoDetectRender;
    private TextView tvRenderStatus;
    private TextView tvAbout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        engine = new BantuEngine(this);

        tvEngineVersion = findViewById(R.id.tv_engine_version);
        btnReinstall = findViewById(R.id.btn_reinstall);
        etDefaultPort = findViewById(R.id.et_default_port);
        switchAutostart = findViewById(R.id.switch_autostart);
        etDdnsDomain = findViewById(R.id.et_ddns_domain);
        etDdnsToken = findViewById(R.id.et_ddns_token);
        btnSaveDdns = findViewById(R.id.btn_save_ddns);
        // Bantu tunnel settings
        etBantuServerUrl = findViewById(R.id.et_bantu_server_url);
        etBantuSubdomain = findViewById(R.id.et_bantu_subdomain);
        etBantuToken     = findViewById(R.id.et_bantu_token);
        btnSaveBantu     = findViewById(R.id.btn_save_bantu);

        // Render API settings
        etRenderApiKey   = findViewById(R.id.et_render_api_key);
        btnSaveRender    = findViewById(R.id.btn_save_render);
        btnAutoDetectRender = findViewById(R.id.btn_auto_detect_render);
        tvRenderStatus   = findViewById(R.id.tv_render_status);
        tvAbout = findViewById(R.id.tv_about);

        // Load current values
        tvEngineVersion.setText("v" + engine.getInstalledVersion());

        android.content.SharedPreferences prefs =
            PreferenceManager.getDefaultSharedPreferences(this);

        etDefaultPort.setText(String.valueOf(
            prefs.getInt("default_port", 8080)));
        switchAutostart.setChecked(
            prefs.getBoolean("autostart", false));
        etDdnsDomain.setText(
            prefs.getString("ddns_domain", ""));
        etDdnsToken.setText(
            prefs.getString("ddns_token", ""));
        // Bantu tunnel settings
        etBantuServerUrl.setText(prefs.getString("bantu_server_url", ""));
        etBantuSubdomain.setText(prefs.getString("bantu_subdomain", ""));
        etBantuToken.setText(prefs.getString("bantu_token", ""));

        // Render API settings
        etRenderApiKey.setText(prefs.getString("render_api_key", ""));
        String savedServiceId = prefs.getString("render_service_id", "");
        String savedServiceName = prefs.getString("render_service_name", "");
        if (!savedServiceId.isEmpty()) {
            tvRenderStatus.setText("Service: " + savedServiceName + "\nID: " + savedServiceId);
        }

        tvAbout.setText(
            "BantuDroid v1.0.0\n" +
            "A Bantu language runtime for Android\n\n" +
            "Bantu is a programming language built with C++17.\n" +
            "Features: Sua web framework, SQLite, PostgreSQL,\n" +
            "MySQL, WebRTC, and cross-platform support.\n\n" +
            "https://github.com/AsseySilivestir/Bantu"
        );

        // Reinstall engine
        btnReinstall.setOnClickListener(v -> {
            btnReinstall.setEnabled(false);
            btnReinstall.setText("Reinstalling...");
            engine.reinstall(new BantuEngine.InstallListener() {
                @Override
                public void onProgress(String message) {}
                @Override
                public void onSuccess(String version) {
                    runOnUiThread(() -> {
                        tvEngineVersion.setText("v" + version);
                        btnReinstall.setEnabled(true);
                        btnReinstall.setText("Reinstall Engine");
                        Toast.makeText(SettingsActivity.this,
                            "Reinstalled!", Toast.LENGTH_SHORT).show();
                    });
                }
                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        btnReinstall.setEnabled(true);
                        btnReinstall.setText("Reinstall Engine");
                        Toast.makeText(SettingsActivity.this,
                            "Failed: " + message, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });

        // Save settings
        btnSaveDdns.setOnClickListener(v -> saveSettings());
        btnSaveBantu.setOnClickListener(v -> saveBantuSettings());
        btnSaveRender.setOnClickListener(v -> saveRenderSettings());
        btnAutoDetectRender.setOnClickListener(v -> autoDetectRenderService());

        // Save on switch toggle
        switchAutostart.setOnCheckedChangeListener((buttonView, isChecked) -> saveSettings());
    }

    private void saveSettings() {
        try {
            int port = Integer.parseInt(etDefaultPort.getText().toString().trim());
            android.content.SharedPreferences.Editor editor =
                PreferenceManager.getDefaultSharedPreferences(this).edit();
            editor.putInt("default_port", port);
            editor.putBoolean("autostart", switchAutostart.isChecked());
            editor.putString("ddns_domain", etDdnsDomain.getText().toString().trim());
            editor.putString("ddns_token", etDdnsToken.getText().toString().trim());
            editor.apply();
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Save Bantu tunnel settings (server URL, subdomain, token).
     */
    private void saveBantuSettings() {
        String serverUrl = etBantuServerUrl.getText().toString().trim();
        String subdomain = etBantuSubdomain.getText().toString().trim();
        String token     = etBantuToken.getText().toString().trim();

        if (!serverUrl.isEmpty()) {
            while (serverUrl.endsWith("/")) serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
            if (serverUrl.endsWith("/ws")) serverUrl = serverUrl.substring(0, serverUrl.length() - 3);
        }

        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString("bantu_server_url", serverUrl)
            .putString("bantu_subdomain", subdomain)
            .putString("bantu_token", token)
            .apply();

        Toast.makeText(this, "Bantu tunnel settings saved", Toast.LENGTH_SHORT).show();
    }

    /**
     * Save Render API key. The service ID is auto-detected separately.
     */
    private void saveRenderSettings() {
        String apiKey = etRenderApiKey.getText().toString().trim();
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString("render_api_key", apiKey)
            .apply();
        Toast.makeText(this, "Render API key saved", Toast.LENGTH_SHORT).show();
        // Auto-detect the bantu-tunnel service if we don't have one yet
        if (apiKey.length() > 10 &&
            PreferenceManager.getDefaultSharedPreferences(this).getString("render_service_id", "").isEmpty()) {
            autoDetectRenderService();
        }
    }

    /**
     * Auto-detect the bantu-tunnel service on the user's Render account
     * by looking for a service whose name contains 'bantu'.
     */
    private void autoDetectRenderService() {
        String apiKey = etRenderApiKey.getText().toString().trim();
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Enter your Render API key first", Toast.LENGTH_SHORT).show();
            return;
        }
        tvRenderStatus.setText("Searching for bantu-tunnel service...");
        btnAutoDetectRender.setEnabled(false);

        RenderApi.findServiceByName(apiKey, "bantu", new RenderApi.ServiceCallback() {
            @Override public void onServiceFound(String serviceId, String serviceName) {
                runOnUiThread(() -> {
                    btnAutoDetectRender.setEnabled(true);
                    PreferenceManager.getDefaultSharedPreferences(SettingsActivity.this).edit()
                        .putString("render_service_id", serviceId)
                        .putString("render_service_name", serviceName)
                        .apply();
                    tvRenderStatus.setText("Service: " + serviceName + "\nID: " + serviceId);
                    Toast.makeText(SettingsActivity.this,
                        "Found: " + serviceName, Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    btnAutoDetectRender.setEnabled(true);
                    tvRenderStatus.setText("Error: " + error);
                    Toast.makeText(SettingsActivity.this,
                        error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
