package com.bantu.droid;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Terminal emulator activity.
 * Provides a full terminal interface for running Bantu commands.
 *
 * Features:
 * - Green-on-black terminal display with monospace font
 * - Command input with history (up/down arrows)
 * - Quick action buttons for common commands
 * - Background server start/stop with notification
 * - Process output streaming in real-time
 */
public class TerminalActivity extends AppCompatActivity
    implements BantuProcess.OutputListener {

    private static final int NOTIFICATION_PERMISSION_CODE = 1001;

    private TextView terminalOutput;
    private EditText commandInput;
    private Button btnRun, btnStop, btnClear;
    private ScrollView scrollview;
    private LinearLayout quickActions;
    private HorizontalScrollView quickActionsScroll;

    private BantuEngine engine;
    private BantuProcess currentProcess;
    private StringBuilder outputBuffer;
    private CommandHistory commandHistory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        engine = new BantuEngine(this);
        outputBuffer = new StringBuilder();
        commandHistory = new CommandHistory();

        // Bind views
        terminalOutput = findViewById(R.id.terminal_output);
        commandInput = findViewById(R.id.command_input);
        btnRun = findViewById(R.id.btn_run);
        btnStop = findViewById(R.id.btn_stop);
        btnClear = findViewById(R.id.btn_clear);
        scrollview = findViewById(R.id.scrollview);
        quickActions = findViewById(R.id.quick_actions);
        quickActionsScroll = findViewById(R.id.quick_actions_scroll);

        // Terminal styling
        terminalOutput.setTypeface(Typeface.MONOSPACE);

        // Welcome message
        appendOutput(getString(R.string.terminal_welcome));

        // Ensure engine is installed
        if (!engine.isInstalled()) {
            appendOutput("Installing engine...\n");
            engine.install(new BantuEngine.InstallListener() {
                @Override
                public void onProgress(String message) {
                    runOnUiThread(() -> appendOutput("  " + message + "\n"));
                }
                @Override
                public void onSuccess(String version) {
                    runOnUiThread(() -> {
                        appendOutput("Engine installed! v" + version + "\n\n");
                        showQuickActions();
                    });
                }
                @Override
                public void onError(String message) {
                    runOnUiThread(() -> appendOutput("ERROR: " + message + "\n"));
                }
            });
        } else {
            showQuickActions();
        }

        // Run button
        btnRun.setOnClickListener(v -> runCommand());

        // Stop button
        btnStop.setOnClickListener(v -> stopProcess());

        // Clear button
        btnClear.setOnClickListener(v -> {
            outputBuffer.setLength(0);
            terminalOutput.setText("");
        });

        // Enter key in input field
        commandInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                runCommand();
                return true;
            }
            return false;
        });

        // Command history navigation with volume keys or dpad
        commandInput.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    String prev = commandHistory.previous();
                    if (prev != null) commandInput.setText(prev);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    String next = commandHistory.next();
                    if (next != null) commandInput.setText(next != null ? next : "");
                    return true;
                }
            }
            return false;
        });

        // Request notification permission (Android 13+)
        requestNotificationPermission();
    }

    private void showQuickActions() {
        quickActionsScroll.setVisibility(View.VISIBLE);
        setupQuickAction(R.id.qa_ddns, "bantu run bantuddns.b");
        setupQuickAction(R.id.qa_server, "bantu run server.b");
        setupQuickAction(R.id.qa_hello, "bantu run hello.b");
        setupQuickAction(R.id.qa_db, "bantu run db.b");
        setupQuickAction(R.id.qa_bench, "bantu run bench.b");
    }

    private void setupQuickAction(int buttonId, String command) {
        Button btn = findViewById(buttonId);
        if (btn != null) {
            btn.setOnClickListener(v -> {
                commandInput.setText(command);
                runCommand();
            });
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Command execution
    // ──────────────────────────────────────────────────────────────

    private void runCommand() {
        String cmd = commandInput.getText().toString().trim();
        if (cmd.isEmpty()) return;

        // Add to history
        commandHistory.add(cmd);

        // Display command in terminal
        appendOutput("$ " + cmd + "\n");

        // Clear input
        commandInput.setText("");

        try {
            if (cmd.startsWith("bantu run ")) {
                // Run a .b file
                String file = cmd.substring("bantu run ".length()).trim();
                currentProcess = engine.run(file);
                currentProcess.readOutput(this);
                setRunning(true);

            } else if (cmd.startsWith("bantu ")) {
                // Run with arguments
                String[] args = cmd.substring("bantu ".length()).trim().split("\\s+");
                currentProcess = engine.execute(args);
                currentProcess.readOutput(this);
                setRunning(true);

            } else if (cmd.equals("clear") || cmd.equals("cls")) {
                // Built-in: clear screen
                outputBuffer.setLength(0);
                terminalOutput.setText("");

            } else if (cmd.equals("help")) {
                // Built-in: show help
                showHelp();

            } else if (cmd.equals("ls") || cmd.equals("dir")) {
                // Built-in: list .b files
                listFiles();

            } else if (cmd.startsWith("start-service ")) {
                // Start as background service
                String file = cmd.substring("start-service ".length()).trim();
                startServerService(file);

            } else if (cmd.equals("stop-service")) {
                // Stop background service
                stopServerService();

            } else {
                appendOutput("Unknown command. Type 'help' for available commands.\n");
                appendOutput("Bantu commands: bantu run <file.b>, bantu <args>\n\n");
            }

        } catch (Exception e) {
            appendOutput("Error: " + e.getMessage() + "\n\n");
        }
    }

    private void showHelp() {
        appendOutput(
            "═══════════════════════════════════════════\n" +
            "  BantuDroid Terminal — Available Commands\n" +
            "═══════════════════════════════════════════\n\n" +
            "  bantu run <file.b>         Run a Bantu file\n" +
            "  bantu <args>               Run Bantu with arguments\n" +
            "  start-service <file.b>     Start server in background\n" +
            "  stop-service               Stop background server\n" +
            "  ls / dir                   List .b files\n" +
            "  clear / cls                Clear terminal\n" +
            "  help                       Show this help\n\n" +
            "Quick actions: Use the buttons above the terminal.\n\n"
        );
    }

    private void listFiles() {
        appendOutput("Bantu projects:\n");
        for (BantuEngine.BantuFile f : engine.listProjects()) {
            appendOutput("  " + f.getName() + "  (" + f.getSizeFormatted() + ")\n");
        }
        appendOutput("\n");
    }

    private void startServerService(String file) {
        if (!engine.isInstalled()) {
            appendOutput("Error: Engine not installed\n");
            return;
        }

        Intent intent = new Intent(this, ServerService.class);
        intent.setAction(ServerService.ACTION_START);
        intent.putExtra(ServerService.EXTRA_FILE, file);
        ContextCompat.startForegroundService(this, intent);
        appendOutput("Background server started: " + file + "\n");
        appendOutput("It will keep running even when you close the app.\n\n");
    }

    private void stopServerService() {
        Intent intent = new Intent(this, ServerService.class);
        intent.setAction(ServerService.ACTION_STOP);
        ContextCompat.startForegroundService(this, intent);
        appendOutput("Background server stopped.\n\n");
    }

    private void stopProcess() {
        if (currentProcess != null && currentProcess.isRunning()) {
            currentProcess.stop();
            appendOutput("\n^C — Process stopped\n\n");
            setRunning(false);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // OutputListener callbacks (called on background thread!)
    // ──────────────────────────────────────────────────────────────

    @Override
    public void onOutput(String line) {
        runOnUiThread(() -> appendOutput(line + "\n"));
    }

    @Override
    public void onError(String line) {
        runOnUiThread(() -> appendOutput("ERROR: " + line + "\n"));
    }

    @Override
    public void onExit(int exitCode) {
        runOnUiThread(() -> {
            appendOutput("\nProcess exited with code " + exitCode + "\n\n");
            setRunning(false);
            currentProcess = null;
        });
    }

    // ──────────────────────────────────────────────────────────────
    // Terminal display helpers
    // ──────────────────────────────────────────────────────────────

    private void appendOutput(String text) {
        outputBuffer.append(text);

        // Keep buffer from growing too large (max ~100KB)
        if (outputBuffer.length() > 100_000) {
            outputBuffer.delete(0, outputBuffer.length() - 80_000);
        }

        terminalOutput.setText(outputBuffer.toString());
        scrollview.post(() -> scrollview.fullScroll(View.FOCUS_DOWN));
    }

    private void setRunning(boolean running) {
        btnRun.setEnabled(!running);
        btnStop.setEnabled(running);
        commandInput.setEnabled(!running);
    }

    // ──────────────────────────────────────────────────────────────
    // Notification permission (Android 13+)
    // ──────────────────────────────────────────────────────────────

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                "android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{"android.permission.POST_NOTIFICATIONS"},
                    NOTIFICATION_PERMISSION_CODE);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        // Don't kill the process — the ServerService will manage it
        // if the user started it as a background service.
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Keep process running even when activity is paused
    }

    // ──────────────────────────────────────────────────────────────
    // Command history
    // ──────────────────────────────────────────────────────────────

    private static class CommandHistory {
        private static final int MAX_HISTORY = 50;
        private final String[] history = new String[MAX_HISTORY];
        private int size = 0;
        private int pointer = 0;

        void add(String cmd) {
            // Don't add duplicates
            if (size > 0 && cmd.equals(history[size - 1])) return;
            if (size < MAX_HISTORY) {
                history[size++] = cmd;
            } else {
                System.arraycopy(history, 1, history, 0, MAX_HISTORY - 1);
                history[MAX_HISTORY - 1] = cmd;
            }
            pointer = size;
        }

        String previous() {
            if (pointer > 0) {
                pointer--;
                return history[pointer];
            }
            return null;
        }

        String next() {
            if (pointer < size - 1) {
                pointer++;
                return history[pointer];
            }
            pointer = size;
            return null;
        }
    }
}
