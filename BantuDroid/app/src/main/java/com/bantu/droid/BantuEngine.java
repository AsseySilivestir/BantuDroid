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
 *   -> chmod 755 does NOT help, kernel blocks exec() at VFS level
 * - /data/app/.../lib/ (nativeLibraryDir) IS executable
 *   -> Android extracts lib/*.so from APK here with correct SELinux context
 * - We ship libbantu.so via jniLibs/ -> it lands in nativeLibraryDir
 * - android:extractNativeLibs="true" in manifest + useLegacyPackaging in gradle
 *   ensures .so files are actually extracted to disk (not kept in APK)
 * - We use direct ProcessBuilder execution (NOT /system/bin/sh -c)
 * - As fallback, we use JNI BantuBridge which does fork()+execv() from native code
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

    /** Cached binary path -- resolved once, reused for all executions */
    private String cachedBinaryPath = null;

    /** JNI bridge for fork+execv fallback */
    private BantuBridge bridge;

    /** Static app context for BantuBridge */
    private static Context appContext;

    public BantuEngine(Context context) {
        this.context = context.getApplicationContext();
        appContext = this.context;
        this.binDir = new File(context.getFilesDir(), "bin");
        this.projectsDir = new File(context.getFilesDir(), "projects");
        this.logDir = new File(context.getFilesDir(), "logs");
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Initialize JNI bridge
        BantuBridge.init();
        this.bridge = new BantuBridge();
    }

    /** Get app context (used by BantuBridge) */
    public static Context getAppContext() {
        return appContext;
    }

    // -----------------------------------------------------------------
    // Binary Path Resolution (the critical fix)
    // -----------------------------------------------------------------

    /**
     * Find the absolute path to the Bantu binary.
     *
     * Strategy (in order of reliability):
     * 1. nativeLibraryDir/libbantu.so -- Android-extracted, SELinux allows exec
     * 2. files/bin/bantu -- our manually extracted copy (needs chmod, may be noexec)
     * 3. codeCacheDir/bantu -- alternative writable location (may allow exec)
     *
     * We do NOT require canExecute() because Java's File.canExecute() uses
     * access(X_OK) which may return false due to SELinux even when the file
     * IS actually executable via exec(). We just check exists() and try chmod.
     */
    private String resolveBinaryPath() {
        if (cachedBinaryPath != null && new File(cachedBinaryPath).exists()) {
            return cachedBinaryPath;
        }

        Log.i(TAG, "=== Resolving Bantu binary path ===");

        // -- Strategy 1: nativeLibraryDir (BEST -- always executable on Android) --
        try {
            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            if (nativeLibDir != null) {
                File libBinary = new File(nativeLibDir, "libbantu.so");
                Log.i(TAG, "Checking nativeLibraryDir: " + libBinary.getAbsolutePath());
                Log.i(TAG, "  exists=" + libBinary.exists() + " canRead=" + libBinary.canRead() + " canExecute=" + libBinary.canExecute());

                if (libBinary.exists()) {
                    Log.i(TAG, "FOUND binary in nativeLibraryDir: " + libBinary.getAbsolutePath());
                    chmodBinary(libBinary);
                    cachedBinaryPath = libBinary.getAbsolutePath();
                    return cachedBinaryPath;
                } else {
                    Log.w(TAG, "libbantu.so NOT found in nativeLibraryDir: " + nativeLibDir);
                    // List what IS in nativeLibraryDir for debugging
                    File libDir = new File(nativeLibDir);
                    if (libDir.exists() && libDir.isDirectory()) {
                        String[] contents = libDir.list();
                        if (contents != null && contents.length > 0) {
                            Log.i(TAG, "nativeLibraryDir contents: " + String.join(", ", contents));
                        } else {
                            Log.w(TAG, "nativeLibraryDir is EMPTY! extractNativeLibs may be false");
                        }
                    } else {
                        Log.w(TAG, "nativeLibraryDir directory does not exist: " + nativeLibDir);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "nativeLibraryDir check failed", e);
        }

        // -- Strategy 2: files/bin/bantu with chmod --
        File filesBinary = new File(binDir, BINARY_NAME);
        if (filesBinary.exists()) {
            Log.i(TAG, "Found binary in files/bin: " + filesBinary.getAbsolutePath());
            chmodBinary(filesBinary);
            cachedBinaryPath = filesBinary.getAbsolutePath();
            return cachedBinaryPath;
        }

        // -- Strategy 3: codeCacheDir --
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

        // -- No binary found anywhere --
        Log.e(TAG, "Bantu binary not found in any location!");
        return null;
    }

    /**
     * Run chmod 755 on the binary file. This is a best-effort operation.
     */
    private void chmodBinary(File binary) {
        try {
            // Java API
            boolean setExec = binary.setExecutable(true, false);
            boolean setRead = binary.setReadable(true, false);
            Log.i(TAG, "Java setExecutable(" + binary.getAbsolutePath() + ") = " + setExec);

            // Shell chmod
            Process p = new ProcessBuilder("/system/bin/chmod", "755",
                binary.getAbsolutePath()).start();
            int chmodResult = p.waitFor();
            Log.i(TAG, "chmod 755 on " + binary.getAbsolutePath() +
                " -> exit=" + chmodResult + ", canExecute=" + binary.canExecute());
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

    // -----------------------------------------------------------------
    // Installation
    // -----------------------------------------------------------------

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
                    Log.i(TAG, "Binary already in nativeLibraryDir -- no extraction needed");
                    chmodBinary(libBinary);
                    listener.onProgress("Binary ready in nativeLibraryDir");
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

                    // Also try copying to codeCacheDir
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

    public boolean isInstalled() {
        if (resolveBinaryPath() == null) return false;
        // Also check that at least one .b project file exists
        // (projects may not have been extracted if binary was found in nativeLibraryDir)
        if (projectsDir.isDirectory()) {
            File[] files = projectsDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".b")) return true;
                }
            }
        }
        // Binary exists but no projects — not fully installed
        Log.w(TAG, "Binary found but no .b project files — will re-extract projects");
        return false;
    }

    public String getInstalledVersion() {
        return prefs.getString(KEY_VERSION, "unknown");
    }

    public void reinstall(InstallListener listener) {
        deleteRecursive(binDir);
        prefs.edit().clear().apply();
        installed = false;
        cachedBinaryPath = null;
        install(listener);
    }

    // -----------------------------------------------------------------
    // Running Bantu — uses JNI bridge or direct ProcessBuilder
    // -----------------------------------------------------------------

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

        // Try JNI bridge first (more reliable on MIUI/Xiaomi), then ProcessBuilder
        return executeBinary(binaryPath, new String[]{"run", file.getAbsolutePath()});
    }

    /**
     * Run a raw Bantu command with arbitrary arguments.
     * Example: execute("run", "server.b", "--port", "9090")
     */
    public BantuProcess execute(String... args) throws IOException {
        ensureInstalled();

        String binaryPath = requireBinaryPath();
        Log.i(TAG, "Executing: " + binaryPath + " " + String.join(" ", args));

        return executeBinary(binaryPath, args);
    }

    /**
     * Run a command in the projects directory (for terminal-style operations).
     * Parses "bantu <args>" and executes directly via ProcessBuilder or JNI bridge.
     * NEVER uses /system/bin/sh -c (causes Permission denied on Android 10+).
     */
    public BantuProcess runShellCommand(String command) throws IOException {
        ensureInstalled();

        String binaryPath = requireBinaryPath();
        String trimmed = command.trim();

        // Parse "bantu <args>" or just "<args>"
        if (trimmed.startsWith("bantu ")) {
            trimmed = trimmed.substring("bantu ".length()).trim();
        } else if (trimmed.equals("bantu")) {
            trimmed = "--help";
        }

        // Split args respecting quoted strings
        List<String> argList = parseArgs(trimmed);
        String[] args = argList.toArray(new String[0]);

        Log.i(TAG, "Shell command: " + binaryPath + " " + String.join(" ", args));

        return executeBinary(binaryPath, args);
    }

    /**
     * Core execution method. Tries ProcessBuilder first (handles output correctly),
     * then JNI bridge as fallback for strict SELinux devices.
     * Both approaches execute the binary DIRECTLY (no /system/bin/sh wrapper).
     */
    private BantuProcess executeBinary(String binaryPath, String[] args) throws IOException {
        // PRIMARY: Direct ProcessBuilder execution (handles output streaming correctly)
        // This works now that extractNativeLibs=true ensures libbantu.so is
        // extracted to nativeLibraryDir with correct SELinux context.
        try {
            return executeViaProcessBuilder(binaryPath, args);
        } catch (IOException e) {
            Log.w(TAG, "ProcessBuilder failed: " + e.getMessage());

            // FALLBACK: Try JNI bridge (fork+execv bypasses some SELinux restrictions)
            if (BantuBridge.isJniAvailable()) {
                try {
                    Log.i(TAG, "Trying JNI bridge fallback...");
                    return bridge.execute(binaryPath, args, projectsDir.getAbsolutePath());
                } catch (IOException e2) {
                    Log.e(TAG, "JNI bridge also failed: " + e2.getMessage());
                }
            }

            // LAST RESORT: Try a different binary location
            String fallback = getFallbackBinaryPath(binaryPath);
            if (fallback != null) {
                Log.i(TAG, "Trying fallback binary path: " + fallback);
                try {
                    return executeViaProcessBuilder(fallback, args);
                } catch (IOException e3) {
                    Log.e(TAG, "Fallback also failed: " + e3.getMessage());
                }
            }

            throw new IOException(
                "Cannot execute Bantu binary.\n" +
                "Path tried: " + binaryPath + "\n" +
                "Error: " + e.getMessage() + "\n\n" +
                "This may be caused by Android security restrictions (SELinux).\n" +
                "Please try: Settings > Apps > BantuDroid > Storage > Clear Data, then reopen.");
        }
    }

    /**
     * Execute via ProcessBuilder with direct binary path.
     * NO /system/bin/sh wrapper — that's what causes Permission denied.
     */
    private BantuProcess executeViaProcessBuilder(String binaryPath, String[] args) throws IOException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = binaryPath;
        System.arraycopy(args, 0, cmd, 1, args.length);

        Log.i(TAG, "ProcessBuilder: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectsDir);
        pb.redirectErrorStream(true);
        setupEnvironment(pb);

        Process process = pb.start();
        return new BantuProcess(process, String.join(" ", args));
    }

    /**
     * If the current binary path is from one strategy, return the path from another.
     */
    private String getFallbackBinaryPath(String currentPath) {
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;

        if (currentPath.contains("lib") && currentPath.endsWith("libbantu.so")) {
            // Current is nativeLibraryDir, try files/bin
            File fallback = new File(binDir, BINARY_NAME);
            if (fallback.exists() && !fallback.getAbsolutePath().equals(currentPath)) {
                return fallback.getAbsolutePath();
            }
        } else {
            // Current is files/bin or elsewhere, try nativeLibraryDir
            if (nativeLibDir != null) {
                File fallback = new File(nativeLibDir, "libbantu.so");
                if (fallback.exists() && !fallback.getAbsolutePath().equals(currentPath)) {
                    return fallback.getAbsolutePath();
                }
            }
        }

        return null;
    }

    /**
     * Simple argument parser that handles quoted strings.
     */
    private List<String> parseArgs(String args) {
        List<String> result = new ArrayList<>();
        if (args == null || args.isEmpty()) return result;

        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (Character.isWhitespace(c) && !inSingleQuote && !inDoubleQuote) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }

        return result;
    }

    // -----------------------------------------------------------------
    // File Management
    // -----------------------------------------------------------------

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

    // -----------------------------------------------------------------
    // Workspace & Project Management (v2.2.2)
    // -----------------------------------------------------------------

    /**
     * Get the Bantu workspace root directory.
     * This is where `bantu init` creates projects.
     */
    public File getWorkspaceRoot() {
        return context.getFilesDir();
    }

    /**
     * Execute a Bantu command in a specific working directory.
     * Critical for `bantu run` to work inside a project directory.
     */
    public BantuProcess executeInDir(File workingDir, String... args) throws IOException {
        ensureInstalled();
        String binaryPath = requireBinaryPath();
        Log.i(TAG, "Executing in " + workingDir.getAbsolutePath() + ": " + binaryPath + " " + String.join(" ", args));
        return executeBinaryInDir(binaryPath, workingDir, args);
    }

    /**
     * Execute a raw shell-style bantu command in a specific directory.
     */
    public BantuProcess runShellCommandInDir(File workingDir, String command) throws IOException {
        ensureInstalled();
        String binaryPath = requireBinaryPath();
        String trimmed = command.trim();

        if (trimmed.startsWith("bantu ")) {
            trimmed = trimmed.substring("bantu ".length()).trim();
        } else if (trimmed.equals("bantu")) {
            trimmed = "--help";
        }

        List<String> argList = parseArgs(trimmed);
        String[] args = argList.toArray(new String[0]);

        Log.i(TAG, "Shell command in " + workingDir.getAbsolutePath() + ": " + binaryPath + " " + String.join(" ", args));
        return executeBinaryInDir(binaryPath, workingDir, args);
    }

    private BantuProcess executeBinaryInDir(String binaryPath, File workingDir, String[] args) throws IOException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = binaryPath;
        System.arraycopy(args, 0, cmd, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workingDir);
        pb.redirectErrorStream(true);
        setupEnvironment(pb);

        Process process = pb.start();
        return new BantuProcess(process, String.join(" ", args));
    }

    /**
     * Scan the workspace for all Bantu projects.
     */
    public List<BantuProject> scanProjects() {
        List<BantuProject> projects = new ArrayList<>();
        File workspace = getWorkspaceRoot();

        // Scan workspace root for project directories
        File[] dirs = workspace.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (d.isDirectory() && !d.getName().equals("bin") && !d.getName().equals("logs") && !d.getName().equals("projects") && !d.getName().equals("code_cache") && !d.getName().equals("shared_prefs") && !d.getName().equals("cache")) {
                    boolean isProject = new File(d, "bantu.json").exists();
                    if (!isProject) {
                        File[] files = d.listFiles();
                        if (files != null) {
                            for (File f : files) {
                                if (f.getName().endsWith(".b")) {
                                    isProject = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (isProject) {
                        projects.add(new BantuProject(d));
                    }
                }
            }
        }

        // Also scan projects dir for standalone .b files
        File[] bFiles = projectsDir.listFiles();
        if (bFiles != null) {
            for (File f : bFiles) {
                if (f.getName().endsWith(".b")) {
                    projects.add(new BantuProject(f));
                }
            }
        }

        return projects;
    }

    /**
     * Create a new project directory.
     */
    public File initProjectDir(String name) {
        File projectDir = new File(getWorkspaceRoot(), name);
        projectDir.mkdirs();
        return projectDir;
    }

    /**
     * Represents a Bantu project (directory with bantu.json or .b files).
     */
    public static class BantuProject {
        private final File dir;
        private final boolean isDirectory;

        public BantuProject(File dir) {
            this.dir = dir;
            this.isDirectory = dir.isDirectory();
        }

        public String getName() { return dir.getName(); }
        public String getPath() { return dir.getAbsolutePath(); }
        public File getDir() { return dir; }
        public boolean isDirectoryProject() { return isDirectory; }
        public long getLastModified() { return dir.lastModified(); }

        public boolean hasBantuJson() {
            return new File(dir, "bantu.json").exists();
        }

        public String getMainFile() {
            File main = new File(dir, "main.b");
            if (main.exists()) return main.getAbsolutePath();
            if (dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().endsWith(".b")) return f.getAbsolutePath();
                    }
                }
            }
            return null;
        }

        public List<File> listAllFiles() {
            List<File> result = new ArrayList<>();
            if (dir.isDirectory()) {
                listRecursive(dir, result);
            } else {
                result.add(dir);
            }
            return result;
        }

        private void listRecursive(File d, List<File> result) {
            File[] children = d.listFiles();
            if (children == null) return;
            for (File f : children) {
                result.add(f);
                if (f.isDirectory()) {
                    listRecursive(f, result);
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

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
        pb.environment().put("PATH", binaryDir + ":" + System.getenv("PATH"));
        pb.environment().put("TERM", "xterm-256color");
        pb.environment().put("LANG", "en_US.UTF-8");

        // Set LD_LIBRARY_PATH so the bantu binary can find system shared libs
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        if (nativeLibDir != null) {
            String existing = System.getenv("LD_LIBRARY_PATH");
            if (existing != null && !existing.isEmpty()) {
                pb.environment().put("LD_LIBRARY_PATH", nativeLibDir + ":" + existing);
            } else {
                pb.environment().put("LD_LIBRARY_PATH", nativeLibDir);
            }
        }
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

    // -----------------------------------------------------------------
    // Inner classes & interfaces
    // -----------------------------------------------------------------

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
