# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/qianzhu123/AppLocator/compare/v1.1...HEAD
[1.1]: https://github.com/qianzhu123/AppLocator/compare/v1.0...v1.1
[1.0]: https://github.com/qianzhu123/AppLocator/commits/v1.0
