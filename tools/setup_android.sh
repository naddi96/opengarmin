#!/usr/bin/env bash
# Sets up Gradle + Android SDK in user space (no sudo). Background-friendly.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$ROOT/tools"
mkdir -p "$TOOLS/dl"
cd "$TOOLS"

export JAVA_HOME="$TOOLS/jdk"
export PATH="$JAVA_HOME/bin:$PATH"

say() { printf '\n=== %s ===\n' "$*"; }

# ---------- Gradle ----------
GRADLE_VER=8.7
if [ ! -x "$TOOLS/gradle-$GRADLE_VER/bin/gradle" ]; then
  say "Downloading Gradle $GRADLE_VER"
  curl -fsSL -o dl/gradle.zip \
    "https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip"
  unzip -o -q dl/gradle.zip -d "$TOOLS"
fi
"$TOOLS/gradle-$GRADLE_VER/bin/gradle" --version | head -8

# ---------- Android SDK command line tools ----------
SDK="$TOOLS/android-sdk"
mkdir -p "$SDK/cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  say "Downloading Android command-line tools"
  curl -fsSL -o dl/cmdline-tools.zip \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  unzip -o -q dl/cmdline-tools.zip -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest" 2>/dev/null || true
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
SDKMGR="$SDK/cmdline-tools/latest/bin/sdkmanager"

say "Accepting licenses + installing SDK packages"
yes | "$SDKMGR" --sdk_root="$SDK" --licenses > /dev/null 2>&1 || true
"$SDKMGR" --sdk_root="$SDK" "platform-tools" "platforms;android-34" "build-tools;34.0.0" > /dev/null 2>&1

say "DONE Android SDK at $SDK"
ls "$SDK"
