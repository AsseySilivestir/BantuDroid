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
import java.io.InputStreamReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * Terminal emulator activity — full shell with working cd, ls, pwd, cat, mkdir, etc.
 *
 * Features:
 * - Working directory tracking (cd, pwd)
 * - Real filesystem commands: ls, dir, cat, mkdir, rmdir, rm, cp, mv, touch, pwd, cd
 * - Bantu commands: bantu run <file>, bantu <args>
 * - Server commands: start-service, stop-service
 * - Shell passthrough: any unrecognized command runs via /system/bin/sh
 * - Command history with up/down navigation
 * - Green-on-black terminal display
 * - Quick action buttons
 */
public class TerminalActivity extends AppCompatActivity
    implements BantuProcess.OutputListener {

    private static final int NOTIFICATION_PERMISSION_CODE = 1001;

    private TextView terminalOutput;
    private TextView tvPrompt;
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
        tvPrompt = findViewById(R.id.prompt_prefix);
        commandInput = findViewById(R.id.command_input);
        btnRun = findViewById(R.id.btn_run);
        btnStop = findViewById(R.id.btn_stop);
        btnClear = findViewById(R.id.btn_clear);
        scrollview = findViewById(R.id.scrollview);
        quickActions = findViewById(R.id.quick_actions);
        quickActionsScroll = findViewById(R.id.quick_actions_scroll);

        // Terminal styling
        terminalOutput.setTypeface(Typeface.MONOSPACE);

        // Update prompt
        updatePrompt();

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

        // Command history navigation
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

        // If started from FileManager with a file to run
        String runFile = getIntent().getStringExtra("run_file");
        if (runFile != null) {
            commandInput.setText("bantu run " + runFile);
            runCommand();
        }

        requestNotificationPermission();
    }

    private void updatePrompt() {
        String path = currentDir.getAbsolutePath();
        // Shorten prompt: replace app files dir with ~
        String home = getFilesDir().getAbsolutePath();
        if (path.startsWith(home)) {
            path = "~" + path.substring(home.length());
        }
        if (tvPrompt != null) {
            tvPrompt.setText(path + " $ ");
        }
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
    // Command execution — full shell with built-in commands
    // ──────────────────────────────────────────────────────────────

    private void runCommand() {
        String cmd = commandInput.getText().toString().trim();
        if (cmd.isEmpty()) return;

        commandHistory.add(cmd);

        // Show command with prompt
        String path = currentDir.getAbsolutePath();
        String home = getFilesDir().getAbsolutePath();
        if (path.startsWith(home)) {
            path = "~" + path.substring(home.length());
        }
        appendOutput(path + " $ " + cmd + "\n");

        commandInput.setText("");

        try {
            // ── Built-in commands ──

            if (cmd.equals("pwd")) {
                appendOutput(currentDir.getAbsolutePath() + "\n\n");

            } else if (cmd.startsWith("cd ")) {
                builtinCd(cmd.substring(3).trim());

            } else if (cmd.equals("cd") || cmd.equals("cd ~")) {
                currentDir = getFilesDir();
                updatePrompt();
                appendOutput(currentDir.getAbsolutePath() + "\n\n");

            } else if (cmd.equals("ls") || cmd.equals("dir")) {
                builtinLs(currentDir);

            } else if (cmd.startsWith("ls ") || cmd.startsWith("dir ")) {
                String target = cmd.substring(cmd.indexOf(' ') + 1).trim();
                File targetDir = resolvePath(target);
                if (targetDir != null && targetDir.isDirectory()) {
                    builtinLs(targetDir);
                } else {
                    appendOutput("No such directory: " + target + "\n\n");
                }

            } else if (cmd.startsWith("cat ")) {
                builtinCat(cmd.substring(4).trim());

            } else if (cmd.startsWith("mkdir ")) {
                builtinMkdir(cmd.substring(6).trim());

            } else if (cmd.startsWith("rmdir ")) {
                builtinRmdir(cmd.substring(6).trim());

            } else if (cmd.startsWith("rm ")) {
                builtinRm(cmd.substring(3).trim());

            } else if (cmd.startsWith("touch ")) {
                builtinTouch(cmd.substring(6).trim());

            } else if (cmd.startsWith("cp ")) {
                builtinCp(cmd.substring(3).trim());

            } else if (cmd.startsWith("mv ")) {
                builtinMv(cmd.substring(3).trim());

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

            } else if (cmd.equals("uname") || cmd.equals("uname -a")) {
                appendOutput("BantuDroid " + android.os.Build.VERSION.RELEASE +
                    " " + android.os.Build.SUPPORTED_ABIS[0] + "\n\n");

            } else if (cmd.startsWith("start-service ")) {
                String file = cmd.substring("start-service ".length()).trim();
                startServerService(file);

            } else if (cmd.equals("stop-service")) {
                stopServerService();

            // ── Bantu commands ──

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

            // ── Shell passthrough ──

            } else {
                // Run via system shell for any unrecognized command
                runShellCommand(cmd);
            }

        } catch (Exception e) {
            appendOutput("Error: " + e.getMessage() + "\n\n");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Built-in shell commands
    // ──────────────────────────────────────────────────────────────

    private void builtinCd(String path) {
        if (path.equals("-")) {
            // Could track previous dir, for now just show current
            appendOutput(currentDir.getAbsolutePath() + "\n\n");
            return;
        }

        File target = resolvePath(path);
        if (target == null) {
            appendOutput("cd: no such directory: " + path + "\n\n");
            return;
        }
        if (!target.isDirectory()) {
            appendOutput("cd: not a directory: " + path + "\n\n");
            return;
        }
        if (!target.canRead()) {
            appendOutput("cd: permission denied: " + path + "\n\n");
            return;
        }
        currentDir = target;
        updatePrompt();
        appendOutput(currentDir.getAbsolutePath() + "\n\n");
    }

    private void builtinLs(File dir) {
        if (!dir.canRead()) {
            appendOutput("ls: permission denied\n\n");
            return;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            appendOutput("(empty directory)\n\n");
            return;
        }

        // Sort: directories first, then files, alphabetically
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd HH:mm", Locale.getDefault());

        for (File f : files) {
            String type = f.isDirectory() ? "d" : "-";
            String size = f.isDirectory() ? "     -" : String.format("%7d", f.length());
            String date = sdf.format(new Date(f.lastModified()));
            String name = f.getName();
            if (f.isDirectory()) name += "/";

            // Color hints: directories in green, .b files in orange
            String line = String.format("%s%s  %s  %s", type, size, date, name);
            appendOutput(line + "\n");
        }
        appendOutput("\n" + files.length + " items\n\n");
    }

    private void builtinCat(String path) {
        File file = resolvePath(path);
        if (file == null || !file.exists()) {
            appendOutput("cat: file not found: " + path + "\n\n");
            return;
        }
        if (file.isDirectory()) {
            appendOutput("cat: is a directory: " + path + "\n\n");
            return;
        }
        if (!file.canRead()) {
            appendOutput("cat: permission denied: " + path + "\n\n");
            return;
        }
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(file)));
            String line;
            while ((line = reader.readLine()) != null) {
                appendOutput(line + "\n");
            }
            reader.close();
            appendOutput("\n");
        } catch (IOException e) {
            appendOutput("cat: error reading file: " + e.getMessage() + "\n\n");
        }
    }

    private void builtinMkdir(String path) {
        File dir = resolvePath(path);
        if (dir == null) {
            // resolvePath returns null for non-existing absolute paths
            // Try creating as relative path
            dir = new File(currentDir, path);
        }
        if (dir.exists()) {
            appendOutput("mkdir: already exists: " + path + "\n\n");
            return;
        }
        if (dir.mkdirs()) {
            appendOutput("Created: " + dir.getAbsolutePath() + "\n\n");
        } else {
            appendOutput("mkdir: failed to create: " + path + "\n\n");
        }
    }

    private void builtinRmdir(String path) {
        File dir = resolvePath(path);
        if (dir == null || !dir.exists()) {
            appendOutput("rmdir: not found: " + path + "\n\n");
            return;
        }
        if (!dir.isDirectory()) {
            appendOutput("rmdir: not a directory: " + path + "\n\n");
            return;
        }
        if (dir.delete()) {
            appendOutput("Removed: " + path + "\n\n");
        } else {
            appendOutput("rmdir: directory not empty or permission denied\n\n");
        }
    }

    private void builtinRm(String path) {
        File file = resolvePath(path);
        if (file == null || !file.exists()) {
            // Try as relative path
            file = new File(currentDir, path);
        }
        if (!file.exists()) {
            appendOutput("rm: file not found: " + path + "\n\n");
            return;
        }
        if (file.isDirectory()) {
            appendOutput("rm: is a directory (use rmdir): " + path + "\n\n");
            return;
        }
        if (file.delete()) {
            appendOutput("Deleted: " + path + "\n\n");
        } else {
            appendOutput("rm: permission denied: " + path + "\n\n");
        }
    }

    private void builtinTouch(String path) {
        File file = resolvePath(path);
        if (file == null) {
            file = new File(currentDir, path);
        }
        try {
            if (file.exists()) {
                file.setLastModified(System.currentTimeMillis());
            } else {
                file.createNewFile();
            }
            appendOutput("Touched: " + file.getAbsolutePath() + "\n\n");
        } catch (IOException e) {
            appendOutput("touch: error: " + e.getMessage() + "\n\n");
        }
    }

    private void builtinCp(String args) {
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) {
            appendOutput("Usage: cp <source> <dest>\n\n");
            return;
        }
        File src = resolvePath(parts[0]);
        if (src == null) src = new File(currentDir, parts[0]);
        File dst = resolvePath(parts[1]);
        if (dst == null) dst = new File(currentDir, parts[1]);

        if (!src.exists()) {
            appendOutput("cp: source not found: " + parts[0] + "\n\n");
            return;
        }
        try {
            copyFile(src, dst);
            appendOutput("Copied: " + parts[0] + " -> " + parts[1] + "\n\n");
        } catch (IOException e) {
            appendOutput("cp: error: " + e.getMessage() + "\n\n");
        }
    }

    private void builtinMv(String args) {
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) {
            appendOutput("Usage: mv <source> <dest>\n\n");
            return;
        }
        File src = resolvePath(parts[0]);
        if (src == null) src = new File(currentDir, parts[0]);
        File dst = resolvePath(parts[1]);
        if (dst == null) dst = new File(currentDir, parts[1]);

        if (!src.exists()) {
            appendOutput("mv: source not found: " + parts[0] + "\n\n");
            return;
        }
        if (src.renameTo(dst)) {
            appendOutput("Moved: " + parts[0] + " -> " + parts[1] + "\n\n");
        } else {
            appendOutput("mv: failed (cross-device? try cp + rm)\n\n");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Shell passthrough
    // ──────────────────────────────────────────────────────────────

    private void runShellCommand(String cmd) {
        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
                pb.directory(currentDir);
                pb.redirectErrorStream(true);
                Process proc = pb.start();

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    final String l = line;
                    runOnUiThread(() -> appendOutput(l + "\n"));
                }

                int exitCode = proc.waitFor();
                if (exitCode != 0) {
                    runOnUiThread(() -> appendOutput("(exit code: " + exitCode + ")\n\n"));
                } else {
                    runOnUiThread(() -> appendOutput("\n"));
                }
                reader.close();
            } catch (Exception e) {
                runOnUiThread(() -> appendOutput("Shell error: " + e.getMessage() + "\n\n"));
            }
        }).start();
    }

    // ──────────────────────────────────────────────────────────────
    // Path resolution
    // ──────────────────────────────────────────────────────────────

    /**
     * Resolve a path string to a File object.
     * Supports: absolute paths, ~ (home), .. (parent), . (current)
     */
    private File resolvePath(String path) {
        if (path == null || path.isEmpty()) return currentDir;

        // Handle ~ (home directory)
        String home = getFilesDir().getAbsolutePath();
        if (path.equals("~")) return getFilesDir();
        if (path.startsWith("~/")) {
            path = home + path.substring(1);
        }

        File file;
        if (path.startsWith("/")) {
            file = new File(path);
        } else {
            file = new File(currentDir, path);
        }

        // Normalize: resolve . and ..
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            return file;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // File utilities
    // ──────────────────────────────────────────────────────────────

    private void copyFile(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists()) dst.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyFile(child, new File(dst, child.getName()));
                }
            }
        } else {
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
    }

    // ──────────────────────────────────────────────────────────────
    // Help
    // ──────────────────────────────────────────────────────────────

    private void showHelp() {
        appendOutput(
            "═══════════════════════════════════════════\n" +
            "  BantuDroid Terminal — Commands\n" +
            "═══════════════════════════════════════════\n\n" +
            "  FILE SYSTEM:\n" +
            "    pwd                       Print working directory\n" +
            "    cd <dir>                  Change directory (~, .., .)\n" +
            "    ls [dir]                  List files (dirs first)\n" +
            "    cat <file>                Display file contents\n" +
            "    mkdir <dir>               Create directory\n" +
            "    rmdir <dir>               Remove empty directory\n" +
            "    rm <file>                 Delete file\n" +
            "    touch <file>              Create empty file\n" +
            "    cp <src> <dst>            Copy file/dir\n" +
            "    mv <src> <dst>            Move/rename file\n" +
            "    echo <text>               Print text\n\n" +
            "  BANTU COMMANDS:\n" +
            "    bantu run <file.b>        Run a Bantu program\n" +
            "    bantu <args>              Run Bantu with arguments\n\n" +
            "  SERVER:\n" +
            "    start-service <file.b>    Start in background\n" +
            "    stop-service              Stop background server\n\n" +
            "  OTHER:\n" +
            "    clear / cls               Clear terminal\n" +
            "    whoami                     Show user\n" +
            "    uname                      Show system info\n" +
            "    help                       This help\n" +
            "    <any cmd>                  Run in system shell\n\n"
        );
    }

    // ──────────────────────────────────────────────────────────────
    // Bantu server management
    // ──────────────────────────────────────────────────────────────

    private void startServerService(String file) {
        if (!engine.isInstalled()) {
            appendOutput("Error: Engine not installed\n");
            return;
        }
        Intent intent = new Intent(this, ServerService.class);
        intent.setAction(ServerService.ACTION_START);
        intent.putExtra(ServerService.EXTRA_FILE, file);
        ContextCompat.startForegroundService(this, intent);
        appendOutput("Background server started: " + file + "\n\n");
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
    // OutputListener callbacks
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
    // Terminal display
    // ──────────────────────────────────────────────────────────────

    private void appendOutput(String text) {
        outputBuffer.append(text);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
            if (pointer > 0) return history[--pointer];
            return null;
        }

        String next() {
            if (pointer < size - 1) return history[++pointer];
            pointer = size;
            return null;
        }
    }
}
