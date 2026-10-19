# On-device regression: inject a Chinese task into AI Assistant and monitor the AutoGLM vision loop.
# Usage: .\uitest\run_task.ps1 -Task '...' [-Polls 8] [-IntervalSec 20]
# Requires: device connected via adb; accessibility service enabled (use OneClickDeploy.bat to restore).
# NOTE: keep this file ASCII-only. PowerShell 5.1 misparses BOM-less UTF-8 literals under GBK codepage.
#       Chinese flows in as a runtime parameter, which is safe.
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

if ((Adb get-state 2>$null) -ne 'device') { Write-Output '[FAIL] no device'; exit 1 }

# 1. Switch to ADBKeyboard IME (required for Chinese injection; QQ Pinyin corrupts input text)
$adbIme = 'com.android.adbkeyboard/.AdbIME'
$origIme = ((Adb shell settings get secure default_input_method) -join '').Trim()
$imeOk = $false
foreach ($try in 1..3) {
  Adb shell ime enable $adbIme 2>$null | Out-Null
  Adb shell ime set $adbIme 2>$null | Out-Null
  Start-Sleep -Seconds 2
  $cur = ((Adb shell settings get secure default_input_method) -join '').Trim()
  if ($cur -eq $adbIme) { $imeOk = $true; break }
  Write-Output "[warn] ime set did not stick (try $try): $cur"
}
if (-not $imeOk) { Write-Output '[FAIL] cannot activate ADBKeyboard'; exit 1 }

function Get-Dump([string]$name) {
  $null = Adb shell timeout 6 uiautomator dump /sdcard/$name.xml 2>$null
  $null = Adb pull /sdcard/$name.xml "$out\$name.xml" 2>$null
  if (Test-Path "$out\$name.xml") { Get-Content "$out\$name.xml" -Encoding utf8 -Raw } else { '' }
}
function Tap-Rid([string]$xml, [string]$rid) {
  $m = [regex]::Match($xml, 'resource-id="' + $PKG + ':id/' + $rid + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $m.Success) { $m = [regex]::Match($xml, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*resource-id="' + $PKG + ':id/' + $rid + '"') }
  if ($m.Success) {
    $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    Adb shell input tap $x $y; Start-Sleep -Milliseconds 1000; return $true
  }
  return $false
}

try {
  $crashBefore = ((Adb shell logcat -d -b crash 2>$null) -join "`n").Length

  # 2. Ensure main screen
  Adb shell am start -n "$PKG/.MainActivity" | Out-Null
  Start-Sleep -Seconds 3
  $d = Get-Dump 'main'
  if ($d -notmatch 'etInput') { Write-Output '[FAIL] etInput not found on main screen'; exit 1 }

  # 3. Inject task and send
  $in = [regex]::Match($d, 'resource-id="' + $PKG + ':id/etInput"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $in.Success) { $in = [regex]::Match($d, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*resource-id="' + $PKG + ':id/etInput"') }
  $ix = [int](([int]$in.Groups[1].Value + [int]$in.Groups[3].Value) / 2)
  $iy = [int](([int]$in.Groups[2].Value + [int]$in.Groups[4].Value) / 2)
  Adb shell input tap $ix $iy; Start-Sleep -Milliseconds 800
  # clear any leftover text, then inject with verification (ime/broadcast can silently fail)
  Adb shell input keycombination 113 29 2>$null
  Adb shell input keyevent 67 2>$null
  Start-Sleep -Milliseconds 400
  $injected = $false
  foreach ($try in 1..3) {
    Adb shell am broadcast -a ADB_INPUT_TEXT --es msg $Task 2>$null | Out-Null
    Start-Sleep -Milliseconds 1000
    $d2 = Get-Dump "inj$try"
    $m2 = [regex]::Match($d2, ' text="([^"]*)"[^>]*resource-id="' + $PKG + ':id/etInput"')
    if ($m2.Success -and $m2.Groups[1].Value.Trim() -eq $Task.Trim()) { $injected = $true; break }
    Write-Output "[warn] injection mismatch (try $try): [$($m2.Groups[1].Value)]"
    Adb shell input tap $ix $iy; Start-Sleep -Milliseconds 600
    Adb shell input keycombination 113 29 2>$null
    Adb shell input keyevent 67 2>$null
    Start-Sleep -Milliseconds 400
  }
  if (-not $injected) { Write-Output '[FAIL] task text injection failed'; exit 1 }
  Adb shell logcat -c 2>$null
  $d = Get-Dump 'sent'
  $sd = [regex]::Match($d, 'resource-id="' + $PKG + ':id/btnSend"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $sd.Success) { $sd = [regex]::Match($d, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*resource-id="' + $PKG + ':id/btnSend"') }
  $sdx = [int](([int]$sd.Groups[1].Value + [int]$sd.Groups[3].Value) / 2)
  $sdy = [int](([int]$sd.Groups[2].Value + [int]$sd.Groups[4].Value) / 2)
  Adb shell input tap $sdx $sdy
  Write-Output "[OK] task sent: $Task"

  # 4. Monitor
  $done = $false
  for ($i = 1; $i -le $Polls; $i++) {
    Start-Sleep -Seconds $IntervalSec
    $fg = (((Adb shell dumpsys window 2>$null) | Select-String 'mCurrentFocus').Line -replace '.*u0 ', '').Trim()
    $logs = (Adb shell logcat -d -s AIButler 2>$null) -join "`n" -split "`n" |
      Where-Object { $_ -match 'AGLM round|finish|AGENT' } | Select-Object -Last 3
    Write-Output "[poll $i] fg=$fg"
    $logs | ForEach-Object {
      $s = ($_ -replace '^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s*', '').Trim()
      if ($s.Length -gt 200) { $s = $s.Substring(0, 200) + '...' }
      Write-Output "   L: $s"
    }
    Adb shell screencap -p /sdcard/m.png; $null = Adb pull /sdcard/m.png "$out\mon_$i.png" 2>$null
    # task end = model issued finish() AND our app was pulled back to foreground
    if (($logs -join ' ') -match 'finish\(' -and $fg -match 'aibutler\.app') { $done = $true; break }
  }

  # 5. Final state
  Start-Sleep -Seconds 2
  Adb shell screencap -p /sdcard/f.png; $null = Adb pull /sdcard/f.png "$out\final.png" 2>$null
  $crash = (Adb shell logcat -d -b crash 2>$null) -join "`n"
  $crash | Out-File -Encoding utf8 "$out\crash.txt"
  $grew = $crash.Length - $crashBefore
  $pid2 = ((Adb shell pidof $PKG) -join '').Trim()
  Write-Output ''
  Write-Output '================ RESULT ================'
  Write-Output "task finished marker: $done"
  Write-Output "process alive: $([bool]$pid2) (pid=$pid2)"
  Write-Output "crash buffer grew: $grew bytes"
  Write-Output "artifacts: $out"
  if (-not $done -or $grew -gt 0) { exit 2 }
} finally {
  if ($origIme -and $origIme -ne 'null') { Adb shell ime set $origIme 2>$null | Out-Null }
}
