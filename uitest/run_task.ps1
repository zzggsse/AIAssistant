# 真机自动化回归：向 AI小助手 注入中文任务并监控视觉脑(AutoGLM)执行
# 用法: .\uitest\run_task.ps1 -Task '打开快手极速版，去签到领金币' [-Polls 8] [-IntervalSec 20]
# 前提: 手机已连接 adb；无障碍服务已开启（可用 一键增量部署.bat 恢复）
# 原理: 切到 ADBKeyboard 输入法(支持中文注入) -> am broadcast 写入输入框 -> 点发送
#       -> 轮询前台Activity + AIButler 日志 + 截图，结束后检查 crash buffer
param(
  [Parameter(Mandatory=$true)][string]$Task,
  [int]$Polls = 8,
  [int]$IntervalSec = 20
)
$ErrorActionPreference = 'Continue'
$ADB = 'D:\test\_tools\android-sdk\platform-tools\adb.exe'
$PKG = 'com.aibutler.app'
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$out = "D:\test\AIAssistant\_tmp\uitest\$stamp"
New-Item -ItemType Directory -Force -Path $out | Out-Null
function Adb { & $ADB @args }

if ((Adb get-state 2>$null) -ne 'device') { "[FAIL] 检测不到手机"; exit 1 }

# 1. 切 ADBKeyboard（中文注入必需；QQ拼音会吃掉 input text 的字符！）
$qqIme  = 'com.tencent.qqpinyin/.QQPYInputMethodService'
$adbIme = 'com.android.adbkeyboard/.AdbIME'
$origIme = ((Adb shell settings get secure default_input_method) -join '').Trim()
Adb shell ime enable $adbIme 2>$null | Out-Null
Adb shell ime set $adbIme 2>$null | Out-Null
Start-Sleep -Seconds 1

function Get-Dump([string]$name) {
  $null = Adb shell timeout 6 uiautomator dump /sdcard/$name.xml 2>$null
  $null = Adb pull /sdcard/$name.xml "$out\$name.xml" 2>$null
  if (Test-Path "$out\$name.xml") { Get-Content "$out\$name.xml" -Encoding utf8 -Raw } else { '' }
}
function Tap-Rid([string]$xml, [string]$rid) {
  $m = [regex]::Match($xml, 'resource-id="' + $PKG + ':id/' + $rid + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $m.Success) { $m = [regex]::Match($xml, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*resource-id="' + $PKG + ':id/' + $rid + '"') }
  if ($m.Success) {
    $x=[int](([int]$m.Groups[1].Value+[int]$m.Groups[3].Value)/2); $y=[int](([int]$m.Groups[2].Value+[int]$m.Groups[4].Value)/2)
    Adb shell input tap $x $y; Start-Sleep -Milliseconds 1000; return $true
  }
  return $false
}

try {
  # 2. 确保在主界面
  $crashBefore = ((Adb shell logcat -d -b crash 2>$null) -join "`n").Length
  Adb shell am start -n "$PKG/.MainActivity" | Out-Null
  Start-Sleep -Seconds 3
  $d = Get-Dump 'main'
  if ($d -notmatch 'etInput') { "[FAIL] 主界面没有 etInput"; exit 1 }

  # 3. 注入任务并发送
  $in = [regex]::Match($d, 'resource-id="' + $PKG + ':id/etInput"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $in.Success) { $in = [regex]::Match($d, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*resource-id="' + $PKG + ':id/etInput"') }
  $ix=[int](([int]$in.Groups[1].Value+[int]$in.Groups[3].Value)/2); $iy=[int](([int]$in.Groups[2].Value+[int]$in.Groups[4].Value)/2)
  Adb shell input tap $ix $iy; Start-Sleep -Milliseconds 800
  Adb shell am broadcast -a ADB_INPUT_TEXT --es msg $Task 2>$null | Out-Null
  Start-Sleep -Milliseconds 800
  Adb shell logcat -c 2>$null
  $d = Get-Dump 'sent'
  $sd = [regex]::Match($d, 'resource-id="' + $PKG + ':id/btnSend"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $sd.Success) { $sd = [regex]::Match($d, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*resource-id="' + $PKG + ':id/btnSend"') }
  $sdx=[int](([int]$sd.Groups[1].Value+[int]$sd.Groups[3].Value)/2); $sdy=[int](([int]$sd.Groups[2].Value+[int]$sd.Groups[4].Value)/2)
  Adb shell input tap $sdx $sdy
  "[OK] 任务已发送: $Task"

  # 4. 监控
  $done = $false
  for ($i = 1; $i -le $Polls; $i++) {
    Start-Sleep -Seconds $IntervalSec
    $fg = (((Adb shell dumpsys window 2>$null) | Select-String 'mCurrentFocus').Line -replace '.*u0 ','').Trim()
    $logs = (Adb shell logcat -d -s AIButler 2>$null) -join "`n" -split "`n" |
      Where-Object { $_ -match 'AGLM round|验证关|收尾|AGENT 结束|finish\(' } | Select-Object -Last 3
    "[poll $i] fg=$fg"
    $logs | ForEach-Object {
      $s = ($_ -replace '^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s*','').Trim()
      if ($s.Length -gt 200) { $s = $s.Substring(0,200) + '…' }
      "   L: $s"
    }
    Adb shell screencap -p /sdcard/m.png; $null = Adb pull /sdcard/m.png "$out\mon_$i.png" 2>$null
    if (($logs -join ' ') -match 'AGENT 结束|收尾') { $done = $true; break }
  }

  # 5. 终态检查
  Start-Sleep -Seconds 2
  Adb shell screencap -p /sdcard/f.png; $null = Adb pull /sdcard/f.png "$out\final.png" 2>$null
  $crash = (Adb shell logcat -d -b crash 2>$null) -join "`n"
  $crash | Out-File -Encoding utf8 "$out\crash.txt"
  $grew = (($crash.Length) - $crashBefore)
  $pid2 = ((Adb shell pidof $PKG) -join '').Trim()
  ""
  "================ 结果 ================"
  "任务结束标志: $done"
  "进程存活: $([bool]$pid2)  (pid=$pid2)"
  "crash 新增字节: $grew"
  "产物目录: $out"
  if (-not $done -or $grew -gt 0) { exit 2 }
} finally {
  # 6. 还原输入法
  if ($origIme -and $origIme -ne 'null') { Adb shell ime set $origIme 2>$null | Out-Null }
}
