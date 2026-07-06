package com.bantu.droid;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * DNS management screen — configures DNS records on Cloudflare for a
 * custom domain (e.g. splannes.co.tz bought at Wazohost).
 */
public class DnsActivity extends AppCompatActivity {

    private EditText etDomain, etApiToken, etRecordName, etRecordValue;
    private RadioGroup rgRecordType;
    private Button btnAutoDetectZone, btnCreateRecord, btnCreateBantuCname;
    private TextView tvStatus, tvInstructions, tvLog;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dns);

        etDomain = findViewById(R.id.et_domain);
        etApiToken = findViewById(R.id.et_cf_api_token);
        etRecordName = findViewById(R.id.et_record_name);
        etRecordValue = findViewById(R.id.et_record_value);
        rgRecordType = findViewById(R.id.rg_record_type);
        btnAutoDetectZone = findViewById(R.id.btn_auto_zone);
        btnCreateRecord = findViewById(R.id.btn_create_record);
        btnCreateBantuCname = findViewById(R.id.btn_bantu_cname);
        tvStatus = findViewById(R.id.tv_status);
        tvInstructions = findViewById(R.id.tv_instructions);
        tvLog = findViewById(R.id.tv_log);
        tvLog.setMovementMethod(new ScrollingMovementMethod());

        etDomain.setText(prefs().getString("dns_domain", ""));
        etApiToken.setText(prefs().getString("dns_cf_token", ""));
        if (etRecordName.getText().toString().isEmpty()) etRecordName.setText("@");
        etRecordValue.setText(prefs().getString("bantu_server_url", ""));

        btnAutoDetectZone.setOnClickListener(v -> autoDetectZone());
        btnCreateRecord.setOnClickListener(v -> createRecord());
        btnCreateBantuCname.setOnClickListener(v -> createBantuCname());
    }

    private android.content.SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(this);
    }

    private void log(String msg) {
        String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
        mainHandler.post(() -> {
            tvLog.append("[" + ts + "] " + msg + "\n");
            tvLog.post(() -> tvLog.scrollTo(0, tvLog.getBottom()));
        });
    }

    private void setStatus(String s) { mainHandler.post(() -> tvStatus.setText(s)); }

    private void autoDetectZone() {
        final String domain = etDomain.getText().toString().trim();
        final String token = etApiToken.getText().toString().trim();
        if (domain.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "Enter domain and API token first", Toast.LENGTH_SHORT).show();
            return;
        }
        btnAutoDetectZone.setEnabled(false);
        setStatus("Looking up zone for " + domain + "...");
        log("Looking up Cloudflare Zone ID for " + domain);
        new Thread(() -> {
            String zoneId = CloudflareApi.getZoneId(token, domain, new TunnelManager.TunnelCallback() {
                @Override public void onMessage(String msg) { log(msg); }
                @Override public void onError(String err) { log("ERROR: " + err); }
                @Override public void onConnected(String u) {}
                @Override public void onDisconnected() {}
            });
            mainHandler.post(() -> {
                btnAutoDetectZone.setEnabled(true);
                if (zoneId != null) {
                    prefs().edit().putString("dns_zone_id", zoneId).putString("dns_domain", domain).putString("dns_cf_token", token).apply();
                    setStatus("Zone ID: " + zoneId);
                    log("Zone ID found: " + zoneId);
                } else {
                    setStatus("Zone lookup failed");
                    log("Could not find zone. Make sure you added " + domain + " in Cloudflare dashboard.");
                }
            });
        }).start();
    }

    private void createRecord() {
        final String domain = etDomain.getText().toString().trim();
        final String token = etApiToken.getText().toString().trim();
        final String recordName = etRecordName.getText().toString().trim();
        final String recordValue = etRecordValue.getText().toString().trim();
        final String recordType;
        int selectedId = rgRecordType.getCheckedRadioButtonId();
        if (selectedId == R.id.rb_type_a) recordType = "A";
        else if (selectedId == R.id.rb_type_aaaa) recordType = "AAAA";
        else recordType = "CNAME";
        if (domain.isEmpty() || token.isEmpty() || recordName.isEmpty() || recordValue.isEmpty()) {
            Toast.makeText(this, "Fill in all fields first", Toast.LENGTH_SHORT).show();
            return;
        }
        btnCreateRecord.setEnabled(false);
        setStatus("Creating " + recordType + " record...");
        new Thread(() -> {
            final String fullRecordName;
            if (recordName.equals("@") || recordName.isEmpty()) fullRecordName = domain;
            else if (recordName.contains(".")) fullRecordName = recordName;
            else fullRecordName = recordName + "." + domain;
            String zoneId = prefs().getString("dns_zone_id", null);
            if (zoneId == null) {
                zoneId = CloudflareApi.getZoneId(token, domain, new TunnelManager.TunnelCallback() {
                    @Override public void onMessage(String m) { log(m); }
                    @Override public void onError(String e) { log("ERROR: " + e); }
                    @Override public void onConnected(String u) {}
                    @Override public void onDisconnected() {}
                });
            }
            if (zoneId == null) {
                mainHandler.post(() -> { btnCreateRecord.setEnabled(true); setStatus("Failed: could not resolve zone ID"); });
                return;
            }
            log("Creating " + recordType + " record: " + fullRecordName + " -> " + recordValue);
            boolean ok = createDnsRecord(token, zoneId, recordType, fullRecordName, recordValue);
            mainHandler.post(() -> {
                btnCreateRecord.setEnabled(true);
                if (ok) { setStatus(recordType + " record created for " + fullRecordName); log("SUCCESS!"); }
                else setStatus("Failed \u2014 see log");
            });
        }).start();
    }

    private void createBantuCname() {
        String bantuUrl = prefs().getString("bantu_server_url", "");
        if (bantuUrl.isEmpty()) {
            Toast.makeText(this, "Set your Bantu server URL in Settings first", Toast.LENGTH_LONG).show();
            return;
        }
        String host = bantuUrl.replaceAll("^https?://", "").replaceAll("/.*$", "").trim();
        etRecordName.setText("@");
        etRecordValue.setText(host);
        rgRecordType.check(R.id.rb_type_cname);
        log("Wiring domain to Bantu tunnel server: " + host);
        createRecord();
    }

    private boolean createDnsRecord(String apiToken, String zoneId, String type, String name, String content) {
        try {
            JSONObject body = new JSONObject();
            body.put("type", type);
            body.put("name", name);
            body.put("content", content);
            body.put("ttl", 1);
            body.put("proxied", true);
            String response = cfApiRequest("POST", "https://api.cloudflare.com/client/v4/zones/" + zoneId + "/dns_records", apiToken, body.toString());
            if (response == null) { log("API request returned null"); return false; }
            JSONObject json = new JSONObject(response);
            if (!json.optBoolean("success", false)) {
                JSONArray errors = json.optJSONArray("errors");
                String errMsg = "DNS creation failed";
                if (errors != null && errors.length() > 0) {
                    errMsg = errors.getJSONObject(0).optString("message", errMsg);
                    if (errMsg.contains("already exists")) { log("Record already exists (OK)"); return true; }
                }
                log("Cloudflare error: " + errMsg);
                return false;
            }
            log("Record created successfully");
            return true;
        } catch (Exception e) { log("Exception: " + e.getMessage()); return false; }
    }

    private static String cfApiRequest(String method, String urlStr, String apiToken, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "Bearer " + apiToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            if (body != null && (method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
            }
            int status = conn.getResponseCode();
            java.io.InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally { conn.disconnect(); }
    }
}
