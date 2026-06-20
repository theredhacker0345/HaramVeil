#!/usr/bin/env bash
# Installs the Android SDK (cmdline-tools, platform-tools, platforms, build-tools,
# emulator, and a system image) so the Android Emulator can run inside the Codespace.
# Designed for the Ubuntu 24.04 devcontainer base (glibc-based) so that the
# emulator's prebuilt QEMU binaries (which require glibc) work correctly.
set -euo pipefail

echo "=== [setup-android-sdk] Starting Android SDK setup ==="

ANDROID_HOME="/opt/android-sdk"
SDK_MANAGER_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

# Create SDK directory
sudo mkdir -p "${ANDROID_HOME}/cmdline-tools"
sudo chown -R "$(id -u):$(id -g)" "${ANDROID_HOME}"

# Download and install cmdline-tools if not present
if [ ! -d "${ANDROID_HOME}/cmdline-tools/latest" ]; then
    echo ">>> Downloading Android cmdline-tools..."
    cd /tmp
    curl -fsSL -o cmdtools.zip "${SDK_MANAGER_URL}"
    unzip -q cmdtools.zip -d "${ANDROID_HOME}/cmdline-tools"
    mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest"
    rm -f cmdtools.zip
    echo ">>> cmdline-tools installed."
fi

export ANDROID_HOME="${ANDROID_HOME}"
export PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator:${PATH}"

# Accept all SDK licenses
echo ">>> Accepting SDK licenses..."
yes | sdkmanager --licenses >/dev/null 2>&1 || true

# Install required SDK packages (including emulator + system image for testing)
echo ">>> Installing SDK packages (platform-tools, platforms, build-tools, emulator, system-image)..."
sdkmanager \
    "platform-tools" \
    "platforms;android-36" \
    "build-tools;36.0.0" \
    "emulator" \
    "system-images;android-36;google_apis;x86_64"

# Create a default AVD for testing
echo ">>> Creating AVD 'haramveil_avd'..."
echo "no" | avdmanager create avd \
    -n haramveil_avd \
    -k "system-images;android-36;google_apis;x86_64" \
    -d pixel_6 \
    --force || true

# Create local.properties pointing at the SDK
if [ -d /workspaces/HaramVeil ]; then
    echo ">>> Writing local.properties..."
    echo "sdk.dir=${ANDROID_HOME}" > /workspaces/HaramVeil/local.properties
fi

echo "=== [setup-android-sdk] Done. ==="
echo ">>> To boot the emulator:"
echo "    emulator -avd haramveil_avd -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect"
