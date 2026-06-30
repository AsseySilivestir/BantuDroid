package com.bantu.droid;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Manages SSH reverse tunnels and Cloudflare tunnel processes.
 *
 * SSH Tunnel providers:
 *   - Pinggy (a.pinggy.io)
 *   - Serveo (serveo.net)
 *   - localhost.run (localhost.run)
 *
 * Uses ResolvedIpSocketFactory to handle DNS issues where
 * the SSH hostname can't be resolved but its IP works.
 */
public class TunnelManager {

    public interface TunnelCallback {
        void onMessage(String msg);
        void onError(String err);
        void onConnected(String url);
        void onDisconnected();
    }

    // SSH tunnel providers
    public static final String[][] SSH_PROVIDERS = {
        {"Pinggy", "a.pinggy.io", "443", "pinggy"},
        {"Serveo", "serveo.net", "443", "serveo"},
        {"localhost.run", "localhost.run", "443", "localhostrun"}
    };

    private Session sshSession;
    private Thread sshThread;
    private volatile boolean sshRunning = false;
    private String sshUrl;

    private Process cloudflaredProcess;
    private Thread cfThread;
    private volatile boolean cfRunning = false;
    private String cfUrl;

    /**
     * Start SSH reverse tunnel.
     * @param providerIndex index into SSH_PROVIDERS
     * @param localPort the local server port to expose
     */
    public void startSshTunnel(int providerIndex, int localPort, TunnelCallback cb) {
        if (sshRunning) {
            cb.onError("SSH tunnel already running");
            return;
        }

        String[] provider = SSH_PROVIDERS[providerIndex];
        String name = provider[0];
        String host = provider[1];
        int port = Integer.parseInt(provider[2]);

        sshThread = new Thread(() -> {
            try {
                cb.onMessage("Connecting to " + name + " (" + host + ")...");

                JSch jsch = new JSch();
                jsch.setKnownHosts("/dev/null");

                // Try to resolve DNS first, use IP with hostname for SNI
                String resolvedIp = resolveHost(host);
                String connectHost = (resolvedIp != null) ? resolvedIp : host;

                // Create session - use empty password (these services accept any)
                sshSession = jsch.getSession("", connectHost, port);
                sshSession.setPassword("");
                sshSession.setConfig("StrictHostKeyChecking", "no");
                sshSession.setConfig("UserKnownHostsFile", "/dev/null");
                sshSession.setConfig("PreferredAuthentications", "password");
                sshSession.setConfig("server_alive_interval", "15");
                sshSession.setConfig("server_alive_count_max", "3");

                // Set the real hostname if we're connecting via IP
                if (resolvedIp != null && !resolvedIp.equals(host)) {
                    sshSession.setHost(host);
                    sshSession.setConfig("hostname", host);
                }

                sshSession.connect(30000);
                sshRunning = true;

                cb.onMessage("SSH session established");

                // Set up reverse port forwarding
                // setPortForwardingR returns void in newer JSch — we request port 0
                // and the actual port is assigned by the server
                sshSession.setPortForwardingR(0, "127.0.0.1", localPort);
                cb.onMessage("Reverse port forwarding set up: R:0 -> localhost:" + localPort);

                // Read the URL from the SSH banner
                // The URL is typically shown in the initial output
                InputStream in = null;
                try {
                    // Execute a simple command to get the URL info
                    Channel channel = sshSession.openChannel("exec");
                    ((ChannelExec) channel).setCommand("true");
                    channel.setInputStream(null);
                    InputStream cin = channel.getInputStream();
                    channel.connect(5000);

                    BufferedReader reader = new BufferedReader(new InputStreamReader(cin));
                    String line;
                    StringBuilder banner = new StringBuilder();
                    long deadline = System.currentTimeMillis() + 5000;
                    while (System.currentTimeMillis() < deadline) {
                        if (reader.ready()) {
                            line = reader.readLine();
                            if (line != null) {
                                banner.append(line).append("\n");
                                if (line.contains("http")) {
                                    sshUrl = extractUrl(line);
                                }
                            }
                        } else {
                            Thread.sleep(100);
                        }
                    }
                    channel.disconnect();
                } catch (Exception ignored) {}

                // Construct the URL based on provider
                if (sshUrl == null) {
                    // We don't know the actual remote port, so show a generic message
                    sshUrl = "Connected via " + name + " — check provider dashboard for URL";
                }

                cb.onConnected(sshUrl);
                cb.onMessage("Tunnel URL: " + sshUrl);

                // Keep the session alive
                while (sshRunning && sshSession.isConnected()) {
                    Thread.sleep(1000);
                }

            } catch (JSchException e) {
                if (sshRunning) {
                    cb.onError("SSH error: " + e.getMessage());
                }
            } catch (Exception e) {
                if (sshRunning) {
                    cb.onError("Tunnel error: " + e.getMessage());
                }
            } finally {
                sshRunning = false;
                disconnectSsh();
                cb.onDisconnected();
            }
        }, "ssh-tunnel");
        sshThread.setDaemon(true);
        sshThread.start();
    }

    /**
     * Stop the SSH tunnel.
     */
    public void stopSshTunnel() {
        sshRunning = false;
        disconnectSsh();
    }

    /**
     * Start Cloudflare quick tunnel via cloudflared binary.
     */
    public void startCloudflareTunnel(int localPort, TunnelCallback cb) {
        if (cfRunning) {
            cb.onError("Cloudflare tunnel already running");
            return;
        }

        cfThread = new Thread(() -> {
            try {
                // Find cloudflared binary
                String cloudflared = findCloudflared();
                if (cloudflared == null) {
                    cb.onError("cloudflared binary not found. Install Termux and run: pkg install cloudflared");
                    return;
                }

                cb.onMessage("Starting cloudflared...");

                ProcessBuilder pb = new ProcessBuilder(
                    cloudflared, "tunnel", "--url", "http://localhost:" + localPort);
                pb.redirectErrorStream(true);
                cloudflaredProcess = pb.start();

                cfRunning = true;

                // Read output for the URL
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(cloudflaredProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    cb.onMessage(line);
                    if (line.contains("https://") && line.contains("trycloudflare.com")) {
                        cfUrl = extractUrl(line);
                        if (cfUrl != null) {
                            cb.onConnected(cfUrl);
                        }
                    }
                }

                int exit = cloudflaredProcess.waitFor();
                if (cfRunning) {
                    cb.onMessage("cloudflared exited with code " + exit);
                }

            } catch (Exception e) {
                if (cfRunning) {
                    cb.onError("Cloudflare error: " + e.getMessage());
                }
            } finally {
                cfRunning = false;
                cfUrl = null;
                cb.onDisconnected();
            }
        }, "cf-tunnel");
        cfThread.setDaemon(true);
        cfThread.start();
    }

    /**
     * Stop the Cloudflare tunnel.
     */
    public void stopCloudflareTunnel() {
        cfRunning = false;
        if (cloudflaredProcess != null) {
            cloudflaredProcess.destroy();
            cloudflaredProcess = null;
        }
        cfUrl = null;
    }

    public boolean isSshRunning() { return sshRunning; }
    public boolean isCfRunning() { return cfRunning; }
    public String getSshUrl() { return sshUrl; }
    public String getCfUrl() { return cfUrl; }

    // ──────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────

    private void disconnectSsh() {
        try {
            if (sshSession != null && sshSession.isConnected()) {
                sshSession.disconnect();
            }
        } catch (Exception ignored) {}
        sshSession = null;
        sshUrl = null;
    }

    private String resolveHost(String hostname) {
        try {
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(hostname);
            if (addrs.length > 0) return addrs[0].getHostAddress();
        } catch (Exception ignored) {}
        return null;
    }

    private String extractUrl(String text) {
        // Find HTTPS URL in text
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("https://[a-zA-Z0-9._-]+\\.[a-z]{2,}(?:[:/][^\\s]*)?");
        java.util.regex.Matcher m = p.matcher(text);
        if (m.find()) return m.group();
        return null;
    }

    private String findCloudflared() {
        // Check common locations
        String[] paths = {
            "/data/data/com.termux/files/usr/bin/cloudflared",
            "/usr/bin/cloudflared",
            "/system/bin/cloudflared"
        };
        for (String path : paths) {
            java.io.File f = new java.io.File(path);
            if (f.exists() && f.canExecute()) return path;
        }

        // Try to find via PATH
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"which", "cloudflared"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            if (line != null && !line.isEmpty()) return line.trim();
        } catch (Exception ignored) {}

        return null;
    }
}
