#!/bin/bash
# bootstrap.sh — one-time setup on Fedora Linux.
# Downloads the Gradle wrapper jar and Android SDK command-line tools.
# Run this once before your first ./gradlew build.
set -euo pipefail

GRADLE_VERSION="8.3"
ANDROID_CMDTOOLS_VERSION="11076708"   # r11, works with AGP 8.1

echo "==> Checking for JDK 17..."
if ! java -version 2>&1 | grep -q "17\|21"; then
    echo "    Installing openjdk-17..."
    sudo dnf install -y java-17-openjdk java-17-openjdk-devel
fi
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
echo "    JAVA_HOME=$JAVA_HOME"

echo ""
echo "==> Downloading Gradle $GRADLE_VERSION wrapper jar..."
mkdir -p gradle/wrapper
WRAPPER_JAR_URL="https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}.0/gradle/wrapper/gradle-wrapper.jar"
curl -fsSL "$WRAPPER_JAR_URL" -o gradle/wrapper/gradle-wrapper.jar
chmod +x gradlew
echo "    gradle/wrapper/gradle-wrapper.jar downloaded."

echo ""
echo "==> Setting up Android SDK command-line tools..."
ANDROID_HOME="${HOME}/android-sdk"
mkdir -p "${ANDROID_HOME}/cmdline-tools"

CMDTOOLS_ZIP="/tmp/cmdline-tools.zip"
curl -fsSL \
  "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDTOOLS_VERSION}_latest.zip" \
  -o "$CMDTOOLS_ZIP"
unzip -q "$CMDTOOLS_ZIP" -d /tmp/android-cmdtools
mkdir -p "${ANDROID_HOME}/cmdline-tools/latest"
cp -r /tmp/android-cmdtools/cmdline-tools/* "${ANDROID_HOME}/cmdline-tools/latest/"
rm -rf /tmp/android-cmdtools "$CMDTOOLS_ZIP"

export PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

echo ""
echo "==> Accepting Android SDK licenses..."
yes | sdkmanager --licenses >/dev/null 2>&1 || true

echo ""
echo "==> Installing SDK platform 34 and build tools..."
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo ""
echo "==> Writing local.properties..."
cat > local.properties <<EOF
sdk.dir=${ANDROID_HOME}
EOF

echo ""
echo "══════════════════════════════════════════════════════════"
echo " Bootstrap complete."
echo ""
echo " Add these to your ~/.bashrc (or run them now in your shell):"
echo ""
echo "   export ANDROID_HOME=${ANDROID_HOME}"
echo "   export PATH=\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools"
echo ""
echo " Then build the debug APK:"
echo "   ./gradlew assembleDebug"
echo ""
echo " APK will appear at:"
echo "   app/build/outputs/apk/debug/app-debug.apk"
echo "══════════════════════════════════════════════════════════"
