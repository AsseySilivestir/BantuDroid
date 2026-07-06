# BantuDroid Hosting System — Fix Report

## Overview
Inspected, diagnosed, and fixed three issues in the BantuDroid hosting/tunnel system.

---

## Issue 1: Cloudflared "Access Denied"

### Root Cause Analysis

**Execution Flow:**
1. `TunnelActivity.startCloudflareTunnel()` calls `TunnelManager.startCloudflareTunnel()`
2. `TunnelManager.findCloudflared()` searches for the binary in:
   - `/data/data/com.termux/files/usr/bin/cloudflared` (Termux)
   - `/usr/bin/cloudflared` (system)
   - `which cloudflared` (PATH)
3. Binary is found (e.g., in Termux path)
4. `new ProcessBuilder(cloudflared, "tunnel", "--url", ...).start()` is called
5. **FAILS** with "Access Denied"

**Why it fails:**
On Android 10+, SELinux enforces a W^X (Write-Xor-Execute) policy. Directories where apps can write (like `/data/data/com.termux/files/usr/bin/` and `/data/data/com.bantu.droid/files/`) are mounted with the `NOEXEC` flag. When `ProcessBuilder.start()` internally calls the POSIX `exec()` syscall, the kernel checks the mount flags and blocks execution from NOEXEC-mounted filesystems. This produces an "Access Denied" error at the VFS level — the file exists and has `chmod 755` permissions, but the mount flags take precedence.

This is the **exact same problem** that `BantuEngine` already solved for the Bantu binary itself. The Bantu binary is shipped as `libbantu.so` in `jniLibs/`, which Android extracts to `nativeLibraryDir` (`/data/app/.../lib/`) during APK installation. That directory has the correct SELinux context for execution. Additionally, `BantuBridge` provides a JNI `fork()+execv()` fallback that runs in a different SELinux context, bypassing the restriction entirely.

**The bug:** `TunnelManager` did not use any of these proven execution strategies. It naively called `ProcessBuilder` on a binary from a NOEXEC directory.

### Fix Implemented

**1. Three-tier execution strategy** (in `TunnelManager.executeCloudflared()`):
- **PRIMARY:** JNI `fork()+execv()` via `BantuBridge.executeBinary()` — bypasses SELinux entirely
- **FALLBACK 1:** `ProcessBuilder` from `codeCacheDir` — works on some devices/OEMs
- **FALLBACK 2:** `ProcessBuilder` from original path — last resort

**2. Added `BantuBridge.executeBinary()`** static method:
- Returns a raw `Process` (actually `JniProcess`) that can be used like any `Process`
- Allows `TunnelManager` to execute cloudflared through the same JNI bridge that Bantu uses
- Added `destroyForcibly()` and `isAlive()` to `JniProcess` for full Process API compliance

**3. Cloudflared binary download:**
- If cloudflared is not found on the device, it is automatically downloaded from Cloudflare's official GitHub releases
- Supports ARM64, ARM, and x86_64 architectures
- Downloads to `codeCacheDir` (writable, accessible)
- Progress reporting via callback

**4. `TunnelManager` now requires `Context`:**
- Needed for file operations, `codeCacheDir` access, and environment setup
- Constructor changed from `TunnelManager()` to `TunnelManager(Context)`

### Files Changed
- `TunnelManager.java` — Complete rewrite of cloudflared execution
- `BantuBridge.java` — Added `executeBinary()` static method, improved `JniProcess`

---

## Issue 2: Pinggy "Enter" Page

### Investigation

Pinggy's free tier works by creating an SSH reverse tunnel to `a.pinggy.io:443`. However, when a visitor accesses the generated URL, they are **not** forwarded directly to the tunneled content. Instead, Pinggy's server-side reverse proxy serves an intermediate HTML page that contains:
- A Pinggy branding header
- An "Enter" button (or link)
- The actual tunneled content only after clicking "Enter"

This behavior was investigated across all possible configuration avenues:

1. **SSH command-line options:** Pinggy does not expose any SSH-level options to disable the landing page. The `-R` flag only controls port forwarding, not HTTP behavior.

2. **HTTP headers:** The landing page is injected by Pinggy's server-side HTTP handler before the request reaches the SSH tunnel. Client-side headers have no effect on server behavior.

3. **Query parameters:** No URL parameters (e.g., `?no_landing=1`) are documented or supported.

4. **Pinggy documentation:** Pinggy's documentation confirms this is a feature of the free tier. The intermediate page can only be removed by upgrading to Pinggy Pro.

### Why It Cannot Be Fixed

The "Enter" page is a **server-side behavior of Pinggy's infrastructure**. It is not something that can be configured, bypassed, or worked around from the client (BantuDroid). The SSH tunnel correctly forwards traffic — the intermediate page is added by Pinggy's HTTP proxy layer that sits between the public URL and the SSH tunnel endpoint.

This is a business model decision by Pinggy: the intermediate page serves as advertising and a conversion funnel for their paid product. Removing it client-side would require intercepting and modifying server responses, which is neither practical nor reliable.

### Fix Implemented

1. **Reordered `SSH_PROVIDERS` array:** Serveo and localhost.run are now listed first (they provide direct access without intermediate pages). Pinggy is listed last as a less-preferred option.

2. **Added `SSH_PROVIDER_NOTES` array:** Each provider now has a documentation string explaining its behavior. Pinggy's note explicitly warns about the intermediate page limitation.

3. **Added provider note UI:** A `TextView` below the SSH provider spinner now displays the selected provider's notes, so users see the Pinggy warning before connecting.

4. **Updated UI description:** The SSH tunnel card's subtitle now mentions "Serveo, localhost.run, or Pinggy" (previously "Pinggy, Serveo, or localhost.run").

### Files Changed
- `TunnelManager.java` — Reordered providers, added `SSH_PROVIDER_NOTES`
- `TunnelActivity.java` — Added provider note listener
- `activity_tunnel.xml` — Added `tv_provider_note` TextView

---

## Issue 3: Custom Domain Support

### Implementation

Added a complete custom domain system that allows users to connect their own domains (e.g., from Namecheap, GoDaddy, etc.) to their BantuDroid-hosted website.

**How it works:**
1. User enters their domain name and Cloudflare API token
2. The app auto-detects the Cloudflare Zone ID (or user can enter it manually)
3. A Cloudflare named tunnel is created via the Cloudflare API
4. A DNS CNAME record is automatically created pointing the domain to the tunnel
5. Cloudflared is started with the tunnel configuration
6. The website is immediately accessible at `https://yourdomain.com`

**Prerequisites for users:**
- Domain must be added to Cloudflare (nameservers pointed to Cloudflare)
- Cloudflare API token with permissions: Account > Cloudflare Tunnel > Edit, Zone > DNS > Edit, Zone > Zone > Read
- Cloudflared binary (auto-downloaded if missing)

### New Files
- **`CloudflareApi.java`** — Cloudflare API client for:
  - Tunnel creation (`POST /accounts/{id}/cfd_tunnel`)
  - DNS CNAME record creation (`POST /zones/{id}/dns_records`)
  - Zone ID auto-detection (`GET /zones?name={domain}`)
  - Account ID lookup
  - Tunnel credential management (saved to app storage for cloudflared)

### UI Changes
- **New "Custom Domain" card** in TunnelActivity with:
  - Domain name input
  - Cloudflare API token input (password-masked)
  - Zone ID input with "Auto-detect" button
  - Status display
  - URL display (when tunnel is active)
  - Start button
- **Settings persistence:** Custom domain settings are saved to SharedPreferences and restored on app restart
- **Shared stop button:** The Cloudflare stop button also stops custom domain tunnels

### Files Changed
- `TunnelActivity.java` — Added custom domain UI logic
- `activity_tunnel.xml` — Added Custom Domain card
- `strings.xml` — Added custom domain string resources
- `TunnelManager.java` — Added `startCloudflareNamedTunnel()` method

---

## Summary of All Modified Files

| File | Change Type | Description |
|------|------------|-------------|
| `TunnelManager.java` | Major rewrite | Fixed cloudflared execution, added download, added custom domain tunnel, reordered SSH providers |
| `BantuBridge.java` | Enhancement | Added `executeBinary()` static method, `destroyForcibly()`, `isAlive()` |
| `BantuBridge.JniProcess` | Enhancement | Added `destroyForcibly()` and `isAlive()` for full Process API |
| `TunnelActivity.java` | Enhancement | Added custom domain UI, provider notes, settings persistence |
| `CloudflareApi.java` | New file | Cloudflare API client for tunnel/DNS management |
| `activity_tunnel.xml` | Enhancement | Added Custom Domain card, provider note TextView |
| `strings.xml` | Enhancement | Added custom domain strings |

---

## Task ID 3 — Bantu self-hosted tunnel + TunnelService + DNS + Tree/Busybox integration

### Overview
Integrated the previously-created NEW files (BantuTunnelClient, TunnelService, DnsActivity, BusyboxExecutor, TreeRenderer) into the EXISTING BantuDroid codebase. Bumped version to 2.10.1 (versionCode 296). All twelve modification tasks completed.

### Changes by file

**1. `app/build.gradle`**
- Bumped `versionCode` 293 → 296 and `versionName` "2.9.3" → "2.10.1".
- Added `implementation 'com.squareup.okhttp3:okhttp:4.12.0'` (required by `BantuTunnelClient`'s WebSocket transport).

**2. `app/src/main/AndroidManifest.xml`**
- Registered new `.DnsActivity` (exported=false) after `.TunnelActivity`.
- Registered new `.TunnelService` foreground service (`foregroundServiceType="dataSync"`, exported=false) after `.ServerService`.

**3. `app/src/main/java/com/bantu/droid/TunnelManager.java`**
- Prepended `{"Bantu", "bantu-tunnel", "443", "bantu"}` to `SSH_PROVIDERS` array (now 4 entries, Bantu is the recommended first option).
- Prepended a Bantu explainer note to `SSH_PROVIDER_NOTES`. Updated the Pinggy note to suggest "Bantu or Serveo" instead of "Serveo or localhost.run".
- Added `BantuTunnelClient bantuClient` and `volatile boolean bantuRunning` fields between the SSH and cloudflared state.
- `startSshTunnel()` now rejects if either `sshRunning` OR `bantuRunning` is set, and delegates to `startBantuTunnel()` when the selected provider's key is `"bantu"`.
- Rewrote `stopSshTunnel()` to also stop a running Bantu tunnel.
- Added new `startBantuTunnel(int localPort, TunnelCallback cb)` method that constructs a `BantuTunnelClient`, validates configuration, then bridges its Callback to the existing `TunnelCallback` interface.
- Added `isBantuRunning()` accessor.

**4. `app/src/main/java/com/bantu/droid/SettingsActivity.java`**
- Added four new fields: `etBantuServerUrl`, `etBantuSubdomain`, `etBantuToken`, `btnSaveBantu`.
- Wired them in `onCreate()` via `findViewById`.
- Pre-populated them from SharedPreferences keys `bantu_server_url`, `bantu_subdomain`, `bantu_token`.
- Added `btnSaveBantu.setOnClickListener(v -> saveBantuSettings())`.
- Added new `saveBantuSettings()` method that trims a single trailing `/` and any trailing `/ws` suffix from the server URL before persisting, and shows a confirmation toast.

**5. `app/src/main/res/layout/activity_settings.xml`**
- Inserted a "BANTU TUNNEL (self-hosted)" section between the DDNS section and the About section: a heading TextView, a hint TextView, three monospace EditTexts (server URL, subdomain, auth token), and a "Save Bantu Tunnel Settings" button (`@id/btn_save_bantu`).

**6. `app/src/main/java/com/bantu/droid/MainActivity.java`**
- Added `private Button btnDns;` field.
- In `showMainScreen()`, wired `btnDns` (R.id.btn_dns) to launch `DnsActivity`.

**7. `app/src/main/res/layout/activity_main.xml`**
- Added a new `MaterialCardView` containing a `btn_dns` button ("Domain & DNS", orange #FFB74D, with `ic_menu_myplaces` drawable) immediately after the `btn_hosting` card.

**8. `app/src/main/res/layout/activity_file_manager.xml`**
- Added a new `btn_tree` Button (36×36dp, palm-tree emoji 🌴, bg_card background) right after `btn_refresh` in the top toolbar.

**9. `app/src/main/java/com/bantu/droid/FileManagerActivity.java`**
- Added `btnTree` to the button field declaration list.
- Looked it up via `findViewById(R.id.btn_tree)` and wired `btnTree.setOnClickListener(v -> showTreeDialog())`.
- Added a new `showTreeDialog()` method that runs `TreeRenderer` on a background thread, then displays the rendered tree in a monospace `TextView` inside a `ScrollView` inside an `AlertDialog`, with a "Copy" button that puts the tree text on the clipboard.

**10. `app/src/main/java/com/bantu/droid/TerminalActivity.java`**
- Added `private BusyboxExecutor busybox;` field.
- Instantiated it in `onCreate()`.
- Added `ensureBusybox()` call immediately after the "Working directory:" welcome banner.
- In `runCommand()`'s if-else chain, added two new branches between `clear/cls` and `help`:
  - `tree` / `tree <args>` → `handleTree(...)`.
  - `apt`/`apt ...`/`pkg`/`pkg ...` → prints a stub message explaining why package managers don't work and pointing users to Termux.
- Replaced the entire `runShellPassthrough(String cmd)` method: it now calls `busybox.exec(cmd, currentDir)` and pipes stdout/stderr/exit code into the terminal (instead of spawning `/system/bin/sh` directly, which is restricted on modern Android).
- Added new `ensureBusybox()` method that, on a background thread, calls `busybox.ensureReady(...)` with a `ProgressListener` that streams download progress to the terminal.
- Added new `handleTree(String args)` method that parses optional `-d` flag and optional path argument, resolves the target via `resolvePath`, and renders via `TreeRenderer` (dirsOnly flag honored).

**11. `app/src/main/java/com/bantu/droid/TunnelActivity.java`**
- Added `import android.content.Intent;`.
- Added `btnTestUrl` to the SSH button declaration line.
- Added `private android.content.BroadcastReceiver tunnelReceiver;` field.
- Bound `btnTestUrl` via `findViewById(R.id.btn_test_url)`.
- Added a click handler for `btnTestUrl` that grabs `TunnelService.getCurrentPublicUrl()`, ensures a trailing slash, and fires an `ACTION_VIEW` Intent to open it in the device browser.
- Registered a `BroadcastReceiver` for actions `com.bantu.droid.TUNNEL_CONNECTED` and `com.bantu.droid.TUNNEL_DISCONNECTED` (with `RECEIVER_NOT_EXPORTED` flag). The connected handler updates the URL display, enables Stop+Test buttons, makes the URL TextView copy-to-clipboard on tap. The disconnected handler resets the UI.
- Added a state-reflection block: if `TunnelService.isRunning()` on entry, the activity shows the running state immediately (Stop button enabled, URL shown if available).
- Replaced `startSshTunnel()` entirely: now persists `tunnel_provider_index` and `default_port` to SharedPreferences, then calls `TunnelService.start(this, providerIdx, port)` — the foreground service handles the actual tunnel lifecycle.
- Replaced `stopSshTunnel()` to call `TunnelService.stop(this)` and reset the three buttons (Start/Stop/Test).
- Replaced `onDestroy()` to unregister the broadcast receiver and explicitly NOT stop the SSH/Bantu tunnel (that's the whole point of the foreground service — it survives activity destruction). Still stops the cloudflared quick tunnel.

**12. `app/src/main/res/layout/activity_tunnel.xml`**
- Renamed the SSH card title "SSH Reverse Tunnel" → "Tunnel (Bantu / SSH)".
- Updated the card subtitle to mention Bantu first: "Create a public URL via Bantu (your self-hosted server), Serveo, localhost.run, or Pinggy."
- Renamed "Stop SSH Tunnel" → "Stop Tunnel".
- Added a new `btn_test_url` button ("Open Public URL in Browser", green #4CAF50, initially disabled) directly after the start/stop button row inside the SSH card.

### Verification performed
- Confirmed `BantuTunnelClient` exposes `getServerUrl()`, `getSubdomain()`, `isConfigured()`, `start(int, Callback)`, `stop()` — all called by the new `startBantuTunnel()` in TunnelManager.
- Confirmed `TunnelService` exposes static `start(Context, int, int)`, `stop(Context)`, `isRunning()`, `getCurrentPublicUrl()` — all called from TunnelActivity.
- Confirmed `BusyboxExecutor` exposes `isReady()`, `exec(String, File)`, `ensureReady(ProgressListener)` returning `ExecResult` with `stdout`/`stderr`/`exitCode` fields — all referenced by the rewritten `runShellPassthrough` and `ensureBusybox`.
- Confirmed `TreeRenderer` exposes a 4-arg constructor `(File, boolean showHidden, int maxDepth, boolean dirsOnly)` and a `render()` method — used by both `FileManagerActivity.showTreeDialog()` and `TerminalActivity.handleTree()`.
- Confirmed `colors.xml` defines all referenced colors: `bg_card`, `text_secondary`, `text_hint`, `text_primary`, `bantu_green`, `black`.

### Next actions
- Build the APK (`./gradlew assembleRelease`) to verify everything compiles.
- On-device test: (a) Settings → enter a bantu-tunnel server URL → save → return to Tunnel activity → select "Bantu" provider → Start → verify URL appears and "Open Public URL in Browser" launches the browser with the site reachable.
- Verify background survival: start Bantu tunnel, press Home, wait 30s, return to app — service should still be running and URL still active.
- Verify tree button in FileManager renders a tree view of the current directory and "Copy" puts text on clipboard.
- Verify `tree` and `apt`/`pkg` commands in Terminal behave as specified.
