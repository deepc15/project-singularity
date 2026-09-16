# Project Singular

Two applications, built from `docs/Project Singular APP.docx`:

| | | |
|---|---|---|
| **Singular Desk** | `windows/` | The receiving screen. Electron + TypeScript. Hosts each cast app as a tile, decodes H.264 with WebCodecs, and drives the app with the PC's mouse and keyboard. |
| **Singular Cast** | `android/` | The sender. Kotlin + Compose. Lists installed and running apps, casts one when you swipe it toward your chosen edge, gives you a touchpad to drive it, and brings it back on demand. |

They speak [Singular Protocol v1](PROTOCOL.md) over TCP on the local network.

## What the spec asked for, and how each part works

| Requirement | Implementation |
|---|---|
| Windows receiving screen | [`windows/src/renderer/app.ts`](windows/src/renderer/app.ts) — a tile grid; each tile is a [`Tile`](windows/src/renderer/tile.ts) with its own decoder and input routing. |
| Add apps from the phone, including running ones | [`AppRepository`](android/app/src/main/java/com/singular/cast/apps/AppRepository.kt) — launchable apps from `PackageManager`; "running" from the privileged task list, "recent" from `UsageStatsManager`. |
| Send an app to the PC by sliding to a side | [`SwipeToCast`](android/app/src/main/java/com/singular/cast/ui/SwipeToCast.kt) — drag distance and direction are both configurable. |
| The side is user-modifiable | `CastEdge` in [`Settings.kt`](android/app/src/main/java/com/singular/cast/model/Settings.kt), chosen on the Settings tab. |
| The app automatically fits the PC client screen | The virtual display is created at the PC tile's pixel size, and re-cut when the tile is resized — `stream.geometry` → [`CastEngine.resizeStream`](android/app/src/main/java/com/singular/cast/cast/CastEngine.kt). The tile also letterboxes with [`fitRect`](windows/src/renderer/input.ts). |
| A button to get the app back to the phone | "Bring back" on the phone and on each PC tile → `stream.recall` → `moveTaskToDisplay(task, 0)`. |
| Control it with PC keyboard and mouse | Tile pointer/wheel/key handlers → `input.*` → real `MotionEvent`/`KeyEvent` injection on the cast display. |
| Automatic touchpad controller on the phone | [`TouchpadScreen`](android/app/src/main/java/com/singular/cast/ui/TouchpadScreen.kt) — a pad with the cast display's aspect ratio, one finger to touch, two to scroll. |
| Keyboard appears automatically when text is needed | [`SingularAccessibilityService`](android/app/src/main/java/com/singular/cast/ime/SingularAccessibilityService.kt) notices editable focus on the cast display → `ime.show` → the phone's keyboard bridge takes focus and raises the soft keyboard. |

## The one hard constraint

Android has no public API for "capture just this app". Putting a single app on
its own screen needs a **trusted virtual display** plus the right to launch an
activity onto it, which is gated behind `ADD_TRUSTED_DISPLAY` — a signature
permission. So Singular Cast has two paths:

- **Privileged path (what the spec describes).** With [Shizuku](https://shizuku.rikka.app/)
  or an ADB-started shell service, a `SingularUserService` runs as `shell` and
  creates one trusted display per app, launches the app onto it, and injects
  input into it. The phone's own screen stays free — you can keep using it while
  three apps run on the PC.
- **Fallback path.** With no Shizuku, the only capture API available is
  `MediaProjection`, which mirrors the *whole screen* as a single tile. Input
  falls back to accessibility gestures, which can tap, swipe and set text but
  cannot stream a pointer. Singular Desk labels this tile `screen mirror` so the
  difference is never hidden from the user.

The Desk UI shows which path is active as chips in the title bar.

## Build and run

### Singular Desk (Windows)

Needs Node 20+.

```bash
cd windows && npm install && npm start
```

Package an installer:

```bash
cd windows && npm run dist
```

On first run, allow **Singular Desk** through the Windows Firewall on private
networks — without that the phone cannot reach TCP 8787 and discovery will find
nothing.

### Singular Cast (Android)

Needs JDK 17+ and the Android SDK (API 35, build-tools 35.0.0). Point
`android/local.properties` at your SDK (`sdk.dir=...`), or just open `android/`
in Android Studio and let it write that file for you.

```bash
cd android && ./gradlew :app:assembleDebug
```

Install it on a connected device:

```bash
cd android && ./gradlew :app:installDebug
```

Both variants are known to build clean — `assembleDebug`, `assembleRelease`
(R8 enabled) and `lintDebug` all pass with no warnings. The release APK is
2.4 MB.

If your network does TLS interception, the wrapper may fail to download Gradle
itself with a `PKIX path building failed` error: the JDK has its own truststore
and will not see your corporate root CA. Either import that CA into the JDK's
`cacerts`, or build with a locally installed Gradle 8.11+.

Then, on the phone:

1. Install and start [Shizuku](https://shizuku.rikka.app/) (wireless debugging
   or root). Open Singular Cast → Settings → **Grant** next to Shizuku.
2. Settings → **Open settings** next to *Accessibility bridge*, and enable
   "Singular Cast input bridge". This is what makes the keyboard appear
   automatically.
3. Optional: grant *Usage access* so the app list can mark recently used apps.

### Using them together

1. Start Singular Desk on the PC.
2. Open Singular Cast, let discovery find the PC, tap **Connect** (or type the
   PC's IP if your network blocks UDP broadcast).
3. Swipe an app toward your configured edge. It appears as a tile on the PC,
   already sized to fit.
4. Use the PC's mouse and keyboard on the tile, or the **Control** tab on the
   phone. Right-click on a tile is Android *Back*.
5. **Bring back** returns the app to the phone.

## Layout

```
PROTOCOL.md                     wire protocol, both apps implement it
android/gradlew(.bat)           wrapper, pinned to Gradle 8.11.1
windows/
  src/main/protocol.ts          framing + message types
  src/main/server.ts            TCP server, UDP discovery responder
  src/main/main.ts              Electron entry point
  src/preload/preload.ts        the only bridge into the renderer
  src/renderer/decoder.ts       H.264 Annex-B via WebCodecs
  src/renderer/tile.ts          one cast app: canvas, decode, input
  src/renderer/input.ts         Windows input -> Android events, aspect fit
  src/renderer/app.ts           receiving screen shell
android/app/src/main/
  aidl/.../ISingularService.aidl  the privileged interface
  java/.../cast/SingularUserService.kt  runs as shell: displays, launch, input
  java/.../cast/ShizukuBridge.kt        binds it, degrades gracefully
  java/.../cast/CastEngine.kt           orchestrates sessions
  java/.../cast/ScreenEncoder.kt        H.264 from a Surface
  java/.../cast/CastService.kt          foreground service
  java/.../net/                         protocol, discovery, TCP client
  java/.../apps/AppRepository.kt        installed + running apps
  java/.../ime/                         auto-keyboard + fallback input
  java/.../ui/                          Compose screens
```

## Known limits

- Per-app casting requires Shizuku/ADB, for the reason above. Everything
  degrades to screen mirroring without it.
- `injectText` synthesises key events through `KeyCharacterMap`, so characters
  that a US virtual keyboard cannot produce are dropped rather than mangled.
- Audio is not forwarded. The protocol reserves frame types `0x04`/`0x05` for it.
- One phone per PC at a time; a second connection supersedes the first.
- Some OEM builds (notably older MIUI/EMUI) refuse `FLAG_TRUSTED` even as shell.
  `createDisplay` retries without it and reports failure rather than silently
  producing a black tile.
