# TVWizard Bridge

Android TV app that dials an outbound WebSocket to the TVWizard relay
(`tv.djwizard.ai`) and translates inbound key commands into AccessibilityService
actions. Pair one TV, control it from any Claude surface.

## Status

v0 — hardcoded bearer token, AccessibilityService + DPAD/HOME/BACK/VOL
dispatch. No pairing UI yet; token is injected via `-PbridgeToken=` at
build time.

## Build + install on the emulator

```bash
source ~/.android-env.sh   # or set JAVA_HOME + ANDROID_HOME yourself

# 1. One-time: create an Android TV AVD
avdmanager create avd -n TVEmu \
    -k "system-images;android-34;android-tv;arm64-v8a" \
    -d "tv_1080p"

# 2. Boot the emulator headless
emulator -avd TVEmu -no-window -no-audio -no-boot-anim -no-snapshot-save &

# 3. Pair a device against the relay and copy the token printed to stdout
go run ./cmd/fakebridge --relay https://tv.djwizard.ai --device-id emu-1
# → TOKEN=...

# 4. Build + install with that token baked in
./gradlew assembleDebug \
  -PrelayUrl=https://tv.djwizard.ai \
  -PbridgeToken=<paste token here> \
  -PdeviceId=emu-1
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 5. Enable the accessibility service
adb shell settings put secure enabled_accessibility_services \
    ai.djwizard.tvbridge/ai.djwizard.tvbridge.TVAccessibilityService

# 6. Launch
adb shell am start -n ai.djwizard.tvbridge/.MainActivity

# 7. Tail logs
adb logcat -s BridgeService TVAccessibility
```

## Wire protocol

Inbound (relay → bridge):

```json
{ "id": "01HZX...", "op": "key", "params": { "key": "UP" } }
```

Outbound (bridge → relay):

```json
{ "id": "01HZX...", "ok": true, "data": { "key": "UP", "device_id": "emu-1" } }
```

Supported keys: `POWER HOME BACK UP DOWN LEFT RIGHT OK VOL_UP VOL_DOWN`.
Mapping lives in [`KeyAction.kt`](app/src/main/java/ai/djwizard/tvbridge/KeyAction.kt).

## What's here

| File | Role |
|---|---|
| `MainActivity.kt` | One-screen UI: shows connection status + a button to jump to Accessibility Settings |
| `BridgeService.kt` | Foreground service. Holds the WebSocket, reconnects with backoff, acks every frame |
| `TVAccessibilityService.kt` | AccessibilityService the bridge calls to perform key actions |
| `KeyAction.kt` | Relay key-name → Android global-action or AudioManager dir |
| `Protocol.kt` | Shared types + wire constants |

## What's not here (yet)

- Pairing UI. Today the token is baked into `BuildConfig`.
- App launch / deep-link ops. Only single key events land.
- EncryptedSharedPreferences. Once pairing moves into the app the token
  lives there.
- Signed release APK. Debug builds only.

## License

Copyright 2026 DJWizard. All rights reserved.
