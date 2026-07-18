# Detection Pipeline

HaramVeil detects unwanted content through three cooperating layers. Each layer
is independently configurable and feeds a single decision point: the **Veil**.

```
   Screen content
        │
        ▼
 ┌──────────────┐   match? ──► Veil (Mode 1)
 │  Mode 1:     │
 │  UI Tree     │   no match ──► still wake Mode 2 & 3
 └──────┬───────┘
        │ Mode1Triggered (wake)
        ├──────────────┐
        ▼              ▼
 ┌──────────────┐  ┌──────────────┐
 │  Mode 2: OCR │  │ Mode 3: Visual│
 │  text in img │  │ AI (NudeNet) │
 └──────┬───────┘  └──────┬───────┘
        │ match            │ match
        ▼                  ▼
     Veil (Mode 2)      Veil (Mode 3)
```

---

## Mode 1 — UI Tree Analysis (Keyword & Domain)

**File:** `detection/mode1/UITreeScanner.kt`
**Driver:** `accessibility/HaramVeilAccessibilityService.kt`

Inspects the **accessibility node tree** of the active application through the
`AccessibilityService`. It walks the tree (iterative `ArrayDeque` traversal),
collecting visible text, view resource IDs, and content descriptions.

### Matching

- **Keyword matching** — each entry in `keywordBlocklist` is tested against the
  collected signals. Entries containing regex metacharacters are treated as
  `Regex` (case-insensitive); otherwise a plain case-insensitive substring
  match is used.
- **Domain matching** — specialized for browsers and high-risk apps. The scanner
  extracts candidate domains from text (via `([a-z0-9-]+\.)+[a-z]{2,}`), then
  compares against `domainBlocklist` using substring or regex rules.

### Package classification

`UITreeScanner` maintains curated sets:

| Set | Examples |
|-----|----------|
| `BrowserPackages` | Chrome, Firefox, Samsung Internet, Brave, DuckDuckGo, Edge, Kiwi… |
| `HighRiskPackages` | Instagram, TikTok, Snapchat, X, YouTube, Reddit, Telegram, dating apps… |
| `BrowserKeywords` / `HighRiskKeywords` | substring fallbacks (e.g. `tiktok`, `reddit`) |

Browsers and high-risk apps are **always** scanned even if not in the user's
monitored list, because risky content frequently appears there.

### Risk levels

| RiskLevel | Condition | Veil behavior |
|-----------|-----------|---------------|
| `HIGH` | A keyword or domain matched | Veil shown immediately |
| `MEDIUM` | Monitored (non-browser) app on screen | Veil shown immediately |
| `LOW` | No match, not monitored | No veil; Mode 2/3 may still be woken |

`computeNodeHash()` produces a stable hash of the visible content so the
`ThrottleManager` can skip re-scanning identical frames.

---

## Mode 2 — Intelligent OCR

**Files:** `detection/mode2/` (`OcrEngine`, `FossOcrEngine`, `MLKitOcrEngine`,
`Mode2Processor`)

Periodically captures an internal screen "clip" (processed **entirely in
memory**) and extracts text that may be hidden from the UI tree — text rendered
inside images or videos, for example.

- **`fossOnnx` flavor** → `FossOcrEngine` (RapidOCR, ONNX — no Google deps).
- **`mlkit` flavor** → `MLKitOcrEngine` (Google ML Kit text recognition).

The selected engine is chosen by `OcrEngineSelector` from
`ProtectionSettings.selectedTextEngine`. Extracted text is tested against the
same keyword and domain blocklists as Mode 1. OCR only runs when Mode 1 has
woken it (it never scans on its own), which conserves battery.

---

## Mode 3 — Visual AI Inference (NudeNet)

**Files:** `detection/mode3/` (`NudeNetInferenceEngine`, `NudeNetPreprocessor`,
`ModelSelector`, `Mode3Processor`)

Runs a **NudeNet** deep-learning model **locally via ONNX Runtime**. The screen
clip is preprocessed (`NudeNetPreprocessor`) into a normalized tensor and fed to
the model.

### Model selection

`VisualModelOption` exposes at least:
- **320n** ("Mobile", ~6 MB) — fast, lighter accuracy.
- **640n** ("Enhanced", ~15 MB) — better accuracy where supported.

Models can be **bundled assets** or **user-imported files**
(`ModelSource.BUNDLED_ASSET` vs `IMPORTED_FILE`). Imported models live in
`filesDir/models/` and are loaded directly.

### Output decoding

`NudeNetInferenceEngine` robustly decodes both:
- **Ultralytics YOLOv8 detection** tensors (the shipped `320n.onnx` is a YOLOv8
  detection head: channels `5 + K`, anchor grids `21·k²`), and
- flat classification vectors.

It resolves class labels from model metadata, falls back to a known NudeNet
label set, and normalizes raw logits via sigmoid. A detection is "unsafe" when an
unsafe-class score (e.g. `BUTTOCKS_EXPOSED`, `FEMALE_GENITALIA_EXPOSED`)
exceeds `DefaultUnsafeThreshold = 0.35`.

### Thermal guard

To protect device health, `Mode3Processor` measures inference time. If inference
exceeds `1.5 s` three times in a row, it raises the visual-scan interval to
`2 s` ("thermal guard") and persists the new pacing.

---

## Coordination & Throttling

- **`ThrottleManager`** debounces window events into scan triggers. The debounce
  interval is `frameSkipIntervalMs` (default `500 ms`, `StrictFrameSkipIntervalMs`
  in strict mode). Identical node hashes are skipped.
- **`DetectionBus`** is a `SharedFlow` event bus carrying `Mode1Triggered`,
  `Mode2Triggered`/`Mode2Clear`, `Mode3Triggered`/`Mode3Clear`, and
  `VeilRequested` events. Mode 2 and Mode 3 processors subscribe to
  `Mode1Triggered` and run concurrently.
- **`ScreenCaptureManager.captureHaramClip()`** captures a configurable vertical
  region (`topCapturePercent`, `middleCapturePercent`) rather than the full
  screen, reducing memory and inference cost.

## Tuning knobs (`ProtectionSettings`)

| Setting | Default | Effect |
|---------|---------|--------|
| `frameSkipIntervalMs` | 500 ms | Mode 1 scan debounce |
| `mode3InferenceIntervalMs` | 1000 ms | Mode 3 min interval (clamped 1–2 s) |
| `topCapturePercent` / `middleCapturePercent` | 30 / 40 | Screen-clip region |
| `lockdownDurationMs` | 15 min | Per-app lockdown window |
| `strictModeEnabled` | false | Uses stricter skip/intervals |
| `mode3ForcedPackages` | — | Apps forced through visual scan |
