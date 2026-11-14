# AppLocator

**Find any app's icon on your home screen.** AppLocator lists every app that has a launcher icon, shows its basic info, and — with one tap — jumps to the home screen, flips through pages (and opens folders) to find the icon, and marks it with a pulsing red highlight.

No root required. Powered by the Accessibility service.

## Features

- **App inventory** — lists all apps with a home-screen (launcher) icon, with icon, label, package name, version, APK size, and system-app badge
- **Instant search** — filter by app name or package name as you type
- **Locate on home screen** — taps "Locate" and the app steps aside: the service scans the current page, swipes through every page, opens folders one by one, and pins the target with a 4-second pulsing highlight
- **No QUERY_ALL_PACKAGES** — uses a manifest `<queries>` declaration, so it's Play-policy friendly

## How it works

1. Apps are enumerated via `queryIntentActivities(ACTION_MAIN, CATEGORY_LAUNCHER)`
2. Tapping **Locate** sends the app label to the accessibility service and returns to the launcher
3. The service polls the active window's accessibility tree, matching clickable icon nodes by their `content-desc`
4. If not found, it swipes to the next page (page signatures detect wrap-around), then opens unexplored folders and looks inside
5. On a match, a `TYPE_ACCESSIBILITY_OVERLAY` view draws a pulsing ring + rounded frame over the icon for 4 seconds

## Requirements

- Android 10+ (API 29)
- Accessibility service permission (Settings → Accessibility → AppLocator)

> **Why accessibility?** On non-rooted devices, reading the launcher's view tree is only possible through the accessibility APIs. The service never collects or transmits any data; it only reads the foreground window's node tree while locating.

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Known limitations

- Icons that live only in the app drawer (not placed on the home screen or in a folder) cannot be located — the app will tell you
- Launcher UIs differ across vendors; known-good: Pixel Launcher, MIUI/HyperOS (com.miui.home) — PRs for other launchers welcome
- The service waits up to 8 seconds for the launcher to come to the foreground before giving up

## License

MIT
