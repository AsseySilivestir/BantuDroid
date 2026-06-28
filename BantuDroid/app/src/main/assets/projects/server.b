# server.b — Simple web server for BantuDroid
# Starts a Sua web server with basic routes.

include "./db.b";

$PORT = 8080;

print("Starting Bantu web server on port " + $PORT + "...");

# Health check endpoint
sua.server.get("/api/health", def($req, $res) {
    $res.json({
        "status": "running",
        "engine": "bantu",
        "platform": "android",
        "uptime": "0s"
    });
});

# Home page
sua.server.get("/", def($req, $res) {
    $res.html("<html><head><title>BantuDroid</title></head>" +
        "<body style='font-family:monospace;background:#121212;color:#00ff00;padding:40px;'>" +
        "<h1>BantuDroid Server</h1>" +
        "<p>Host the web from your phone.</p>" +
        "<h2>Endpoints</h2>" +
        "<ul>" +
        "<li><a href='/api/health'>/api/health</a> — Server status</li>" +
        "<li><a href='/api/info'>/api/info</a> — System info</li>" +
        "<li><a href='/api/visitors'>/api/visitors</a> — Visitor count</li>" +
        "</ul></body></html>");
});

# System info endpoint
sua.server.get("/api/info", def($req, $res) {
    $res.json({
        "engine": "bantu",
        "platform": "android",
        "framework": "sua",
        "database": "sqlite"
    });
});

# Start listening
sua.server.listen($PORT, def() {
    print("Server listening on http://localhost:" + $PORT);
    print("Open the Dashboard tab to see it!");
});
