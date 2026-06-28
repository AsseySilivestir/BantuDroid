package com.bantu.droid;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Main launcher activity.
 * Shows a setup screen on first launch (engine installation),
 * then navigates to Terminal / Files / Dashboard / Settings.
 */
public class MainActivity extends AppCompatActivity {

    private BantuEngine engine;

    // Setup screen
    private LinearLayout setupLayout;
    private ProgressBar setupProgress;
    private TextView setupStatus;
    private Button btnInstall;

    // Main screen
    private LinearLayout mainLayout;
    private Button btnTerminal;
    private Button btnFiles;
    private Button btnDashboard;
    private Button btnSettings;
    private TextView tvEngineStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        engine = new BantuEngine(this);

        // Setup screen views
        setupLayout = findViewById(R.id.setup_layout);
        setupProgress = findViewById(R.id.setup_progress);
        setupStatus = findViewById(R.id.setup_status);
        btnInstall = findViewById(R.id.btn_install);

        // Main screen views
        mainLayout = findViewById(R.id.main_layout);
        btnTerminal = findViewById(R.id.btn_terminal);
        btnFiles = findViewById(R.id.btn_files);
        btnDashboard = findViewById(R.id.btn_dashboard);
        btnSettings = findViewById(R.id.btn_settings);
        tvEngineStatus = findViewById(R.id.tv_engine_status);

        // Check if engine is already installed
        if (engine.isInstalled()) {
            showMainScreen();
        } else {
            showSetupScreen();
        }
    }

    private void showSetupScreen() {
        setupLayout.setVisibility(View.VISIBLE);
        mainLayout.setVisibility(View.GONE);

        btnInstall.setOnClickListener(v -> {
            btnInstall.setEnabled(false);
            setupProgress.setVisibility(View.VISIBLE);
            setupStatus.setText(R.string.engine_installing);

            engine.install(new BantuEngine.InstallListener() {
                @Override
                public void onProgress(String message) {
                    runOnUiThread(() -> setupStatus.setText(message));
                }

                @Override
                public void onSuccess(String version) {
                    runOnUiThread(() -> {
                        setupStatus.setText(getString(R.string.engine_installed) +
                            " (v" + version + ")");
                        setupProgress.setVisibility(View.GONE);
                        // Auto-navigate after 1 second
                        setupStatus.postDelayed(() -> showMainScreen(), 1000);
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        setupStatus.setText(getString(R.string.engine_failed) +
                            ": " + message);
                        setupProgress.setVisibility(View.GONE);
                        btnInstall.setEnabled(true);
                    });
                }
            });
        });
    }

    private void showMainScreen() {
        setupLayout.setVisibility(View.GONE);
        mainLayout.setVisibility(View.VISIBLE);

        String version = engine.getInstalledVersion();
        tvEngineStatus.setText(getString(R.string.engine_ready) +
            " (v" + version + ")");

        btnTerminal.setOnClickListener(v ->
            startActivity(new Intent(this, TerminalActivity.class)));

        btnFiles.setOnClickListener(v ->
            startActivity(new Intent(this, FileManagerActivity.class)));

        btnDashboard.setOnClickListener(v ->
            startActivity(new Intent(this, DashboardActivity.class)));

        btnSettings.setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));
    }
}
