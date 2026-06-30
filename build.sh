#!/bin/bash
export JAVA_HOME=/home/z/jdk-17.0.12
export ANDROID_HOME=/home/z/android-sdk
cd /home/z/my-project/BantuDroid
echo "Starting build at $(date)" > /tmp/build3.log
./gradlew assembleDebug --no-daemon >> /tmp/build3.log 2>&1
echo "Build exit code: $?" >> /tmp/build3.log
echo "Finished at $(date)" >> /tmp/build3.log
