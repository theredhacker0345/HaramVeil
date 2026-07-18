# Development

Build, run, test, and contribute to HaramVeil.

## Prerequisites

- **Android Studio** Ladybug or newer
- **JDK 17**
- **Android SDK** Platform 36 (compile/target), minimum SDK 29
- An Android 10+ device or emulator for runtime testing

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

Output APKs:

```
app/build/outputs/apk/fossOnnx/release/app-fossOnnx-release-unsigned.apk
app/build/outputs/apk/mlkit/release/app-mlkit-release-unsigned.apk
```

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

CI workflows in `.github/workflows/` (`build.yml`, `app-ui-test.yml`) run the
Gradle build, lint, and UI tests on every push.

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
