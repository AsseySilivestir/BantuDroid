#!/bin/bash
# BantuDroid APK Build Script
cd /home/z/my-project/BantuDroid
export ANDROID_HOME=/home/z/android-sdk
export ANDROID_SDK_ROOT=/home/z/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

echo "=== BUILD STARTED at $(date) ==="

# Clean previous build
rm -rf app/build

# Run the build
./gradlew assembleDebug --no-daemon 2>&1

EXIT_CODE=$?
echo "=== BUILD FINISHED at $(date) with exit code: $EXIT_CODE ==="

# Copy APK if successful
if [ $EXIT_CODE -eq 0 ]; then
    mkdir -p /home/z/my-project/download
    cp app/build/outputs/apk/debug/app-debug.apk /home/z/my-project/download/BantuDroid-debug.apk 2>/dev/null
    echo "=== APK COPIED to /home/z/my-project/download/BantuDroid-debug.apk ==="
fi

echo $EXIT_CODE > /tmp/build-exit-code.txt
