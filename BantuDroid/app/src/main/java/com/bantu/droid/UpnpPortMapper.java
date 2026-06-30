package com.bantu.droid;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Enumeration;

/**
 * Lightweight UPnP IGD (Internet Gateway Device) port mapper.
 *
 * Implements SSDP discovery + SOAP AddPortMapping / DeletePortMapping
 * without any external library. Works on Android's standard Java networking.
 *
 * Flow:
 * 1. SSDP M-SEARCH to discover IGD devices on the LAN
 * 2. Fetch IGD description XML to find control URL
 * 3. AddPortMapping via SOAP to forward external:port → internal:port
 * 4. DeletePortMapping via SOAP to remove the mapping on stop
 *
 * This allows direct public IP access to the local server by
 * configuring the router's NAT port forwarding automatically.
 */
public class UpnpPortMapper {

    private static final String TAG = "UpnpPortMapper";

    // SSDP constants
    private static final String SSDP_MULTICAST = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final int SSDP_TIMEOUT_MS = 5000;

    // IGD device info
    private String igdDescriptionUrl;
    private String igdControlUrl;
    private String igdServiceType;
    private String externalIpAddress;

    // Current mapping
    private int mappedExternalPort = -1;
    private int mappedInternalPort = -1;
    private String mappedProtocol = "TCP";
    private String mappedInternalClient;
    private boolean mappingActive = false;

    public interface UpnpCallback {
        void onSuccess(String externalIp, int externalPort, String publicUrl);
        void onFailure(String error);
        void onProgress(String message);
    }

    // ──────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────

    /**
     * Discover the IGD and create a port mapping.
     * Must be called on a background thread.
     */
    public void addPortMapping(int internalPort, int externalPort, UpnpCallback callback) {
        try {
            callback.onProgress("Discovering UPnP gateway...");

            // Step 1: Get local IP
            String localIp = getLocalIpAddress();
            if (localIp == null) {
                callback.onFailure("No local IP address found");
                return;
            }
            callback.onProgress("Local IP: " + localIp);
            mappedInternalClient = localIp;

            // Step 2: SSDP discovery
            String location = discoverIgd(localIp);
            if (location == null) {
                callback.onFailure("No UPnP gateway found on your network. " +
                    "Your router may not support UPnP, or it may be disabled. " +
                    "Enable UPnP in your router settings or try a different tunnel method.");
                return;
            }
            igdDescriptionUrl = location;
            callback.onProgress("Gateway found: " + location);

            // Step 3: Parse IGD description
            if (!parseIgdDescription(location)) {
                callback.onFailure("Failed to parse gateway description. " +
                    "The router may use a non-standard UPnP implementation.");
                return;
            }
            callback.onProgress("Control URL: " + igdControlUrl);

            // Step 4: Get external IP
            externalIpAddress = getExternalIpFromIgd();
            if (externalIpAddress == null || externalIpAddress.isEmpty()) {
                callback.onProgress("Could not get external IP from gateway, using web API...");
                externalIpAddress = fetchPublicIpFromWeb();
            }
            if (externalIpAddress == null) {
                callback.onFailure("Could not determine your public IP address");
                return;
            }
            callback.onProgress("External IP: " + externalIpAddress);

            // Step 5: Add port mapping
            if (!addPortMappingSoap(internalPort, externalPort, localIp, "TCP")) {
                callback.onFailure("Port mapping failed. The router may reject UPnP mappings. " +
                    "Try a different external port or check router settings.");
                return;
            }

            mappedExternalPort = externalPort;
            mappedInternalPort = internalPort;
            mappingActive = true;

            String publicUrl = "http://" + externalIpAddress + ":" + externalPort;
            callback.onSuccess(externalIpAddress, externalPort, publicUrl);

        } catch (Exception e) {
            Log.e(TAG, "UPnP port mapping failed", e);
            callback.onFailure("UPnP error: " + e.getMessage());
        }
    }

    /**
     * Remove the current port mapping.
     */
    public boolean removePortMapping() {
        if (!mappingActive || igdControlUrl == null || mappedExternalPort < 0) {
            return false;
        }
        try {
            boolean result = deletePortMappingSoap(mappedExternalPort, mappedProtocol);
            if (result) {
                mappingActive = false;
                mappedExternalPort = -1;
                mappedInternalPort = -1;
                Log.i(TAG, "UPnP port mapping removed");
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Failed to remove port mapping", e);
            return false;
        }
    }

    public boolean isMappingActive() {
        return mappingActive;
    }

    public String getExternalIpAddress() {
        return externalIpAddress;
    }

    public int getMappedExternalPort() {
        return mappedExternalPort;
    }

    // ──────────────────────────────────────────────────────────────
    // SSDP Discovery
    // ──────────────────────────────────────────────────────────────

    private String discoverIgd(String localIp) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(SSDP_TIMEOUT_MS);

            // Bind to the interface with our local IP for multi-homed devices
            InetAddress localAddr = InetAddress.getByName(localIp);
            socket.bind(null); // Use ephemeral port

            // SSDP M-SEARCH for Internet Gateway Device
            String[] searchTargets = {
                "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
                "upnp:rootdevice"
            };

            for (String st : searchTargets) {
                String search = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: " + SSDP_MULTICAST + ":" + SSDP_PORT + "\r\n" +
                    "ST: " + st + "\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "\r\n";

                byte[] data = search.getBytes();
                DatagramPacket packet = new DatagramPacket(
                    data, data.length,
                    InetAddress.getByName(SSDP_MULTICAST), SSDP_PORT
                );

                socket.send(packet);

                // Wait for response
                long deadline = System.currentTimeMillis() + SSDP_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        byte[] buf = new byte[2048];
                        DatagramPacket response = new DatagramPacket(buf, buf.length);
                        socket.receive(response);
                        String respStr = new String(response.getData(), 0, response.getLength());

                        // Look for LOCATION header
                        String location = extractHeader(respStr, "LOCATION");
                        if (location != null && !location.isEmpty()) {
                            Log.i(TAG, "SSDP found IGD: " + location + " (ST=" + st + ")");
                            return location;
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        // No more responses for this ST, try next
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "SSDP discovery error", e);
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────
    // IGD Description Parsing
    // ──────────────────────────────────────────────────────────────

    private boolean parseIgdDescription(String descriptionUrl) {
        try {
            String xml = httpGet(descriptionUrl);
            if (xml == null) return false;

            // Find WANIPConnection or WANPPPConnection service
            String[] serviceTypes = {
                "urn:schemas-upnp-org:service:WANIPConnection:1",
                "urn:schemas-upnp-org:service:WANIPConnection:2",
                "urn:schemas-upnp-org:service:WANPPPConnection:1"
            };

            for (String serviceType : serviceTypes) {
                int serviceIdx = xml.indexOf(serviceType);
                if (serviceIdx < 0) continue;

                // Find the <service> block containing this service type
                int serviceStart = xml.lastIndexOf("<service>", serviceIdx);
                int serviceEnd = xml.indexOf("</service>", serviceIdx);
                if (serviceStart < 0 || serviceEnd < 0) continue;

                String serviceBlock = xml.substring(serviceStart, serviceEnd);

                // Extract controlURL
                String controlUrl = extractXmlTag(serviceBlock, "controlURL");
                if (controlUrl != null && !controlUrl.isEmpty()) {
                    igdServiceType = serviceType;
                    igdControlUrl = resolveUrl(descriptionUrl, controlUrl);
                    Log.i(TAG, "IGD service: " + serviceType + " controlURL: " + igdControlUrl);
                    return true;
                }
            }

            Log.w(TAG, "No suitable WAN connection service found in IGD description");
            return false;

        } catch (Exception e) {
            Log.e(TAG, "Failed to parse IGD description", e);
            return false;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // SOAP Actions
    // ──────────────────────────────────────────────────────────────

    private boolean addPortMappingSoap(int internalPort, int externalPort,
                                        String internalClient, String protocol) {
        String soapBody = "<?xml version=\"1.0\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
            "  <s:Body>\n" +
            "    <u:AddPortMapping xmlns:u=\"" + igdServiceType + "\">\n" +
            "      <NewRemoteHost></NewRemoteHost>\n" +
            "      <NewExternalPort>" + externalPort + "</NewExternalPort>\n" +
            "      <NewProtocol>" + protocol + "</NewProtocol>\n" +
            "      <NewInternalPort>" + internalPort + "</NewInternalPort>\n" +
            "      <NewInternalClient>" + internalClient + "</NewInternalClient>\n" +
            "      <NewEnabled>1</NewEnabled>\n" +
            "      <NewPortMappingDescription>BantuDroid Hosting</NewPortMappingDescription>\n" +
            "      <NewLeaseDuration>86400</NewLeaseDuration>\n" +
            "    </u:AddPortMapping>\n" +
            "  </s:Body>\n" +
            "</s:Envelope>";

        String response = soapRequest("AddPortMapping", soapBody);
        if (response != null) {
            // Check for error in response
            if (response.contains("errorCode") || response.contains("errorCode")) {
                String errorCode = extractXmlTag(response, "errorCode");
                String errorDesc = extractXmlTag(response, "errorDescription");
                Log.e(TAG, "AddPortMapping error: " + errorCode + " - " + errorDesc);
                return false;
            }
            Log.i(TAG, "Port mapping added: " + externalPort + " → " + internalClient + ":" + internalPort);
            return true;
        }
        return false;
    }

    private boolean deletePortMappingSoap(int externalPort, String protocol) {
        String soapBody = "<?xml version=\"1.0\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
            "  <s:Body>\n" +
            "    <u:DeletePortMapping xmlns:u=\"" + igdServiceType + "\">\n" +
            "      <NewRemoteHost></NewRemoteHost>\n" +
            "      <NewExternalPort>" + externalPort + "</NewExternalPort>\n" +
            "      <NewProtocol>" + protocol + "</NewProtocol>\n" +
            "    </u:DeletePortMapping>\n" +
            "  </s:Body>\n" +
            "</s:Envelope>";

        String response = soapRequest("DeletePortMapping", soapBody);
        return response != null && !response.contains("errorCode");
    }

    private String getExternalIpFromIgd() {
        String soapBody = "<?xml version=\"1.0\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
            "  <s:Body>\n" +
            "    <u:GetExternalIPAddress xmlns:u=\"" + igdServiceType + "\">\n" +
            "    </u:GetExternalIPAddress>\n" +
            "  </s:Body>\n" +
            "</s:Envelope>";

        String response = soapRequest("GetExternalIPAddress", soapBody);
        if (response != null) {
            return extractXmlTag(response, "NewExternalIPAddress");
        }
        return null;
    }

    private String soapRequest(String action, String soapBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(igdControlUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
            conn.setRequestProperty("SOAPAction", "\"" + igdServiceType + "#" + action + "\"");
            conn.setRequestProperty("Connection", "close");

            byte[] body = soapBody.getBytes("UTF-8");
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            conn.getOutputStream().write(body);
            conn.getOutputStream().flush();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String response = readStream(is);

            Log.d(TAG, "SOAP " + action + " response (" + code + "): " +
                (response != null ? response.substring(0, Math.min(200, response.length())) : "null"));
            return response;

        } catch (Exception e) {
            Log.e(TAG, "SOAP " + action + " failed", e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Public IP Detection (Web API fallback)
    // ──────────────────────────────────────────────────────────────

    /**
     * Fetch public IP from external web APIs.
     * Must be called on a background thread.
     */
    public static String fetchPublicIpFromWeb() {
        String[] apis = {
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
            "https://icanhazip.com",
            "https://checkip.amazonaws.com",
            "https://api64.ipify.org"
        };

        for (String api : apis) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(api);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "BantuDroid/2.5");

                int code = conn.getResponseCode();
                if (code == 200) {
                    String ip = readStream(conn.getInputStream());
                    if (ip != null) {
                        ip = ip.trim();
                        // Validate it looks like an IP
                        if (ip.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}") ||
                            ip.matches("[0-9a-fA-F:]+")) {
                            Log.i(TAG, "Public IP from " + api + ": " + ip);
                            return ip;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to fetch IP from " + api + ": " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────
    // CGNAT Detection
    // ──────────────────────────────────────────────────────────────

    /**
     * Check if the device is likely behind Carrier-Grade NAT (CGNAT).
     * CGNAT ranges: 100.64.0.0/10 (RFC 6598), also common:
     * 10.x, 172.16-31.x, 192.168.x are regular NAT (not CGNAT).
     * CGNAT is specifically the RFC 6598 shared address space.
     */
    public static boolean isCgnat(String localIp, String publicIp) {
        if (localIp == null || publicIp == null) return false;

        // If they're the same, no NAT at all
        if (localIp.equals(publicIp)) return false;

        try {
            // Check RFC 6598 CGNAT range: 100.64.0.0/10
            byte[] addr = InetAddress.getByName(localIp).getAddress();
            if (addr.length == 4) {
                int first = addr[0] & 0xFF;
                int second = addr[1] & 0xFF;
                // 100.64.0.0/10 = 100.01000000.0.0 to 100.01111111.255.255
                if (first == 100 && (second & 0xC0) == 0x40) {
                    return true; // CGNAT range
                }
            }
        } catch (Exception ignored) {}

        return false;
    }

    /**
     * Check if device is behind any NAT (local IP != public IP).
     */
    public static boolean isBehindNat(String localIp, String publicIp) {
        if (localIp == null || publicIp == null) return true; // Assume NAT if unknown
        return !localIp.equals(publicIp);
    }

    // ──────────────────────────────────────────────────────────────
    // Network Utilities
    // ──────────────────────────────────────────────────────────────

    /**
     * Get the device's local (LAN) IPv4 address.
     */
    public static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) continue;

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isLoopbackAddress()) continue;
                    if (addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        // Prefer wifi/LAN addresses (not mobile data CGNAT)
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") ||
                            ip.startsWith("172.")) {
                            return ip;
                        }
                    }
                }
            }

            // Fallback: any IPv4 that's not loopback
            interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get local IP", e);
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────
    // HTTP/Text Utilities
    // ──────────────────────────────────────────────────────────────

    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "BantuDroid/2.5");

            int code = conn.getResponseCode();
            if (code == 200) {
                return readStream(conn.getInputStream());
            }
        } catch (Exception e) {
            Log.e(TAG, "HTTP GET failed for " + urlStr, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static String readStream(InputStream is) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractHeader(String response, String header) {
        String[] lines = response.split("\r\n");
        for (String line : lines) {
            if (line.toLowerCase().startsWith(header.toLowerCase() + ":")) {
                return line.substring(header.length() + 1).trim();
            }
        }
        return null;
    }

    private static String extractXmlTag(String xml, String tag) {
        String startTag = "<" + tag + ">";
        String endTag = "</" + tag + ">";
        int s = xml.indexOf(startTag);
        if (s < 0) return null;
        int e = xml.indexOf(endTag, s);
        if (e < 0) return null;
        return xml.substring(s + startTag.length(), e).trim();
    }

    /**
     * Resolve a relative URL against a base URL.
     * E.g., base="http://192.168.1.1:80/desc.xml" + rel="/ctrl" → "http://192.168.1.1:80/ctrl"
     */
    private static String resolveUrl(String baseUrl, String relUrl) {
        if (relUrl.startsWith("http://") || relUrl.startsWith("https://")) {
            return relUrl;
        }
        try {
            URL base = new URL(baseUrl);
            return new URL(base, relUrl).toString();
        } catch (Exception e) {
            // Manual fallback
            String schemeHost;
            int pathStart = baseUrl.indexOf("/", baseUrl.indexOf("//") + 2);
            if (pathStart > 0) {
                schemeHost = baseUrl.substring(0, pathStart);
            } else {
                schemeHost = baseUrl;
            }
            if (relUrl.startsWith("/")) {
                return schemeHost + relUrl;
            } else {
                return schemeHost + "/" + relUrl;
            }
        }
    }
}
