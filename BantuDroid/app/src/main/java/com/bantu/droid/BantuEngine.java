package com.bantu.droid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Core Bantu Engine wrapper.
 * Manages the lifecycle of the Bantu binary on Android:
 * - Locates the binary from nativeLibraryDir (Android-extracted, always executable)
 * - Falls back to extracting from assets with chmod
 * - Provides methods to run Bantu commands and .b files
 * - Manages bundled project files
 *
 * ANDROID EXECUTION MODEL (critical for Android 10+):
 * - /data/data/pkg/files/ is mounted NOEXEC on Android 10+
 *   → chmod 755 does NOT help, kernel blocks exec() at VFS level
 * - /data/app/…/lib/ (nativeLibraryDir) IS executable
 *   → Android extracts lib/*.so from APK here with correct SELinux context
 * - We ship libbantu.so via jniLibs/ → it lands in nativeLibraryDir
 * - If nativeLibraryDir fails, we try codeCacheDir as a last resort
 */
public class BantuEngine {

    private static final String TAG = "BantuEngine";
    private static final String BINARY_NAME = "bantu";
    private static final String PREFS_NAME = "bantu_engine";
    private static final String KEY_INSTALLED = "engine_installed";
    private static final String KEY_VERSION = "engine_version";

    private final Context context;
    private final File binDir;
    private final File projectsDir;
    private final File logDir;
    private final SharedPreferences prefs;
    private boolean installed = false;

    /** Cached binary path — resolved once, reused for all executions */
    private String cachedBinaryPath = null;

    public BantuEngine(Context context) {
        this.context = context.getApplicationContext();
        this.binDir = new File(context.getFilesDir(), "bin");
        this.projectsDir = new File(context.getFilesDir(), "projects");
        this.logDir = new File(context.getFilesDir(), "logs");
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ──────────────────────────────────────────────────────────────
    // Binary Path Resolution (the critical fix)
    // ──────────────────────────────────────────────────────────────

    /**
     * Find the absolute path to the Bantu binary.
     *
     * Strategy (in order of reliability):
     * 1. nativeLibraryDir/libbantu.so — Android-extracted, SELinux allows exec
     * 2. files/bin/bantu — our manually extracted copy (needs chmod, may be noexec)
     * 3. codeCacheDir/bantu — alternative writable location (may allow exec)
     *
     * We do NOT check canExecute() because Java's File.canExecute() uses
     * access(X_OK) which may return false due to SELinux even when the file
     * IS actually executable via exec(). We just check exists() and try chmod.
     */
    private String resolveBinaryPath() {
        if (cachedBinaryPath != null && new File(cachedBinaryPath).exists()) {
            return cachedBinaryPath;
        }

        Log.i(TAG, "Resolving Bantu binary path...");

        // ── Strategy 1: nativeLibraryDir (BEST — always executable on Android) ──
        try {
            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            if (nativeLibDir != null) {
                File libBinary = new File(nativeLibDir, "libbantu.so");
                if (libBinary.exists()) {
                    Log.i(TAG, "Found binary in nativeLibraryDir: " + libBinary.getAbsolutePath());
                    // Try chmod just in case (usually already 755)
                    chmodBinary(libBinary);
                    cachedBinaryPath = libBinary.getAbsolutePath();
                    return cachedBinaryPath;
                } else {
                    Log.w(TAG, "libbantu.so NOT found in nativeLibraryDir: " + nativeLibDir);
                    // List what IS in nativeLibraryDir for debugging
                    File libDir = new File(nativeLibDir);
                    if (libDir.exists() && libDir.isDirectory()) {
                        String[] contents = libDir.list();
                        if (contents != null) {
                            Log.i(TAG, "nativeLibraryDir contents: " + String.join(", ", contents));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "nativeLibraryDir check failed", e);
        }

        // ── Strategy 2: files/bin/bantu with chmod ──
        File filesBinary = new File(binDir, BINARY_NAME);
        if (filesBinary.exists()) {
            Log.i(TAG, "Found binary in files/bin: " + filesBinary.getAbsolutePath());
            chmodBinary(filesBinary);
            cachedBinaryPath = filesBinary.getAbsolutePath();
            return cachedBinaryPath;
        }

        // ── Strategy 3: codeCacheDir ──
        try {
            File codeCacheDir = context.getCodeCacheDir();
            File cacheBinary = new File(codeCacheDir, "bantu");
            if (cacheBinary.exists()) {
                Log.i(TAG, "Found binary in codeCacheDir: " + cacheBinary.getAbsolutePath());
                chmodBinary(cacheBinary);
                cachedBinaryPath = cacheBinary.getAbsolutePath();
                return cachedBinaryPath;
            }
        } catch (Exception e) {
            Log.w(TAG, "codeCacheDir check failed", e);
        }

        // ── No binary found anywhere ──
        Log.e(TAG, "Bantu binary not found in any location!");
        return null;
    }

    /**
     * Run chmod 755 on the binary file. This is a best-effort operation.
     * On noexec mounts, chmod succeeds but exec() still fails — that's OK,
     * we'll try the next strategy.
     */
    private void chmodBinary(File binary) {
        try {
            // Java API
            binary.setExecutable(true, false);
            binary.setReadable(true, false);

            // Shell chmod — more reliable on some Android versions
            Process p = new ProcessBuilder("/system/bin/chmod", "755",
                binary.getAbsolutePath()).start();
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);

            Log.i(TAG, "chmod 755 on " + binary.getAbsolutePath() +
                " → canExecute=" + binary.canExecute());
        } catch (Exception e) {
            Log.w(TAG, "chmod failed for " + binary.getAbsolutePath(), e);
        }
    }

    /**
     * Get the binary path, throwing if not found.
     */
    private String requireBinaryPath() throws IOException {
        String path = resolveBinaryPath();
        if (path == null) {
            throw new IOException(
                "Bantu binary not found. Please reinstall the app.\n" +
                "Searched: nativeLibraryDir, files/bin, codeCacheDir");
        }
        return path;
    }

    // ──────────────────────────────────────────────────────────────
    // Installation
    // ──────────────────────────────────────────────────────────────

    /**
     * Install the Bantu engine by extracting the binary and projects
     * from APK assets. Must be called on first launch or after updates.
     */
    public void install(InstallListener listener) {
        new Thread(() -> {
            try {
                listener.onProgress("Creating directories...");
                binDir.mkdirs();
                projectsDir.mkdirs();
                logDir.mkdirs();

                // Check if nativeLibraryDir already has our binary (from jniLibs)
                listener.onProgress("Checking native library...");
                String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
                File libBinary = (nativeLibDir != null)
                    ? new File(nativeLibDir, "libbantu.so") : null;

                if (libBinary != null && libBinary.exists()) {
                    Log.i(TAG, "Binary already in nativeLibraryDir — no extraction needed");
                    chmodBinary(libBinary);
                } else {
                    // Fallback: extract from assets to files/bin/
                    Log.i(TAG, "libbantu.so not in nativeLibraryDir, extracting from assets...");
                    listener.onProgress("Extracting Bantu binary...");
                    String abi = getSupportedAbi();
                    String assetPath = "bin/" + abi + "/bantu";
                    File binary = new File(binDir, BINARY_NAME);

                    if (!assetExists(assetPath)) {
                        assetPath = "bin/bantu";
                    }

                    extractAsset(assetPath, binary);
                    chmodBinary(binary);

                    // Also try copying to codeCacheDir (may be executable on some devices)
                    try {
                        File cacheBinary = new File(context.getCodeCacheDir(), "bantu");
                        copyFile(binary, cacheBinary);
                        chmodBinary(cacheBinary);
                    } catch (Exception e) {
                        Log.w(TAG, "codeCacheDir copy failed", e);
                    }
                }

                // Verify binary works
                listener.onProgress("Verifying engine...");
                String version = getVersionFromBinary();
                if (version == null) {
                    Log.w(TAG, "Could not read version from binary, but continuing");
                }

                // Extract bundled projects
                listener.onProgress("Extracting project files...");
                extractProjects();

                // Mark as installed
                prefs.edit()
                    .putBoolean(KEY_INSTALLED, true)
                    .putString(KEY_VERSION, version != null ? version : "unknown")
                    .apply();

                installed = true;
                cachedBinaryPath = null; // force re-resolve
                listener.onSuccess(version);

            } catch (Exception e) {
                Log.e(TAG, "Installation failed", e);
                listener.onError(e.getMessage());
            }
        }).start();
    }

    /**
     * Quick check if the engine binary exists (in any location).
     */
    public boolean isInstalled() {
        return resolveBinaryPath() != null;
    }

    /**
     * Get the installed engine version string.
     */
    public String getInstalledVersion() {
        return prefs.getString(KEY_VERSION, "unknown");
    }

    /**
     * Force reinstall (e.g., after an app update with new binary).
     */
    public void reinstall(InstallListener listener) {
        deleteRecursive(binDir);
        prefs.edit().clear().apply();
        installed = false;
        cachedBinaryPath = null;
        install(listener);
    }

    // ──────────────────────────────────────────────────────────────
    // Running Bantu
    // ──────────────────────────────────────────────────────────────

    /**
     * Run a .b file with the Bantu interpreter.
     * Equivalent to: bantu run <file>
     */
    public BantuProcess run(String bantuFile) throws IOException {
        ensureInstalled();

        File file = resolveFile(bantuFile);
        if (!file.exists()) {
            throw new FileNotFoundException(
                "File not found: " + bantuFile + "\n" +
                "Looked in: " + file.getAbsolutePath());
        }

        String binaryPath = requireBinaryPath();
        Log.i(TAG, "Running: " + binaryPath + " run " + file.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(binaryPath, "run", file.getAbsolutePath());
        pb.directory(projectsDir);
        pb.redirectErrorStream(true);
        setupEnvironment(pb);

        try {
            Process process = pb.start();
            return new BantuProcess(process, bantuFile);
        } catch (IOException e) {
            // If direct execution fails (noexec mount), try via /system/bin/sh
            Log.w(TAG, "Direct exec failed, trying via shell wrapper: " + e.getMessage());
            return runViaShell(binaryPath, "run", file.getAbsolutePath());
        }
    }

    /**
     * Run a raw Bantu command with arbitrary arguments.
     * Example: execute("run", "server.b", "--port", "9090")
     */
    public BantuProcess execute(String... args) throws IOException {
        ensureInstalled();

        String binaryPath = requireBinaryPath();
        String[] cmd = new String[args.length + 1];
        cmd[0] = binaryPath;
        System.arraycopy(args, 0, cmd, 1, args.length);

        Log.i(TAG, "Running: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectsDir);
        pb.redirectErrorStream(true);
        setupEnvironment(pb);

        try {
            return new BantuProcess(pb.start(), String.join(" ", args));
        } catch (IOException e) {
            Log.w(TAG, "Direct exec failed, trying via shell wrapper: " + e.getMessage());
            return runViaShell(binaryPath, args);
        }
    }

    /**
     * Run a command in the projects directory (for shell-style operations).
     */
    public BantuProcess runShellCommand(String command) throws IOException {
        ensureInstalled();

        String binaryPath = requireBinaryPath();
        // Always use shell wrapper for terminal commands — it handles PATH etc.
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c",
            binaryPath + " " + command);
        pb.directory(projectsDir);
        pb.redirectErrorStream(true);
        setupEnvironment(pb);

        return new BantuProcess(pb.start(), command);
    }

    /**
     * Fallback: run the binary via /system/bin/sh.
     * This wraps the call in "sh -c 'chmod 755 binary && binary args'"
     * which sometimes works when direct ProcessBuilder exec doesn't.
     */
    private BantuProcess runViaShell(String binaryPath, String... args) throws IOException {
        StringBuilder cmd = new StringBuilder();
        cmd.append("/system/bin/chmod 755 ").append(binaryPath).append(" && ");
        cmd.append(binaryPath);
        for (String arg : args) {
            cmd.append(" '").append(arg.replace("'", "'\\''")).append("'");
        }

        Log.i(TAG, "Shell fallback: " + cmd);

        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd.toString());
        pb.directory(projectsDir);
        pb.redirectErrorStream(true);
        setupEnvironment(pb);

        return new BantuProcess(pb.start(), String.join(" ", args));
    }

    // ──────────────────────────────────────────────────────────────
    // File Management
    // ──────────────────────────────────────────────────────────────

    public List<BantuFile> listProjects() {
        List<BantuFile> files = new ArrayList<>();
        File[] children = projectsDir.listFiles();
        if (children != null) {
            for (File f : children) {
                if (f.getName().endsWith(".b")) {
                    files.add(new BantuFile(f));
                }
            }
        }
        return files;
    }

    public File createProject(String name, String content) throws IOException {
        if (!name.endsWith(".b")) name += ".b";
        File file = new File(projectsDir, name);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
        return file;
    }

    public String readProject(String name) throws IOException {
        File file = resolveFile(name);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    public boolean deleteProject(String name) {
        return resolveFile(name).delete();
    }

    public File importFile(File source) throws IOException {
        File dest = new File(projectsDir, source.getName());
        try (InputStream is = new FileInputStream(source);
             FileOutputStream os = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
        }
        return dest;
    }

    public File getProjectsDir() { return projectsDir; }
    public File getLogDir() { return logDir; }

    // ──────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────

    private void ensureInstalled() throws IllegalStateException {
        if (!isInstalled()) {
            throw new IllegalStateException(
                "Bantu engine is not installed. Call install() first.");
        }
    }

    private void setupEnvironment(ProcessBuilder pb) {
        String binaryPath = resolveBinaryPath();
        String binaryDir = (binaryPath != null)
            ? new File(binaryPath).getParent()
            : binDir.getAbsolutePath();

        pb.environment().put("HOME", context.getFilesDir().getAbsolutePath());
        pb.environment().put("BANTU_HOME", context.getFilesDir().getAbsolutePath());
        pb.environment().put("PATH", binaryDir + ":" +
            System.getenv("PATH"));
        pb.environment().put("TERM", "xterm-256color");
        pb.environment().put("LANG", "en_US.UTF-8");
    }

    private String getSupportedAbi() {
        if (Build.SUPPORTED_ABIS.length > 0) {
            return Build.SUPPORTED_ABIS[0];
        }
        return "arm64-v8a";
    }

    private boolean assetExists(String path) {
        try {
            context.getAssets().open(path).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void extractAsset(String assetPath, File target) throws IOException {
        try (InputStream is = context.getAssets().open(assetPath);
             FileOutputStream os = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
        }
        target.setReadable(true, false);
        target.setWritable(true, true);
    }

    private void copyFile(File src, File dest) throws IOException {
        try (InputStream is = new FileInputStream(src);
             FileOutputStream os = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
        }
        dest.setReadable(true, false);
        dest.setWritable(true, true);
    }

    private void extractProjects() throws IOException {
        String[] projects = context.getAssets().list("projects");
        if (projects != null) {
            for (String project : projects) {
                String assetPath = "projects/" + project;
                File target = new File(projectsDir, project);
                if (!target.exists()) {
                    extractAsset(assetPath, target);
                }
            }
        }
    }

    private String getVersionFromBinary() {
        try {
            String binaryPath = resolveBinaryPath();
            if (binaryPath == null) return null;

            ProcessBuilder pb = new ProcessBuilder(binaryPath, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            p.destroy();

            return line != null ? line.trim() : null;
        } catch (Exception e) {
            Log.w(TAG, "getVersionFromBinary failed", e);
            return null;
        }
    }

    private File resolveFile(String name) {
        File file = new File(name);
        if (file.isAbsolute() && file.exists()) return file;

        file = new File(projectsDir, name);
        if (file.exists()) return file;

        if (!name.endsWith(".b")) {
            file = new File(projectsDir, name + ".b");
            if (file.exists()) return file;
        }

        return new File(projectsDir, name);
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    // ──────────────────────────────────────────────────────────────
    // Inner classes & interfaces
    // ──────────────────────────────────────────────────────────────

    public interface InstallListener {
        void onProgress(String message);
        void onSuccess(String version);
        void onError(String message);
    }

    public static class BantuFile {
        private final File file;

        public BantuFile(File file) {
            this.file = file;
        }

        public String getName() { return file.getName(); }
        public String getPath() { return file.getAbsolutePath(); }
        public long getSize() { return file.length(); }
        public long getLastModified() { return file.lastModified(); }
        public File getFile() { return file; }

        public String getSizeFormatted() {
            long size = file.length();
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        }
    }
}
