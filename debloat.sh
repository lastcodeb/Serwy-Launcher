#!/usr/bin/env bash
# Mi TV Box S 2nd Gen (MiTV-AFKR0, Google TV / Android 11) debloat
# Run from the PC (Git Bash): ./debloat.sh [IP:PORT]
# Only uses "pm disable-user --user 0" -- nothing is uninstalled. Undo with restore.sh.

DEV="${1:-192.168.110.31:5555}"
ADB="${ADB:-/c/mibox/platform-tools/adb.exe}"
adb_sh() { "$ADB" -s "$DEV" shell "$@"; }

"$ADB" connect "$DEV"

PACKAGES=(
  # Xiaomi analytics / ads / recommendations
  com.miui.tv.analytics
  com.xiaomi.statistic
  com.mitv.tvhome.oemtab
  com.mitv.tvhome.atv
  com.mitv.tvhome.michannel
  com.mitv.tvhome.mitvplus
  com.xm.webcontent
  android.autoinstalls.config.xioami.mibox3
  # Google extras
  com.google.android.apps.tv.dreamx
  com.google.android.videos
  com.google.android.play.games
  com.google.android.feedback
  com.google.android.marvin.talkback
  com.google.android.syncadapters.calendar
  # AOSP extras
  com.android.printspooler
  com.android.bluetoothmidiservice
  com.android.dynsystem
  com.android.hotspot2.osulogin
  # Xiaomi media apps / Miracast
  com.xiaomi.mimusic2
  com.mitv.gallery
  com.mitv.videoplayer
  com.xiaomo.tv.milegal
  com.xiaomi.mitv.smartshare
  com.xiaomi.wfdsinkhelperservice
)

# dreamx is the active screensaver; switch to the AOSP one first
adb_sh settings put secure screensaver_components com.android.dreams.basic/com.android.dreams.basic.Colors

for p in "${PACKAGES[@]}"; do
  adb_sh pm disable-user --user 0 "$p"
done

# Performance settings
adb_sh settings put global window_animation_scale 0.5
adb_sh settings put global transition_animation_scale 0.5
adb_sh settings put global animator_duration_scale 0.5
# Not persistent: DeviceConfig resets on reboot (no root), re-run this script after a reboot
adb_sh device_config put activity_manager max_cached_processes 16

# Launcher: Projectivy (com.spocky.projengmenu) must already be installed from Play Store.
# Google TV forces launcherx (priority 2), then setupwraith's RecoveryActivity (priority 1),
# so both are disabled; setup is already complete (user_setup_complete=1).
if adb_sh pm list packages | grep -q com.spocky.projengmenu; then
  adb_sh pm disable-user --user 0 com.google.android.tungsten.setupwraith
  adb_sh pm disable-user --user 0 com.google.android.apps.tv.launcherx
  adb_sh cmd package set-home-activity --user 0 com.spocky.projengmenu/.ui.home.MainActivity
else
  echo "Projectivy not installed -- skipping launcher swap"
fi

echo "Done. Free RAM:"
adb_sh grep MemAvailable /proc/meminfo
