package com.bantu.droid;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * Terminal emulator activity.
 * Provides a full terminal interface for running Bantu commands
 * and real shell commands (cd, pwd, ls, cat, etc.).
 *
 * Features:
 * - Green-on-black terminal display with monospace font
 * - Working directory tracking with cd/pwd
 * - Real filesystem commands: ls, dir, cat, mkdir, rmdir, rm, touch, cp, mv, pwd, cd
 * - Bantu commands: bantu run <file>, bantu <args>
 * - Server commands: start-service, stop-service
 * - Shell passthrough: any unrecognized command runs via /system/bin/sh
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

    // Working directory — starts at app files dir
    private File currentDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        engine = new BantuEngine(this);
        outputBuffer = new StringBuilder();
        commandHistory = new CommandHistory();

        // Start in app's files directory
        currentDir = getFilesDir();

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
        appendOutput("Working directory: " + currentDir.getAbsolutePath() + "\n\n");

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

        // Check if launched from FileManager with a file to run
        String runFile = getIntent().getStringExtra("run_file");
        if (runFile != null) {
            commandInput.setText("bantu run " + runFile);
            // Auto-run after a short delay
            commandInput.postDelayed(this::runCommand, 500);
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
                    commandInput.setText(next != null ? next : "");
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

        // Display command in terminal with prompt showing current dir
        String dirName = currentDir.getName();
        if (currentDir.getAbsolutePath().equals(getFilesDir().getAbsolutePath())) {
            dirName = "~";
        }
        appendOutput(dirName + " $ " + cmd + "\n");

        // Clear input
        commandInput.setText("");

        try {
            // ── Built-in shell commands ──
            if (cmd.equals("pwd")) {
                appendOutput(currentDir.getAbsolutePath() + "\n\n");

            } else if (cmd.startsWith("cd ")) {
                handleCd(cmd.substring(3).trim());

            } else if (cmd.equals("cd")) {
                // cd with no args → go to app files dir
                currentDir = getFilesDir();
                appendOutput(currentDir.getAbsolutePath() + "\n\n");

            } else if (cmd.equals("ls") || cmd.equals("dir")) {
                handleLs(null);

            } else if (cmd.startsWith("ls ") || cmd.startsWith("dir ")) {
                String path = cmd.split("\\s+", 2)[1].trim();
                handleLs(path);

            } else if (cmd.startsWith("cat ")) {
                handleCat(cmd.substring(4).trim());

            } else if (cmd.startsWith("mkdir ")) {
                handleMkdir(cmd.substring(6).trim());

            } else if (cmd.startsWith("rmdir ")) {
                handleRmdir(cmd.substring(6).trim());

            } else if (cmd.startsWith("rm ")) {
                handleRm(cmd.substring(3).trim());

            } else if (cmd.startsWith("touch ")) {
                handleTouch(cmd.substring(6).trim());

            } else if (cmd.startsWith("cp ")) {
                handleCp(cmd.substring(3).trim());

            } else if (cmd.startsWith("mv ")) {
                handleMv(cmd.substring(3).trim());

            } else if (cmd.startsWith("echo ")) {
                appendOutput(cmd.substring(5) + "\n\n");

            } else if (cmd.equals("clear") || cmd.equals("cls")) {
                outputBuffer.setLength(0);
                terminalOutput.setText("");

            } else if (cmd.equals("help")) {
                showHelp();

            } else if (cmd.equals("whoami")) {
                appendOutput("bantu\n\n");

            } else if (cmd.equals("hostname")) {
                appendOutput(android.os.Build.MODEL + "\n\n");

            } else if (cmd.startsWith("start-service ")) {
                String file = cmd.substring("start-service ".length()).trim();
                startServerService(file);

            } else if (cmd.equals("stop-service")) {
                stopServerService();

            // ── Bantu engine commands ──
            } else if (cmd.startsWith("bantu run ")) {
                String file = cmd.substring("bantu run ".length()).trim();
                currentProcess = engine.run(file);
                currentProcess.readOutput(this);
                setRunning(true);

            } else if (cmd.startsWith("bantu ")) {
                String[] args = cmd.substring("bantu ".length()).trim().split("\\s+");
                currentProcess = engine.execute(args);
                currentProcess.readOutput(this);
                setRunning(true);

            // ── Shell passthrough — run via /system/bin/sh ──
            } else {
                runShellCommand(cmd);
            }

        } catch (Exception e) {
            appendOutput("Error: " + e.getMessage() + "\n\n");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Built-in filesystem commands
    // ──────────────────────────────────────────────────────────────

    private File resolvePath(String path) {
        if (path.startsWith("/")) {
            return new File(path);
        } else if (path.equals("~") || path.startsWith("~/")) {
            String rest = path.equals("~") ? "" : path.substring(2);
            return new File(getFilesDir(), rest);
        } else if (path.equals("..")) {
            File parent = currentDir.getParentFile();
            return parent != null ? parent : currentDir;
        } else if (path.equals(".")) {
            return currentDir;
        } else {
            return new File(currentDir, path);
        }
    }

    private void handleCd(String path) {
        File target = resolvePath(path);
        if (target.isDirectory() && target.canRead()) {
            currentDir = target;
            appendOutput(currentDir.getAbsolutePath() + "\n\n");
        } else if (target.exists()) {
            appendOutput("cd: not a directory: " + path + "\n\n");
        } else {
            appendOutput("cd: no such directory: " + path + "\n\n");
        }
    }

    private void handleLs(String path) {
        File dir = path != null ? resolvePath(path) : currentDir;
        if (!dir.isDirectory()) {
            appendOutput("ls: not a directory: " + dir.getPath() + "\n\n");
            return;
        }
        File[] entries = dir.listFiles();
        if (entries == null || entries.length == 0) {
            appendOutput("(empty)\n\n");
            return;
        }

        // Sort: directories first, then files
        Arrays.sort(entries, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd HH:mm", Locale.getDefault());
        for (File f : entries) {
            String type = f.isDirectory() ? "d" : "-";
            String size = f.isDirectory() ? "" : String.format("%8d", f.length());
            String date = sdf.format(new Date(f.lastModified()));
            String name = f.getName();
            if (f.isDirectory()) name += "/";
            appendOutput(String.format("%s %s  %s  %s\n", type, size, date, name));
        }
        appendOutput("\n");
    }

    private void handleCat(String path) {
        File file = resolvePath(path);
        if (!file.exists()) {
            appendOutput("cat: no such file: " + path + "\n\n");
            return;
        }
        if (file.isDirectory()) {
            appendOutput("cat: is a directory: " + path + "\n\n");
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                appendOutput(line + "\n");
            }
            appendOutput("\n");
        } catch (Exception e) {
            appendOutput("cat: error reading file: " + e.getMessage() + "\n\n");
        }
    }

    private void handleMkdir(String path) {
        File dir = resolvePath(path);
        if (dir.mkdirs() || dir.exists()) {
            appendOutput("Created: " + dir.getAbsolutePath() + "\n\n");
        } else {
            appendOutput("mkdir: failed to create: " + path + "\n\n");
        }
    }

    private void handleRmdir(String path) {
        File dir = resolvePath(path);
        if (!dir.exists()) {
            appendOutput("rmdir: no such directory: " + path + "\n\n");
            return;
        }
        if (dir.delete()) {
            appendOutput("Removed: " + dir.getAbsolutePath() + "\n\n");
        } else {
            appendOutput("rmdir: directory not empty or cannot delete: " + path + "\n\n");
        }
    }

    private void handleRm(String path) {
        File file = resolvePath(path);
        if (!file.exists()) {
            appendOutput("rm: no such file: " + path + "\n\n");
            return;
        }
        if (file.delete()) {
            appendOutput("Removed: " + file.getAbsolutePath() + "\n\n");
        } else {
            appendOutput("rm: cannot delete: " + path + "\n\n");
        }
    }

    private void handleTouch(String path) {
        File file = resolvePath(path);
        try {
            if (file.createNewFile() || file.setLastModified(System.currentTimeMillis())) {
                appendOutput("Created: " + file.getAbsolutePath() + "\n\n");
            } else {
                appendOutput("touch: failed: " + path + "\n\n");
            }
        } catch (Exception e) {
            appendOutput("touch: error: " + e.getMessage() + "\n\n");
        }
    }

    private void handleCp(String args) {
        String[] parts = args.split("\\s+");
        if (parts.length < 2) {
            appendOutput("cp: usage: cp <src> <dst>\n\n");
            return;
        }
        try {
            File src = resolvePath(parts[0]);
            File dst = resolvePath(parts[1]);
            if (!src.exists()) {
                appendOutput("cp: source not found: " + parts[0] + "\n\n");
                return;
            }
            copyFile(src, dst);
            appendOutput("Copied: " + src.getName() + " -> " + dst.getAbsolutePath() + "\n\n");
        } catch (Exception e) {
            appendOutput("cp: error: " + e.getMessage() + "\n\n");
        }
    }

    private void handleMv(String args) {
        String[] parts = args.split("\\s+");
        if (parts.length < 2) {
            appendOutput("mv: usage: mv <src> <dst>\n\n");
            return;
        }
        File src = resolvePath(parts[0]);
        File dst = resolvePath(parts[1]);
        if (!src.exists()) {
            appendOutput("mv: source not found: " + parts[0] + "\n\n");
            return;
        }
        if (src.renameTo(dst)) {
            appendOutput("Moved: " + src.getName() + " -> " + dst.getAbsolutePath() + "\n\n");
        } else {
            appendOutput("mv: failed to move\n\n");
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(src);
        java.io.OutputStream out = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        in.close();
        out.close();
    }

    // ──────────────────────────────────────────────────────────────
    // Shell passthrough
    // ──────────────────────────────────────────────────────────────

    private void runShellCommand(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
            pb.directory(currentDir);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                appendOutput(line + "\n");
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                appendOutput("Exit code: " + exitCode + "\n");
            }
            appendOutput("\n");
        } catch (Exception e) {
            appendOutput("sh: " + e.getMessage() + "\n\n");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Help
    // ──────────────────────────────────────────────────────────────

    private void showHelp() {
        appendOutput(
            "═══════════════════════════════════════════\n" +
            "  BantuDroid Terminal — Available Commands\n" +
            "═══════════════════════════════════════════\n\n" +
            "  Bantu Engine:\n" +
            "    bantu run <file.b>       Run a Bantu file\n" +
            "    bantu <args>             Run Bantu with arguments\n" +
            "    start-service <file.b>   Start server in background\n" +
            "    stop-service             Stop background server\n\n" +
            "  File System:\n" +
            "    pwd                      Print working directory\n" +
            "    cd [dir]                 Change directory\n" +
            "    ls [dir]                 List files and directories\n" +
            "    cat <file>               Display file contents\n" +
            "    mkdir <dir>              Create directory\n" +
            "    rmdir <dir>              Remove empty directory\n" +
            "    rm <file>                Delete file\n" +
            "    touch <file>             Create empty file\n" +
            "    cp <src> <dst>           Copy file\n" +
            "    mv <src> <dst>           Move/rename file\n" +
            "    echo <text>              Print text\n\n" +
            "  Other:\n" +
            "    clear / cls              Clear terminal\n" +
            "    help                     Show this help\n" +
            "    whoami                   Show current user\n" +
            "    hostname                 Show device name\n\n" +
            "  Shell:\n" +
            "    Any other command runs via /system/bin/sh\n\n"
        );
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
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
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
