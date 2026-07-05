package com.bantu.droid;

import android.util.Log;

import android.os.ParcelFileDescriptor;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JNI Bridge for executing the Bantu binary on Android.
 *
 * This class provides two execution strategies:
 *
 * 1. JNI fork+execv (PRIMARY): Uses native fork()+execv() to bypass
 *    Android's SELinux restrictions on Java ProcessBuilder.exec().
 *    This works because the native code runs in a different SELinux context.
 *
 * 2. Java ProcessBuilder (FALLBACK): Direct Java execution, works when
 *    the binary is in nativeLibraryDir and the device allows exec.
 *
 * Why we need the JNI approach:
 * - On Android 10+ (especially Xiaomi/MIUI devices), SELinux W^X policy
 *   blocks exec() from app-writable directories
 * - Java's ProcessBuilder internally uses fork()+exec() through the JVM,
 *   which is subject to these restrictions
 * - Native fork()+execv() from a loaded .so has different SELinux context
 *   that allows exec() from nativeLibraryDir
 */
public class BantuBridge {

    private static final String TAG = "BantuBridge";
    private static AtomicBoolean libraryLoaded = new AtomicBoolean(false);
    private static boolean jniAvailable = false;

    /**
     * Load the JNI bridge library. Call this early (e.g., in Application.onCreate).
     */
    public static synchronized void init() {
        if (libraryLoaded.compareAndSet(false, true)) {
            try {
                System.loadLibrary("banturun");
                jniAvailable = true;
                Log.i(TAG, "JNI bridge library loaded successfully");
            } catch (UnsatisfiedLinkError e) {
                jniAvailable = false;
                Log.w(TAG, "JNI bridge library not available, will use ProcessBuilder fallback", e);
            }
        }
    }

    /**
     * Check if the JNI bridge is available.
     */
    public static boolean isJniAvailable() {
        return jniAvailable;
    }

    // Native method declarations
    private native int nativeExec(String binaryPath, String[] args, String workDir);
    private native int[] nativeForkExec(String binaryPath, String[] args, String workDir);
    private native int nativeCheckExecutable(String filePath);
    private native int nativeWaitForPid(int pid);

    /**
     * Execute ANY binary via JNI fork+execv and return a raw Process.
     * This is the static entry point used by TunnelManager to execute
     * external binaries (like cloudflared) that need to bypass SELinux.
     *
     * The returned Process (JniProcess) can be used exactly like a
     * regular Process — getInputStream(), getErrorStream(), waitFor(),
     * destroy() all work correctly.
     *
     * @param binaryPath  Absolute path to the binary to execute
     * @param args        Command-line arguments
     * @param workDir     Working directory for the process
     * @return a Process object (specifically JniProcess) for the running process
     * @throws IOException if JNI is not available or fork+exec fails
     */
    public static Process executeBinary(String binaryPath, String[] args, String workDir)
            throws IOException {
        if (!jniAvailable) {
            throw new IOException(
                "JNI bridge not available. Cannot execute external binary: " + binaryPath);
        }

        // Use a temporary BantuBridge instance to access the native method
        BantuBridge bridge = new BantuBridge();
        int[] result = bridge.nativeForkExec(binaryPath, args, workDir);

        if (result == null) {
            throw new IOException("JNI fork+exec failed for: " + binaryPath);
        }

        int pid = result[0];
        int stdoutFd = result[1];
        int stderrFd = result[2];

        Log.i(TAG, "executeBinary: pid=" + pid + " stdoutFd=" + stdoutFd + " stderrFd=" + stderrFd);

        ParcelFileDescriptor stdoutPfd = ParcelFileDescriptor.adoptFd(stdoutFd);
        ParcelFileDescriptor stderrPfd = ParcelFileDescriptor.adoptFd(stderrFd);
        FileInputStream stdoutStream = new FileInputStream(stdoutPfd.getFileDescriptor());
        FileInputStream stderrStream = new FileInputStream(stderrPfd.getFileDescriptor());

        return new JniProcess(pid, stdoutStream, stderrStream, stdoutPfd, stderrPfd);
    }

    /**
     * Execute the Bantu binary and return a BantuProcess for output streaming.
     *
     * @param binaryPath  Absolute path to the Bantu binary
     * @param args        Arguments to pass (e.g., ["run", "hello.b"])
     * @param workDir     Working directory
     * @return BantuProcess for reading output
     */
    public BantuProcess execute(String binaryPath, String[] args, String workDir) throws IOException {
        if (jniAvailable) {
            Log.i(TAG, "Using JNI fork+exec for: " + binaryPath);
            return executeViaJni(binaryPath, args, workDir);
        } else {
            Log.i(TAG, "Using ProcessBuilder fallback for: " + binaryPath);
            return executeViaProcessBuilder(binaryPath, args, workDir);
        }
    }

    /**
     * Check if the binary is executable.
     */
    public boolean isExecutable(String binaryPath) {
        if (jniAvailable) {
            int result = nativeCheckExecutable(binaryPath);
            Log.i(TAG, "JNI checkExecutable(" + binaryPath + ") = " + result);
            return result == 0;
        }
        // Fallback: Java check
        File f = new File(binaryPath);
        return f.canExecute();
    }

    /**
     * Execute via JNI fork+execv with output streaming.
     */
    private BantuProcess executeViaJni(String binaryPath, String[] args, String workDir) throws IOException {
        // Use nativeForkExec which gives us pipe file descriptors for streaming output
        int[] result = nativeForkExec(binaryPath, args, workDir);

        if (result == null) {
            throw new IOException("JNI fork+exec failed for: " + binaryPath);
        }

        int pid = result[0];
        int stdoutFd = result[1];
        int stderrFd = result[2];

        Log.i(TAG, "JNI fork+exec: pid=" + pid + " stdoutFd=" + stdoutFd + " stderrFd=" + stderrFd);

        // Create a Process-like wrapper using the file descriptors
        // Convert raw int fds to FileDescriptor via ParcelFileDescriptor.adoptFd()
        ParcelFileDescriptor stdoutPfd = ParcelFileDescriptor.adoptFd(stdoutFd);
        ParcelFileDescriptor stderrPfd = ParcelFileDescriptor.adoptFd(stderrFd);
        FileInputStream stdoutStream = new FileInputStream(stdoutPfd.getFileDescriptor());
        FileInputStream stderrStream = new FileInputStream(stderrPfd.getFileDescriptor());

        // Create a pseudo-process that wraps the JNI fork result
        JniProcess jniProcess = new JniProcess(pid, stdoutStream, stderrStream, stdoutPfd, stderrPfd);

        // Build a descriptive command string
        StringBuilder cmdStr = new StringBuilder(binaryPath);
        for (String arg : args) {
            cmdStr.append(" ").append(arg);
        }

        return new BantuProcess(jniProcess, cmdStr.toString());
    }

    /**
     * Execute via Java ProcessBuilder (fallback).
     */
    private BantuProcess executeViaProcessBuilder(String binaryPath, String[] args, String workDir) throws IOException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = binaryPath;
        System.arraycopy(args, 0, cmd, 1, args.length);

        Log.i(TAG, "ProcessBuilder: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);

        // Set up environment
        String nativeLibDir = getNativeLibDir();
        if (nativeLibDir != null) {
            String existing = System.getenv("LD_LIBRARY_PATH");
            if (existing != null && !existing.isEmpty()) {
                pb.environment().put("LD_LIBRARY_PATH", nativeLibDir + ":" + existing);
            } else {
                pb.environment().put("LD_LIBRARY_PATH", nativeLibDir);
            }
        }

        Process process = pb.start();

        StringBuilder cmdStr = new StringBuilder(binaryPath);
        for (String arg : args) {
            cmdStr.append(" ").append(arg);
        }

        return new BantuProcess(process, cmdStr.toString());
    }

    private String getNativeLibDir() {
        try {
            return BantuEngine.getAppContext().getApplicationInfo().nativeLibraryDir;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Wrapper around a JNI-forked process that implements the full Process API.
     *
     * This class is used both by BantuBridge (for Bantu execution) and by
     * TunnelManager (for cloudflared execution). It wraps the file descriptors
     * returned by nativeForkExec() into a Process-compatible interface.
     *
     * Important behavior:
     * - getInputStream() returns the merged stdout+stderr stream
     * - destroy() kills the process via SIGKILL
     * - waitFor() blocks until the stdout pipe closes (process exit)
     * - destroyForcibly() is supported
     */
    public static class JniProcess extends Process {
        private final int pid;
        private final InputStream stdout;
        private final InputStream stderr;
        private final InputStream combinedOutput;
        private final ParcelFileDescriptor stdoutPfd;
        private final ParcelFileDescriptor stderrPfd;
        private volatile boolean exited = false;
        private int exitCode = -1;

        public JniProcess(int pid, InputStream stdout, InputStream stderr,
                          ParcelFileDescriptor stdoutPfd, ParcelFileDescriptor stderrPfd) {
            this.pid = pid;
            this.stdout = stdout;
            this.stderr = stderr;
            // Since nativeForkExec now merges stderr into the stdout pipe,
            // both stdout and stderr are the same stream. Use stdout for all
            // output reading — it already contains stderr data.
            this.combinedOutput = stdout;
            this.stdoutPfd = stdoutPfd;
            this.stderrPfd = stderrPfd;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();  // stdin not supported in JNI mode
        }

        @Override
        public InputStream getInputStream() {
            return combinedOutput;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            // Use native waitpid() for a proper, reliable wait.
            // The old implementation tried to detect exit by reading from
            // the stdout pipe, which failed when the process writes only
            // to stderr or when another thread is already reading.
            try {
                BantuBridge bridge = new BantuBridge();
                int code = bridge.nativeWaitForPid(pid);
                exited = true;
                exitCode = (code >= 0) ? code : 0;
            } catch (Exception e) {
                // Fallback: drain stdout until EOF
                Log.w(TAG, "nativeWaitForPid failed, falling back to pipe drain", e);
                try {
                    while (stdout.read() != -1) { /* drain */ }
                } catch (IOException ignored) {}
                exited = true;
                exitCode = 0;
            }
            return exitCode;
        }

        @Override
        public int exitValue() {
            if (!exited) throw new IllegalThreadStateException("Process not yet exited");
            return exitCode;
        }

        @Override
        public void destroy() {
            if (!exited) {
                // Kill the process group first, then the process itself
                try {
                    Runtime.getRuntime().exec(new String[]{"kill", "-9", "-" + pid}).waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e1) {
                    try {
                        Runtime.getRuntime().exec(new String[]{"kill", "-9", String.valueOf(pid)}).waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception e2) {
                        Log.w(TAG, "Failed to kill process " + pid, e2);
                    }
                }
                exited = true;
            }
            closeStreams();
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return !exited;
        }

        public int getPid() {
            return pid;
        }

        private void closeStreams() {
            try { stdout.close(); } catch (Exception e) { /* ignore */ }
            try { stderr.close(); } catch (Exception e) { /* ignore */ }
            try { stdoutPfd.close(); } catch (Exception e) { /* ignore */ }
            try { stderrPfd.close(); } catch (Exception e) { /* ignore */ }
        }
    }
}
