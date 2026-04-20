# Sysmon Android

A home-network monitor for Huawei HG8145V5 (and soon other) fibre routers. Polls the router every minute, stores usage history locally, attributes bandwidth to individual Wi-Fi devices, and tracks ISP data-bundle cycles — all without shipping any data off the phone.

Built in Kotlin + Jetpack Compose (Material 3). Runs as a foreground service so polling continues while the app is backgrounded.

## Features (shipped)

- **Minute-by-minute router scraping** — WAN byte counters + associated-device list via a single authenticated session, cookie-managed manually (Huawei's non-RFC Set-Cookie format defeats OkHttp's CookieJar).
- **Pluggable router adapters** — `RouterKind` dropdown in Settings; Huawei HG8145V5 today, ZTE / TP-Link / Generic stubs in place. Adding a model = one new class implementing `RouterAdapter`.
- **Per-device attribution** — WAN delta split across Wi-Fi clients by *change* in link rate (not absolute link rate — that systematically over-credits devices near the router).
- **Bundle-cycle tracking** — manual recharge + mid-cycle top-ups + "I currently have N GB left" override. Optional SMS paste to auto-parse WE / Vodafone / Orange / Etisalat renewal messages (Arabic + English).
- **Reactive UI** — every screen is Flow-backed; pull-to-refresh + `onResume` nudges the poll service for instant fresh data.
- **Per-device reports** — today / hourly / 30-day / 12-month charts per device with a monthly totals table (month-to-date labelled honestly).
- **Foreground service** with partial wake-lock, auto-resume on boot, battery-opt exemption prompt.
- **Encrypted credential storage** — `EncryptedSharedPreferences` (AES-256 via the Android Keystore).
- **Edge-to-edge Material You** — dynamic colour on Android 12+, brand fallback palette otherwise.

## Stack

- Kotlin 2.0, Jetpack Compose, Material 3 (BOM 2024.10.01)
- Room 2.6.1 (KSP)
- OkHttp 4.12 (HTTP/1.1 forced for Huawei compatibility)
- WorkManager 2.9 (daily report worker)
- MPAndroidChart 3.1
- `androidx.security:security-crypto` for at-rest credential encryption

## Build

```bash
cd android
./gradlew.bat assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.ahmed.sysmon/.MainActivity
```

`compileSdk = 36`, `minSdk = 26`, `targetSdk = 36`.

## First-run setup

1. Pick your router model from the dropdown on the Login screen.
2. Enter the admin URL, username, and password.
3. Tap **Sign in & start monitoring** — the poll service comes up immediately.
4. (Optional) Settings → **Data bundle** → "I recharged today — N GB" to start tracking remaining GB.

## Architecture sketch

```
MainActivity
  └── AppNav                        (bottom-nav + NavHost with slide transitions)
        ├── DashboardScreen         (status + hero bundle card + alerts strip)
        ├── UsageScreen             (hourly bars + 7-day trend + threshold line)
        ├── DevicesScreen           (list, sortable, clickable -> DeviceProfile)
        ├── DeviceProfileScreen     (today / hourly / 30d / 12mo charts)
        ├── AlertsScreen
        ├── JobsScreen
        ├── LogsScreen
        └── SettingsScreen          (router / thresholds / bundle cycle / session)

RouterPollService (foreground)
  └── RouterAdapters.forKind(...)
        ├── HuaweiHG8145V5Adapter
        └── MockAdapter             (canned data for previews / demos)

AppDatabase (Room, v4)
  ├── usage_samples                 (minute-by-minute WAN totals)
  ├── devices
  ├── device_usage                  (per-device attribution, kept 90d)
  ├── alerts
  ├── jobs
  ├── log_entries                   (kept 7d)
  └── bundle_cycles                 (recharge / top-up / SMS / remaining anchors)
```

## Roadmap

See [`twinkly-orbiting-garden.md`](../Users/HP/.claude/plans/twinkly-orbiting-garden.md) locally for the full multi-sprint plan:

- **S3** — offline-gap backfill (distribute usage across the minutes the phone was off Wi-Fi)
- **S4** — router control actions (QoS / Wi-Fi toggle / MAC block + parental schedules)
- **S5** — friendly device names + groups + OUI auto-labelling + per-device budgets
- **S6** — speed test, ping monitor, LAN scanner, Wi-Fi analyser
- **S7** — baseline-based anomalies, bundle-runway forecast, quiet hours, weekly digest
- **S8** — home-screen widget, CSV + PDF export
- **S9** — biometric lock, backup/restore, Arabic localisation, real Room migrations

## Not shipping off-device

The app talks only to the router on the LAN (and, for SMS parsing, never to the internet). No analytics, no crash reporting, no cloud backup. Credentials are stored with `EncryptedSharedPreferences` keyed by the Android Keystore.

## License

Private / personal project. No public licence grant at this time.
