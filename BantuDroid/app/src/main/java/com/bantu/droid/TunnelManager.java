package com.bantu.droid;

import android.content.Context;
import android.util.Log;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.SocketFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TunnelManager - Central manager for all tunneling methods in BantuDroid.
 *
 * Supports three tunnel modes:
 * 1. DIRECT - Public IP direct access (UPnP port forwarding)
 * 2. SSH    - SSH reverse tunnels (Pinggy, Serveo, localhost.run)
 * 3. CLOUDFLARE - Cloudflare Tunnel via Termux cloudflared
 *
 * The DIRECT mode is preferred when the user has a public IP
 * and their router supports UPnP. It provides the fastest and
 * most reliable connection without depending on third-party servers.
 *
 * SSH mode is a fallback when direct access isn't possible.
 * Cloudflare mode provides another alternative with CDN benefits.
 */
public class TunnelManager {

    private static final String TAG = "TunnelManager";

    // Tunnel modes
    public static final String MODE_DIRECT = "direct";
    public static final String MODE_SSH = "ssh";
    public static final String MODE_CLOUDFLARE = "cloudflare";

    // SSH providers
    public static final String SSH_PINGGY = "pinggy";
    public static final String SSH_SERVEO = "serveo";
    public static final String SSH_LOCALHOST_RUN = "localhost.run";

    // State
    private String activeMode = null;
    private String activeSshProvider = null;
    private boolean tunnelActive = false;
    private String publicUrl = null;
    private String publicIp = null;
    private int localPort = 8080;
    private int externalPort = 8080;

    // UPnP
    private UpnpPortMapper upnpMapper;

    // SSH session
    private Session sshSession;
    private Channel sshChannel;
    private Thread sshThread;

    // Cloudflare process
    private Process cloudflaredProcess;
    private Thread cfThread;

    // Context
    private final Context context;

    // Listener
    private TunnelListener listener;

    public interface TunnelListener {
        void onTunnelStarted(String mode, String url);
        void onTunnelStopped(String mode);
        void onTunnelError(String mode, String error);
        void onTunnelProgress(String mode, String message);
        void onPublicIpDetected(String ip, boolean behindNat, boolean cgnat);
    }

    public TunnelManager(Context context) {
        this.context = context;
    }

    public void setListener(TunnelListener listener) {
        this.listener = listener;
    }

    public void setLocalPort(int port) {
        this.localPort = port;
    }

    public void setExternalPort(int port) {
        this.externalPort = port;
    }

    // ──────────────────────────────────────────────────────────────
    // Public IP Detection
    // ──────────────────────────────────────────────────────────────

    /**
     * Detect the device's public IP and NAT status.
     * Must be called on a background thread.
     */
    public void detectPublicIp() {
        new Thread(() -> {
            try {
                String localIp = UpnpPortMapper.getLocalIpAddress();
                String pubIp = UpnpPortMapper.fetchPublicIpFromWeb();

                if (pubIp == null) {
                    if (listener != null) {
                        listener.onTunnelError(MODE_DIRECT, "Cannot detect public IP. Check internet connection.");
                    }
                    return;
                }

                this.publicIp = pubIp;
                boolean behindNat = UpnpPortMapper.isBehindNat(localIp, pubIp);
                boolean cgnat = UpnpPortMapper.isCgnat(localIp, pubIp);

                Log.i(TAG, "IP detection: local=" + localIp + " public=" + pubIp +
                    " behindNAT=" + behindNat + " CGNAT=" + cgnat);

                if (listener != null) {
                    listener.onPublicIpDetected(pubIp, behindNat, cgnat);
                }

            } catch (Exception e) {
                Log.e(TAG, "Public IP detection failed", e);
                if (listener != null) {
                    listener.onTunnelError(MODE_DIRECT, "IP detection failed: " + e.getMessage());
                }
            }
        }).start();
    }

    // ──────────────────────────────────────────────────────────────
    // DIRECT MODE - Public IP + UPnP Port Forwarding
    // ──────────────────────────────────────────────────────────────

    /**
     * Start direct public IP access using UPnP port forwarding.
     * Must be called on a background thread.
     */
    public void startDirectTunnel() {
        if (tunnelActive) {
            stopTunnel();
        }

        activeMode = MODE_DIRECT;
        new Thread(() -> {
            try {
                if (upnpMapper == null) {
                    upnpMapper = new UpnpPortMapper();
                }

                upnpMapper.addPortMapping(localPort, externalPort, new UpnpPortMapper.UpnpCallback() {
                    @Override
                    public void onSuccess(String externalIp, int extPort, String url) {
                        tunnelActive = true;
                        publicUrl = url;
                        publicIp = externalIp;
                        Log.i(TAG, "Direct tunnel active: " + url);
                        if (listener != null) {
                            listener.onTunnelStarted(MODE_DIRECT, url);
                        }
                    }

                    @Override
                    public void onFailure(String error) {
                        Log.e(TAG, "Direct tunnel failed: " + error);
                        if (listener != null) {
                            listener.onTunnelError(MODE_DIRECT, error);
                        }
                    }

                    @Override
                    public void onProgress(String message) {
                        Log.d(TAG, "UPnP: " + message);
                        if (listener != null) {
                            listener.onTunnelProgress(MODE_DIRECT, message);
                        }
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Direct tunnel error", e);
                if (listener != null) {
                    listener.onTunnelError(MODE_DIRECT, e.getMessage());
                }
            }
        }).start();
    }

    // ──────────────────────────────────────────────────────────────
    // SSH TUNNEL MODE
    // ──────────────────────────────────────────────────────────────

    /**
     * Start an SSH reverse tunnel via a provider.
     * Must be called on a background thread.
     */
    public void startSshTunnel(String provider) {
        if (tunnelActive) {
            stopTunnel();
        }

        activeMode = MODE_SSH;
        activeSshProvider = provider;

        String host;
        int port;

        switch (provider) {
            case SSH_PINGGY:
                host = "a.pinggy.io";
                port = 443;
                break;
            case SSH_SERVEO:
                host = "serveo.net";
                port = 22;
                break;
            case SSH_LOCALHOST_RUN:
                host = "localhost.run";
                port = 22;
                break;
            default:
                if (listener != null) {
                    listener.onTunnelError(MODE_SSH, "Unknown SSH provider: " + provider);
                }
                return;
        }

        sshThread = new Thread(() -> startSshTunnelInternal(host, port, provider));
        sshThread.setDaemon(true);
        sshThread.start();
    }

    private void startSshTunnelInternal(String host, int port, String provider) {
        try {
            if (listener != null) {
                listener.onTunnelProgress(MODE_SSH, "Connecting to " + host + "...");
            }

            // Resolve hostname to IP first (avoids Android DNS issues)
            String resolvedIp = null;
            try {
                InetAddress[] addrs = InetAddress.getAllByName(host);
                if (addrs.length > 0) {
                    // Prefer IPv4
                    for (InetAddress addr : addrs) {
                        if (addr instanceof Inet4Address) {
                            resolvedIp = addr.getHostAddress();
                            break;
                        }
                    }
                    if (resolvedIp == null) {
                        resolvedIp = addrs[0].getHostAddress();
                    }
                }
            } catch (UnknownHostException e) {
                String err = "Cannot resolve " + host + ": " + e.getMessage();
                Log.e(TAG, err);
                if (listener != null) {
                    listener.onTunnelError(MODE_SSH, err + ". Check DNS/internet connection.");
                }
                return;
            }

            final String finalResolvedIp = resolvedIp;
            Log.i(TAG, "Resolved " + host + " → " + finalResolvedIp);

            JSch jsch = new JSch();
            sshSession = jsch.getSession("tunnel", host, port);

            // Use ResolvedIpSocketFactory to connect via resolved IP
            // while keeping original hostname for SSH protocol
            sshSession.setSocketFactory(new ResolvedIpSocketFactory(finalResolvedIp));

            // No password/key needed for these providers
            sshSession.setConfig("StrictHostKeyChecking", "no");
            sshSession.setConfig("UserKnownHostsFile", "/dev/null");

            // Keep-alive: send SSH keepalive every 15 seconds
            sshSession.setConfig("ServerAliveInterval", "15");
            sshSession.setConfig("ServerAliveCountMax", "4");

            // Connection timeout
            sshSession.setTimeout(30000);

            if (listener != null) {
                listener.onTunnelProgress(MODE_SSH, "Authenticating...");
            }

            sshSession.connect(30000);
            Log.i(TAG, "SSH session connected to " + host);

            // Build the remote port forwarding command based on provider
            String command = buildSshCommand(provider, localPort);
            Log.i(TAG, "SSH command: " + command);

            if (listener != null) {
                listener.onTunnelProgress(MODE_SSH, "Setting up tunnel...");
            }

            sshChannel = sshSession.openChannel("exec");
            ((ChannelExec) sshChannel).setCommand(command);
            ((ChannelExec) sshChannel).setErrStream(System.err);

            InputStream in = sshChannel.getInputStream();
            InputStream err = sshChannel.getExtInputStream();
            sshChannel.connect(15000);

            Log.i(TAG, "SSH channel connected, reading output...");

            // Read output to find the assigned URL
            StringBuilder output = new StringBuilder();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + 30000; // 30s to get URL

            while (System.currentTimeMillis() < deadline) {
                while (in.available() > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, in.available()));
                    if (n < 0) break;
                    String chunk = new String(buf, 0, n);
                    output.append(chunk);
                    Log.d(TAG, "SSH stdout: " + chunk.trim());

                    // Try to extract URL from output
                    String url = extractTunnelUrl(output.toString(), provider);
                    if (url != null) {
                        tunnelActive = true;
                        publicUrl = url;
                        Log.i(TAG, "SSH tunnel URL: " + url);
                        if (listener != null) {
                            listener.onTunnelStarted(MODE_SSH, url);
                        }
                        return; // Don't exit the reading loop - keep alive
                    }
                }

                while (err.available() > 0) {
                    int n = err.read(buf, 0, Math.min(buf.length, err.available()));
                    if (n < 0) break;
                    String chunk = new String(buf, 0, n);
                    output.append(chunk);
                    Log.d(TAG, "SSH stderr: " + chunk.trim());

                    String url = extractTunnelUrl(output.toString(), provider);
                    if (url != null) {
                        tunnelActive = true;
                        publicUrl = url;
                        Log.i(TAG, "SSH tunnel URL: " + url);
                        if (listener != null) {
                            listener.onTunnelStarted(MODE_SSH, url);
                        }
                        return;
                    }
                }

                if (sshChannel.isClosed()) {
                    int exitCode = sshChannel.getExitStatus();
                    Log.w(TAG, "SSH channel closed with exit code: " + exitCode);
                    String outputStr = output.toString();
                    if (!tunnelActive) {
                        if (listener != null) {
                            listener.onTunnelError(MODE_SSH,
                                "SSH tunnel closed unexpectedly (code " + exitCode + "). " +
                                "Output: " + outputStr.substring(0, Math.min(500, outputStr.length())));
                        }
                    }
                    break;
                }

                Thread.sleep(500);
            }

            // If we get here without finding a URL
            if (!tunnelActive && listener != null) {
                String outputStr = output.toString();
                listener.onTunnelError(MODE_SSH,
                    "Could not get tunnel URL. Provider may be down. Output: " +
                    outputStr.substring(0, Math.min(300, outputStr.length())));
            }

        } catch (Exception e) {
            Log.e(TAG, "SSH tunnel failed", e);
            if (listener != null) {
                listener.onTunnelError(MODE_SSH, "SSH tunnel error: " + e.getMessage());
            }
        }
    }

    private String buildSshCommand(String provider, int localPort) {
        switch (provider) {
            case SSH_PINGGY:
                // Pinggy: use -R for reverse tunnel, -p 443 for port
                // The XXXX is auto-assigned by Pinggy
                return "pinggy -p 443 -R 0:localhost:" + localPort +
                    " -o StrictHostKeyChecking=no -o ServerAliveInterval=15";

            case SSH_SERVEO:
                // Serveo: -R 0:localhost:PORT auto-assigns a port
                return "sh -c \"echo '" + localPort + "'\"";

            case SSH_LOCALHOST_RUN:
                // localhost.run: just connect, it auto-assigns
                return "";

            default:
                return "";
        }
    }

    /**
     * Extract the public tunnel URL from SSH provider output.
     */
    private String extractTunnelUrl(String output, String provider) {
        if (output == null || output.isEmpty()) return null;

        // Common URL patterns for tunnel providers
        Pattern[] patterns = {
            // Pinggy: https://rntp-xx-xx.a.pinggy.io
            Pattern.compile("https://[a-zA-Z0-9\\-]+\\.a\\.pinggy\\.io"),
            // Pinggy: https://xxxx.a.pinggy.io
            Pattern.compile("https://[a-zA-Z0-9]+\\.pinggy\\.link"),
            // Serveo: https://xxxx.serveo.net
            Pattern.compile("https://[a-zA-Z0-9]+\\.serveo\\.net"),
            // localhost.run: https://xxxx.localhost.run
            Pattern.compile("https://[a-zA-Z0-9\\-]+\\.localhost\\.run"),
            // Generic: any https://xxx.tunnel.xxx or https://xxx.xxx.io/net
            Pattern.compile("https://[a-zA-Z0-9\\-]+\\.[a-zA-Z0-9\\-]+\\.(io|net|com|run|link|dev|org)"),
            // Port-based: just show the URL with port
            Pattern.compile("http://[a-zA-Z0-9\\-\\.]+:\\d+"),
        };

        for (Pattern p : patterns) {
            Matcher m = p.matcher(output);
            if (m.find()) {
                return m.group();
            }
        }

        // For Serveo, also check for "Forwarding HTTP traffic from"
        Pattern serveoForward = Pattern.compile("Forwarding HTTP traffic from (https?://[^\\s]+)");
        Matcher m = serveoForward.matcher(output);
        if (m.find()) {
            return m.group(1);
        }

        // For localhost.run, check " tunneled with tls termination"
        Pattern lhrPattern = Pattern.compile("(https://[a-zA-Z0-9\\-]+\\.localhost\\.run)");
        m = lhrPattern.matcher(output);
        if (m.find()) {
            return m.group(1);
        }

        return null;
    }

    // ──────────────────────────────────────────────────────────────
    // CLOUDFLARE TUNNEL MODE
    // ──────────────────────────────────────────────────────────────

    /**
     * Start a Cloudflare tunnel using cloudflared from Termux.
     */
    public void startCloudflareTunnel() {
        if (tunnelActive) {
            stopTunnel();
        }

        activeMode = MODE_CLOUDFLARE;

        // Find cloudflared binary in Termux
        String cloudflaredPath = findCloudflaredBinary();
        if (cloudflaredPath == null) {
            if (listener != null) {
                listener.onTunnelError(MODE_CLOUDFLARE,
                    "cloudflared not found. Install it in Termux:\n" +
                    "  pkg install cloudflared\n" +
                    "Or: wget https://github.com/cloudflare/cloudflared/releases/...\n" +
                    "Then retry.");
            }
            return;
        }

        cfThread = new Thread(() -> {
            try {
                if (listener != null) {
                    listener.onTunnelProgress(MODE_CLOUDFLARE, "Starting cloudflared...");
                }

                ProcessBuilder pb = new ProcessBuilder(
                    cloudflaredPath, "tunnel", "--url",
                    "http://localhost:" + localPort
                );
                pb.redirectErrorStream(true);
                cloudflaredProcess = pb.start();

                InputStream is = cloudflaredProcess.getInputStream();
                StringBuilder output = new StringBuilder();
                byte[] buf = new byte[4096];
                long deadline = System.currentTimeMillis() + 45000; // 45s timeout

                while (System.currentTimeMillis() < deadline) {
                    while (is.available() > 0) {
                        int n = is.read(buf);
                        if (n < 0) break;
                        String chunk = new String(buf, 0, n);
                        output.append(chunk);
                        Log.d(TAG, "cloudflared: " + chunk.trim());

                        // Extract the tunnel URL
                        Pattern urlPattern = Pattern.compile("https://[a-zA-Z0-9\\-]+\\.trycloudflare\\.com");
                        Matcher m = urlPattern.matcher(output.toString());
                        if (m.find()) {
                            tunnelActive = true;
                            publicUrl = m.group();
                            Log.i(TAG, "Cloudflare tunnel URL: " + publicUrl);
                            if (listener != null) {
                                listener.onTunnelStarted(MODE_CLOUDFLARE, publicUrl);
                            }
                            // Keep reading to keep process alive
                            keepReadingCloudflared(is);
                            return;
                        }
                    }

                    try {
                        int exitCode = cloudflaredProcess.exitValue();
                        String out = output.toString();
                        if (listener != null) {
                            listener.onTunnelError(MODE_CLOUDFLARE,
                                "cloudflared exited (" + exitCode + "). Output: " +
                                out.substring(0, Math.min(500, out.length())));
                        }
                        return;
                    } catch (IllegalThreadStateException ignored) {
                        // Process still running, good
                    }

                    Thread.sleep(500);
                }

                // Timeout
                if (!tunnelActive && listener != null) {
                    listener.onTunnelError(MODE_CLOUDFLARE,
                        "Cloudflare tunnel timed out. cloudflared may need updating.");
                }

            } catch (Exception e) {
                Log.e(TAG, "Cloudflare tunnel failed", e);
                if (listener != null) {
                    listener.onTunnelError(MODE_CLOUDFLARE, "Error: " + e.getMessage());
                }
            }
        });
        cfThread.setDaemon(true);
        cfThread.start();
    }

    private void keepReadingCloudflared(InputStream is) {
        // Keep reading cloudflared output in background to prevent pipe blocking
        new Thread(() -> {
            try {
                byte[] buf = new byte[4096];
                while (true) {
                    if (is.available() > 0) {
                        int n = is.read(buf);
                        if (n < 0) break;
                    } else {
                        Thread.sleep(1000);
                    }
                    // Check if process is still alive
                    try {
                        cloudflaredProcess.exitValue();
                        break; // Process exited
                    } catch (IllegalThreadStateException ignored) {}
                }
            } catch (Exception ignored) {}
        }, "cf-reader").start();
    }

    /**
     * Find the cloudflared binary in Termux paths.
     */
    private String findCloudflaredBinary() {
        String[] paths = {
            "/data/data/com.termux/files/usr/bin/cloudflared",
            "/data/data/com.termux/files/usr/bin/cloudflared-beta",
        };

        for (String path : paths) {
            java.io.File f = new java.io.File(path);
            if (f.exists() && f.canExecute()) {
                Log.i(TAG, "Found cloudflared at: " + path);
                return path;
            }
        }

        // Try finding via PATH
        try {
            Process p = new ProcessBuilder("which", "cloudflared").start();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[256];
            int n = is.read(buf);
            if (n > 0) {
                String result = new String(buf, 0, n).trim();
                if (!result.isEmpty() && new java.io.File(result).exists()) {
                    return result;
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    // ──────────────────────────────────────────────────────────────
    // STOP / CLEANUP
    // ──────────────────────────────────────────────────────────────

    /**
     * Stop the active tunnel, whatever mode it is.
     */
    public void stopTunnel() {
        String prevMode = activeMode;
        Log.i(TAG, "Stopping tunnel (mode=" + prevMode + ")");

        // Stop UPnP
        if (upnpMapper != null && upnpMapper.isMappingActive()) {
            upnpMapper.removePortMapping();
            upnpMapper = null;
        }

        // Stop SSH
        if (sshChannel != null) {
            try {
                sshChannel.disconnect();
            } catch (Exception ignored) {}
            sshChannel = null;
        }
        if (sshSession != null) {
            try {
                sshSession.disconnect();
            } catch (Exception ignored) {}
            sshSession = null;
        }
        if (sshThread != null) {
            sshThread.interrupt();
            sshThread = null;
        }

        // Stop Cloudflare
        if (cloudflaredProcess != null) {
            try {
                cloudflaredProcess.destroy();
            } catch (Exception ignored) {}
            cloudflaredProcess = null;
        }
        if (cfThread != null) {
            cfThread.interrupt();
            cfThread = null;
        }

        tunnelActive = false;
        publicUrl = null;
        activeMode = null;
        activeSshProvider = null;

        if (prevMode != null && listener != null) {
            listener.onTunnelStopped(prevMode);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // State getters
    // ──────────────────────────────────────────────────────────────

    public boolean isTunnelActive() {
        return tunnelActive;
    }

    public String getActiveMode() {
        return activeMode;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public String getPublicIp() {
        return publicIp;
    }

    public int getLocalPort() {
        return localPort;
    }

    public int getExternalPort() {
        return externalPort;
    }

    // ──────────────────────────────────────────────────────────────
    // ResolvedIpSocketFactory - Connect via resolved IP for JSch
    // ──────────────────────────────────────────────────────────────

    /**
     * SocketFactory that connects via a pre-resolved IP address.
     * This avoids Android DNS resolution issues (UnknownHostException)
     * while allowing JSch to use the original hostname for SSH protocol.
     *
     * How it works:
     * - JSch calls createSocket(host, port) where host is the original hostname
     * - We ignore the host parameter and connect via the pre-resolved IP
     * - The SSH handshake still uses the original hostname for host key verification
     *   (but we've disabled strict host key checking)
     */
    private static class ResolvedIpSocketFactory implements SocketFactory {
        private final String resolvedIp;

        ResolvedIpSocketFactory(String resolvedIp) {
            this.resolvedIp = resolvedIp;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(resolvedIp, port), 30000);
            socket.setKeepAlive(true);
            socket.setSoTimeout(60000);
            return socket;
        }

        @Override
        public java.io.InputStream getInputStream(Socket socket) throws IOException {
            return socket.getInputStream();
        }

        @Override
        public java.io.OutputStream getOutputStream(Socket socket) throws IOException {
            return socket.getOutputStream();
        }
    }
}
