@echo off
rem 一键编译 AI小助手 APK
set JAVA_HOME=D:\java\JDK21
D:\test\_tools\gradle-8.7\bin\gradle.bat -p %~dp0 assembleDebug
if exist "%~dp0app\build\outputs\apk\debug\app-debug.apk" (
  copy /y "%~dp0app\build\outputs\apk\debug\app-debug.apk" "%~dp0..\AI小助手.apk" >nul
  echo.
  echo 编译完成! APK 已复制到 D:\test\AI小助手.apk
)
pause
