# Contributing to Serwy Launcher

Thanks for taking a look. This is a small project run by one person, so a clear issue or a
focused pull request goes a long way.

## Before you start

- **Bugs**: open an issue with the bug template. Say which box and Android version, which theme,
  and what you did. A `logcat` excerpt helps a lot (`adb logcat -s NeonMatch NeonKeys NeonBackup`
  or the whole log around the moment it happened).
- **Features**: open an issue with the feature template first, for anything bigger than a small
  fix, so we can agree on the approach before you write the code.
- **Questions**: a GitHub discussion or the XDA thread.

## Setting up

The [README](README.md#quick-start-build-from-source) has the full setup. In short you need
JDK 17 and the Android SDK (platform 35, build tools 35.0.0); the Gradle wrapper downloads
Gradle itself.

```powershell
cd launcher
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The screensaver needs a free [Pixabay API key](https://pixabay.com/api/docs/) in
`launcher/local.properties` (`pixabay.apiKey=...`). Without one everything else works. That file
is ignored by Git: **never commit a key**, and never paste one in an issue or pull request.

You can test on a real Android TV / Google TV device (Android 11+) or the Android TV emulator
(API 30 or newer). WiZ features need a WiZ light on the same network.

## Pull requests

1. Fork the repository and branch from `main`.
2. Keep one change per pull request; separate refactors from behavior changes.
3. Run `.\gradlew.bat testDebugUnitTest` and `.\gradlew.bat assembleRelease "-Ppixabay.apiKey="`
   before pushing. CI runs both and must pass.
4. Fill in the pull request template: what changed, why, what you tested on, and screenshots
   for anything visible on screen.
5. Expect review comments; small follow-up commits are fine, they get squashed on merge.

## Code style

- **Kotlin + Jetpack Compose for TV**, minSdk 30. Match the code around you: naming, comment
  density and structure. Comments explain *why*, not *what*.
- **Remote first**: every new screen must be fully usable with D-pad, OK and Back. Check that
  focus never gets lost and Back always leads somewhere sensible.
- **Every theme**: a visible change usually needs a look in all six themes (Neon, Cam, Gazete,
  Teletext, Lumen, Aura). If it only fits some, say so in the pull request.
- **Light on the box**: the Mi Box S has 2 GB of RAM. Avoid new dependencies unless they do
  real work, keep work off the main thread, and don't poll more than needed.
- **No root, no cloud accounts**: features must work over plain ADB permissions, and the WiZ
  control stays local (UDP on the LAN).

## Translations

The UI is written in Turkish and translated by lookup: wrap user-visible text in
`t("Türkçe metin")` and add the English entry to `launcher/app/src/main/java/dev/mibox/neon/i18n/EnStrings.kt`.
A unit test fails the build when an entry is missing, duplicated, or its `{0}` placeholders
differ. New languages are welcome: add a catalog like `EnStrings.kt` and pick it in `I18n`.
If you don't speak Turkish, write the English text in the pull request and it will be translated
during review.

## Match day for other clubs

Match day currently follows Galatasaray only (the team id lives in `MatchConfig` and in the
`Mackolik` object in `data/Match.kt`). Making the club configurable is a welcome contribution;
please open an issue first, since it touches the settings, the crests and the light colors.

## License

By contributing you agree that your contribution is released under the [MIT License](LICENSE).
Don't add code, images or fonts you don't have the right to share, and keep club names, crests
and other trademarks out of the repository itself.
