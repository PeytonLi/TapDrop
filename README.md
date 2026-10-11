# TapDrop

Instant, reliable sharing between a Windows laptop (HP Spectre x360) and an Android phone (Galaxy S24), on any
network (including campus Wi-Fi with client isolation), via an end-to-end encrypted Cloudflare relay.

| Path | What |
|---|---|
| `protocol/` | Protocol spec + crypto test vectors (source of truth) |
| `relay/` | Cloudflare Worker + Durable Object relay |
| `windows/` | .NET 8 core library, CLI, and Windows tray app |
| `android/` | Kotlin Android app |

Phase 1: share sheet / Quick Settings tile / hotkey / Send-to sharing. Phase 2: magnet-tap gesture (phone
magnetometer + accelerometer at the laptop's right edge). Phase 3: laptop → phone gestures, LAN fast path.

## Install on Windows

Download `TapDrop-Setup-<version>.exe` from [GitHub Releases](https://github.com/PeytonLi/TapDrop/releases). The installer is per-user and does not require admin rights. Windows SmartScreen may show a warning because the installer is unsigned; choose **More info → Run anyway**. To update, run the newer installer; it closes TapDrop and upgrades in place. Uninstall from **Settings → Apps**; your settings and pairing data are kept unless you choose to delete them.

For a silent install, use `/VERYSILENT /CURRENTUSER`. To also delete user data during silent uninstall, pass `/DELETEUSERDATA` to `unins000.exe`.

## Development

Run the local relay or deploy it with Wrangler:

```sh
cd relay
npm ci
npm run dev
npm run deploy # after `npx wrangler login`
```

### Deployed relay

The deployed relay is `https://tapdrop-relay.lipeyton2006.workers.dev`. To deploy an update with Wrangler:

```sh
cd relay && npx wrangler deploy
```

Build and use the development CLI:

```sh
dotnet build windows/TapDrop.sln
dotnet run --project windows/TapDrop.Cli -- pair-new --relay http://127.0.0.1:8787 --name Laptop --out laptop.json
dotnet run --project windows/TapDrop.Cli -- listen --config laptop.json --role laptop --save-dir received
dotnet run --project windows/TapDrop.Cli -- send-text --config laptop.json --role laptop --text "Hello"
```

#### Knock to send (laptop → phone)

Enable **Knock to send** in the tray app's **Settings** (off by default), then double-knock the laptop palm rest or lid to send the clipboard to the phone. Use **Test knock** and the sensitivity slider to calibrate. The microphone is open only while knock-to-send is enabled and the workstation is unlocked; it is released when disabled or locked. Audio is processed in memory only and is never stored or sent anywhere.

### Cutting a release

Bump `<Version>` in `windows/Directory.Build.props`, create a `vX.Y.Z` tag, and push it. To build the Windows installer locally, run `pwsh windows/installer/build.ps1`.

### Android

Build the sideloadable debug APK with JDK 17 and Android SDK platform 35:

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Samsung Galaxy phones, set **Settings → Apps → TapDrop → Battery → Unrestricted** so the paired relay connection can remain available in the background. Allow notifications and add TapDrop's **Send to laptop** tile in Quick Settings for clipboard sharing.

#### Magnet tap-to-send

Enable **Magnet tap** in TapDrop, then open **Calibrate magnet tap** with the laptop online and the phone unlocked. Tune the magnet, bump, and timing sliders while watching the live sensor values; enable **Dry run** to see the URL or image filename the next tap would send without transmitting it.

For browser URLs and screen capture, enable TapDrop under **Settings → Accessibility → Installed apps**. Android 13+ may block sideloaded accessibility services until you open **App info → ⋮ → Allow restricted settings**. TapDrop reads a supported browser's address-bar text only when a tap is detected; otherwise it captures the screen on Android 11+ when permitted. Grant full photo access if recent photos should take priority; selected-photo-only access is intentionally skipped. Magnet sensing runs only while the paired laptop is online and the phone is interactive and unlocked.

Run the local relay-to-CLI end-to-end test (including a 10 MiB file and offline queue):

```sh
bash scripts/e2e.sh
```
