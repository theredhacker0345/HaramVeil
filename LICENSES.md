# HaramVeil — Asset Licenses

This file documents the licenses of all third-party assets bundled in the APK.

## Source Code

- **License**: GNU General Public License v3.0 only (GPL-3.0-only)
- **File**: [LICENSE](LICENSE)

## Third-Party Libraries

See [NOTICE](NOTICE) for a complete list of dependencies and their licenses.

All dependencies are Free/Libre Open Source Software (FLOSS):
- AndroidX (Apache 2.0)
- ONNX Runtime (MIT)
- Coil (Apache 2.0)
- Bcrypt — at.favre.lib (Apache 2.0)
- SQLCipher (BSD-style)
- Kotlin Coroutines (Apache 2.0)
- Jetpack Compose (Apache 2.0)

## Bundled Assets

### ONNX Model Weights

| File | Size | Source | License |
|------|------|--------|---------|
| `app/src/main/assets/320n.onnx` | 12 MB | [NudeNet v3.4 weights](https://github.com/notAI-tech/NudeNet/releases/tag/v3.4-weights) | AGPL-3.0 (upstream repo) |
| `app/src/main/assets/640m.onnx` | 99 MB | [NudeNet v3.4 weights](https://github.com/notAI-tech/NudeNet/releases/tag/v3.4-weights) | AGPL-3.0 (upstream repo) |

These are pre-trained neural network weight files (data, not executable code).
The upstream NudeNet repository is licensed under AGPL-3.0. The weight files
are distributed as part of the NudeNet release. Since the HaramVeil app is
GPL-3.0 and AGPL-3.0 is compatible (AGPL is a stronger copyleft), these
weights can be redistributed.

### Quranic Text

| File | Size | Content | License |
|------|------|---------|---------|
| `app/src/main/assets/hadiths_ayahs.json` | 14 KB | Quran verses and Hadith text | Public Domain |

The Quran and Hadith are centuries-old religious texts in the public domain.

### Font

| File | Size | Font | License |
|------|------|------|---------|
| `app/src/main/res/font/amiri_regular.ttf` | 599 KB | [Amiri](https://github.com/aliftype/amiri) | SIL Open Font License 1.1 (OFL) |

Amiri is a Quran-compatible Arabic typeface. The OFL is a FLOSS-compatible
license accepted by F-Droid.

### App Icon and Graphics

| File | License |
|------|---------|
| `app/src/main/res/mipmap-*/ic_launcher.png` | GPL-3.0 (project) |
| `app/src/main/res/mipmap-*/ic_launcher_round.png` | GPL-3.0 (project) |
| `app/src/main/res/drawable/ic_haramveil_shield.xml` | GPL-3.0 (project) |
| `app/src/main/res/drawable/veil_geometric_pattern.xml` | GPL-3.0 (project) |

All app icons and vector graphics were created for the HaramVeil project and
are licensed under the same GPL-3.0 as the source code.

## F-Droid Compliance Notes

1. **No proprietary dependencies** in the `fossOnnx` flavor (the only flavor
   built for F-Droid). The `mlkit` flavor (which includes Google Play
   Services) is NOT built for F-Droid.

2. **No INTERNET permission** in release builds — the `app/src/release/AndroidManifest.xml`
   strips the INTERNET permission via `tools:node="remove"`.

3. **No tracking, analytics, or advertising** libraries.

4. **No API keys** bundled in the source or binary.

5. **No runtime binary downloads** in release builds (INTERNET permission is
   stripped, so `ModelDownloadManager` is dead code in the FOSS release).

6. **All assets** have documented FLOSS-compatible licenses.
