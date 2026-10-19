# Record a manual device walk for later path analysis.
# Usage: .\uitest\record_walk.ps1 [-Seconds 300]
# NOTE: keep this file ASCII-only (PS5.1 GBK misparses BOM-less UTF-8).
# Known limits (see README): Douyin Lite pages expose almost NO a11y text nodes,
# so signature-based page-change detection rarely fires there; screenshots are
# captured on a fixed interval instead as the fallback evidence.
param([int]$Seconds = 300)
$ErrorActionPreference = 'Continue'
$ADB = 'D:\test\_tools\android-sdk\platform-tools\adb.exe'
function Adb { & $ADB @args }
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$out = "D:\test\AIAssistant\_tmp\uitest\manual_$stamp"
New-Item -ItemType Directory -Force -Path $out | Out-Null
Write-Output "REC START $out"
$deadline = (Get-Date).AddSeconds($Seconds)
$i = 0
$lastSig = ''
$lastShot = -10
while ((Get-Date) -lt $deadline) {
  $ts = Get-Date -Format 'HH:mm:ss'
  $fg = (((Adb shell dumpsys window 2>$null) | Select-String 'mCurrentFocus').Line -replace '.*u0 ', '').Trim()
  $null = Adb shell timeout 5 uiautomator dump /sdcard/rec.xml 2>$null
  $null = Adb pull /sdcard/rec.xml "$out\rec_$i.xml" 2>$null
  $sig = ''
  $nodes = ''
  $d = $null
  if (Test-Path "$out\rec_$i.xml") { $d = Get-Content "$out\rec_$i.xml" -Encoding utf8 -Raw }
  if ($d) {
    $sigs = [regex]::Matches($d, ' (text|content-desc)="([^"]{1,40})"') |
      ForEach-Object { $_.Groups[2].Value } | Where-Object { $_ -ne '' } | Sort-Object -Unique
    $sig = ($sigs -join '|')
    $nodes = ($sigs | Select-Object -First 30) -join ' / '
  }
  $changed = ($sig -ne $lastSig)
  $line = "[$i] $ts fg=$fg siglen=$($sig.Length)"
  $line | Out-File "$out\trace.log" -Append -Encoding utf8
  if ($changed) {
    if ($nodes) { "    nodes: $nodes" | Out-File "$out\trace.log" -Append -Encoding utf8 }
    $lastSig = $sig
  }
  # screenshot every ~4 iterations (dump cycle is slow) as fallback evidence
  if ($i - $lastShot -ge 4) {
    Adb shell screencap -p /sdcard/r.png; $null = Adb pull /sdcard/r.png "$out\shot_$i.png" 2>$null
    $lastShot = $i
  }
  $i++
  Start-Sleep -Milliseconds 1800
}
Write-Output "REC DONE $out ($i frames)"
