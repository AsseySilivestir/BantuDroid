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