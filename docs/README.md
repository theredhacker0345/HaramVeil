# HaramVeil

> *"Tell the believing men to lower their gaze and guard their private parts. That is purer for them."* — **Quran 24:30**

**HaramVeil** is a privacy-centric Android application that acts as an automated "digital guardian." It identifies and veils adult content in real time, across the entire device, using local AI and layered detection heuristics. All processing happens **on-device** — production builds do not even request the `INTERNET` permission.

- **Platform:** Android 10 (API 29) and above, targeting Android 16 (API 36)
- **Language:** 100% Kotlin (Kotlin 2.1, JVM target 17)
- **UI:** Jetpack Compose + Material 3
- **License:** GNU GPL v3.0
- **Repository:** `darkmaster0345/HaramVeil`

---

## Why HaramVeil?

DNS blockers and browser extensions are easy to bypass and miss embedded media. HaramVeil analyzes **what is actually being displayed on screen**, providing a resilient last line of defense that is intelligent and difficult to circumvent. It is designed to be used alongside network-level blockers (NextDNS, AdGuard, etc.) as the final safeguard.

---

## Highlights

- **Three-layer detection** — UI-tree keyword/domain scanning, OCR text extraction, and on-device visual AI inference.
- **100% offline** — OCR and AI inference are local; the FOSS build ships with **no `INTERNET` permission**.
- **Hardened anti-bypass** — bcrypt PIN, device-admin uninstall protection, lockdown mode, and a self-closing "Safe Window."
- **Privacy-first telemetry** — all stats are stored in an SQLCipher database keyed to your PIN hash.
- **Two build flavors** — `fossOnnx` (fully open source) and `mlkit` (Google ML Kit OCR).

See the [full feature breakdown](docs/FEATURES.md), [architecture](docs/ARCHITECTURE.md), and [detection pipeline](docs/DETECTION.md).

---

## Quick Start

```bash
git clone https://github.com/darkmaster0345/HaramVeil.git
cd HaramVeil
./gradlew :app:assembleFossOnnxRelease
# APK: app/build/outputs/apk/fossOnnx/release/app-fossOnnx-release-unsigned.apk
```

For users, the recommended path is the F-Droid build (`fossOnnx`), or a signed APK from the [Releases](https://github.com/darkmaster0345/HaramVeil/releases) page. During onboarding, grant **Accessibility** and **Display over other apps (Overlay)** permissions, then set a strong PIN.

---

## Repository Layout

```
HaramVeil/
├── app/
│   ├── build.gradle.kts          # App module, flavors, dependencies
│   ├── src/
│   │   ├── main/                 # Shared app code
│   │   │   ├── java/com/haramveil/
│   │   │   │   ├── accessibility/   # HaramVeilAccessibilityService (entry point)
│   │   │   │   ├── detection/       # Mode 1/2/3 detection logic
│   │   │   │   ├── overlay/         # VeilOverlayService + controller
│   │   │   │   ├── security/        # PIN, device admin, lockdown
│   │   │   │   ├── service/         # Foreground service, boot recovery, health
│   │   │   │   ├── ui/              # Compose dashboard, settings, stats
│   │   │   │   ├── data/            # Repositories, SQLCipher DB, models
│   │   │   │   ├── utils/           # DetectionBus, throttling, capture
│   │   │   │   └── onboarding/      # First-run coordinator
│   │   │   └── res/                # Resources (manifest under main)
│   │   ├── fossOnnx/              # FOSS OCR engine (RapidOCR)
│   │   ├── mlkit/                 # Google ML Kit OCR engine
│   │   └── test/                  # Unit tests (UITreeScanner)
│   └── proguard-rules.pro
├── config/                       # detekt configuration
├── fastlane/                     # Fastlane metadata
├── fdroid/                       # F-Droid build metadata
├── gradle/                       # Wrapper
├── build.gradle.kts              # Root project, detekt
└── settings.gradle
```

---

## Documentation

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | System design, module responsibilities, data/control flow |
| [DETECTION.md](docs/DETECTION.md) | The three detection modes and how they cooperate |
| [SECURITY.md](docs/SECURITY.md) | PIN, lockdown, device admin, safe window, data encryption |
| [DEVELOPMENT.md](docs/DEVELOPMENT.md) | Build flavors, setup, testing, contribution guidelines |
| [FEATURES.md](docs/FEATURES.md) | User-facing feature reference |
| [FAQ.md](docs/FAQ.md) | Frequently asked questions |

---

## License

Copyright (C) 2026 Ubaid ur Rehman (HaramVeil Contributors).

This program is free software: you can redistribute it and/or modify it under the terms of the **GNU General Public License v3.0**. See [LICENSE](LICENSE) for details.

<p align="center"><b>Built for the community, by the community.</b><br>May Allah reward all those who strive for purity.</p>
