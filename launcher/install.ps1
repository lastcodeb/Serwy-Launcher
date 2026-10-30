# Builds Neon, installs it on the Mi Box, grants its permission and makes it the default HOME.
# The current launcher (Projectivy) is NOT disabled; rollback.ps1 switches back to it.
#   powershell -ExecutionPolicy Bypass -File .\install.ps1 [-Device 192.168.110.32:5555] [-SkipBuild]
param(
    [string]$Device = "192.168.110.32:5555",
    [switch]$SkipBuild
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$adb = "C:\mibox\platform-tools\adb.exe"
$pkg = "dev.mibox.neon"
$home_activity = "$pkg/.MainActivity"
$apk = "$root\app\build\outputs\apk\release\app-release.apk"

function Adb-Shell([string]$cmd) { & $adb -s $Device shell $cmd }

if (-not $SkipBuild) {
    $env:JAVA_HOME = "C:\mibox\tools\jdk17"
    $env:ANDROID_HOME = "C:\mibox\tools\android-sdk"
    Push-Location $root
    try {
        # On Windows the Gradle daemon sometimes keeps R8's classes.dex open and the next build
        # fails with "file in use": on failure kill the daemons, wipe the dex folder, retry once
        # Gradle runs as its own process logging to a file: the daemon it leaves behind inherits
        # that file, not our stdout, so this script (and anything piping it) can finish
        $dex = "$root\app\build\intermediates\dex"
        $log = "$root\app\build\gradle-build.log"
        New-Item -ItemType Directory -Force "$root\app\build" | Out-Null
        foreach ($attempt in 1..2) {
            Remove-Item -Recurse -Force $dex -ErrorAction SilentlyContinue
            $g = Start-Process -FilePath "$root\gradlew.bat" -ArgumentList "assembleRelease", "--console=plain" `
                -WorkingDirectory $root -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
            $null = $g.Handle # without touching Handle first, ExitCode reads back as null
            $g.WaitForExit()
            Get-Content $log, "$log.err" | Select-String -Pattern "^e: |BUILD|What went wrong" | ForEach-Object { Write-Host $_.Line }
            if ($g.ExitCode -eq 0) { break }
            if ($attempt -eq 2) { throw "Gradle build failed" }
            Write-Host "Build failed, stopping Gradle daemons and retrying"
            Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
                Where-Object { $_.CommandLine -like '*GradleDaemon*' -or $_.CommandLine -like '*kotlin-daemon*' } |
                ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
            Start-Sleep 2
        }
    } finally { Pop-Location }
}

& $adb connect $Device | Out-Null
if (-not ((& $adb devices) -match [regex]::Escape($Device) + "\s+device")) { throw "Device $Device not connected" }

# Remember the current HOME so rollback.ps1 can return to it
$current = (Adb-Shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" | Select-Object -Last 1).Trim()
if ($current -and $current -notlike "$pkg/*") {
    Set-Content -Path "$root\previous_home.txt" -Value $current -Encoding ascii
    Write-Host "Previous HOME saved: $current"
}

Write-Host "Installing $apk"
& $adb -s $Device install -r $apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

# PACKAGE_USAGE_STATS is a special appop (last used app, showcase)
Adb-Shell "appops set $pkg GET_USAGE_STATS allow"
# READ_TV_LISTINGS is a runtime permission (İzlemeye devam et); a launcher has no good moment to
# ask for it, so grant it here. INTERNET, KILL_BACKGROUND_PROCESSES, QUERY_ALL_PACKAGES and
# READ_EPG_DATA are install-time permissions.
Adb-Shell "pm grant $pkg android.permission.READ_TV_LISTINGS"

# The remote's app buttons (RemoteKeys). Reinstalling unbinds an accessibility service, so it
# is switched off and on again every time
Adb-Shell "settings put secure enabled_accessibility_services null"
Start-Sleep 1
Adb-Shell "settings put secure enabled_accessibility_services $pkg/$pkg.RemoteKeys"
Adb-Shell "settings put secure accessibility_enabled 1"
# Now playing (play/pause for Spotify & co.): a notification listener that reads no notifications
Adb-Shell "cmd notification allow_listener $pkg/$pkg.data.MediaListener"

# Ahead-of-time compile everything for the fastest cold start
Adb-Shell "cmd package compile -m speed -f $pkg"

Adb-Shell "cmd package set-home-activity --user 0 $home_activity"
Adb-Shell "input keyevent KEYCODE_HOME"

Write-Host "HOME now:" (Adb-Shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" | Select-Object -Last 1)
