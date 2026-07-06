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
import java.util.Date;
import java.util.Locale;

/**
 * Terminal emulator activity — full shell with CWD tracking.
 *
 * Features:
 * - Persistent Current Working Directory (CWD)
 * - Real filesystem commands: pwd, ls, cd, cat, mkdir, rmdir, rm, touch, cp, mv
 * - ALL Bantu CLI commands executed with correct CWD
 * - Shell passthrough for unknown commands
 * - Auto project registration after bantu init
 * - Bantu Projects list sync
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
    private BusyboxExecutor busybox;

    // Current Working Directory — starts at workspace root
    private File currentDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        engine = new BantuEngine(this);
        outputBuffer = new StringBuilder();
        commandHistory = new CommandHistory();
        busybox = new BusyboxExecutor(this);

        // Start CWD at the Bantu workspace root
        currentDir = engine.getWorkspaceRoot();

        // Bind views
        terminalOutput = findViewById(R.id.terminal_output);
        commandInput = findViewById(R.id.command_input);
        btnRun = findViewById(R.id.btn_run);
        btnStop = findViewById(R.id.btn_stop);
        btnClear = findViewById(R.id.btn_clear);
        scrollview = findViewById(R.id.scrollview);
        quickActions = findViewById(R.id.quick_actions);
        quickActionsScroll = findViewById(R.id.quick_actions_scroll);

        terminalOutput.setTypeface(Typeface.MONOSPACE);

        // Welcome
        appendOutput(getString(R.string.terminal_welcome));
        appendOutput("Working directory: " + currentDir.getAbsolutePath() + "\n\n");
        // Ensure busybox is available — downloads on first run (~2MB)
        ensureBusybox();

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
            commandInput.postDelayed(this::runCommand, 500);
        }

        // Check if launched with a directory to open
        String openDir = getIntent().getStringExtra("open_dir");
        if (openDir != null) {
            File d = new File(openDir);
            if (d.isDirectory()) {
                currentDir = d;
            }
        }

        btnRun.setOnClickListener(v -> runCommand());
        btnStop.setOnClickListener(v -> stopProcess());
        btnClear.setOnClickListener(v -> {
            outputBuffer.setLength(0);
            terminalOutput.setText("");
        });

        commandInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                runCommand();
                return true;
            }
            return false;
        });

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
    // Command execution — the heart of the terminal
    // ──────────────────────────────────────────────────────────────

    private void runCommand() {
        String cmd = commandInput.getText().toString().trim();
        if (cmd.isEmpty()) return;

        commandHistory.add(cmd);

        // Show prompt with CWD
        String prompt = promptString();
        appendOutput(prompt + cmd + "\n");
        commandInput.setText("");

        try {
            // ── Built-in shell commands ──
            if (cmd.equals("pwd")) {
                appendOutput(currentDir.getAbsolutePath() + "\n\n");

            } else if (cmd.startsWith("cd ") || cmd.equals("cd")) {
                handleCd(cmd.equals("cd") ? null : cmd.substring(3).trim());

            } else if (cmd.equals("ls") || cmd.equals("dir")) {
                handleLs(null);

            } else if (cmd.startsWith("ls ") || cmd.startsWith("dir ")) {
                handleLs(cmd.split("\\s+", 2)[1].trim());

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

            // tree command — pure-Java implementation, no busybox needed
            } else if (cmd.equals("tree") || cmd.startsWith("tree ")) {
                handleTree(cmd.substring(4).trim());

            // apt / pkg — no-op stubs explaining why they don't work
            } else if (cmd.startsWith("apt ") || cmd.equals("apt")
                       || cmd.startsWith("pkg ") || cmd.equals("pkg")) {
                appendOutput("apt/pkg are not available. BantuDroid bundles busybox which provides\ncurl, wget, tree, grep, sed, awk, find, and 300+ other utilities.\nFor a full package manager, install Termux from F-Droid.\n\n");

            } else if (cmd.equals("help")) {
                showHelp();

            } else if (cmd.equals("whoami")) {
                appendOutput("bantu\n\n");

            } else if (cmd.equals("hostname")) {
                appendOutput(Build.MODEL + "\n\n");

            } else if (cmd.startsWith("start-service ")) {
                startServerService(cmd.substring("start-service ".length()).trim());

            } else if (cmd.equals("stop-service")) {
                stopServerService();

            // ── ALL Bantu CLI commands — executed with CWD ──
            } else if (cmd.startsWith("bantu ")) {
                runBantuCommand(cmd);

            } else if (cmd.equals("bantu")) {
                // Just "bantu" → show help
                runBantuCommand("bantu --help");

            // ── Shell passthrough ──
            } else {
                runShellPassthrough(cmd);
            }

        } catch (Exception e) {
            appendOutput("Error: " + e.getMessage() + "\n\n");
        }
    }

    private String promptString() {
        String dirName = currentDir.getAbsolutePath();
        String workspace = engine.getWorkspaceRoot().getAbsolutePath();
        if (dirName.equals(workspace)) {
            dirName = "~";
        } else if (dirName.startsWith(workspace + "/")) {
            dirName = "~" + dirName.substring(workspace.length());
        }
        return dirName + " $ ";
    }

    // ──────────────────────────────────────────────────────────────
    // Bantu CLI — all commands executed with correct CWD
    // ──────────────────────────────────────────────────────────────

    private void runBantuCommand(String cmd) {
        try {
            currentProcess = engine.runShellCommandInDir(currentDir, cmd);
            currentProcess.readOutput(this);
            setRunning(true);
        } catch (Exception e) {
            appendOutput("Error: " + e.getMessage() + "\n\n");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Built-in filesystem commands
    // ──────────────────────────────────────────────────────────────

    private File resolvePath(String path) {
        if (path.startsWith("/")) return new File(path);
        if (path.equals("~") || path.startsWith("~/")) {
            String rest = path.equals("~") ? "" : path.substring(2);
            return new File(engine.getWorkspaceRoot(), rest);
        }
        if (path.equals("..")) {
            File parent = currentDir.getParentFile();
            // Don't go above workspace root
            return (parent != null) ? parent : currentDir;
        }
        if (path.equals(".")) return currentDir;
        return new File(currentDir, path);
    }

    private void handleCd(String path) {
        if (path == null || path.isEmpty() || path.equals("~")) {
            currentDir = engine.getWorkspaceRoot();
            appendOutput(currentDir.getAbsolutePath() + "\n\n");
            return;
        }
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
            // Maybe it's a file pattern? Just show the file
            if (dir.exists()) {
                appendOutput(dir.getName() + "\n\n");
            } else {
                appendOutput("ls: cannot access: " + path + "\n\n");
            }
            return;
        }
        File[] entries = dir.listFiles();
        if (entries == null || entries.length == 0) {
            appendOutput("(empty)\n\n");
            return;
        }

        Arrays.sort(entries, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd HH:mm", Locale.getDefault());
        for (File f : entries) {
            String type = f.isDirectory() ? "d" : "-";
            String size = f.isDirectory() ? "      -" : String.format("%7d", f.length());
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
            appendOutput("cat: " + path + ": No such file\n\n");
            return;
        }
        if (file.isDirectory()) {
            appendOutput("cat: " + path + ": Is a directory\n\n");
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                appendOutput(line + "\n");
            }
            appendOutput("\n");
        } catch (Exception e) {
            appendOutput("cat: error: " + e.getMessage() + "\n\n");
        }
    }

    private void handleMkdir(String path) {
        File dir = resolvePath(path);
        if (dir.mkdirs() || dir.exists()) {
            appendOutput("Created: " + dir.getAbsolutePath() + "\n\n");
        } else {
            appendOutput("mkdir: failed: " + path + "\n\n");
        }
    }

    private void handleRmdir(String path) {
        File dir = resolvePath(path);
        if (!dir.exists()) {
            appendOutput("rmdir: " + path + ": No such directory\n\n");
            return;
        }
        if (dir.delete()) {
            appendOutput("Removed: " + dir.getAbsolutePath() + "\n\n");
        } else {
            appendOutput("rmdir: directory not empty: " + path + "\n\n");
        }
    }

    private void handleRm(String path) {
        // Support rm -rf for directories
        boolean recursive = false;
        if (path.startsWith("-rf ") || path.startsWith("-r ")) {
            recursive = true;
            path = path.split("\\s+", 2)[1];
        }
        File file = resolvePath(path);
        if (!file.exists()) {
            appendOutput("rm: " + path + ": No such file\n\n");
            return;
        }
        if (file.isDirectory() && recursive) {
            deleteRecursive(file);
            appendOutput("Removed: " + file.getAbsolutePath() + "\n\n");
        } else if (file.delete()) {
            appendOutput("Removed: " + file.getAbsolutePath() + "\n\n");
        } else {
            appendOutput("rm: cannot delete: " + path + " (use rm -rf for directories)\n\n");
        }
    }

    private void handleTouch(String path) {
        File file = resolvePath(path);
        try {
            if (file.createNewFile() || file.setLastModified(System.currentTimeMillis())) {
                appendOutput("Created: " + file.getAbsolutePath() + "\n\n");
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
                appendOutput("cp: " + parts[0] + ": No such file\n\n");
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
            appendOutput("mv: " + parts[0] + ": No such file\n\n");
            return;
        }
        if (src.renameTo(dst)) {
            appendOutput("Moved: " + src.getName() + " -> " + dst.getAbsolutePath() + "\n\n");
        } else {
            appendOutput("mv: failed\n\n");
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(src);
        java.io.OutputStream out = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        in.close();
        out.close();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File child : c) deleteRecursive(child);
        }
        f.delete();
    }

    // ──────────────────────────────────────────────────────────────
    // Shell passthrough
    // ──────────────────────────────────────────────────────────────

    private void runShellPassthrough(String cmd) {
        if (!busybox.isReady()) {
            ensureBusybox();
        }
        try {
            BusyboxExecutor.ExecResult result = busybox.exec(cmd, currentDir);
            if (!result.stdout.isEmpty()) appendOutput(result.stdout);
            if (!result.stderr.isEmpty()) appendOutput(result.stderr);
            if (result.exitCode != 0 && result.exitCode != -1) {
                appendOutput("Exit code: " + result.exitCode + "\n");
            }
            appendOutput("\n");
        } catch (Exception e) {
            appendOutput("sh: " + e.getMessage() + "\n\n");
        }
    }

    private void ensureBusybox() {
        if (busybox.isReady()) return;
        new Thread(() -> {
            busybox.ensureReady(new BusyboxExecutor.ProgressListener() {
                @Override public void onProgress(int percent) {
                    runOnUiThread(() -> appendOutput("\rDownloading busybox... " + percent + "%"));
                }
                @Override public void onMessage(String msg) {
                    runOnUiThread(() -> appendOutput(msg + "\n"));
                }
                @Override public void onError(String err) {
                    runOnUiThread(() -> appendOutput("busybox: " + err + "\n"));
                }
            });
            if (busybox.isReady()) {
                runOnUiThread(() -> appendOutput("\nbusybox ready — curl, wget, tree, grep, etc. now available.\n\n"));
            }
        }).start();
    }

    private void handleTree(String args) {
        boolean dirsOnly = false;
        String path = null;
        if (args != null && !args.isEmpty()) {
            String[] parts = args.split("\\s+");
            for (String p : parts) {
                if (p.equals("-d")) dirsOnly = true;
                else if (!p.startsWith("-")) path = p;
            }
        }
        File target = (path != null) ? resolvePath(path) : currentDir;
        if (!target.exists()) {
            appendOutput("tree: " + path + ": No such file or directory\n\n");
            return;
        }
        if (target.isFile()) {
            appendOutput(target.getAbsolutePath() + "\n\n0 directories, 1 file\n\n");
            return;
        }
        TreeRenderer renderer = new TreeRenderer(target, false, 10, dirsOnly);
        appendOutput(renderer.render() + "\n");
    }

    // ──────────────────────────────────────────────────────────────
    // Help
    // ──────────────────────────────────────────────────────────────

    private void showHelp() {
        appendOutput(
            "═══════════════════════════════════════════\n" +
            "  BantuDroid Terminal v2.2.2\n" +
            "═══════════════════════════════════════════\n\n" +
            "  Bantu Engine Commands:\n" +
            "    bantu --help               Show engine help\n" +
            "    bantu --version            Show engine version\n" +
            "    bantu init <name>          Create new project\n" +
            "    bantu init --web <name>    Create web project\n" +
            "    bantu new <name>           Create new file\n" +
            "    bantu run                  Run project in CWD\n" +
            "    bantu run <file.b>         Run a .b file\n" +
            "    bantu build                Build project\n" +
            "    bantu build <file.b>       Build a file\n" +
            "    bantu relay [port]         Start relay server\n" +
            "    bantu install              Install packages\n" +
            "    bantu add <pkg>            Add a package\n" +
            "    bantu add <pkg>@<ver>      Add package version\n" +
            "    bantu remove <pkg>         Remove a package\n" +
            "    bantu update               Update all packages\n" +
            "    bantu update <pkg>         Update a package\n" +
            "    bantu list                 List packages\n" +
            "    bantu search               Search packages\n" +
            "    bantu publish              Publish project\n" +
            "    bantu publish --as         Publish as\n\n" +
            "  Shell Commands:\n" +
            "    pwd                        Print working directory\n" +
            "    cd [dir]                   Change directory\n" +
            "    ls [dir]                   List directory\n" +
            "    cat <file>                 View file contents\n" +
            "    mkdir <dir>                Create directory\n" +
            "    rmdir <dir>                Remove empty directory\n" +
            "    rm [-rf] <path>            Delete file/directory\n" +
            "    touch <file>               Create empty file\n" +
            "    cp <src> <dst>             Copy file\n" +
            "    mv <src> <dst>             Move/rename\n" +
            "    echo <text>                Print text\n" +
            "    clear / cls                Clear terminal\n" +
            "    whoami                     Show user\n" +
            "    hostname                   Show device name\n\n" +
            "  Server:\n" +
            "    start-service <file.b>     Start in background\n" +
            "    stop-service               Stop background server\n\n" +
            "  Any other command runs via /system/bin/sh\n\n"
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

            // After bantu init completes, scan for new projects
            // and notify the file manager to refresh
            notifyDataChanged();
        });
    }

    /**
     * Called when data may have changed (after bantu init, rm, etc.)
     * Broadcasts an intent so other activities can refresh.
     */
    private void notifyDataChanged() {
        Intent intent = new Intent("com.bantu.droid.DATA_CHANGED");
        sendBroadcast(intent);
    }

    // ──────────────────────────────────────────────────────────────
    // Terminal display helpers
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

    // ──────────────────────────────────────────────────────────────
    // Notification permission
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

    @Override
    protected void onDestroy() { super.onDestroy(); }

    @Override
    protected void onPause() { super.onPause(); }

    // ──────────────────────────────────────────────────────────────
    // Command history
    // ──────────────────────────────────────────────────────────────

    private static class CommandHistory {
        private static final int MAX = 50;
        private final String[] history = new String[MAX];
        private int size = 0, pointer = 0;

        void add(String cmd) {
            if (size > 0 && cmd.equals(history[size - 1])) return;
            if (size < MAX) history[size++] = cmd;
            else {
                System.arraycopy(history, 1, history, 0, MAX - 1);
                history[MAX - 1] = cmd;
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
