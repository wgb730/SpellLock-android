@echo off
rem ============================================================
rem  SpellLock 一键构建（Windows）
rem  用法：双击运行，或在 cmd 里执行 tools\build-install.bat
rem  需要：JDK 17（JAVA_HOME 指向它）、Android Studio 自带的 SDK
rem ============================================================
setlocal
cd /d "%~dp0.."

if not defined JAVA_HOME (
  echo [ERROR] 没有设置 JAVA_HOME。请安装 JDK 17 并把它设成 JAVA_HOME。
  echo         例：set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
  exit /b 1
)

echo [1/3] Gradle assembleDebug ...
call gradlew.bat assembleDebug
if errorlevel 1 (
  echo [ERROR] 构建失败，请把上面的报错发出来。
  exit /b 1
)

set APK=app\build\outputs\apk\debug\app-debug.apk
echo [2/3] 产物: %APK%

where adb >nul 2>nul
if errorlevel 1 (
  echo [3/3] 未找到 adb。请手动把 %APK% 拷到手机里安装。
  echo       或把手机 Download 目录里的 APK 交给 DSH 安装。
  exit /b 0
)

echo [3/3] 安装到手机 ...
adb install -r "%APK%"
if errorlevel 1 (
  echo [WARN] adb install 失败（手机没连上？驱动？未授权？）。
  echo        也可以把 %APK% 拷到手机的 Download 目录，再用 DSH 的 adb 安装。
  exit /b 0
)

echo 启动 App ...
adb shell am start -n com.detox.spelllock/.MainActivity
echo.
echo 完成。下一步：打开无障碍服务（设置 - 无障碍 - 拼写自律锁）。
