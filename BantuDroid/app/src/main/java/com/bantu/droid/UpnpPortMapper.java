package com.bantu.droid;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight UPnP IGD client for automatic port forwarding.
 *
 * Discovers the router's IGD (Internet Gateway Device) via SSDP,
 * then uses SOAP AddPortMapping/DeletePortMapping to forward ports.
 * Also detects CGNAT (RFC 6598 range 100.64.0.0/10).
 *
 * No external dependencies — uses only java.net.
 */
public class UpnpPortMapper {

    public interface Callback {
        void onMessage(String msg);
        void onError(String err);
    }

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String SSDP_SEARCH =
        "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 3\r\n" +
        "\r\n";

    private String controlUrl;
    private String serviceType;
    private String externalIp;
    private String internalIp;
    private boolean mapped = false;
    private int mappedExternalPort;

    /**
     * Discover UPnP IGD on the local network.
     * Returns true if a router with IGD was found.
     */
    public boolean discover(Callback cb) {
        try {
            cb.onMessage("Searching for UPnP IGD...");

            // Get local IP
            internalIp = getLocalIp();
            if (internalIp == null) {
                cb.onError("No local IP found");
                return false;
            }
            cb.onMessage("Local IP: " + internalIp);

            // Send SSDP M-SEARCH
            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(5000);

            byte[] search = SSDP_SEARCH.getBytes();
            DatagramPacket packet = new DatagramPacket(
                search, search.length,
                InetAddress.getByName(SSDP_ADDR), SSDP_PORT);

            socket.send(packet);

            // Listen for responses
            byte[] buf = new byte[4096];
            DatagramPacket response = new DatagramPacket(buf, buf.length);

            String location = null;
            long deadline = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    socket.setSoTimeout((int)(deadline - System.currentTimeMillis()));
                    socket.receive(response);
                    String resp = new String(response.getData(), 0, response.getLength());
                    if (resp.contains("InternetGatewayDevice") || resp.contains("WANIPConnection") || resp.contains("WANPPPConnection")) {
                        // Extract LOCATION header
                        for (String line : resp.split("\r\n")) {
                            if (line.toLowerCase().startsWith("location:")) {
                                location = line.substring(9).trim();
                                break;
                            }
                        }
                        if (location != null) break;
                    }
                } catch (java.net.SocketTimeoutException e) {
                    break;
                }
            }
            socket.close();

            if (location == null) {
                cb.onError("No UPnP IGD found on network");
                return false;
            }

            cb.onMessage("Found IGD at: " + location);

            // Fetch description XML
            String descXml = httpGet(location);
            if (descXml == null) {
                cb.onError("Failed to fetch IGD description");
                return false;
            }

            // Parse control URL and service type
            parseDescription(descXml, location);

            if (controlUrl == null) {
                cb.onError("No WANIPConnection service found in IGD");
                return false;
            }

            cb.onMessage("Control URL: " + controlUrl);

            // Get external IP
            externalIp = getExternalIpFromUpnp();
            if (externalIp != null) {
                cb.onMessage("External IP (UPnP): " + externalIp);

                // Check for CGNAT
                if (isCgnat(externalIp)) {
                    cb.onMessage("WARNING: CGNAT detected! Public IP is in RFC 6598 range.");
                    cb.onMessage("UPnP may not work. Use SSH Tunnel or Cloudflare instead.");
                }
            }

            return true;

        } catch (Exception e) {
            cb.onError("Discovery error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Add a port mapping via UPnP.
     */
    public boolean addPortMapping(int internalPort, int externalPort,
                                   String protocol, String description, Callback cb) {
        if (controlUrl == null) {
            cb.onError("Not discovered. Run discover() first.");
            return false;
        }

        if (externalIp == null) {
            externalIp = getExternalIpFromUpnp();
        }

        try {
            String soapBody =
                "<?xml version=\"1.0\"?>\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                "  <s:Body>\n" +
                "    <u:AddPortMapping xmlns:u=\"" + serviceType + "\">\n" +
                "      <NewRemoteHost></NewRemoteHost>\n" +
                "      <NewExternalPort>" + externalPort + "</NewExternalPort>\n" +
                "      <NewProtocol>" + protocol + "</NewProtocol>\n" +
                "      <NewInternalPort>" + internalPort + "</NewInternalPort>\n" +
                "      <NewInternalClient>" + internalIp + "</NewInternalClient>\n" +
                "      <NewEnabled>1</NewEnabled>\n" +
                "      <NewPortMappingDescription>" + description + "</NewPortMappingDescription>\n" +
                "      <NewLeaseDuration>0</NewLeaseDuration>\n" +
                "    </u:AddPortMapping>\n" +
                "  </s:Body>\n" +
                "</s:Envelope>";

            String result = soapAction("AddPortMapping", soapBody);
            if (result != null && !result.contains("errorCode")) {
                mapped = true;
                mappedExternalPort = externalPort;
                cb.onMessage("Port forwarded: " + externalIp + ":" + externalPort +
                    " -> " + internalIp + ":" + internalPort + " (" + protocol + ")");
                return true;
            } else {
                cb.onError("AddPortMapping failed. Router may not support UPnP or CGNAT is active.");
                return false;
            }
        } catch (Exception e) {
            cb.onError("AddPortMapping error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Remove a port mapping via UPnP.
     */
    public boolean deletePortMapping(int externalPort, String protocol, Callback cb) {
        if (controlUrl == null) {
            cb.onError("Not discovered");
            return false;
        }

        try {
            String soapBody =
                "<?xml version=\"1.0\"?>\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                "  <s:Body>\n" +
                "    <u:DeletePortMapping xmlns:u=\"" + serviceType + "\">\n" +
                "      <NewRemoteHost></NewRemoteHost>\n" +
                "      <NewExternalPort>" + externalPort + "</NewExternalPort>\n" +
                "      <NewProtocol>" + protocol + "</NewProtocol>\n" +
                "    </u:DeletePortMapping>\n" +
                "  </s:Body>\n" +
                "</s:Envelope>";

            String result = soapAction("DeletePortMapping", soapBody);
            if (result != null) {
                mapped = false;
                cb.onMessage("Port mapping removed: " + externalPort + " (" + protocol + ")");
                return true;
            } else {
                cb.onError("DeletePortMapping failed");
                return false;
            }
        } catch (Exception e) {
            cb.onError("DeletePortMapping error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Detect public IP via web API (fallback if UPnP doesn't work).
     */
    public static String detectPublicIp() {
        String[] apis = {
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
            "https://icanhazip.com",
            "https://checkip.amazonaws.com"
        };
        for (String api : apis) {
            try {
                String ip = httpGet(api);
                if (ip != null) {
                    ip = ip.trim();
                    if (ip.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
                        return ip;
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Check if an IP is in CGNAT range (RFC 6598: 100.64.0.0/10).
     */
    public static boolean isCgnat(String ip) {
        try {
            String[] parts = ip.split("\\.");
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            // 100.64.0.0/10 means first=100, second in 64-127
            return first == 100 && second >= 64 && second <= 127;
        } catch (Exception e) {
            return false;
        }
    }

    public String getExternalIp() { return externalIp; }
    public String getInternalIp() { return internalIp; }
    public boolean isMapped() { return mapped; }
    public int getMappedExternalPort() { return mappedExternalPort; }

    // ──────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────

    private String getExternalIpFromUpnp() {
        try {
            String soapBody =
                "<?xml version=\"1.0\"?>\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                "  <s:Body>\n" +
                "    <u:GetExternalIPAddress xmlns:u=\"" + serviceType + "\">\n" +
                "    </u:GetExternalIPAddress>\n" +
                "  </s:Body>\n" +
                "</s:Envelope>";

            String result = soapAction("GetExternalIPAddress", soapBody);
            if (result != null) {
                Pattern p = Pattern.compile("<NewExternalIPAddress>(.*?)</NewExternalIPAddress>");
                Matcher m = p.matcher(result);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String soapAction(String action, String soapBody) {
        try {
            URL url = new URL(controlUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
            conn.setRequestProperty("SOAPAction", "\"" + serviceType + "#" + action + "\"");

            byte[] body = soapBody.getBytes("UTF-8");
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            conn.getOutputStream().write(body);

            int code = conn.getResponseCode();
            BufferedReader reader;
            if (code >= 400) {
                reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
            } else {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            }
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void parseDescription(String xml, String baseUrl) {
        try {
            URL base = new URL(baseUrl);

            // Find WANIPConnection service
            Pattern svcPattern = Pattern.compile(
                "<service>\\s*<serviceType>(urn:schemas-upnp-org:service:WANIPConnection:[0-9]+)</serviceType>\\s*<controlURL>([^<]+)</controlURL>",
                Pattern.DOTALL);
            Matcher m = svcPattern.matcher(xml);
            if (m.find()) {
                serviceType = m.group(1);
                String path = m.group(2);
                controlUrl = new URL(base, path).toString();
                return;
            }

            // Fallback: WANPPPConnection
            svcPattern = Pattern.compile(
                "<service>\\s*<serviceType>(urn:schemas-upnp-org:service:WANPPPConnection:[0-9]+)</serviceType>\\s*<controlURL>([^<]+)</controlURL>",
                Pattern.DOTALL);
            m = svcPattern.matcher(xml);
            if (m.find()) {
                serviceType = m.group(1);
                String path = m.group(2);
                controlUrl = new URL(base, path).toString();
            }
        } catch (Exception ignored) {}
    }

    private static String httpGet(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "BantuDroid/2.2.1");

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    public static String getLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Get the default gateway IP on Android.
     */
    public static String getGateway() {
        try {
            Process p = Runtime.getRuntime().exec("ip route");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("default") || line.contains("default via")) {
                    String[] parts = line.split("\\s+");
                    for (int i = 0; i < parts.length - 1; i++) {
                        if (parts[i].equals("via")) {
                            return parts[i + 1];
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
