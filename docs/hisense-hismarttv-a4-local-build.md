# HiSmartTV A4: the local build

This fork carries the launcher exactly as it runs on one TV in Shabankareh, Iran: a Hisense
HiSmartTV A4, kept as Home, with Karing as the VPN and Persian dates on screen. Everything on
this page was checked on that set over ADB, from 31 October 2026.

## The TV

| | |
|---|---|
| Model | HiSmartTV A4 (Hisense) |
| Device name | `xiaoyushan` |
| SoC | MStar `m7322`, 4× ARMv8 (Cortex-A55-class) |
| GPU | ARM Mali-470 MP, OpenGL ES 2.0 (`131072`) |
| RAM | 1.87 GB (`MemTotal: 1,873,048 kB`) |
| Storage | 10 GB `/data`, 2.5 GB used, 7.7 GB free at the time of testing |
| OS | Android TV 9, API 28 |
| Build | `PTMR.190127.037`, 11 August 2020 |
| Display | 1920×1080, 320 dpi |
| Input | IR remote, ADB over Wi-Fi on port 5555 |
| Home | `com.gathin.tvhome/.MainActivity` |
| Screensaver | `com.gathin.tvhome/.ClockDream` |
| VPN app | Karing 1.2.25 (`com.nebula.karing`), the 32-bit build, switched by broadcast from the pill |
| Removed apps | [`my-debloat.txt`](../my-debloat.txt): OTA, Netflix, Prime Video and Play Movies kept on purpose |

The stock home rows, store demo, pre-load auto-installs and log reporting are disabled the same way
the upstream example for this TV ([`examples/hisense-hismarttv-a4`](../examples/hisense-hismarttv-a4))
describes; nothing is uninstalled, so the upstream restore script still puts everything back.

## What this build adds

All switches live in [`launcher/app/src/main/res/values/config.xml`](../launcher/app/src/main/res/values/config.xml);
either one documents itself in a comment there.

### Weather under the clock

A live line from [Open-Meteo](https://open-meteo.com): no key, no account, one request an hour.

```
Shabankareh · 26° Mainly clear · 21–37°
```

The last reading is cached in SharedPreferences, so the line is there the moment Home comes up
after a reboot, while the network is still coming up. Home and the screensaver share both the
request and the cache ([`WeatherLine`](../launcher/app/src/main/java/com/gathin/tvhome/WeatherLine.java)).
Switches: `weather_line`, `weather_place`, `weather_lat`, `weather_lon`.

### Shamsi date

`shamsi_date` is on by default for this set; Home and the screensaver both show it
("9 Aban 1405" over "SATURDAY 31 OCTOBER").

### Photograph screensaver

Ten 1920×1080 photographs ship in `res/raw`, one at a time, a new one every five minutes with a
1.2 s crossfade, each on a slow 6 % Ken Burns zoom for its turn. The clock drifts within the lower
band of the picture and a full-width gradient rises from the bottom edge, as Google's ambient
screensaver does: the picture keeps its colour above, the text sits on the dark. Photographs come
from [Picsum](https://picsum.photos) (Unsplash images, free licence), bundled so the screensaver
needs no network and no account. Switches: `clock_photos`, `clock_photos_dim` (percent, 25 here),
`clock_photos_minutes` (5).

### Netflix button opens VLC

The remote's Netflix button arrives as keycode 265 (`KEYCODE_STEM_1`, the vendor's
`KEYCODE_MTKIR_NETFLIX`), and the firmware also broadcasts `GLOBAL_BUTTON` with it; with Netflix
uninstalled the press did nothing. `app_keys` now maps `265:org.videolan.vlc`, and the launcher's
`InputKeyService` accessibility service, which filters keys system-wide, opens VLC from any app.
`adb shell input keyevent 265` has no effect here, so the button itself is the check.

Enabling that service once, over ADB:

```sh
adb shell settings put secure enabled_accessibility_services com.gathin.tvhome/.InputKeyService
adb shell settings put secure accessibility_enabled 1
```

The setting survives reboots and updates; a force-stop of the launcher drops it until it is set
again (or `restore_key_services` is on with `WRITE_SECURE_SETTINGS` granted).

### VPN from the network pill

The network pill is the VPN switch, backed by Karing (1.2.25, the 32-bit `armeabi-v7a` build). While a
tunnel is up the whole pill is solid green; pressing it stops the tunnel, and pressing the red
"VPN off" pill starts it, both without the app's UI:

1. the pill sends Karing's own automation broadcasts, `com.nebula.karing.action.CONNECT` and
   `...DISCONNECT`, to `com.nebula.karing`, which `AutomationCommandReceiver` handles in the
   background;
2. after a start the pill reads "Connecting…" until a VPN network actually appears, then turns
   green (it opens Karing if the tunnel never comes up within 15 s).

Holding the pill still opens Wi-Fi settings, as upstream. Switches: `vpn_app`,
`vpn_toggle_activity`, `vpn_start_action`, `vpn_stop_action`, `vpn_daemon_port` (0 here: no gRPC
core to call), `vpn_network_green`.

Hiddify was removed: its core would not start on this firmware (the app's gRPC port on
`127.0.0.1` refused every connection), so it could not even import a subscription. Karing imports
the same Speedur subscription and connects with its auto-select node (Germany, 104 ms).

### SmartTube next to YouTube

The TV's YouTube app keeps its own tile and still opens Google's app. SmartTube, the open-source TV
client that skips the ads and needs no account, was installed alongside it and shows as its own tile
in the grid — the YouTube tile is untouched, so both are one press away:

- SmartTube stable 32.68, the `armeabi-v7a` build: the TV's userspace is 32-bit only
  (`ro.product.cpu.abilist` is `armeabi-v7a,armeabi`), and the arm64 APK fails with
  `INSTALL_FAILED_NO_MATCHING_ABIS`.
- The launcher lists it like any other TV app (`org.smarttube.stable`); its tile can be dragged
  into the dock with the reorder button if it should sit next to YouTube.

### Smaller changes

- `wifi_ping`: the link's round-trip time after the network name, off by default.
- OkHttp (h2c for the gRPC call) and a network security config that permits cleartext only to
  `127.0.0.1` and `localhost`.
- Aliyun Maven mirrors ahead of `google()`/`mavenCentral()`, for builds on networks where
  `dl.google.com` is blocked.
- The focused-tile glow is limited to the halo style: on the glass and rim styles it read as a
  second frame around the tile.

## Install and update

```sh
adb connect <tv-ip>:5555
adb install -r launcher/app/build/outputs/apk/release/app-release.apk
```

Set the launcher as Home once (Settings → Apps → Default apps), and the screensaver through
Settings → Device Preferences → Screen saver → Clock. Both are already set on this TV.

The APK is signed with the debug key (`signingConfig signingConfigs.debug`), which is fine for
sideloading and keeps updates over the installed copy; it is not suitable for a store.

## Build

JDK 17 and the Android SDK (platform 34) are enough:

```sh
cd launcher
JAVA_HOME=<jdk-17> ANDROID_HOME=<sdk> ./gradlew assembleRelease
```

## Roadmap

| Item | State |
|---|---|
| Weather place, photo interval and dim in the launcher's own settings screen instead of `config.xml` | Planned |
| Screensaver photographs from a USB stick or a local folder, bundled set as the fallback | Planned |
| Read VPN state from Karing's own status instead of the network list, so the pill answers on press | Planned |
| Reduce cold-boot time to the launcher on the A4 (the first Home after a reboot is slow) | Investigating |
| Release signing, so updates no longer depend on this Mac's debug key | Planned; one uninstall/reinstall on the TV |
| Pan directions and weather icons on the screensaver photographs | Nice to have |
| Keep the personal defaults in a theme file rather than `values/config.xml`, so upstream merges stay clean | Planned refactor |
| Turn `wifi_ping` on by default, once a day of readings shows no battery or layout cost | Optional |

## Caveats seen on this TV

- ADB over Wi-Fi stayed up through Karing's tunnel; with Hiddify 4.1.1 every start dropped it, and
  `adb connect` was needed again.
- Karing needs its system VPN consent dialog accepted once, from the app. After that the pill's
  broadcasts work with no UI.
- Karing reconnects with the node it used last (the subscription's Auto Select), so a start needs
  no profile argument. `vpn_start_action`, `vpn_stop_action` and `vpn_app` are what to change for
  another VPN app.
