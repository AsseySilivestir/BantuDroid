package com.bantu.droid;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * BusyboxExecutor — manages a downloaded busybox binary and runs shell
 * commands through it. Provides curl, wget, tree, grep, sed, awk, find,
 * tar, head, tail, wc, sort, uniq, and 300+ other utilities.
 *
 * Downloads busybox from github.com/meefik/busybox/releases on first use
 * (~2MB, arm64). Falls back to /system/bin/sh while downloading.
 */
public class BusyboxExecutor {

    private static final String TAG = "BusyboxExecutor";
    private static final String DOWNLOAD_BASE = "https://github.com/meefik/busybox/releases/download/1.36.1/";

    private final Context appContext;
    private volatile File busyboxBinary = null;
    private volatile boolean initAttempted = false;

    public BusyboxExecutor(Context context) {
        this.appContext = context.getApplicationContext();
        File cached = getCachedBinary();
        if (cached != null) busyboxBinary = cached;
    }

    public boolean isReady() { return busyboxBinary != null && busyboxBinary.exists(); }
    public String getBinaryPath() { return isReady() ? busyboxBinary.getAbsolutePath() : null; }

    public synchronized boolean ensureReady(ProgressListener listener) {
        if (isReady()) return true;
        if (initAttempted) return false;
        initAttempted = true;
        try {
            String arch = Build.SUPPORTED_ABIS[0];
            String downloadFile;
            if (arch.contains("arm64") || arch.contains("aarch64")) downloadFile = "busybox-android-arm64";
            else if (arch.contains("arm")) downloadFile = "busybox-android-arm";
            else if (arch.contains("x86_64")) downloadFile = "busybox-android-x86_64";
            else if (arch.contains("x86")) downloadFile = "busybox-android-x86";
            else downloadFile = "busybox-android-arm64";

            String downloadUrl = DOWNLOAD_BASE + downloadFile;
            File target = new File(appContext.getFilesDir(), "busybox");
            if (listener != null) listener.onMessage("Downloading busybox for " + arch + "...");

            URL url = new URL(downloadUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            conn.setInstanceFollowRedirects(true);
            int status = conn.getResponseCode();
            if (status == 302 || status == 301) {
                String redirectUrl = conn.getHeaderField("Location");
                if (redirectUrl != null) {
                    conn.disconnect();
                    conn = (HttpURLConnection) new URL(redirectUrl).openConnection();
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(120000);
                }
            }
            long fileSize = conn.getContentLengthLong();
            try (InputStream is = conn.getInputStream(); FileOutputStream os = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int len; long total = 0; int lastPercent = -1;
                while ((len = is.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                    total += len;
                    if (fileSize > 0 && listener != null) {
                        int percent = (int) ((total * 100) / fileSize);
                        if (percent != lastPercent) { lastPercent = percent; listener.onProgress(percent); }
                    }
                }
            }
            conn.disconnect();
            target.setExecutable(true, false);
            try { new ProcessBuilder("/system/bin/chmod", "755", target.getAbsolutePath()).start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
            if (!validateElf(target)) { target.delete(); if (listener != null) listener.onError("Downloaded busybox is corrupted"); return false; }
            busyboxBinary = target;
            if (listener != null) listener.onMessage("Busybox ready");
            return true;
        } catch (Exception e) {
            if (listener != null) listener.onError("Failed: " + e.getMessage());
            return false;
        }
    }

    private File getCachedBinary() {
        String[] candidates = {
            new File(appContext.getFilesDir(), "busybox").getAbsolutePath(),
            new File(appContext.getCodeCacheDir(), "busybox").getAbsolutePath(),
            "/data/local/tmp/busybox",
        };
        for (String path : candidates) {
            File f = new File(path);
            if (f.exists() && f.canRead() && f.length() > 100_000 && validateElf(f)) return f;
        }
        return null;
    }

    private boolean validateElf(File f) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] header = new byte[4];
            int n = fis.read(header);
            return n >= 4 && header[0] == 0x7F && header[1] == 'E' && header[2] == 'L' && header[3] == 'F';
        } catch (Exception e) { return false; }
    }

    public ExecResult exec(String command, File cwd) {
        ExecResult result = new ExecResult();
        if (command == null || command.trim().isEmpty()) { result.exitCode = 0; return result; }
        Process process = null;
        try {
            ProcessBuilder pb;
            if (isReady()) {
                String bbPath = busyboxBinary.getAbsolutePath();
                String bbDir = busyboxBinary.getParent();
                pb = new ProcessBuilder(bbPath, "sh", "-c", command);
                pb.environment().put("PATH", bbDir + ":/system/bin:/system/xbin:/vendor/bin");
                pb.environment().put("BUSYBOX", bbPath);
            } else {
                pb = new ProcessBuilder("/system/bin/sh", "-c", command);
                pb.environment().put("PATH", "/system/bin:/system/xbin:/vendor/bin");
            }
            pb.directory(cwd != null ? cwd : appContext.getFilesDir());
            pb.redirectErrorStream(false);
            final Process p = pb.start();
            process = p;
            final StringBuilder outBuf = new StringBuilder();
            final StringBuilder errBuf = new StringBuilder();
            Thread outReader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line; while ((line = r.readLine()) != null) outBuf.append(line).append('\n');
                } catch (Exception ignored) {}
            });
            Thread errReader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                    String line; while ((line = r.readLine()) != null) errBuf.append(line).append('\n');
                } catch (Exception ignored) {}
            });
            outReader.start(); errReader.start();
            boolean finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.exitCode = -1; result.stderr = "Command timed out after 60s\n"; return result;
            }
            outReader.join(2000); errReader.join(2000);
            result.stdout = outBuf.toString();
            result.stderr = errBuf.toString();
            result.exitCode = process.exitValue();
            return result;
        } catch (Exception e) {
            result.exitCode = -1; result.stderr = "exec error: " + e.getMessage() + "\n";
            return result;
        } finally {
            if (process != null) process.destroy();
        }
    }

    public List<String> listApplets() {
        List<String> applets = new ArrayList<>();
        if (!isReady()) return applets;
        try {
            Process p = new ProcessBuilder(busyboxBinary.getAbsolutePath(), "--list").start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line; while ((line = r.readLine()) != null) applets.add(line.trim());
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return applets;
    }

    public interface ProgressListener {
        void onProgress(int percent);
        void onMessage(String msg);
        void onError(String err);
    }

    public static class ExecResult {
        public String stdout = "";
        public String stderr = "";
        public int exitCode = 0;
        public boolean isSuccess() { return exitCode == 0; }
        public String combined() { return stdout + (stderr.isEmpty() ? "" : stderr); }
    }
}
