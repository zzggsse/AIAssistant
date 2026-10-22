@echo off
chcp 65001 >nul
cd /d %~dp0
rem 一键抓取真机日志：完整 logcat + 崩溃缓冲区，保存到 _tmp 目录
set "ADB="
where adb >nul 2>nul
if not errorlevel 1 set "ADB=adb"
if not defined ADB if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
if not defined ADB (
  echo [失败] 找不到 adb，请先安装 Android Platform Tools 或将其加入 PATH。
  pause & exit /b 1
)
if not exist "_tmp" mkdir "_tmp"

echo [1/3] 检查设备连接...
"%ADB%" -d devices
"%ADB%" -d get-state >nul 2>&1
if errorlevel 1 (
  echo [失败] 检测不到手机，检查 USB 线和授权弹窗。
  pause & exit /b 1
)

echo [2/3] 抓取主日志缓冲区...
"%ADB%" -d logcat -d -v time > "_tmp\logcat_latest.txt" 2>&1

echo [3/3] 抓取崩溃缓冲区...
"%ADB%" -d logcat -d -b crash -v time > "_tmp\logcat_crash.txt" 2>&1

echo 完成。输出文件：
echo   _tmp\logcat_latest.txt  ^(完整日志^)
echo   _tmp\logcat_crash.txt   ^(崩溃日志^)
pause
