# db.b — SQLite database helper for BantuDroid
# Creates and manages the app database.

$DB_NAME = "bantudroid.db";

# Connect to SQLite
sua.sqlite.connect($DB_NAME);

# Create visitors table
sua.sqlite.query(
    "CREATE TABLE IF NOT EXISTS visitors (" +
    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
    "  ip TEXT," +
    "  path TEXT," +
    "  timestamp DATETIME DEFAULT CURRENT_TIMESTAMP" +
    ")"
);

# Create ip_changes table for DDNS
sua.sqlite.query(
    "CREATE TABLE IF NOT EXISTS ip_changes (" +
    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
    "  old_ip TEXT," +
    "  new_ip TEXT," +
    "  timestamp DATETIME DEFAULT CURRENT_TIMESTAMP" +
    ")"
);

print("Database initialized: " + $DB_NAME);

# Helper function to log a visitor
def log_visitor($ip, $path) {
    sua.sqlite.query(
        "INSERT INTO visitors (ip, path) VALUES ('" + $ip + "', '" + $path + "')"
    );
}

# Helper function to log an IP change
def log_ip_change($old_ip, $new_ip) {
    sua.sqlite.query(
        "INSERT INTO ip_changes (old_ip, new_ip) VALUES ('" + $old_ip + "', '" + $new_ip + "')"
    );
    print("IP changed: " + $old_ip + " -> " + $new_ip);
}
