# HiSmartTV A4: the local build

This fork carries the launcher exactly as it runs on one TV in Shabankareh, Iran: a Hisense
HiSmartTV A4, kept as Home, with Hiddify as the VPN and Persian dates on screen. Everything on
this page was checked on that set over ADB, on 31 October 2026.

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
| VPN app | Hiddify 4.1.1 (`app.hiddify.com`), core API on `127.0.0.1:17079` |
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

### VPN from the network pill

The network pill is the VPN switch. While a tunnel is up the whole pill is solid green; pressing
it while it is down starts the tunnel without Hiddify's UI:

1. the shell's exported `ShortcutActivity` brings Hiddify's background service up;
2. `VpnDaemon` asks the running core to start over its local gRPC API (`hcore.Core/Start`, h2c on
   `127.0.0.1:17079`) with an empty request, so the core reuses the config it started last;
3. the pill reads "Connecting…" until a VPN network actually appears, then turns green.

Pressing the green pill stops the tunnel through the same toggle activity. Holding the pill still
opens Wi-Fi settings, as upstream. Switches: `vpn_app`, `vpn_toggle_activity`, `vpn_daemon_port`,
`vpn_network_green`.

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
| Read VPN state from the core's status API, so the pill answers on press instead of on the next network callback | Planned |
| Remove Hiddify's brief white "Toggle" screen on a cold service start | Blocked upstream: the activity draws it before the service starts; needs a Hiddify-side change |
| Reduce cold-boot time to the launcher on the A4 (the first Home after a reboot is slow) | Investigating |
| Release signing, so updates no longer depend on this Mac's debug key | Planned; one uninstall/reinstall on the TV |
| Pan directions and weather icons on the screensaver photographs | Nice to have |
| Keep the personal defaults in a theme file rather than `values/config.xml`, so upstream merges stay clean | Planned refactor |
| Turn `wifi_ping` on by default, once a day of readings shows no battery or layout cost | Optional |

## Caveats seen on this TV

- ADB over Wi-Fi drops the moment Hiddify's tunnel comes up; reconnect with `adb connect` after
  each start.
- Hiddify 4.1.1 reuses the profile it started last when the core gets an empty start request. A
  different Hiddify version may move the port or the semantics; `vpn_daemon_port` and
  `vpn_toggle_activity` are there to be changed.
- A stale Hiddify daemon answers "configure tun interface: permission denied"; force-stopping
  Hiddify and letting the shortcut start it again fixes it.
