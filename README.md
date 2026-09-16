# Browser2 — ultra-light Android browser (Kotlin + WebView)

දිදුලන අවශ්‍යතා සහිත අති-සැහැල්ලු Android browser එකක්. **Kotlin + Android WebView** මත ගොඩනඟා ඇත —
WebView යනු Android phone එකේම තියෙන system engine එක නිසා, browser engine එකක් (V8/Blink ~100MB+)
APK එකට bundle කරන්න ඕනේ නැහැ. ඒ නිසා APK එක **~2.5 MB** විතරයි, RAM/battery වියදම අඩුයි,
ඒ වගේම YouTube/WebM/WebRTC වගේ modern sites 100% වැඩ කරයි. Engine එකක් දෙවන පාරක් bundle කරන
"full-power browser" එකකට වඩා බොහොම low-end phone වලට සුදුසුයි.

## What's included

| Feature | Status |
|---|---|
| Web browsing (search + URL bar) | ✅ |
| Tabs (multiple tabs, close / switch / add) | ✅ |
| Incognito tabs (memory-only, never saved) | ✅ |
| Site cache (WebView HTTP cache) | ✅ |
| Downloads (system DownloadManager + notification) | ✅ |
| Background media (YouTube / music / video keeps playing after closing) | ✅ |
| Media notification: thumbnail + previous / play-pause / next | ✅ |
| Desktop-site toggle | ✅ |
| Custom app logo (replace to make it yours) | ✅ |

> ⚠️ Note: YouTube **video** background playback is blocked by Google for third-party browsers; YouTube
> **Music/audio** and most other media services work in the background and show controls in the notification.
> Web videos (MP4/WebM) play in the background too.

## Get the APK right now

This repo builds automatically on GitHub. **Your APK is already built** — open the
[**Actions** tab](https://github.com/Sudantha123/Browser2/actions) → the latest green **"Build APK"** run →
scroll to **Artifacts** → download **`browser2-debug`** (zip). Unzip, copy the `app-debug.apk` to your phone,
allow "Install unknown apps" and install.

## Build it from GitHub

### Option A — GitHub Actions (no Android Studio needed)

1. Push/merge this repo to your GitHub (`main`), or open the **Actions** tab and run the
   **"Build APK"** workflow manually (`workflow_dispatch`).
2. When it finishes, open the run → **Artifacts** → download `browser2-debug`.
3. Install the APK on your phone (allow "Install unknown apps") and enjoy.

### Option B — Local build

```bash
# Requirements: JDK 17 + Android SDK 34
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

## Put your own logo

Your logo lives in `app/src/main/res/mipmap-*` (one PNG per density folder).

**Fastest way — replace `ic_launcher.png` with your image:**

```
mipmap-mdpi/ic_launcher.png        48x48
mipmap-hdpi/ic_launcher.png        72x72
mipmap-xhdpi/ic_launcher.png       96x96
mipmap-xxhdpi/ic_launcher.png     144x144
mipmap-xxxhdpi/ic_launcher.png    192x192

mipmap-*-dpi/ic_launcher_round.png     (same sizes, circular)
mipmap-*/ic_launcher_foreground.png    (adaptive-icon foreground, 108dp canvas)
```

Also override the accent colour in `app/src/main/res/values/colors.xml` (`<color name="accent">#E53935</color>`)
to match your brand.

**Easiest for adaptive launchers:** generate your icon at 432×432 with your logo centered in the
middle ~66% (safe zone) and save it over every `ic_launcher_foreground.png`, then set `accent`
as your brand background. Android will crop/mask it automatically.

Finally, change the app name in `app/src/main/res/values/strings.xml` (`app_name`) and the
`applicationId` in `app/build.gradle` if you want your own package name.

## Project structure

```
app/src/main/java/com/browser2/app/
  MainActivity.kt        — tabs, address bar, incognito switch, menus, media prompt
  TabFragment.kt         — one WebView per active tab (others are destroyed = 0 RAM/CPU)
  MediaPlaybackService.kt— foreground service: MediaSession + notification controls
  MediaBridge.kt         — media detection JS bridge + hidden "play in background" WebView
  DownloadReceiver.kt    — download complete/failed notifications + "open file"
  Model.kt / Notify.kt   — tiny prefs store + notification channels
```

## Notes / requirements

- **minSdk 21** (Android 5.0+) → runs on very old phones.
- Background playback needs the `POST_NOTIFICATIONS` permission on Android 13+ (the app asks
  automatically when you first play media).
- Downloads go to the public **Downloads** folder.
