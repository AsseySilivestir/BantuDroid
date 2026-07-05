#!/bin/bash

export ANDROID_HOME=/home/z/android-sdk
export ANDROID_SDK_ROOT=/home/z/android-sdk
export JAVA_HOME=/tmp/jdk-17.0.12+7
export PATH="$JAVA_HOME/bin:$PATH"

cd /home/z/my-project/BantuDroid/BantuDroid

LOG="/home/z/my-project/BantuDroid/build-v280-release2.log"
echo "=== Build started at $(date) ===" > "$LOG"

./gradlew assembleRelease --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx1536m -XX:+UseSerialGC" >> "$LOG" 2>&1
BUILD_EXIT=$?

echo "=== Build exit code: $BUILD_EXIT at $(date) ===" >> "$LOG"

if [ $BUILD_EXIT -eq 0 ]; then
    APK=$(find app/build/outputs/apk/release/ -name "*.apk" 2>/dev/null | head -1)
    if [ -n "$APK" ]; then
        echo "APK: $APK" >> "$LOG"
        ls -la "$APK" >> "$LOG"
        cp "$APK" /home/z/my-project/BantuDroid/download/BantuDroid-v2.8.0-release.apk
        echo "DONE" >> "$LOG"
    fi
else
    echo "FAILED" >> "$LOG"
fi