package com.bantu.droid;

import android.util.Log;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages a running Bantu process — reads output, sends input,
 * and provides lifecycle control (start/stop/restart).
 *
 * Usage:
 *   BantuProcess proc = engine.run("server.b");
 *   proc.readOutput(new BantuProcess.OutputListener() {
 *       void onOutput(String line) { ... }
 *       void onError(String line)  { ... }
 *       void onExit(int code)      { ... }
 *   });
 *   proc.sendInput("hello\n");
 *   proc.stop();
 */
public class BantuProcess {

    private static final String TAG = "BantuProcess";

    private final Process process;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final ExecutorService outputExecutor;
    private final ExecutorService errorExecutor;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final String command;
    private long startTime;
    private OutputListener listener;

    public BantuProcess(Process process, String command) {
        this.process = process;
        this.command = command;
        this.startTime = System.currentTimeMillis();
        this.reader = new BufferedReader(
            new InputStreamReader(process.getInputStream()));
        this.writer = new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream()));
        this.outputExecutor = Executors.newSingleThreadExecutor();
        this.errorExecutor = Executors.newSingleThreadExecutor();
    }

    // ──────────────────────────────────────────────────────────────
    // Output reading
    // ──────────────────────────────────────────────────────────────

    public interface OutputListener {
        /** Called for each line of output from the process */
        void onOutput(String line);
        /** Called for each line of stderr (if not merged) */
        void onError(String line);
        /** Called when the process exits */
        void onExit(int exitCode);
    }

    /**
     * Start reading output asynchronously. The listener callbacks
     * are called on a background thread — use runOnUiThread() for UI updates.
     */
    public void readOutput(OutputListener listener) {
        this.listener = listener;
        outputExecutor.submit(this::readStdout);
    }

    /**
     * Start reading both stdout and stderr on separate threads.
     * Only use this if you did NOT set redirectErrorStream(true).
     */
    public void readOutputSeparate(OutputListener listener) {
        this.listener = listener;
        outputExecutor.submit(this::readStdout);
        errorExecutor.submit(this::readStderr);
    }

    private void readStdout() {
        try {
            String line;
            while ((line = reader.readLine()) != null && running.get()) {
                if (listener != null) {
                    listener.onOutput(line);
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                Log.w(TAG, "stdout read error: " + e.getMessage());
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        } finally {
            waitForExit();
        }
    }

    private void readStderr() {
        try {
            BufferedReader errReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()));
            String line;
            while ((line = errReader.readLine()) != null && running.get()) {
                if (listener != null) {
                    listener.onError(line);
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                Log.w(TAG, "stderr read error: " + e.getMessage());
            }
        }
    }

    private void waitForExit() {
        try {
            int exitCode = process.waitFor();
            running.set(false);
            if (listener != null) {
                listener.onExit(exitCode);
            }
            Log.i(TAG, "Process exited with code " + exitCode +
                " (ran for " + getUptime() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            cleanup();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Input
    // ──────────────────────────────────────────────────────────────

    /**
     * Send input to the running process.
     * Useful for interactive programs or sending commands.
     */
    public void sendInput(String input) throws IOException {
        if (!running.get() || !process.isAlive()) {
            throw new IOException("Process is not running");
        }
        writer.write(input);
        if (!input.endsWith("\n")) {
            writer.newLine();
        }
        writer.flush();
    }

    /**
     * Send a line of input (automatically appends newline).
     */
    public void sendLine(String line) throws IOException {
        sendInput(line + "\n");
    }

    // ──────────────────────────────────────────────────────────────
    // Lifecycle control
    // ──────────────────────────────────────────────────────────────

    /**
     * Gracefully stop the process:
     * 1. Close stdin (signals EOF to the process)
     * 2. Wait briefly for graceful exit
     * 3. Force destroy if still alive
     */
    public void stop() {
        if (!running.getAndSet(false)) return;

        Log.i(TAG, "Stopping process: " + command);

        try {
            writer.close();
        } catch (IOException ignored) {}

        try {
            if (process.isAlive()) {
                // Try SIGTERM first (graceful)
                process.destroy();

                // Wait up to 2 seconds for graceful exit
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    // Force SIGKILL
                    process.destroyForcibly();
                    Log.w(TAG, "Force-killed process: " + command);
                }
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }

        cleanup();
    }

    /**
     * Restart the process with the same command.
     * Not implemented here — the caller should use BantuEngine.run() again.
     */
    public boolean isRunning() {
        return running.get() && process.isAlive();
    }

    // ──────────────────────────────────────────────────────────────
    // Status info
    // ──────────────────────────────────────────────────────────────

    public String getCommand() {
        return command;
    }

    public long getStartTime() {
        return startTime;
    }

    /**
     * Get how long the process has been running (in seconds).
     */
    public long getUptime() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }

    /**
     * Get formatted uptime string (e.g., "2h 15m 30s").
     */
    public String getUptimeFormatted() {
        long seconds = getUptime();
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (hours > 0) return String.format("%dh %dm %ds", hours, minutes, secs);
        if (minutes > 0) return String.format("%dm %ds", minutes, secs);
        return String.format("%ds", secs);
    }

    /**
     * Get the process ID (best-effort, may not work on all Android versions).
     */
    public long getPid() {
        try {
            // Use reflection for compatibility with API 24+
            java.lang.reflect.Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            return pidField.getInt(process);
        } catch (Exception e) {
            return -1;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Cleanup
    // ──────────────────────────────────────────────────────────────

    private void cleanup() {
        try { reader.close(); } catch (IOException ignored) {}
        try { writer.close(); } catch (IOException ignored) {}
        outputExecutor.shutdownNow();
        errorExecutor.shutdownNow();
    }
}
