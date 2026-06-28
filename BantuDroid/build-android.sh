#!/bin/bash
# ──────────────────────────────────────────────────────────────
# build-android.sh — Cross-compile Bantu for Android (arm64)
# 
# Usage:
#   ./build-android.sh [/path/to/Bantu/source] [/path/to/BantuDroid]
#
# Prerequisites (Ubuntu/Debian):
#   sudo apt install g++-aarch64-linux-gnu cmake ndk-sdk
#
# Or use the Android NDK standalone toolchain:
#   export NDK=/path/to/android-ndk
#   $NDK/build/tools/make_standalone_toolchain.py \
#       --arch arm64 --api 24 --install-dir ~/android-toolchain
# ──────────────────────────────────────────────────────────────

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Configuration
BANTU_SRC="${1:-../Bantu/bantu-src}"
BANTUDROID="${2:-.}"
BUILD_DIR="/tmp/bantu-android-build"
NDK_PATH="${NDK:-$HOME/android-ndk}"

echo -e "${GREEN}═══════════════════════════════════════════${NC}"
echo -e "${GREEN}  Bantu Android Cross-Compiler${NC}"
echo -e "${GREEN}═══════════════════════════════════════════${NC}"
echo ""
echo "Bantu source:  $BANTU_SRC"
echo "BantuDroid:    $BANTUDROID"
echo "Build dir:     $BUILD_DIR"
echo ""

# ── Check NDK ──────────────────────────────────────────
if [ ! -d "$NDK_PATH" ]; then
    echo -e "${YELLOW}Android NDK not found at $NDK_PATH${NC}"
    echo "Trying system cross-compiler instead..."
    
    if ! command -v aarch64-linux-gnu-g++ &> /dev/null; then
        echo -e "${RED}ERROR: Neither NDK nor aarch64-linux-gnu-g++ found!${NC}"
        echo ""
        echo "Install the cross-compiler:"
        echo "  sudo apt install g++-aarch64-linux-gnu"
        echo ""
        echo "Or download the NDK:"
        echo "  https://developer.android.com/ndk/downloads"
        exit 1
    fi
    
    TOOLCHAIN="system"
else
    TOOLCHAIN="ndk"
fi

# ── Clean build directory ──────────────────────────────
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

# ── Build with system cross-compiler ──────────────────
build_with_system_toolchain() {
    echo -e "${GREEN}Building with system aarch64-linux-gnu-g++...${NC}"
    
    cd "$BANTU_SRC"
    mkdir -p "$BUILD_DIR/system"
    cd "$BUILD_DIR/system"
    
    cmake "$BANTU_SRC" \
        -DCMAKE_SYSTEM_NAME=Linux \
        -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
        -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc \
        -DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++ \
        -DCMAKE_BUILD_TYPE=Release \
        -DSTATIC_LINKING=ON \
        -DCMAKE_EXE_LINKER_FLAGS="-static"
    
    make -j$(nproc)
    
    # Verify
    file bantu
    echo ""
}

# ── Build with Android NDK ────────────────────────────
build_with_ndk() {
    echo -e "${GREEN}Building with Android NDK...${NC}"
    
    local API_LEVEL=24  # Android 7.0+
    
    # Set up NDK toolchain
    local TOOLCHAIN_FILE="$NDK_PATH/build/cmake/android.toolchain.cmake"
    
    if [ ! -f "$TOOLCHAIN_FILE" ]; then
        echo -e "${RED}NDK toolchain file not found: $TOOLCHAIN_FILE${NC}"
        exit 1
    fi
    
    # Build for arm64-v8a
    mkdir -p "$BUILD_DIR/ndk-arm64"
    cd "$BUILD_DIR/ndk-arm64"
    
    cmake "$BANTU_SRC" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-$API_LEVEL \
        -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release
    
    make -j$(nproc)
    
    # Copy to assets
    local ASSET_DIR="$BANTUDROID/app/src/main/assets/bin/arm64-v8a"
    mkdir -p "$ASSET_DIR"
    cp bantu "$ASSET_DIR/bantu"
    chmod +x "$ASSET_DIR/bantu"
    
    echo -e "${GREEN}arm64-v8a binary copied to assets${NC}"
    
    # Build for armeabi-v7a
    mkdir -p "$BUILD_DIR/ndk-armv7"
    cd "$BUILD_DIR/ndk-armv7"
    
    cmake "$BANTU_SRC" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI=armeabi-v7a \
        -DANDROID_PLATFORM=android-$API_LEVEL \
        -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release
    
    make -j$(nproc)
    
    local ASSET_DIR="$BANTUDROID/app/src/main/assets/bin/armeabi-v7a"
    mkdir -p "$ASSET_DIR"
    cp bantu "$ASSET_DIR/bantu"
    chmod +x "$ASSET_DIR/bantu"
    
    echo -e "${GREEN}armeabi-v7a binary copied to assets${NC}"
    
    # Build for x86_64 (emulator)
    mkdir -p "$BUILD_DIR/ndk-x86_64"
    cd "$BUILD_DIR/ndk-x86_64"
    
    cmake "$BANTU_SRC" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI=x86_64 \
        -DANDROID_PLATFORM=android-$API_LEVEL \
        -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release
    
    make -j$(nproc)
    
    local ASSET_DIR="$BANTUDROID/app/src/main/assets/bin/x86_64"
    mkdir -p "$ASSET_DIR"
    cp bantu "$ASSET_DIR/bantu"
    chmod +x "$ASSET_DIR/bantu"
    
    echo -e "${GREEN}x86_64 binary copied to assets (for emulator)${NC}"
}

# ── Execute build ──────────────────────────────────────
if [ "$TOOLCHAIN" = "ndk" ]; then
    build_with_ndk
else
    build_with_system_toolchain
    
    # Copy to assets
    ASSET_DIR="$BANTUDROID/app/src/main/assets/bin/arm64-v8a"
    mkdir -p "$ASSET_DIR"
    cp "$BUILD_DIR/system/bantu" "$ASSET_DIR/bantu"
    chmod +x "$ASSET_DIR/bantu"
    
    echo -e "${GREEN}Binary copied to assets${NC}"
fi

# ── Summary ────────────────────────────────────────────
echo ""
echo -e "${GREEN}═══════════════════════════════════════════${NC}"
echo -e "${GREEN}  Build Complete!${NC}"
echo -e "${GREEN}═══════════════════════════════════════════${NC}"
echo ""
echo "Binary location: $BANTUDROID/app/src/main/assets/bin/"
echo ""
echo "Next steps:"
echo "  1. Open BantuDroid in Android Studio"
echo "  2. Build & Run on a device or emulator"
echo "  3. Type: bantu run bantuddns.b"
echo ""
