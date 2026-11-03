# Dynamic Island for Android

An iPhone-style Dynamic Island for Android, plus a clean status bar that shows only the time.
It updates itself from this GitHub repo, so wherever you are, new versions reach your phone.

## Get it

1. Open **[Releases](../../releases/latest)** on your phone and download `DynamicIsland-v1.x.apk`.
2. Open the file and allow your browser/files app to install apps if asked.
3. Open **Dynamic Island** and follow the **Setup** card:
   - **Island overlay** → turn on the Accessibility service.
     *Greyed out?* Settings → Apps → Dynamic Island → ⋮ (top right) → **Allow restricted settings**, then try again.
   - **Notification access** → allow (needed for notifications, music, calls, timers, maps).
   - **Install updates** → allow (lets the app update itself).

## What it shows

| | |
|---|---|
| Notifications | From every app (or the ones you choose). Hold to see the full message and its buttons, **reply right from the island** |
| Music & video | Album art + waveform; hold for play/pause, skip, seek |
| Calls | Works with any phone app (reads only the call state). Round Answer / Decline, live call timer, hang up |
| Timers | Countdown / stopwatch from your Clock app, with its buttons |
| Maps | Next turn from Google Maps and other nav apps |
| Downloads | Progress ring |
| Charging | Battery animation on plug-in, Fully Charged, Low Battery |
| Silent / Ring | Sound mode changes, iPhone-style |
| Unlock | A padlock pops open when you unlock |
| Flashlight | Torch on / off |
| Headphones | Bluetooth (with battery %) and wired |
| Quick apps | Hold the island when nothing's happening for a tray of your favourite apps |
| Custom prompts | Your own messages: show now, pin, or daily at a set time |

**Gestures (like iPhone):** tap to open the app · touch and hold to expand · swipe sideways to dismiss ·
swipe up to hide. Two activities at once split into the island plus a small circle.

**Choose apps:** switch any app off, and star the ones you want in quick apps.
**Privacy:** message text is hidden while the phone is locked (optional). Nothing leaves your phone except the
update check to GitHub. The diagnostics list records app names and notification types only, never content.

**Status bar icons:** "Hide system icons" removes Android's own icons. It needs a one-time command from a computer:

```
adb shell pm grant com.moongames.dynamicisland android.permission.WRITE_SECURE_SETTINGS
```

**Prompts from other apps (Tasker etc.):** send a broadcast with action
`com.moongames.dynamicisland.SHOW_PROMPT` and extras `text` (and optionally `emoji`, `duration` in ms).

## How updates work

- Every push to `main` runs `.github/workflows/build.yml`, which builds a signed APK and publishes it as a
  Release tagged `build-<number>`.
- The app checks the latest Release when you open it and every 6 hours in the background. With
  "Install updates automatically" on, it downloads and installs the new APK (Android may ask you to tap **Install**).
- Every build is signed with the same private key, so each update installs over the previous one.
  The key is stored in the repo's encrypted secrets (`KEYSTORE_BASE64` and `KEYSTORE_PASSWORD` under
  Settings → Secrets and variables → Actions), never in the code. Keep a backup of it: if the key
  changes, you'd have to uninstall and reinstall the app.

## Building yourself

Android Studio, or `./gradlew assembleRelease` with JDK 17 and the Android SDK.
