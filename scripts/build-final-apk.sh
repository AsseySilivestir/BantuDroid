#!/bin/bash
cd /home/z/my-project/BantuDroid
export ANDROID_HOME=/home/z/android-sdk
export ANDROID_SDK_ROOT=/home/z/android-sdk
export JAVA_HOME=/home/z/jdk17
export PATH="$JAVA_HOME/bin:$PATH"

# Ensure local.properties exists
echo "sdk.dir=/home/z/android-sdk" > local.properties

echo "=== Building APK ==="
./gradlew assembleDebug --no-daemon 2>&1

RESULT=$?
echo "=== Build finished with exit code: $RESULT ==="

if [ $RESULT -eq 0 ] && [ -f app/build/outputs/apk/debug/app-debug.apk ]; then
    mkdir -p /home/z/my-project/download
    cp -f app/build/outputs/apk/debug/app-debug.apk /home/z/my-project/download/BantuDroid-debug.apk
    echo "=== APK READY ==="
fi

echo $RESULT > /tmp/apk-build-exit.txt
