#!/usr/bin/env sh
# ============================================================
#  SpellLock 一键构建（macOS / Linux）
#  用法：sh tools/build-install.sh
#  需要：JDK 17（JAVA_HOME）、Android SDK（local.properties 或 ANDROID_HOME）
# ============================================================
set -e
cd "$(dirname "$0")/.."

if [ -z "$JAVA_HOME" ]; then
  echo "错误：没有设置 JAVA_HOME（需要 JDK 17）。"
  echo "  macOS: export JAVA_HOME=$(/usr/libexec/java_home -v 17)"
  echo "  Linux: export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64"
  exit 1
fi

echo "[1/3] gradle assembleDebug ..."
./gradlew assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
echo "[2/3] 产物: $APK"

if command -v adb >/dev/null 2>&1; then
  echo "[3/3] 安装到手机 ..."
  if adb install -r "$APK"; then
    adb shell am start -n com.detox.spelllock/.MainActivity || true
  else
    echo "警告：adb install 失败。可以把 $APK 拷到手机 Download 目录，交给 DSH 安装。"
  fi
else
  echo "[3/3] 未找到 adb。请手动安装 $APK，或拷到手机 Download 目录交给 DSH。"
fi
