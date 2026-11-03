# Development

Build, run, test, and contribute to HaramVeil.

## Prerequisites

- **Android Studio** Ladybug or newer
- **JDK 21** (the Gradle daemon toolchain; Kotlin targets JVM 17)
- **Android SDK** Platform 36 (compile/target), minimum SDK 29
- An Android 10+ device or emulator for runtime testing
- **CI**: pushes to the GitLab repository run `detekt`, both flavors' unit tests, and APK assembly via [.gitlab-ci.yml](../.gitlab-ci.yml)

## Project Structure

Gradle root uses the `com.android.application` (9.0.0) and Kotlin (2.1.0)
plugins, plus **detekt** (1.23.8) for static analysis. The app is the single
module `:app`.

## Build Flavors

Two product flavors share the `ocrFlavour` dimension:

| Flavor | OCR Engine | External Deps | Notes |
|--------|-----------|---------------|-------|
| `fossOnnx` | RapidOCR (ONNX) | 100% open source | No `INTERNET` — F-Droid / privacy build |
| `mlkit` | Google ML Kit | Proprietary binaries | Faster OCR, Play Services dependency |

The selected flavor sets `BuildConfig.INCLUDE_MLKIT`.

### Assemble

```bash
# FOSS (recommended, fully open source)
./gradlew :app:assembleFossOnnxRelease

# ML Kit
./gradlew :app:assembleMlkitRelease
```

Release builds run R8 + resource shrinking and are **split per ABI**:

```
app/build/outputs/apk/fossOnnx/release/app-fossOnnx-arm64-v8a-release-unsigned.apk   (~52 MB)
app/build/outputs/apk/fossOnnx/release/app-fossOnnx-armeabi-v7a-release-unsigned.apk
app/build/outputs/apk/fossOnnx/release/app-fossOnnx-x86_64-release-unsigned.apk
app/build/outputs/apk/fossOnnx/release/app-fossOnnx-universal-release-unsigned.apk
```

### Signing releases

Release APKs are unsigned unless a keystore is configured. For local signing:

```bash
# one-time: create a keystore
keytool -genkeypair -v -keystore haramveil.jks -alias haramveil \
  -keyalg RSA -keysize 4096 -validity 10000

# sign an already-built APK
zipalign -p -f 4 app-fossOnnx-arm64-v8a-release-unsigned.apk aligned.apk
apksigner sign --ks haramveil.jks --out HaramVeil-fossOnnx-arm64-v8a-release.apk aligned.apk
```

Keep the keystore private and out of version control; wire it into the release
build type via `signingConfigs` when automating releases.

### R8 / ProGuard notes

The rules in `app/proguard-rules.pro` keep the entire `ai.onnxruntime.**` Java
surface (the engines resolve session metadata **reflectively**) and the ML Kit
classes (the `mlkit` flavor drives them 100% via reflection). Blanket
`androidx.**` keeps are deliberately **not** used — library consumer rules handle
them, and blanket keeps cost ~20 MB of dead dex. If you add reflection-based
code, add a targeted keep rule and run the manual release pass from
[TESTING.md](TESTING.md).

### Install to a connected device

```bash
./gradlew :app:installFossOnnxRelease
```

## Running

1. Open the project in Android Studio.
2. Select the `fossOnnxRelease` (or `mlkitRelease`) build variant.
3. Run on a device/emulator.
4. During onboarding, grant **Accessibility** and **Display over other apps**.
5. Set a PIN; protection starts automatically.

> Note: Mode 3 (visual AI) and screen capture require a real device with the
> accessibility screenshot capability. The emulator may not support all capture
> paths.

## Testing

- **Unit tests** live in `app/src/test/` (e.g. `UITreeScannerTest` validating
  keyword/domain matching and risk classification).
  ```bash
  ./gradlew :app:testFossOnnxDebugUnitTest
  ```
- **Instrumented tests** use `androidx.test` + Espresso (configured in
  `build.gradle.kts`). Run on a device/emulator:
  ```bash
  ./gradlew :app:connectedAndroidTest
  ```
- **Lint** is strict (`abortOnError = true`, `checkDependencies = true`) and
  emits XML/HTML reports.
- **detekt** enforces the config in `config/detekt/detekt.yml`:
  ```bash
  ./gradlew detekt
  ```

CI workflows in `.github/workflows/` (`build.yml`, `app-ui-test.yml`) run on the
GitHub mirror; the GitLab repository runs [.gitlab-ci.yml](../.gitlab-ci.yml):
**on push to main it only runs static analysis + unit tests** (skipped entirely
for docs-only commits), and **APK assembly is a manual job** — press the play
button next to `build-apks` on a pipeline (or Run pipeline → trigger it) when
you actually want release APKs. APK builds are deliberately never automatic:
two R8 releases across 4 ABIs would burn pipeline minutes on every push.

## F-Droid

- **Build metadata template**: `fdroid/com.haramveil.yml`. Submission copies it to
  the [fdroiddata](https://gitlab.com/fdroid/fdroiddata) repository as
  `metadata/com.haramveil.yml` and opens a merge request there. The template pins
  the release commit hash; bump it (and add a new `Builds:` block with the new
  versionCode) for every release.
- **Build shape**: F-Droid builds run `gradle -PfdroidBuild assembleFossOnnxRelease`
  — the property disables ABI splits so exactly one universal APK is produced
  (`output:` glob matches it). Normal developer builds keep per-ABI splits.
- **Store texts**: `app/metadata/android/en-US/` (title/short/full descriptions,
  changelogs, icon, screenshots). They live under `app/` because the Build
  metadata uses `subdir: app` — fdroidserver reads them from the build subdir.
- **Bundled binaries**: the two ONNX model weights and the Amiri font are declared
  via `scanignore` in the metadata with their origin documented in
  [LICENSES.md](../LICENSES.md) and [NOTICE](../NOTICE). Model sources must stay
  verifiable — never add a model without a license + origin note + scanignore
  entry.
- **Known policy constraints**: F-Droid asks for ~3 months of active development
  before inclusion (exceptions possible — state the user base and maintenance
  commitment in the merge request); no Google Play Services in the built flavor
  (the `mlkit` flavor must never be referenced in the metadata).

## Module Map (detection/inference)

Beyond the [architecture](ARCHITECTURE.md) overview, these are the pieces to know
when touching detection:

| File | Role |
|---|---|
| `utils/ScreenCaptureManager.kt` | Screenshot capture; hardware→software bitmap copy; two paths: `captureFullScreen` (Mode 3) and `captureHaramClip` (Mode 2 OCR bands); process-wide mutex + platform-cooldown spacing |
| `detection/mode3/InferenceAccelerator` & `AccelerationModels.kt` | Backend enum (Auto/NNAPI/XNNPACK/CPU) and benchmark result model |
| `detection/mode3/AcceleratorSessionOptions.kt` | Session options per backend; NNAPI needs symbolic dims frozen |
| `detection/mode3/AccelerationBenchmarkRunner.kt` | Times CPU/XNNPACK/NNAPI-FP32/NNAPI-FP16 (median of 5); NNAPI adopted only if ≥20% faster |
| `detection/mode3/NudeNetInferenceEngine.kt` | ONNX session lifecycle per (model, accelerator) with automatic CPU fallback; YOLOv8 tensor decoding |
| `detection/mode3/Mode3Processor.kt` | Trigger loop: rate limit, unchanged-content skip, thermal guard (also downgrades acceleration), full-screen capture |
| `detection/mode2/FossOcrEngine.kt` | Bundled PP-OCRv5 OCR; dictionary fallback; CTC decode; **input normalisation is plain x/255** (the export folds mean/std into the graph) |
| `ui/settings/AccelerationScreen.kt` | Acceleration page: verdict, timings, backend picker, hardware-check runner |

## Key Dependencies

| Library | Purpose |
|---------|---------|
| Jetpack Compose BOM `2026.03.01` | UI (Material 3) |
| `androidx.navigation:navigation-compose` | In-app navigation |
| `androidx.datastore:datastore-preferences` | Settings persistence |
| `androidx.security:security-crypto` | Encrypted preferences |
| `androidx.work:work-runtime-ktx` | Health/resume workers |
| `net.zetetic:android-database-sqlcipher` | Encrypted stats DB |
| `at.favre.lib:bcrypt` | PIN hashing |
| `com.microsoft.onnxruntime:onnxruntime-android` | Local visual AI inference |
| `com.google.android.gms:play-services-mlkit-text-recognition` | ML Kit OCR (**mlkit only**) |
| `io.coil-kt:coil-compose` | Image loading (model assets) |
| `io.gitlab.arturbosch.detekt` | Static analysis |

## Conventions

- **100% Kotlin**, Jetpack Compose UI, coroutines + `StateFlow` for state.
- All source files carry the GPL v3 header.
- Package layout mirrors the [architecture](ARCHITECTURE.md): `accessibility`,
  `detection`, `overlay`, `security`, `service`, `ui`, `data`, `utils`,
  `onboarding`.
- Sensitive actions must go through the PIN gate; do not add unprotected paths
  to disable/clear/change security.

## Contributing

1. Fork and create a feature branch.
2. Follow the existing style; ensure `./gradlew detekt` and lint pass.
3. Add or update tests for new detection/security logic.
4. Open a PR with a clear description. Bug reports should include reproducible
   steps. Translations are welcome.

## Code of Conduct

Be respectful and constructive. HaramVeil is a community project built to help
people; contributions should uphold that purpose.
