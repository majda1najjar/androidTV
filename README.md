# XtreamTV — Android TV IPTV Client (Xtream Codes API)

A remote-first Android TV / Google TV client for any Xtream Codes-compatible IPTV
provider. Pure `player_api.php` integration, no middleware. Kotlin + Leanback +
ExoPlayer.

## Features

- **Persistent session** — provider URL / username / password stored in
  `EncryptedSharedPreferences`; app boots straight to the dashboard when
  credentials exist.
- **Three-tab dashboard** — Live TV (category rail + channel grid), Movies
  (poster grid), TV Shows (poster grid → seasons/episodes browser).
- **100% D-pad driven** — every focusable item gets a bright border + scale-up
  focus state; explicit `nextFocusDown` chains on the login form.
- **Hardware color keys** (dashboard *and* player):

  | Key | Action |
  | --- | --- |
  | 🟡 Yellow | Toggle the highlighted / playing live channel in or out of Favorites |
  | 🔵 Blue | Jump straight to the ★ Favorites category row (exits playback first if pressed in the player) |
  | 🟢 Green | Cycle aspect ratio in the player (Fit / Zoom / Stretch) |

- **Resilient playback** — ExoPlayer with automatic retry (3 attempts, 3 s apart)
  when a provider drops or resets a stream; on-screen fading overlays for
  favorites/aspect/error feedback.

## Stream URL formats used

```
Live:   {BASE}/live/{USER}/{PASS}/{STREAM_ID}.ts
Movie:  {BASE}/movie/{USER}/{PASS}/{STREAM_ID}.{container_extension|mp4}
Series: {BASE}/series/{USER}/{PASS}/{EPISODE_ID}.{container_extension|mp4}
```

## Building

Open the project in **Android Studio** (Hedgehog or newer) and press Run — the
IDE provisions the Android SDK and Gradle 8.14 automatically.

CLI build (requires the Android SDK + a `local.properties` with `sdk.dir`):

```bash
./gradlew assembleDebug
```

Install on a TV / emulator:

```bash
adb connect <tv-ip>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Implementation notes

- **minSdk 23**, targetSdk 34. `usesCleartextTraffic=true` because most Xtream
  providers are plain HTTP.
- Grids use Leanback's `VerticalGridView` (the D-pad-optimized RecyclerView) —
  focus alignment and scrolling behave correctly on TV out of the box.
- ExoPlayer **2.19.1** (`StyledPlayerView`) per spec; the bundled artifact
  includes HLS + TS support. If you later migrate, the drop-in successor is
  `androidx.media3`.
- Favorites are a persisted set of live `stream_id`s in `SharedPreferences`
  (the "lightweight local tracker" option — no Room needed for a set of IDs).
- The Xtream API is type-sloppy (ints as strings, `episodes` as object *or*
  array). The client uses a lenient Gson `Int` adapter and a manual parser for
  `get_series_info`, and filters out entries without usable IDs.
- If the device Keystore is broken (some TV boxes), `SessionManager` falls back
  to plain `SharedPreferences` instead of crash-looping.
# androidTV
