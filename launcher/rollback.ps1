# Switches HOME away from Neon: back to the launcher that was active before install.ps1 if it is
# still installed, otherwise to the stock Google TV launcher (re-enabled for the purpose).
# Neon stays installed; add -Uninstall to remove it as well.
#   powershell -ExecutionPolicy Bypass -File .\rollback.ps1 [-Device 192.168.110.31:5555] [-Uninstall]
param(
    [string]$Device = "192.168.110.31:5555",
    [switch]$Uninstall
)
$ErrorActionPreference = "Stop"
$adb = "C:\mibox\platform-tools\adb.exe"
$pkg = "dev.mibox.neon"
$googleTv = "com.google.android.apps.tv.launcherx/.home.HomeActivity"
$saved = "$PSScriptRoot\previous_home.txt"

& $adb connect $Device | Out-Null
$previous = if (Test-Path $saved) { (Get-Content $saved -TotalCount 1).Trim() } else { $null }
$installed = $previous -and ((& $adb -s $Device shell "pm list packages $($previous.Split('/')[0])") -match "package:")

if ($installed) {
    $target = $previous
} else {
    # Projectivy was uninstalled: fall back to the Google TV launcher debloat.sh disabled
    & $adb -s $Device shell "pm enable --user 0 com.google.android.apps.tv.launcherx"
    $target = $googleTv
}
& $adb -s $Device shell "cmd package set-home-activity --user 0 $target"
if ($Uninstall) { & $adb -s $Device uninstall $pkg }
& $adb -s $Device shell "input keyevent KEYCODE_HOME"

Write-Host "HOME now:" (& $adb -s $Device shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" | Select-Object -Last 1)
