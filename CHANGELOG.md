# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.6] - 2026-11-14

### Added

- **Same-wifi in-app update (LAN-first)**: the app now asks a small PC-side update server (`tool/server.js`, port 8778) for the latest build; a dialog offers a one-tap update that downloads the APK from the PC and hands it straight to the system installer — no browser, no GitHub. A `tool/release.ps1` script pushes each new build into the update channel. GitHub Releases remains a fallback (browser download) for when the PC is offline.

## [1.5] - 2026-11-14

### Fixed

- Folder probing no longer launches random apps: candidate icons are matched against ALL installed apps (not just launcher-visible ones), so hidden components like Mi Mover are no longer mistaken for folders; if a probe click does leave the launcher, the service presses BACK immediately and resumes scanning instead of hanging until timeout
- Page-sweep edge detection actually works now (no-change counter was never incremented, flipping relied on the step cap): after each swipe the icon signature is compared, and two identical pages in a row means an edge was reached — zigzag left-to-the-end then right now covers every page regardless of the starting page
- Accessibility status banner: MIUI reports the service as disabled through the standard API even when it is bound; the app now reads the secure settings string directly

## [1.4] - 2026-11-14

### Fixed

- Highlight offset on MIUI: the overlay is now a small window positioned exactly at the icon's screen coordinates instead of a full-screen window with inset math
- Page flipping got stuck on one direction; folder detection rewritten as behavioral probing (click candidates whose label is not a known app, confirm by view-tree change)

## [1.3] - 2026-11-14

### Fixed

- MIUI preloads adjacent home pages into the accessibility tree with off-screen bounds (e.g. `Rect(0, 1789 - -8718, 2095)`), causing false matches; nodes are now filtered to on-screen bounds
- Folder phase starvation: raised step cap and restructured the scan loop so folder probing actually runs

## [1.2] - 2026-11-14

### Added

- **In-app update check**: on launch the app queries the GitHub Releases API and, when a newer version exists, shows a one-tap update link in the status bar (no dialog)

## [1.1] - 2026-11-14

### Fixed

- Locate flow no longer depends on accessibility events: the service now actively polls the window tree, which fixes "nothing happens" on ROMs that throttle accessibility events (MIUI/HyperOS)
- Launcher detection broadened: any package containing "launcher" plus a known-vendor list (MIUI, Huawei, OPPO, Samsung, etc.) is treated as the home screen
- Icon matching normalizes spaces and case, tolerant of label variants

### Added

- User feedback at each stage: "locating…" toast on tap, result toast on success/miss
- Diagnostics: the service logs each scan step under the `AppLocator` tag

## [1.0] - 2026-11-14

### Added

- App list with icon, label, package, version, size, system badge; instant search
- One-tap locate: page flipping, folder opening, pulsing highlight overlay (TYPE_ACCESSIBILITY_OVERLAY)
- Verified end-to-end on Pixel 2 XL / Android 11 (Pixel Launcher)

[Unreleased]: https://github.com/qianzhu123/AppLocator/compare/v1.2...HEAD
[1.6]: https://github.com/qianzhu123/AppLocator/compare/v1.2...v1.6
[1.5]: https://github.com/qianzhu123/AppLocator/compare/v1.2...v1.5
[1.4]: https://github.com/qianzhu123/AppLocator/compare/v1.3...v1.4
[1.3]: https://github.com/qianzhu123/AppLocator/compare/v1.2...v1.3
[1.2]: https://github.com/qianzhu123/AppLocator/compare/v1.1...v1.2
[1.1]: https://github.com/qianzhu123/AppLocator/compare/v1.0...v1.1
[1.0]: https://github.com/qianzhu123/AppLocator/commits/v1.0
