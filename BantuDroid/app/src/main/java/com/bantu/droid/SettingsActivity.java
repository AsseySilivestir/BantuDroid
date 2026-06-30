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

        tvAbout.setText(
            "BantuDroid v2.5.1\n" +
            "A Bantu language runtime for Android\n\n" +
            "Features:\n" +
            "  - Terminal with cd, ls, cat, mkdir, etc.\n" +
            "  - File manager with real filesystem navigation\n" +
            "  - Web server with DDNS support\n" +
            "  - Hosting & Tunnel (UPnP, SSH, Cloudflare)\n" +
            "  - SQLite, PostgreSQL, MySQL support\n" +
            "  - Sua web framework built-in\n\n" +
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
}
