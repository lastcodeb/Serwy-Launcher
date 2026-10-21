# Serwy Launcher

A fast, custom Android TV home screen (app name **Neon**) plus a debloat toolkit, built for the
**Xiaomi Mi TV Box S (2nd Gen)** running Google TV / Android 11. Everything is set up from a
Windows PC over Wi-Fi ADB. Nothing needs root.

The launcher's UI text is in Turkish; code, scripts and docs are in English.

![Home screen: continue watching](docs/screenshots/home-continue-watching.png)

| | |
|---|---|
| ![Favorites](docs/screenshots/home-favorites.png) | ![For you](docs/screenshots/home-for-you.png) |
| ![All apps](docs/screenshots/home-all-apps.png) | ![Long-press menu](docs/screenshots/app-menu.png) |

---

## Contents

1. [Features](#features)
2. [Tested hardware](#tested-hardware)
3. [Repository layout](#repository-layout)
4. [Requirements](#requirements)
5. [Quick start](#quick-start)
6. [Full setup, step by step](#full-setup-step-by-step)
7. [Using the launcher](#using-the-launcher)
8. [Permissions](#permissions)
9. [How it works](#how-it-works)
10. [Performance](#performance)
11. [Troubleshooting](#troubleshooting)
12. [Known limitations](#known-limitations)
13. [Undo everything](#undo-everything)
14. [Credits and licenses](#credits-and-licenses)

---

## Features

### Home screen
- **Hero** that follows focus: the focused app or episode in a huge gradient title over a faint
  version of the app's real icon, with the whole background glow tinted by the app's colors
  (extracted with Palette from its own banner). Idle for 30 s, it cycles through your favorites.
- **Rows**, one on screen at a time, sliding on a spring:
  - **Favoriler**: apps you pin (smaller cards).
  - **İzlemeye devam et** (continue watching): the system Watch Next list, at most 2 items per
    app, with real thumbnails and progress bars. Opens straight into the episode.
  - **Senin için** (for you): the apps' own recommendation channels, at most 3 per app, mixed
    round-robin.
  - **Tüm uygulamalar** (all apps): every launchable TV app as a 16:9 card with its original
    banner. Long-press to move, hide, favorite, set a light profile or open app info.
- **Top bar**: clock, date and weather (Open-Meteo, no key), WiZ light chip, speed test, RAM usage
  with a one-press "close background apps" button, settings.
- **Control center** (press the clock chip): big clock and weather, light presets, system tools,
  sleep timer, hidden apps, city.
- Pixel-exact layout: the UI is laid out in a fixed 960×540 dp design space (the 1920×1080 design
  at 2×), so it looks identical on any panel density.

### WiZ backlight (WiZ TV Sync Box SYBX-EU-55/65, any WiZ light)
Local UDP control (port 38899), no cloud, discovered automatically.
- On/off, brightness, 8 neon colors, 3 whites, all 32 WiZ scenes, effect speed.
- **Quick access** row with your favorite presets; long-press the bulb chip to toggle Party.
- **Ambient mode**: on the home screen the light takes the focused app's color, and goes back to
  whatever it showed before when you open an app. A light that is off is never switched on.
- **Per-app light profiles**: e.g. Netflix → Film (warm, 30 %), Spotify → Party.
- Effects: a flash in the app's color on launch, a purple "breath" when clearing RAM, a red→green
  gauge during the speed test, a weather-colored light, and a sleep timer that dims then turns
  the light off.

### Screensaver
A real Android `DreamService`: real sea-and-sky footage (Pixabay) chosen by the **actual time of
day and weather** in your city: moonlit sea, sunrise, open sea, sunset, clouds, rain, storm,
fog, snow. Sunrise and sunset times come from Open-Meteo. Hardware-decoded, streamed through a
100 MB cache, 4K when the HDMI output is 4K. Clock, date and weather on top; the WiZ light
follows the scene and is restored afterwards.

### Debloat
`debloat.sh` disables (never uninstalls) Xiaomi analytics, ad and recommendation packages and
other unused system apps, lowers animation scales, and swaps the Google TV launcher out.
`restore.sh` undoes all of it.

---

## Tested hardware

| | |
|---|---|
| Box | Xiaomi Mi TV Box S (2nd Gen), model `MiTV-AFKR0`, codename `jaws`, Amlogic SoC |
| OS | Google TV, Android 11 (SDK 30), build `RTT0.211222.001.767` |
| RAM | 2 GB |
| UI | 1920×1080 @ 320 dpi (960×540 dp) |
| Light | WiZ TV Sync Box `SYBX-EU-55/65`, module `ESP27_MHORGB_01`, firmware 1.39.1 |
| PC | Windows 11 |

It should work on other Android TV / Google TV devices running Android 11+, but the debloat
package list is specific to this Xiaomi model.

---

## Repository layout

```
.
├── README.md
├── debloat.sh                 # what was applied to the box (disable-only)
├── restore.sh                 # re-enables everything and resets settings
├── device-backup/             # package lists and settings captured before any change
├── docs/screenshots/
├── tv-launcher-design/
│   └── hero-glow.svg          # the 1920×1080 design the home screen follows
└── launcher/                  # the Android app (Gradle, Kotlin, Jetpack Compose for TV)
    ├── install.ps1            # build + install + permissions + set as HOME
    ├── rollback.ps1           # switch HOME back (or to Google TV)
    ├── POPPINS_OFL.txt
    └── app/src/main/java/dev/mibox/neon/
        ├── MainActivity.kt
        ├── LauncherViewModel.kt
        ├── SleepReceiver.kt
        ├── data/              # apps, prefs, weather, speed test, WiZ, Watch Next
        ├── dream/             # screensaver (DreamService + video)
        └── ui/                # Compose screens and components
```

---

## Requirements

**On the PC (Windows)**
- [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) (`adb`)
- JDK 17 (e.g. Eclipse Temurin)
- Android SDK command-line tools with `platforms;android-35` and `build-tools;35.0.0`
- Gradle is fetched by the wrapper (or pointed at a local zip, see below)
- Git, and optionally the GitHub CLI

The scripts assume this layout (change the paths at the top of the scripts if yours differs):

```
C:\mibox\platform-tools\adb.exe
C:\mibox\tools\jdk17\
C:\mibox\tools\android-sdk\
C:\mibox\tools\gradle-8.11.1-bin.zip      # optional, used by the wrapper
```

**On the box**
- Developer options and network (USB) debugging enabled
- Same Wi-Fi network as the PC

**Optional**
- A free [Pixabay API key](https://pixabay.com/api/docs/) for the screensaver footage
- A WiZ light with *local communication* allowed in the WiZ app

---

## Quick start

```powershell
# 1. Connect to the box (accept the "Allow USB debugging?" prompt on the TV)
C:\mibox\platform-tools\adb.exe connect 192.168.110.31:5555

# 2. Point the build at the SDK and (optionally) the Pixabay key
Set-Content C:\mibox\launcher\local.properties @"
sdk.dir=C\:\\mibox\\tools\\android-sdk
pixabay.apiKey=YOUR_PIXABAY_KEY
"@

# 3. Build, install, grant permissions, make it the default home
powershell -ExecutionPolicy Bypass -File C:\mibox\launcher\install.ps1 -Device 192.168.110.31:5555

# 4. Use it as the screensaver too
C:\mibox\platform-tools\adb.exe shell settings put secure screensaver_components dev.mibox.neon/.dream.NeonDream
```

---

## Full setup, step by step

### 1. Enable ADB on the box

1. **Settings → System → About**, select **Android TV OS build** seven times until it says you
   are a developer.
2. **Settings → System → Developer options**: turn on **USB debugging** (on this box it also
   covers network debugging on port 5555).
3. Note the box's IP: **Settings → Network & Internet → (your Wi-Fi)**.

### 2. Install the PC toolchain

```powershell
# platform-tools
Invoke-WebRequest https://dl.google.com/android/repository/platform-tools-latest-windows.zip -OutFile pt.zip
Expand-Archive pt.zip C:\mibox

# JDK 17 (Temurin), Android command-line tools, Gradle
Invoke-WebRequest "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" -OutFile jdk.zip
Invoke-WebRequest https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip -OutFile clt.zip
Invoke-WebRequest https://services.gradle.org/distributions/gradle-8.11.1-bin.zip -OutFile C:\mibox\tools\gradle-8.11.1-bin.zip
# unzip jdk.zip to C:\mibox\tools\jdk17 and clt.zip's cmdline-tools to
# C:\mibox\tools\android-sdk\cmdline-tools\latest
```

Then, from Git Bash (accepting the licenses needs `yes`):

```bash
export JAVA_HOME=/c/mibox/tools/jdk17
SM=/c/mibox/tools/android-sdk/cmdline-tools/latest/bin/sdkmanager.bat
yes | "$SM" --sdk_root='C:\mibox\tools\android-sdk' --licenses
"$SM" --sdk_root='C:\mibox\tools\android-sdk' "platforms;android-35" "build-tools;35.0.0"
```

The Gradle wrapper in `launcher/gradle/wrapper/gradle-wrapper.properties` points at the local
zip. To let it download Gradle instead, set `distributionUrl` back to
`https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip`.

### 3. Connect

```powershell
C:\mibox\platform-tools\adb.exe connect <BOX_IP>:5555
```

The first time, the TV shows **"Allow USB debugging?"**: tick *Always allow from this computer*
and select **Allow**. If `adb devices` still says `unauthorized`, disconnect and connect again.

### 4. Back up the box (recommended before any change)

```powershell
$adb = "C:\mibox\platform-tools\adb.exe"
& $adb shell pm list packages -f > device-backup\packages_backup.txt
& $adb shell pm list packages -d > device-backup\disabled_packages_backup.txt
& $adb shell "settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale"
```

This repository already contains the backup taken from the original box in `device-backup/`.

### 5. Debloat (optional)

Read `debloat.sh` first; it only uses `pm disable-user --user 0` (reversible) and:

- disables 24 packages: Xiaomi analytics (`com.miui.tv.analytics`, `com.xiaomi.statistic`), the
  Xiaomi home tabs, channels and Mi TV+, the web-content/ad service, promoted-app auto install,
  the Google TV ambient screensaver, Play Movies, Play Games, feedback, TalkBack, calendar sync,
  print spooler, MIDI, DSU, Hotspot 2.0, the Xiaomi music/gallery/video apps and Miracast;
- sets all three animation scales to 0.5;
- lowers the cached-process limit to 16 (not persistent across reboots, see
  [limitations](#known-limitations));
- disables the Google TV launcher (`launcherx`) **and** the setup wizard (`setupwraith`).

> **Why the setup wizard?** On Google TV, `launcherx` has HOME priority 2 and the setup wizard's
> `RecoveryActivity` has priority 1. Disabling only the launcher leaves you on a "recovery"
> screen. Disabling a single activity needs root, so the whole wizard package is disabled
> (setup is already complete; a factory reset brings it back). Only do this once another
> launcher is installed.

Run it from Git Bash:

```bash
bash debloat.sh 192.168.110.31:5555
```

`debloat.sh` was written while Projectivy was the interim launcher; with Serwy Launcher, run
`install.ps1` before it so a home screen is in place when `launcherx` goes away.

### 6. Configure the build

`launcher/local.properties` (ignored by git, never commit it):

```properties
sdk.dir=C\:\\mibox\\tools\\android-sdk
pixabay.apiKey=YOUR_PIXABAY_KEY
```

Without a Pixabay key everything works; the screensaver just shows the clock on a dark background.
Get a key at <https://pixabay.com/api/docs/> (log in; it is shown in the *key* parameter row).

### 7. Build and install

```powershell
powershell -ExecutionPolicy Bypass -File C:\mibox\launcher\install.ps1 -Device <BOX_IP>:5555
```

`install.ps1`:
1. builds the release APK (R8-minified, signed with the local debug key), wiping the dex folder
   and retrying once if Windows keeps R8's output locked;
2. saves the current HOME activity to `previous_home.txt`;
3. installs the APK;
4. grants the two permissions a launcher cannot ask for itself:
   `appops set dev.mibox.neon GET_USAGE_STATS allow` and
   `pm grant dev.mibox.neon android.permission.READ_TV_LISTINGS`;
5. AOT-compiles the app (`cmd package compile -m speed`) for the fastest cold start;
6. sets it as the default HOME and presses Home.

Add `-SkipBuild` to reinstall the last build. Note that the last step presses **Home** on the
TV, so don't run it while someone is watching.

### 8. Screensaver

```powershell
$adb = "C:\mibox\platform-tools\adb.exe"
& $adb shell settings put secure screensaver_components dev.mibox.neon/.dream.NeonDream
& $adb shell settings put secure screensaver_enabled 1
```

Choose when it starts in **Settings → System → Ambient mode / Screen saver → When to start**.
To preview it right away:

```powershell
& $adb shell service call dreams 6 i32 0 i32 1 s16 dev.mibox.neon s16 dev.mibox.neon.dream.NeonDream
```

### 9. WiZ light

In the WiZ app: **Settings → Security → Allow local communication** must be on. The launcher
discovers the light by broadcasting `getSystemConfig` on UDP 38899 and remembers its IP. Test from
the PC:

```powershell
$c = New-Object System.Net.Sockets.UdpClient; $c.EnableBroadcast = $true
$b = [Text.Encoding]::ASCII.GetBytes('{"method":"getPilot","params":{}}')
[void]$c.Send($b, $b.Length, "<LIGHT_IP>", 38899)
```

---

## Using the launcher

| Where | Press | Does |
|---|---|---|
| Any card | OK | Open the app / resume the episode |
| App card | Long-press OK | Menu: move, hide, add to favorites, light profile, app info |
| Move mode | ◀ ▶ / OK / Back | Move the card / drop it / drop it |
| Rows | ▲ ▼ | Previous / next row; from the top row, the top bar |
| Clock chip | OK | Control center (big clock, weather, light, system, hidden apps, city) |
| Light chip | OK / long-press | Light panel / toggle the first quick preset (Party) |
| RAM chip | OK | Close background apps, shows how much was freed |
| Gear | OK / long-press | Android TV settings / launcher menu (hidden apps, city) |
| Home | – | Back to the top row, first card |

**Unhide an app**: Control center → *Gizli uygulamalar*, or long-press the gear → *Gizli
uygulamalar* → select the app.

**Change the weather city**: launcher menu → *Şehir* (Open-Meteo geocoding; default Gazimağusa).

---

## Permissions

| Permission | Why | How it is granted |
|---|---|---|
| `INTERNET` | Weather, speed test, screensaver footage | Install time |
| `KILL_BACKGROUND_PROCESSES` | "Boşalt" button | Install time |
| `QUERY_ALL_PACKAGES` | A launcher must list every app | Install time |
| `READ_EPG_DATA` | TV provider (Watch Next, channels) | Install time |
| `READ_TV_LISTINGS` | Lets the TV provider return other apps' Watch Next and channel rows | `pm grant` in `install.ps1` |
| `PACKAGE_USAGE_STATS` | Last used / recent apps for the hero and showcase | `appops set` in `install.ps1` |

`READ_TV_LISTINGS` is what makes *İzlemeye devam et* and *Senin için* work without root; without
it the provider silently returns only the caller's own rows.

---

## How it works

- **Kotlin + Jetpack Compose for TV** (`androidx.tv:tv-material`), `minSdk 30`, single activity,
  no background services. The only service is the screensaver, which the system runs only while
  dreaming; the sleep timer is an `AlarmManager` broadcast.
- **Design space**: `NeonTheme` overrides `LocalDensity` so 960 dp always spans the screen width;
  texts are placed on the SVG's baselines with a small `baselineAt()` layout modifier.
- **Cheap animation**: focus scale, row slides and background glows are read in the draw /
  graphics-layer phase, so focus changes don't recompose the screen. Cards scale in the graphics
  layer, so neighbours never move.
- **Art**: banners are rendered once on two IO threads to the exact card size, opaque ones in
  RGB_565; Palette gives each app a glow color. Watch Next thumbnails are cropped to the card size.
  When home goes to the background (`TRIM_MEMORY_UI_HIDDEN`) thumbnails and hero logos are
  released.
- **Rows**: navigation between rows is explicit (`RowsController`), each row restores its last
  focused card.
- **WiZ**: JSON over UDP; every command goes through one mutex so effects, ambient color and
  restores never interleave. Ambient snapshots the light on entering home and restores it on
  leaving.
- **Screensaver**: `NeonDream` hosts Compose (it provides its own `LifecycleOwner` and
  `SavedStateRegistryOwner`); `SkyVideos` maps time of day + weather to a mood, searches
  Pixabay (results cached 24 h, tag-filtered, landscape only), ExoPlayer plays them on a
  `SurfaceView` through a 100 MB `SimpleCache`.

---

## Performance

Measured on the box above.

| | |
|---|---|
| Cold start (system "Displayed") | 0.43–0.45 s |
| APK size | ~3.5 MB |
| RAM on the home screen (PSS) | ~96–99 MB |
| RAM while another app is in front (PSS) | ~81 MB (was 143 MB before trimming) |
| Projectivy Launcher, same box, for comparison | ~87 MB on the home screen |
| Screensaver video decoding | hardware (`OMX.amlogic.avc.decoder`), ~9 % CPU |

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `adb devices` shows `unauthorized` | Accept the prompt on the TV (tick *Always allow*), then `adb disconnect` and connect again. |
| `error: closed` / `device offline` mid-session | Wi-Fi ADB on this box drops now and then. `adb kill-server`, then `adb connect` again; retry once or twice. |
| Build fails with `FileSystemException ... classes.dex ... being used by another process` | The Gradle daemon (or Defender) holds R8's output. `install.ps1` already kills the daemons and retries; manually: stop `java.exe` processes running `GradleDaemon`, delete `launcher/app/build/intermediates/dex`. |
| A PowerShell call to `install.ps1` never returns when piped | The Gradle daemon inherits the pipe. Run the script directly in a console, or redirect its output to a file. |
| HOME keeps going back to Google TV | `launcherx` (priority 2) wins over any preferred HOME on Google TV; it has to be disabled, see step 5. |
| Stuck on a "setup / recovery" screen after disabling `launcherx` | The setup wizard's `RecoveryActivity` took HOME. `pm enable --user 0 com.google.android.apps.tv.launcherx`, then follow step 5. |
| *İzlemeye devam et* / *Senin için* empty | `READ_TV_LISTINGS` was not granted: `adb shell pm grant dev.mibox.neon android.permission.READ_TV_LISTINGS`. |
| Watch Next item opens an "Open with" chooser | Fixed: the intent is pinned to the publishing app. Update to the latest build. |
| Screensaver screenshots come out white | Expected: the video plays on a hardware overlay that `screencap` cannot capture. Look at the TV. |
| Screensaver shows only a clock | No Pixabay key in `local.properties`, or no internet. |
| Light chip dot grey / "Işık bul" | WiZ local communication is off, or the light is on another network. |
| An app is missing from *Tüm uygulamalar* | It is hidden: Control center → *Gizli uygulamalar*. |

---

## Known limitations

- **Cached-process limit** (`device_config activity_manager max_cached_processes`) resets on
  reboot; persisting it needs root.
- **Closing background apps**: `killBackgroundProcesses` cannot stop an app that is playing
  (foreground service) or visible; `FORCE_STOP_PACKAGES` is a signature permission.
- **WiZ firmware updates** only come through the WiZ app / cloud (`autoUpd` is on).
- **TV-sync brightness** of the WiZ Sync Box is decided by the box itself; the colour channel
  gains in its config (`renderFactor`) are undocumented and left untouched.
- **4K screensaver** needs the HDMI output itself at 4K; this box was outputting 1080p, so 1080p
  clips are used.
- The debloat list and some UI choices are specific to this Xiaomi model.

---

## Undo everything

```powershell
# Launcher: HOME back to the previous launcher, or to Google TV if it is gone (-Uninstall removes Serwy)
powershell -ExecutionPolicy Bypass -File C:\mibox\launcher\rollback.ps1 -Device <BOX_IP>:5555
```

```bash
# Box: re-enable every disabled package, restore launcher, screensaver and settings
bash restore.sh <BOX_IP>:5555
```

---

## Credits and licenses

- **Design**: `tv-launcher-design/hero-glow.svg`, made with Claude Design.
- **Font**: [Poppins](https://fonts.google.com/specimen/Poppins), SIL Open Font License 1.1
  (`launcher/POPPINS_OFL.txt`).
- **Weather, sunrise/sunset, geocoding**: [Open-Meteo](https://open-meteo.com/), data under
  CC BY 4.0.
- **Screensaver footage**: streamed from [Pixabay](https://pixabay.com/) under the Pixabay
  Content License (no attribution required).
- **Watch Next / channels / app banners and icons** belong to their respective apps and are shown
  as published by them on the device.
- WiZ, Xiaomi, Google TV and the app names shown are trademarks of their owners; this project is
  not affiliated with any of them.

Private project, all rights reserved.
