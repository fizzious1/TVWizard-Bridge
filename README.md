# TVWizard Bridge

Android TV app that dials an outbound WebSocket to the TVWizard relay
(`tv.djwizard.ai`) and translates inbound key commands into
AccessibilityService actions. Pair one TV, control it from any Claude
surface.

## Status

v1 — in-app pairing (no more rebuild per device), Leanback banner, and
BOOT_COMPLETED receiver. AccessibilityService + DPAD/HOME/BACK/VOL
dispatch. Debug APK only — no signed release yet.

## Pairing flow

The TV does the work. No typing tokens on a remote.

1. Install the APK and enable the accessibility service.
2. The app calls `/pair/init` and displays a 6-digit code on screen.
3. Tell Claude: **"pair my TV with code 612632 and call it living-room"**.
4. Claude calls `tv.claim_device` (or `curl /pair/claim`) with the code
   and a device name. Once claimed, the bridge's WebSocket succeeds on
   the next retry (≤2 s).
5. Token is stored in `EncryptedSharedPreferences` and reused on every
   subsequent launch. Uninstall or "Reset pairing" to re-pair.

## Build + install on the emulator

```bash
source ~/.android-env.sh   # sets JAVA_HOME / ANDROID_HOME / PATH

# 1. One-time: create an Android TV AVD
avdmanager create avd -n TVEmu \
    -k "system-images;android-34;android-tv;arm64-v8a" \
    -d "tv_1080p"

# 2. Boot the emulator headless
emulator -avd TVEmu -no-window -no-audio -no-boot-anim -no-snapshot-save &

# 3. Build a debug APK — no pairing flags required
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 4. Enable the accessibility service (emulator can use adb; real TVs
#    need the user to toggle it in Settings → Accessibility)
adb shell settings put secure enabled_accessibility_services \
    ai.djwizard.tvbridge/ai.djwizard.tvbridge.TVAccessibilityService
adb shell settings put secure accessibility_enabled 1

# 5. Launch
adb shell am start -n ai.djwizard.tvbridge/.MainActivity

# 6. Tail logs to see the pair code
adb logcat -s TVBridge:V
# → pair/init code=612632 token=***
```

Claim the code from your Mac:

```bash
curl -X POST -H "Content-Type: application/json" \
    -d '{"code":"612632","device_id":"living-room"}' \
    https://tv.djwizard.ai/pair/claim
```

The bridge transitions to `Online` within ~2 seconds.

## Testing with a real TV

1. Pairing flow is identical. The only difference is step 4 — accessibility
   must be enabled by the user in Settings, not via `adb shell settings put`.
2. Network ADB (`adb connect <tv-ip>:5555`) is the cleanest install path
   if you don't have a USB connection to the TV.

## Wire protocol

Inbound (relay → bridge):

```json
{ "id": "01HZX...", "op": "key", "params": { "key": "UP" } }
```

Outbound (bridge → relay):

```json
{ "id": "01HZX...", "ok": true, "data": { "key": "UP" } }
```

Supported keys: `POWER HOME BACK UP DOWN LEFT RIGHT OK VOL_UP VOL_DOWN`.
Mapping lives in [`KeyAction.kt`](app/src/main/java/ai/djwizard/tvbridge/KeyAction.kt).

## Layout

| File | Role |
|---|---|
| `MainActivity.kt` | Renders bridge state: AwaitingAccessibility → Pairing(code) → Connecting → Online |
| `TVAccessibilityService.kt` | The one long-lived component. Owns the WebSocket, pairing loop, key dispatch |
| `ConfigStore.kt` | `EncryptedSharedPreferences` wrapper for (token, device_id) |
| `RelayClient.kt` | HTTP client for `/pair/init` |
| `BridgeState.kt` | `sealed class` states published via `StateFlow` |
| `BootReceiver.kt` | Warms up the process on `BOOT_COMPLETED` — the accessibility service auto-restarts regardless; this is belt-and-braces |
| `KeyAction.kt` | Relay key-name → Android global-action or AudioManager dir |
| `Protocol.kt` | Shared types + wire constants |
| `res/drawable/bridge_banner.xml` | 320×180 Leanback banner (vector) |

## Build flags (optional)

For smoke-testing with a pre-existing token (skip the in-app pairing flow):

```bash
./gradlew assembleDebug \
    -PrelayUrl=https://tv.djwizard.ai \
    -PbridgeToken=<pre-claimed-token> \
    -PdeviceId=emu-1
```

The BuildConfig value is a fallback — if prefs are empty, we use it;
otherwise prefs win. Useful for CI and emulator smoke tests; real users
never need these flags.

## License

Copyright 2026 DJWizard. All rights reserved.
