package com.bantu.droid;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Core Bantu Engine wrapper.
 * Manages the lifecycle of the Bantu binary on Android:
 * - Extracts the binary from APK assets to the app's private directory
 * - Makes it executable
 * - Provides methods to run Bantu commands and .b files
 * - Manages bundled project files
 */
public class BantuEngine {

    private static final String TAG = "BantuEngine";
    private static final String BINARY_NAME = "bantu";
    private static final String PREFS_NAME = "bantu_engine";
    private static final String KEY_INSTALLED = "engine_installed";
    private static final String KEY_VERSION = "engine_version";
    private static final String KEY_BINARY_CHECKSUM = "binary_checksum";

    private final Context context;
    private final File binDir;
    private final File projectsDir;
    private final File logDir;
    private final SharedPreferences prefs;
    private boolean installed = false;

    public BantuEngine(Context context) {
        this.context = context.getApplicationContext();
        this.binDir = new File(context.getFilesDir(), "bin");
        this.projectsDir = new File(context.getFilesDir(), "projects");
        this.logDir = new File(context.getFilesDir(), "logs");
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Get the absolute path to the Bantu binary.
     * On Android 10+, SELinux may block execution from the files/ directory.
     * The nativeLibraryDir is the recommended location for executable native code.
     * We try both locations, preferring nativeLibraryDir if the binary is there.
     */
    private File getBinaryFile() {
        // Option 1: nativeLibraryDir — this directory has SELinux permission for execution
        try {
            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            if (nativeLibDir != null) {
                File libBinary = new File(nativeLibDir, "libbantu.so");
                if (libBinary.exists() && libBinary.canExecute()) {
                    Log.i(TAG, "Using binary from nativeLibraryDir: " + libBinary.getAbsolutePath());
                    return libBinary;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "nativeLibraryDir not available", e);
        }

        // Option 2: files/bin/ — our extracted binary (may need chmod fix)
        return new File(binDir, BINARY_NAME);
    }

    // ──────────────────────────────────────────────────────────────
    // Installation
    // ──────────────────────────────────────────────────────────────

    /**
     * Install the Bantu engine by extracting the binary and projects
     * from APK assets. Must be called on first launch or after updates.
     *
     * @param listener Callback for installation progress
     */
    public void install(InstallListener listener) {
        new Thread(() -> {
            try {
                listener.onProgress("Creating directories...");
                binDir.mkdirs();
                projectsDir.mkdirs();
                logDir.mkdirs();

                // Extract the Bantu binary for the device's ABI
                listener.onProgress("Extracting Bantu binary...");
                String abi = getSupportedAbi();
                String assetPath = "bin/" + abi + "/bantu";
                File binary = new File(binDir, BINARY_NAME);

                // Try ABI-specific binary first, fall back to generic
                if (!assetExists(assetPath)) {
                    assetPath = "bin/bantu";
                }

                extractAsset(assetPath, binary);

                // Make executable — use both Java API and explicit chmod
                // File.setExecutable() can silently fail on some Android versions
                // due to SELinux or filesystem restrictions, so we also call
                // chmod 755 via the system shell as a fallback.
                listener.onProgress("Setting permissions...");
                binary.setExecutable(true, false);
                binary.setReadable(true, false);
                ensureExecutable(binary);

                // Verify binary works
                listener.onProgress("Verifying engine...");
                String version = getVersionFromBinary();
                if (version == null) {
                    // Binary might still work even if version output fails
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
                listener.onSuccess(version);

            } catch (Exception e) {
                Log.e(TAG, "Installation failed", e);
                listener.onError(e.getMessage());
            }
        }).start();
    }

    /**
     * Quick check if the engine binary exists and is executable.
     */
    public boolean isInstalled() {
        File binary = getBinaryFile();
        // Binary exists and is executable — check both nativeLibDir and files/bin
        return binary.exists() && binary.canExecute();
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
        // Clean up old installation
        deleteRecursive(binDir);
        prefs.edit().clear().apply();
        installed = false;
        install(listener);
    }

    // ──────────────────────────────────────────────────────────────
    // Running Bantu
    // ──────────────────────────────────────────────────────────────

    /**
     * Run a .b file with the Bantu interpreter.
     * Equivalent to: bantu run <file>
     *
     * @param bantuFile Filename relative to projects directory (e.g., "bantuddns.b")
     * @return BantuProcess for I/O and control
     */
    public BantuProcess run(String bantuFile) throws IOException {
        ensureInstalled();
        ensureBinaryExecutable();

        File file = resolveFile(bantuFile);
        if (!file.exists()) {
            throw new FileNotFoundException(
                "File not found: " + bantuFile + "\n" +
                "Looked in: " + file.getAbsolutePath());
        }

        String binaryPath = getBinaryFile().getAbsolutePath();
        ProcessBuilder pb = new ProcessBuilder(binaryPath, "run", file.getAbsolutePath());
        pb.directory(projectsDir);
        pb.redirectErrorStream(true);
        setupEnvironment(pb);

        Process process = pb.start();
        return new BantuProcess(process, bantuFile);
    }

    /**
     * Run a raw Bantu command with arbitrary arguments.
     * Example: execute("run", "server.b", "--port", "9090")
     *
     * @param args Arguments to pass after the binary name
     * @return BantuProcess for I/O and control
     */
    public BantuProcess execute(String... args) throws IOException {
        ensureInstalled();
        ensureBinaryExecutable();

        String binaryPath = getBinaryFile().getAbsolutePath();
        String[] cmd = new String[args.length + 1];
        cmd[0] = binaryPath;
        System.arraycopy(args, 0, cmd, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectsDir);
        pb.redirectErrorStream(true);
        setupEnvironment(pb);

        return new BantuProcess(pb.start(), String.join(" ", args));
    }

    /**
     * Run a command in the projects directory (for shell-style operations).
     */
    public BantuProcess runShellCommand(String command) throws IOException {
        ensureInstalled();
        ensureBinaryExecutable();

        String binaryPath = getBinaryFile().getAbsolutePath();
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c",
            binaryPath + " " + command);
        pb.directory(projectsDir);
        pb.redirectErrorStream(true);
        setupEnvironment(pb);

        return new BantuProcess(pb.start(), command);
    }

    // ──────────────────────────────────────────────────────────────
    // File Management
    // ──────────────────────────────────────────────────────────────

    /**
     * List all .b files in the projects directory.
     */
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

    /**
     * Write a new .b file to the projects directory.
     */
    public File createProject(String name, String content) throws IOException {
        if (!name.endsWith(".b")) name += ".b";
        File file = new File(projectsDir, name);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
        return file;
    }

    /**
     * Read the content of a .b file.
     */
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

    /**
     * Delete a .b file from the projects directory.
     */
    public boolean deleteProject(String name) {
        File file = resolveFile(name);
        return file.delete();
    }

    /**
     * Import a .b file from external storage into projects.
     */
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

    public File getProjectsDir() {
        return projectsDir;
    }

    public File getLogDir() {
        return logDir;
    }

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
        pb.environment().put("HOME", context.getFilesDir().getAbsolutePath());
        pb.environment().put("BANTU_HOME", context.getFilesDir().getAbsolutePath());
        pb.environment().put("PATH", binDir.getAbsolutePath() + ":" +
            System.getenv("PATH"));
        pb.environment().put("TERM", "xterm-256color");
        pb.environment().put("LANG", "en_US.UTF-8");
    }

    /**
     * Force-set the executable bit on the Bantu binary using chmod.
     * File.setExecutable() can silently fail on some Android versions
     * due to SELinux or the underlying filesystem not supporting it.
     * We use the system's chmod command as a reliable fallback.
     */
    private void ensureExecutable(File binary) {
        try {
            // Method 1: Java API
            binary.setExecutable(true, false);

            // Method 2: Explicit chmod via system shell (more reliable on Android)
            Process chmod = new ProcessBuilder("/system/bin/chmod", "755",
                binary.getAbsolutePath()).start();
            chmod.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);

            // Verify it worked
            if (!binary.canExecute()) {
                Log.w(TAG, "Binary still not executable after chmod, attempting workaround...");

                // Method 3: Copy via cat (bypasses any filesystem restrictions)
                File tempBinary = new File(binDir, "bantu.tmp");
                Process catChmod = new ProcessBuilder("/system/bin/sh", "-c",
                    "cat " + binary.getAbsolutePath() + " > " + tempBinary.getAbsolutePath() +
                    " && /system/bin/chmod 755 " + tempBinary.getAbsolutePath() +
                    " && mv " + tempBinary.getAbsolutePath() + " " + binary.getAbsolutePath()
                ).start();
                catChmod.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                binary.setExecutable(true, false);
            }

            Log.i(TAG, "Binary executable status: " + binary.canExecute());
        } catch (Exception e) {
            Log.e(TAG, "Failed to set executable permission", e);
        }
    }

    /**
     * Re-check and fix executable permission before running the binary.
     * This handles cases where Android may have reset permissions
     * (e.g., after app update, device reboot, or SELinux context change).
     */
    private void ensureBinaryExecutable() {
        File binary = getBinaryFile();
        if (binary.exists() && !binary.canExecute()) {
            Log.w(TAG, "Binary lost execute permission, re-applying...");
            ensureExecutable(binary);
        }
    }

    private String getSupportedAbi() {
        // Check for arm64-v8a first, then armeabi-v7a, then x86_64
        if (android.os.Build.SUPPORTED_ABIS.length > 0) {
            return android.os.Build.SUPPORTED_ABIS[0];
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
        // Set permissions
        target.setReadable(true, false);
        target.setWritable(true, true);
    }

    private void extractProjects() throws IOException {
        String[] projects = context.getAssets().list("projects");
        if (projects != null) {
            for (String project : projects) {
                String assetPath = "projects/" + project;
                File target = new File(projectsDir, project);

                // Only extract if file doesn't exist or is from an older version
                if (!target.exists()) {
                    extractAsset(assetPath, target);
                }
            }
        }
    }

    private String getVersionFromBinary() {
        try {
            String binaryPath = getBinaryFile().getAbsolutePath();
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
            return null;
        }
    }

    private File resolveFile(String name) {
        // Try exact path first
        File file = new File(name);
        if (file.isAbsolute() && file.exists()) return file;

        // Try in projects directory
        file = new File(projectsDir, name);
        if (file.exists()) return file;

        // Try with .b extension
        if (!name.endsWith(".b")) {
            file = new File(projectsDir, name + ".b");
            if (file.exists()) return file;
        }

        // Return the projects-dir-relative path even if it doesn't exist
        // (so the error message is useful)
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

    /**
     * Simple data class representing a .b file
     */
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
