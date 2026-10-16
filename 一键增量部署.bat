@echo off
chcp 65001 >nul
cd /d %~dp0
rem ============================================================
rem  一键增量部署：编译 → 重装 → 自动恢复无障碍授权 → 启动
rem  解决：每次 adb install -r 后无障碍服务被系统断开的问题
rem ============================================================
set "JAVA_HOME=D:\java\JDK21"
set "GRADLE=D:\test\_tools\gradle-8.7\bin\gradle.bat"
set "ADB=D:\test\_tools\android-sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

echo [1/5] 检查手机连接...
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo [失败] 检测不到手机。检查 USB 线和授权弹窗。
  pause & exit /b 1
)

echo [2/5] 保存当前无障碍/通知监听授权...
set "PKG=com.aibutler.app"
set "SVC=%PKG%/%PKG%.AssistantAccessibilityService"
set "A11Y_BEFORE="
for /f "usebackq delims=" %%i in (`"%ADB%" shell settings get secure enabled_accessibility_services 2^>nul`) do set "A11Y_BEFORE=%%i"
set "NOTIF_BEFORE="
for /f "usebackq delims=" %%i in (`"%ADB%" shell settings get secure enabled_notification_listeners 2^>nul`) do set "NOTIF_BEFORE=%%i"
echo    无障碍: %A11Y_BEFORE%
echo    通知监听: %NOTIF_BEFORE%

echo [3/5] 编译 APK...
call "%GRADLE%" -p "%~dp0" assembleDebug
if errorlevel 1 (
  echo [失败] 编译没通过。
  pause & exit /b 1
)
if not exist "%~dp0app\build\outputs\apk\debug\app-debug.apk" (
  echo [失败] 没找到 APK。
  pause & exit /b 1
)

echo [4/5] 增量安装（不卸载，保留数据）...
"%ADB%" install -r "%~dp0app\build\outputs\apk\debug\app-debug.apk"
if errorlevel 1 (
  echo [失败] 安装没成功。手机上如果弹了确认框，点允许后重跑。
  pause & exit /b 1
)

echo [5/5] 恢复授权并启动应用...
rem 恢复无障碍：确保 AI小助手 在列表里
echo "%A11Y_BEFORE%" | findstr /C:"%SVC%" >nul
if errorlevel 1 (
  if "%A11Y_BEFORE%"=="" (
    "%ADB%" shell settings put secure enabled_accessibility_services "%SVC%"
  ) else (
    "%ADB%" shell settings put secure enabled_accessibility_services "%A11Y_BEFORE%:%SVC%"
  )
) else (
  "%ADB%" shell settings put secure enabled_accessibility_services "%A11Y_BEFORE%"
)
rem 恢复通知监听（如果之前授权过且列表里有本应用）
if not "%NOTIF_BEFORE%"=="" (
  echo "%NOTIF_BEFORE%" | findstr /C:"%PKG%" >nul
  if not errorlevel 1 "%ADB%" shell settings put secure enabled_notification_listeners "%NOTIF_BEFORE%"
)
rem 确保授权开关总开关打开
"%ADB%" shell settings put secure accessibility_enabled 1
timeout /t 2 /nobreak >nul
"%ADB%" shell am force-stop %PKG%
timeout /t 1 /nobreak >nul
"%ADB%" shell am start -n %PKG%/.MainActivity

echo.
echo ============================================
echo  部署完成！无障碍已自动恢复，无需手动操作。
echo  如果屏幕视觉（截图权限）掉了，在应用里点一次 👁 即可。
echo ============================================
pause
