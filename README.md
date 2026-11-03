# <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="48" height="48" align="center" /> HaramVeil

[![License: GPL v3](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Android Version](https://img.shields.io/badge/Android-10%2B-green.svg?logo=android&logoColor=white)](https://developer.android.com/about/versions/10)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-purple.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![F-Droid FOSS](https://img.shields.io/badge/F--Droid-FOSS%20Ready-brightgreen.svg?logo=f-droid&logoColor=white)](https://f-droid.org/)
[![APK Size](https://img.shields.io/badge/APK%20arm64-~52%20MB-informational)](#-installation--setup)

> "Tell the believing men to lower their gaze and guard their private parts. That is purer for them." - **Quran 24:30**

**HaramVeil** is a privacy-centric Android application that helps users maintain spiritual purity. It works as an automated "digital guardian": it identifies and veils adult content in real time across the whole device using a local AI detector, on-device OCR, and keyword scanning — **all processing happens on the device, and the app has no INTERNET permission**.

---

> 📚 **Full documentation** lives in the [`docs/`](docs/) folder: [Architecture](docs/ARCHITECTURE.md), [Detection Pipeline](docs/DETECTION.md), [Security & Privacy](docs/SECURITY.md), [Development](docs/DEVELOPMENT.md), [Features](docs/FEATURES.md), and [FAQ](docs/FAQ.md). Asset licensing is tracked in [LICENSES.md](LICENSES.md) and [NOTICE](NOTICE).

## 🌟 Vision & Purpose

In an age where explicit content is often just a click away, **HaramVeil** provides a proactive safety net. Unlike DNS blockers that can be bypassed or miss embedded content, HaramVeil analyzes what is actually displayed on screen — a last line of defense that is intelligent and difficult to circumvent.

---

## ✨ Features

### 🛡️ Layered Detection Architecture

#### 1. Mode 1 — UI Tree Analysis (Keyword & Domain)
* Inspects the accessibility node tree of active apps (single shared traversal, deduplicated by content fingerprint).
* Matches visible text against a local keyword blocklist and browser address bars against a domain blocklist.
* Differentiates LOW / MEDIUM / HIGH risk to choose the right veil response.

#### 2. Mode 2 — Intelligent OCR (works fully offline)
* Captures internal screenshots in memory and reads text hidden from the UI tree (text inside images, address bars, overlays).
* **Bundled FOSS engine**: a PP-OCRv5 English recognition model + dictionary ship inside the `fossOnnx` flavor — no download, no Google dependency, no INTERNET permission needed.
* **ML Kit engine** (optional `mlkit` flavor) for devices that prefer Google's recognizer.

#### 3. Mode 3 — Visual AI Inference (NudeNet)
* A **NudeNet v3.4** YOLOv8 detector analyses the **full screen** and flags the seven explicit body-part classes with box-level scores.
* Runs locally through **ONNX Runtime** with automatic hardware acceleration — see below.
* Choose the bundled 320 "Mobile" model for speed, or import the higher-accuracy 640 "Enhanced" model (~100 MB) from the NudeNet v3.4 release.

### ⚡ Visual AI Acceleration (NNAPI / XNNPACK)
HaramVeil can run the detector on your phone's **AI chip** (GPU/DSP/NPU via Android NNAPI) instead of the CPU — but only when measurement proves it is worth it:

* **Hardware check** (Settings → Advanced → *Visual AI acceleration*): times the real detector on every backend your device offers — standard CPU, XNNPACK-optimized CPU, and NNAPI in FP32/FP16.
* **Auto mode (default)** enables the AI chip only when it is at least **20% faster** than the CPU and inside the scan latency budget. Phones without a usable accelerator driver never enable it — they simply stay on the fastest CPU path.
* **Layered safety**: session creation falls back NNAPI → CPU automatically, the thermal guard downgrades hardware acceleration to the CPU for the session if it repeatedly underperforms, and you can always pin any backend manually.

### 🔒 Hardened Security & Anti-Bypass
* **Lockdown mode**: on detection the app can trigger a persistent lockdown that sends you home and blocks the offending app for a set period.
* **PIN protection**: all sensitive settings are gated behind a locally-hashed PIN, including the acceleration controls.
* **Security questions** for PIN recovery.
* **Device admin integration** prevents impulsive uninstallation.
* **Safe window**: temporarily pause protection with your PIN; it auto-resumes after a timeout.

### 📊 Insights & Stats
* Block history, time saved, and trends stored in a **SQLCipher-encrypted** local database keyed to your PIN.

---

## 🤖 AI Models Used

| Model | Size | Purpose | Source | License |
|---|---|---|---|---|
| **NudeNet 320n** (bundled) | ~12 MB | Mode 3 explicit-content detection (YOLOv8-nano, 18 body-part classes) | [NudeNet v3.4 weights](https://github.com/notAI-tech/NudeNet/releases/tag/v3.4-weights) | AGPL-3.0 (upstream repo — see [NOTICE](NOTICE) for the weights note) |
| **NudeNet 640m** (optional import) | ~100 MB | Higher-accuracy Mode 3 detection (YOLOv8-medium) | same release, imported via the file picker | same |
| **PP-OCRv5 en rec** (bundled, `fossOnnx` flavor) | ~8 MB | Mode 2 offline OCR (text-line recognition) + `ppocrv5_en_dict.txt` dictionary | [RapidAI/RapidOCR](https://github.com/RapidAI/RapidOCR) v3.7.0 (converted from PaddleOCR) | Apache-2.0 |
| **ML Kit Text Recognition** (`mlkit` flavor only) | downloaded by Play Services | Alternative OCR engine | Google ML Kit | proprietary — excluded from F-Droid builds |

The detector input is the full screen (letterboxed 320×320 for the bundled model); the OCR path analyses the top and middle text bands. All inference runs on-device.

---

## 🛠️ Technical Deep Dive

### Tech Stack
| Layer | Technology |
|:--- |:--- |
| **Language** | Kotlin (100%) |
| **UI** | Jetpack Compose + Material 3 (light & dark themes) |
| **Async/State** | Coroutines & StateFlow |
| **Storage** | DataStore (Preferences) + SQLCipher (SQLite) |
| **AI Engine** | ONNX Runtime — **NNAPI / XNNPACK / CPU** execution providers, chosen per device by benchmark |
| **OCR** | PP-OCRv5 (bundled ONNX) / ML Kit |
| **Architecture** | Clean Architecture / MVI-ish |

### Internal Components
- `accessibility/`: the `HaramVeilAccessibilityService` — event intake, throttling, fingerprinting (a single tree walk shared by fingerprint + text scan).
- `detection/`: Mode 1 UI scanning, Mode 2 OCR, Mode 3 ONNX inference + acceleration benchmarking.
- `overlay/`: hardware-accelerated "Veil" overlay with Quran/hadith display and lockdown handling.
- `security/`: PIN management (bcrypt), lockdown deadlines, device admin.
- `service/`: sticky foreground service, health watchdog, boot recovery.
- `ui/`: Compose dashboard, settings (incl. the acceleration page), stats, PIN flows.

### Energy & Smoothness Design
- One accessibility-tree traversal per scan (fingerprint and text scan share the collected signals).
- Mode 3 skips inference when the screen fingerprint hasn't changed (a bounded rescan window keeps video covered).
- Heartbeat writes slow down while the screen is off; no wake locks; root probes are cached.
- Settings changes never restart the detection processors unless the gating flags change.

---

## 📦 Build Flavors

| Flavor | OCR Engine | External Deps | Target Audience |
|:--- |:--- |:--- |:--- |
| **fossOnnx** | Bundled PP-OCRv5 (ONNX) | 100% open source, no INTERNET permission | F-Droid users, privacy advocates |
| **mlkit** | Google ML Kit | proprietary Play Services binaries | Play-distributed builds |

Release builds are minified (R8) and split per ABI: `arm64-v8a` (≈52 MB fossOnnx / ≈46 MB mlkit), `armeabi-v7a` (legacy 32-bit devices), `x86_64` (emulators), plus a universal APK.

---

## 🚀 Installation & Setup

### For Users

#### Option A: F-Droid (Recommended)
Once accepted into the main F-Droid repository:
1. Open [F-Droid](https://f-droid.org/) on your device.
2. Search for "HaramVeil".
3. Tap Install.

The F-Droid build uses the `fossOnnx` flavor — 100% open source, no Google dependencies, no INTERNET permission, OCR and the NSFW detector bundled and functional offline.

#### Option B: Direct APK Download
1. Download the per-ABI release APK (`HaramVeil-fossOnnx-arm64-v8a-release.apk` fits most modern phones) or the universal one from [Releases](https://gitlab.com/theredhacker0345/HaramVeil/-/releases).
2. Install the APK (allow "Unknown Sources" if prompted).
3. **Important**: grant **Accessibility** and **Display over other apps** during onboarding.
4. Set a strong PIN and choose recovery questions you will remember.
5. (Optional) Run the hardware check under *Advanced Settings → Visual AI acceleration* to enable AI-chip acceleration.

#### Option C: Build from Source
```bash
git clone https://gitlab.com/theredhacker0345/HaramVeil.git
cd HaramVeil
./gradlew :app:assembleFossOnnxRelease
# APKs: app/build/outputs/apk/fossOnnx/release/app-fossOnnx-<abi>-release-unsigned.apk
```

### For Developers
1. **Prerequisites**: Android Studio Ladybug+, JDK 21 (Gradle toolchain; Kotlin targets JVM 17), Android SDK 36.
2. **Clone & build**:
   ```bash
   git clone https://gitlab.com/theredhacker0345/HaramVeil.git
   cd HaramVeil
   ./gradlew :app:assembleFossOnnxDebug    # FOSS flavor, debug
   ./gradlew :app:assembleMlkitRelease     # ML Kit flavor, R8 release
   ./gradlew :app:testFossOnnxDebugUnitTest :app:detekt
   ```
3. Signed release: configure a keystore in the release build type; unsigned APKs are produced without it.

---

## 📜 Permissions Explained

| Permission | Necessity |
|:--- |:--- |
| `BIND_ACCESSIBILITY_SERVICE` | Reads the UI tree and takes screenshots for detection. The app cannot function without it. |
| `SYSTEM_ALERT_WINDOW` | Draws the protective veil over other apps. |
| `BIND_DEVICE_ADMIN` | Prevents uninstallation without the app PIN. |
| `RECEIVE_BOOT_COMPLETED` | Restarts protection automatically after boot. |
| `POST_NOTIFICATIONS` | Persistent protection-status notification. |
| `KILL_BACKGROUND_PROCESSES` | Used in lockdown mode to close offending applications. |
| `INTERNET` | **Stripped from release builds** (`tools:node="remove"`). The `mlkit` debug flavor uses it for Google Play Services OCR model delivery. |

---

## ❓ Frequently Asked Questions

**Q: Will this slow down my phone?**
A: The detector is deliberately frugal: it only runs when eligible apps are foreground and the screen content changed, skips unchanged screens, and paces itself. If your phone has a usable AI chip, run the hardware check — acceleration typically cuts inference time several-fold. There is also a "Battery vs Protection" slider.

**Q: Is my screen data being uploaded?**
A: **Never.** All processing (OCR, AI inference, keyword matching) happens strictly on your device, and release builds have no INTERNET permission at all.

**Q: Why is there an "AI chip" option?**
A: The visual detector is the heaviest part of the pipeline. Android's NNAPI can run it on the GPU/DSP/NPU instead of the CPU — but on phones without proper drivers it would be *slower*, so HaramVeil measures first and enables it only when it genuinely helps. You can override the decision on the acceleration page.

**Q: Can I use this with a VPN or DNS blocker?**
A: Yes — it's recommended. HaramVeil is designed as the last line of defense alongside network-level blockers like NextDNS or AdGuard.

**Q: Why are there multiple APK files?**
A: Release APKs are split per CPU architecture so you only download the ~26 MB AI runtime once. Most phones need `arm64-v8a`; the universal APK contains everything.

---

## 🤝 Contributing

We welcome contributions: bug fixes, features, blocklist improvements, translations.
- **Bug reports**: open an issue with clear reproduction steps and device info.
- **Pull requests**: follow the existing style; `./gradlew :app:detekt` and the unit tests must pass.

---

## ⚖️ License

Copyright (C) 2026 Ubaid ur Rehman

This program is licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE).

Third-party components have their own licenses; see [LICENSES.md](LICENSES.md) for the full inventory (AI model weights, OCR model + dictionary, font, libraries) and [NOTICE](NOTICE) for compatibility notes. Notable points:
- The bundled **PP-OCRv5** OCR model and dictionary are **Apache-2.0**.
- The **NudeNet** detector weights come from an **AGPL-3.0** upstream; the weight-file redistribution terms are discussed in [NOTICE](NOTICE).
- The optional `mlkit` flavor depends on **proprietary** Google binaries and is not F-Droid eligible.

---

<p align="center">
  <b>Built for the community, by the community.</b><br>
  May Allah reward all those who strive for purity.
</p>
