# HaramVeil

> "Veil the Haram. Guard Your Gaze."

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android](https://img.shields.io/badge/Android-10%2B-green.svg)](https://android.com)
[![F-Droid](https://img.shields.io/badge/F--Droid-Pending-blue.svg)](https://f-droid.org)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-29-orange.svg)](https://developer.android.com)

HaramVeil is a privacy-first, Islamic-values-aligned Android app that blocks adult and explicit content on-device. It uses a layered detection system (UI text, OCR, and visual AI), a full-screen veil overlay, and a local lockdown timer to interrupt harmful content quickly while keeping everything private.

---

## What This App Does

- Helps users lower their gaze by blocking explicit content on apps and browsers.
- Runs entirely on-device with no cloud inference.
- Protects its own settings with a PIN and device-level protections.

---

## How Blocking Works

- **Mode 1 - Static UI scanning**: Reads visible UI text, resource IDs, and content descriptions from the accessibility tree. Near-zero CPU.
- **Mode 2 - OCR**: Captures HaramClip zones and scans text inside images when Mode 1 detects risk.
- **Mode 3 - Visual AI**: Runs a NudeNet ONNX model to detect explicit visuals when Mode 3 is enabled.

When a risk is detected:
- A full-screen veil overlay appears.
- The offending app is force-closed when possible.
- The user is returned to the home screen.
- The app can enter a lockdown timer.

---

## Features

- Dashboard mode toggles for Mode 1, Mode 2, and Mode 3.
- Mode 3 is opt-in per app with per-app overrides.
- First-run auto-seed: high-risk monitored apps are forced to Mode 3 by default.
- Always-on browser monitoring (even if Mode 1 is disabled).
- Domain blocklist (blocks URLs and domains shown in browser UI).
- Keyword and regex blocklist (local only).
- Strict protection mode that locks aggressive scan pacing.
- Battery vs Protection slider plus fine-grained pacing controls.
- Safe window with PIN gate and auto-resume.
- Test Protection screen (self-checks, test events, veil preview).
- Service heartbeat + scan timeline for health visibility.
- Lockdown timer per app with overrides.
- Local encrypted history and stats.

---

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose (Material 3)
- **Navigation**: AndroidX Navigation Compose
- **State & Async**: Kotlin Coroutines + Flow
- **Storage**: DataStore (Preferences), EncryptedSharedPreferences, SQLCipher
- **Security**: bcrypt PIN hashing, security questions, Device Admin
- **Services**: Accessibility Service, Foreground Service, WorkManager
- **AI / OCR**:
  - ML Kit (optional, full build)
  - RapidOCR ONNX (FOSS build)
  - NudeNet v3.4 ONNX for visual detection

---

## Privacy and Security

- No cloud inference.
- No analytics or crash reporting.
- All data stored locally and encrypted.
- Settings protected by PIN and sensitive actions gated.
- Direct Boot support to keep protection ready before device unlock.

---

## Build Flavors

| Flavor | ML Kit | Internet | Target |
|--------|--------|----------|--------|
| foss | No | Never | F-Droid / de-Googled ROMs |
| full | Yes (optional) | Debug only | GitHub Releases |

---

## Build from Source

### Prerequisites
- Android Studio (latest stable)
- Android SDK 36
- JDK 17+
- Kotlin 2.x

### Build

```bash
git clone https://github.com/darkmaster0345/HaramVeil.git
cd HaramVeil

# FOSS build (for F-Droid)
./gradlew assembleFossRelease

# Full build (with ML Kit)
./gradlew assembleFullRelease
```

---

## ONNX Models

| Model | Source | Size | Use |
|-------|--------|------|-----|
| 320n.onnx | NudeNet v3.4 | ~6MB | Default (all devices) |
| 640m.onnx | NudeNet v3.4 | ~15MB | Enhanced (capable devices) |

Source: https://github.com/notAI-tech/NudeNet/releases/tag/v3.4-weights  
License: MIT

---

## Permissions Explained

| Permission | Why |
|-----------|-----|
| BIND_ACCESSIBILITY_SERVICE | Read the UI tree for Mode 1 detection |
| SYSTEM_ALERT_WINDOW | Show the veil overlay on top of all apps |
| FOREGROUND_SERVICE | Keep protection alive in the background |
| RECEIVE_BOOT_COMPLETED | Auto-start after device reboot |
| BIND_DEVICE_ADMIN | Prevent unauthorized uninstallation |
| POST_NOTIFICATIONS | Show protection status notifications |

**No `INTERNET` permission in production builds.**

---

## FAQ

**Q: Does HaramVeil guarantee 100% blocking?**  
No app can guarantee 100% coverage across every app and every new content type. HaramVeil reduces exposure with layered detection and strong defaults, but some content can still slip through. The Strict Mode + browser domain blocklist provides the strongest coverage.

**Q: Does it send data to the cloud?**  
No. All detection runs locally on-device.

**Q: What should I do if content gets through?**  
Add the domain to the blocked list, add keywords to the blocklist, and force Mode 3 for the app in Advanced Settings.

**Q: Can I pause protection?**  
Yes. Use Safe Window from the Dashboard. It always requires a PIN and auto-resumes.

---

## Known Limitations

- Android 10+ required (API 29).
- `takeScreenshot()` requires Android 11+; Mode 2 capture is skipped gracefully on Android 10.
- Device Admin uninstall resistance can be bypassed by factory reset.
- Some OEM battery optimizations can still kill background services.

---

## License

Copyright (C) 2026 Ubaid ur Rehman

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

See [LICENSE](LICENSE) for full terms.

---

"Tell the believing men to lower their gaze and guard their private parts. That is purer for them." - Quran 24:30
