# vPro TV Launcher

A home screen for Android TV / Google TV, built for one living room: a single dock of the apps that matter, the
clock, the weather, exchange rates and a news ticker over rotating wallpapers. Package `com.muzdai.launcher`,
shown on the TV as **vPro Launcher**. Licensed under the GPL 3 (see `LICENSE`).

Personal project; it runs on a TCL 65C8K (Google TV 14) as a privileged system app. English by default, Russian
when the TV is in Russian.

## Build

JDK 17 and Android SDK platform 34:

```
cd launcher && ./gradlew assembleRelease
```

Without signing variables the debug key signs. Releases are built by `.github/workflows/release.yml` on a `v*`
tag from the repository's signing secrets (`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`), as `vpro-tv-launcher-<version>.apk` plus its SHA-256.

## What is on the screen

- **Dock**: as many apps as there are columns (4-8, default 7); the rest live in the App library. OK opens an app,
  holding OK gives Move, Close (force-stop through the TV's own ADB), Remove, App info.
- **App library**: every TV app. Hold OK: Open, To dock, App info, Uninstall (asks once; removed through the
  TV's own ADB, so no second system screen; without ADB the system's uninstall screen opens).
- **Clock, date, greeting**, the weather under them (Open-Meteo, city picked in settings, forecast now / today /
  3 days / week) and the National Bank of Georgia's rates next to the greeting: up to 4 of USD, EUR, GBP, TRY,
  UAH, AMD, AZN (UAH and AMD per 10 and 1000 units, as the bank quotes them).
- **News ticker** under the dock: Google News headlines by section; text size, speed and band transparency are
  sliders.
- **Wallpapers**: pictures in `/sdcard/Android/data/com.muzdai.launcher/files/wallpapers/`, named
  `<group>-*.jpg`; a new one every 5 minutes, shuffled; a caption from `wallpapers/captions.txt`. Groups
  (Nature, Space, Cities, Cinema, Games) are picked in settings, or Random for all of them.
- **Top bar**: App library, Inputs, Network, Settings. The Inputs panel also opens over any app from the remote's
  Input button.

## Settings

The on-screen gear and the remote's gear open the launcher's settings, laid out like the TV's own: categories on
the left, a preview of the focused one on the right. OK or Right opens, Back or Left goes up, Left and Right move
a slider.

- **System**: TV settings.
- **Home screen**: Home screen (apps per row, dock transparency, clock format), Wallpapers, Information
  (weather, exchange rates and currencies, news), Remote buttons, Animation.
- Reset launcher settings, About.

Built-in defaults live in `launcher/app/src/main/res/values/config.xml` and `fork_defaults.xml`; the owner's
choices are stored on the TV on top of them.

## Remote buttons

Netflix (4062), Prime Video (4099), YouTube (190), TCL Channel (4020) and Live TV (4001), as a TCL G08 remote sends
them. Each can open any TV app, the Inputs panel, Live TV (the hardware tuner), Launcher settings, nothing, or
keep its default. The gear (83) opens Launcher settings, Input (178) the Inputs panel. The key helper is an
accessibility service (`InputKeyService`); the TV must have it enabled:

```
adb shell settings put secure enabled_accessibility_services com.muzdai.launcher/.InputKeyService
adb shell settings put secure accessibility_enabled 1
```

## System app (Magisk)

`magisk/build-module.sh <signed apk>` builds a Magisk module that installs the launcher into `/system/priv-app`
with its privileged permissions (`DELETE_PACKAGES`, `WRITE_SECURE_SETTINGS`) allowlisted in
`system/etc/permissions`. Install it with `magisk --install-module` and reboot. Later versions install over it with
`adb install -r`. Every privileged permission the manifest asks for must be in the allowlist, or a TV with
`ro.control_privapp_permissions=enforce` will not boot. See `docs/RESTORE.md` for the state of the TV and how to
roll back.

## Network use

Only while Home is on screen: weather every 30 min, rates every 3 h, news every 30 min; last answers are cached.
Sent: the typed city name (Open-Meteo geocoding), then its coordinates; nothing else identifies the TV. The
launcher also talks to the TV's own ADB on 127.0.0.1:5555 for Close and Uninstall.

## Fonts

The UI font is Outfit, under the SIL Open Font License (`launcher/FONT-LICENSE-OFL.txt`).
