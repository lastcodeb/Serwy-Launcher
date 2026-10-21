#!/usr/bin/env bash
# Reverts everything debloat.sh did and resets settings to the values saved in
# settings_backup.txt (animations 1.0 / animator default, background limit default).
# Run from the PC (Git Bash): ./restore.sh [IP:PORT]

DEV="${1:-192.168.110.31:5555}"
ADB="${ADB:-/c/mibox/platform-tools/adb.exe}"
adb_sh() { "$ADB" -s "$DEV" shell "$@"; }

"$ADB" connect "$DEV"

PACKAGES=(
  com.miui.tv.analytics
  com.xiaomi.statistic
  com.mitv.tvhome.oemtab
  com.mitv.tvhome.atv
  com.mitv.tvhome.michannel
  com.mitv.tvhome.mitvplus
  com.xm.webcontent
  android.autoinstalls.config.xioami.mibox3
  com.google.android.apps.tv.dreamx
  com.google.android.videos
  com.google.android.play.games
  com.google.android.feedback
  com.google.android.marvin.talkback
  com.google.android.syncadapters.calendar
  com.android.printspooler
  com.android.bluetoothmidiservice
  com.android.dynsystem
  com.android.hotspot2.osulogin
  com.xiaomi.mimusic2
  com.mitv.gallery
  com.mitv.videoplayer
  com.xiaomo.tv.milegal
  com.xiaomi.mitv.smartshare
  com.xiaomi.wfdsinkhelperservice
  # stock Google TV launcher and setup wizard (disabled for the Projectivy swap)
  com.google.android.tungsten.setupwraith
  com.google.android.apps.tv.launcherx
)

for p in "${PACKAGES[@]}"; do
  adb_sh pm enable --user 0 "$p"
done

# Original home screen and screensaver
adb_sh cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity
adb_sh settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop

# Original settings
adb_sh settings put global window_animation_scale 1.0
adb_sh settings put global transition_animation_scale 1.0
adb_sh settings delete global animator_duration_scale
adb_sh settings delete global background_process_limit
adb_sh device_config delete activity_manager max_cached_processes
adb_sh settings delete global activity_manager_constants

echo "Restored. Still disabled (should be empty):"
adb_sh pm list packages -d
echo "Reboot recommended: $ADB -s $DEV reboot"
