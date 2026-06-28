# BantuDroid

**Host the web from your phone.**

BantuDroid is an Android app that bundles the Bantu programming language runtime, letting anyone run Bantu code and host websites directly from their Android device.

## Features

- **Terminal Emulator** — Run Bantu commands like `bantu run server.b`
- **File Manager** — Browse, create, edit, and delete `.b` files
- **Web Dashboard** — View your server's admin panel via WebView
- **Background Service** — Keep your server running even when the app is minimized
- **DDNS Integration** — Auto-update Cloudflare/DuckDNS when your IP changes
- **Quick Actions** — One-tap buttons to start DDNS, web server, or demos

## Project Structure

```
BantuDroid/
├── app/
│   ├── build.gradle                          # App-level Gradle config
│   ├── proguard-rules.pro                    # ProGuard rules
│   └── src/main/
│       ├── AndroidManifest.xml               # Permissions & activities
│       ├── assets/
│       │   ├── bin/                          # Bantu binary (arm64-v8a, etc.)
│       │   │   └── bantu
│       │   └── projects/                     # Bundled .b files
│       │       ├── bantuddns.b               # DDNS + hosting platform
│       │       ├── server.b                  # Simple web server
│       │       ├── db.b                      # Database helper
│       │       ├── hello.b                   # Hello world demo
│       │       └── bench.b                   # Performance benchmarks
│       ├── java/com/bantu/droid/
│       │   ├── BantuEngine.java              # Core engine wrapper
│       │   ├── BantuProcess.java             # Process I/O management
│       │   ├── ServerService.java            # Background server service
│       │   ├── MainActivity.java             # Launcher / setup screen
│       │   ├── TerminalActivity.java         # Terminal emulator
│       │   ├── FileManagerActivity.java      # .b file manager
│       │   ├── DashboardActivity.java        # WebView dashboard
│       │   └── SettingsActivity.java         # App settings
│       └── res/
│           ├── layout/                       # XML layouts
│           │   ├── activity_main.xml
│           │   ├── activity_terminal.xml
│           │   ├── activity_file_manager.xml
│           │   ├── activity_dashboard.xml
│           │   ├── activity_settings.xml
│           │   └── item_file.xml
│           └── values/                       # Resources
│               ├── strings.xml
│               ├── colors.xml
│               └── styles.xml
├── build.gradle                              # Project-level Gradle config
├── settings.gradle                           # Project settings
├── build-android.sh                          # Cross-compile script
└── README.md
```

## Setup

### 1. Prerequisites

- **Android Studio** (Flamingo or later)
- **Android NDK** (for cross-compiling Bantu)
- **Bantu source code** (https://github.com/AsseySilivestir/Bantu.git)

### 2. Cross-Compile Bantu for Android

```bash
# Install cross-compiler (Ubuntu/Debian)
sudo apt install g++-aarch64-linux-gnu cmake

# Or install Android NDK
# Download from: https://developer.android.com/ndk/downloads

# Run the build script
./build-android.sh /path/to/Bantu/bantu-src /path/to/BantuDroid
```

This compiles the Bantu binary for arm64-v8a (and optionally armeabi-v7a, x86_64) and places it in `app/src/main/assets/bin/`.

### 3. Open in Android Studio

1. Open Android Studio
2. File → Open → Select the `BantuDroid` directory
3. Wait for Gradle sync to complete
4. Connect an Android device (API 24+ / Android 7.0+) or use an emulator
5. Click Run

### 4. Using the App

1. **First launch** — Tap "Install Engine" to extract the Bantu binary
2. **Terminal** — Type `bantu run bantuddns.b` to start the DDNS + hosting platform
3. **Quick Actions** — Tap DDNS, Server, or Hello for one-tap execution
4. **Files** — Browse and edit `.b` project files
5. **Dashboard** — View the web dashboard at `http://localhost:8080`
6. **Settings** — Configure DDNS credentials (Cloudflare/DuckDNS)

## How It Works

```
User taps "DDNS" button
        │
        ▼
BantuEngine.run("bantuddns.b")
        │
        ▼
ProcessBuilder: /data/data/com.bantu.droid/files/bin/bantu run /data/.../projects/bantuddns.b
        │
        ▼
Bantu interpreter starts
        ├── Detects public IP (ipify, ifconfig.me)
        ├── Updates Cloudflare/DuckDNS DNS records
        ├── Starts Sua web server on port 8080
        ├── Monitors IP every 60 seconds
        └── Streams output to Terminal UI
        │
        ▼
Phone is now a global web server!
```

## Background Service

To keep the server running when you minimize the app:

```bash
# In the terminal:
start-service bantuddns.b

# Or the server will auto-start as a foreground service
# with a persistent notification showing IP status
```

The `ServerService` uses:
- **WakeLock** — Prevents CPU sleep while server runs
- **Foreground notification** — Required by Android for background services
- **START_STICKY** — Auto-restarts if killed by the system
- **Auto-recovery** — Restarts the Bantu process if it crashes

## Adding the Bantu Binary

The binary must be compiled for Android's architecture. Place it at:

```
app/src/main/assets/bin/arm64-v8a/bantu     # For ARM64 devices
app/src/main/assets/bin/armeabi-v7a/bantu   # For older ARM devices
app/src/main/assets/bin/x86_64/bantu        # For emulators
```

The `BantuEngine` class auto-detects the device's ABI and extracts the matching binary.

## Building the APK for Distribution

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (requires signing key)
./gradlew assembleRelease

# Output location:
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release.apk
```

## Architecture Support

| ABI | Devices | Status |
|-----|---------|--------|
| arm64-v8a | Modern phones (Pixel, Samsung, etc.) | Primary |
| armeabi-v7a | Older phones | Supported |
| x86_64 | Android emulator | Supported |

## Contributing

This project is part of the Bantu programming language ecosystem.

- **Bantu Language**: https://github.com/AsseySilivestir/Bantu
- **Issues**: https://github.com/AsseySilivestir/Bantu/issues

## License

Same as the Bantu programming language project.
