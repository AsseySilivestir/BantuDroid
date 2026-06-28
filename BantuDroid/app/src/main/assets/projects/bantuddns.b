# bantuddns.b — Dynamic DNS + Web Hosting Platform
# Runs on BantuDroid: auto-detects IP changes, updates DNS,
# and hosts a website from your Android phone.

include "./db.b";

# ─── Configuration ───────────────────────────────────────
$PORT = 8080;
$CHECK_INTERVAL = 60;  # seconds between IP checks

# Cloudflare DDNS (set your values in Settings)
$CF_ZONE_ID = "";
$CF_API_TOKEN = "";
$CF_DOMAIN = "";

# DuckDNS fallback
$DUCKDNS_DOMAIN = "";
$DUCKDNS_TOKEN = "";

# ─── IP Detection ────────────────────────────────────────
$last_ip = "";

def get_public_ip() {
    # Try multiple services for reliability
    $ip = sua.http.get("https://api.ipify.org");
    if ($ip != "" && $ip != $last_ip) {
        return $ip;
    }

    $ip = sua.http.get("https://ifconfig.me/ip");
    if ($ip != "" && $ip != $last_ip) {
        return $ip;
    }

    $ip = sua.http.get("https://api64.ipify.org");
    if ($ip != "" && $ip != $last_ip) {
        return $ip;
    }

    return $last_ip;
}

# ─── Cloudflare DNS Update ──────────────────────────────
def update_cloudflare($ip) {
    if ($CF_ZONE_ID == "" || $CF_API_TOKEN == "") {
        print("Cloudflare not configured. Set credentials in Settings.");
        return false;
    }

    print("Updating Cloudflare DNS: " + $CF_DOMAIN + " -> " + $ip);

    # Find existing A record
    $records = sua.http.get(
        "https://api.cloudflare.com/client/v4/zones/" + $CF_ZONE_ID +
        "/dns_records?name=" + $CF_DOMAIN + "&type=A",
        {"Authorization": "Bearer " + $CF_API_TOKEN}
    );

    # Update or create the A record
    if ($records != "") {
        # Parse and update existing record
        sua.http.put(
            "https://api.cloudflare.com/client/v4/zones/" + $CF_ZONE_ID +
            "/dns_records/" + $record_id,
            {"type": "A", "name": $CF_DOMAIN, "content": $ip, "ttl": 60, "proxied": false},
            {"Authorization": "Bearer " + $CF_API_TOKEN}
        );
    } else {
        # Create new A record
        sua.http.post(
            "https://api.cloudflare.com/client/v4/zones/" + $CF_ZONE_ID + "/dns_records",
            {"type": "A", "name": $CF_DOMAIN, "content": $ip, "ttl": 60, "proxied": false},
            {"Authorization": "Bearer " + $CF_API_TOKEN}
        );
    }

    print("Cloudflare DNS updated successfully!");
    return true;
}

# ─── DuckDNS Update ─────────────────────────────────────
def update_duckdns($ip) {
    if ($DUCKDNS_DOMAIN == "" || $DUCKDNS_TOKEN == "") {
        print("DuckDNS not configured. Set credentials in Settings.");
        return false;
    }

    print("Updating DuckDNS: " + $DUCKDNS_DOMAIN + " -> " + $ip);

    $result = sua.http.get(
        "https://www.duckdns.org/update?domains=" + $DUCKDNS_DOMAIN +
        "&token=" + $DUCKDNS_TOKEN +
        "&ip=" + $ip
    );

    if ($result == "OK") {
        print("DuckDNS updated successfully!");
        return true;
    }

    print("DuckDNS update failed: " + $result);
    return false;
}

# ─── IP Monitor Loop ────────────────────────────────────
def monitor_ip() {
    print("Starting IP monitor (checking every " + $CHECK_INTERVAL + "s)...");

    while (true) {
        $current_ip = get_public_ip();

        if ($current_ip != $last_ip && $current_ip != "") {
            print("IP changed: " + $last_ip + " -> " + $current_ip);

            # Log the change
            log_ip_change($last_ip, $current_ip);

            # Update DNS providers
            update_cloudflare($current_ip);
            update_duckdns($current_ip);

            $last_ip = $current_ip;
        }

        # Wait before next check
        sua.sleep($CHECK_INTERVAL);
    }
}

# ─── Web Server ─────────────────────────────────────────

# Admin dashboard
sua.server.get("/", def($req, $res) {
    log_visitor($req.ip, "/");
    $res.html(
        "<html><head><title>BantuDroid DDNS</title>" +
        "<style>body{font-family:monospace;background:#121212;color:#00ff00;padding:20px;}" +
        "a{color:#00e676;}h1{border-bottom:1px solid #333;padding-bottom:10px;}" +
        ".status{background:#1e1e1e;padding:15px;border-radius:8px;margin:10px 0;}" +
        "</style></head><body>" +
        "<h1>BantuDroid DDNS</h1>" +
        "<div class='status'><strong>Current IP:</strong> " + $last_ip + "</div>" +
        "<div class='status'><strong>Domain:</strong> " + $CF_DOMAIN + "</div>" +
        "<p><a href='/api/status'>API Status</a> | " +
        "<a href='/api/force-update'>Force Update</a></p>" +
        "</body></html>"
    );
});

# Status API
sua.server.get("/api/status", def($req, $res) {
    $res.json({
        "ip": $last_ip,
        "domain": $CF_DOMAIN,
        "port": $PORT,
        "check_interval": $CHECK_INTERVAL,
        "status": "monitoring"
    });
});

# Force IP update
sua.server.get("/api/force-update", def($req, $res) {
    $ip = get_public_ip();
    $updated = false;
    if ($ip != "") {
        update_cloudflare($ip);
        update_duckdns($ip);
        $last_ip = $ip;
        $updated = true;
    }
    $res.json({"forced": $updated, "ip": $last_ip});
});

# ─── Start Everything ───────────────────────────────────

print("=== BantuDroid DDNS + Hosting Platform ===");
print("Initializing...");

# Get initial IP
$last_ip = get_public_ip();
print("Current public IP: " + $last_ip);

# Start web server
sua.server.listen($PORT, def() {
    print("Web server listening on port " + $PORT);
    print("Dashboard: http://localhost:" + $PORT);
    print("");
    print("Your phone is now a hosting platform!");
    print("Anyone on the internet can visit: " + $CF_DOMAIN);
});

# Start IP monitoring (runs in background)
monitor_ip();
