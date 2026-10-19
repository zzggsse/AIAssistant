@echo off
chcp 65001 >nul
rem 一键运行本地逻辑回归测试（不需要手机，不需要登录任何App）
set JAVA_HOME=D:\java\JDK21
D:\test\_tools\gradle-8.7\bin\gradle.bat -p %~dp0 testDebugUnitTest --tests "com.aibutler.app.AgentLogicTest"
echo.
echo ============================================
echo 测试完成。看到 BUILD SUCCESSFUL = 全部通过。
echo 如果是 BUILD FAILED，往上翻找带 (AgentLogicTest) 的行就是失败项。
echo ============================================
pause
