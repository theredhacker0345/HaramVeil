# Changelog

All notable changes to HaramVeil are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/) and the project
adheres to [Semantic Versioning](https://semver.org/).

## [1.0.0] — 2026-11-03

### Fixed
- **Visual detection (Mode 3) and OCR (Mode 2) were completely broken**: every
  screenshot was wrapped as a hardware bitmap and drawn on a software canvas, which
  throws on every capture. Captures now deep-copy to a software bitmap first, are
  serialized process-wide (the platform allows one in-flight `takeScreenshot` per
  display) and space out to the platform's 1-second cooldown.
- Mode 3 analyses the **full screen**; previously the detector only saw a distorted
  stacked band-crop it was never trained on.
- FOSS OCR now works **fully offline**: the PP-OCRv5 English model and its dictionary
  are bundled in the `fossOnnx` flavor. Fixes three stacked bugs: no INTERNET
  permission for the old download path, missing `character` model metadata (decoder
  only ever produced blank text), and double normalisation producing garbage decodes.
- OCR setup reminder notification is limited to once per 6 hours instead of firing on
  every OCR trigger.

### Added
- **Visual AI acceleration (NNAPI / XNNPACK)**: the detector can run on the phone's
  AI chip. Adoption is benchmark-gated — an on-device hardware check (Settings →
  Advanced → Visual AI acceleration) times every backend and Auto mode enables the
  AI chip only when it is ≥20% faster than the CPU and inside the scan budget, so
  budget devices without accelerator drivers never enable it. Automatic fallbacks:
  NNAPI → CPU on driver failure, and thermal-guard downgrade to CPU mid-session.
- New PIN-gated **acceleration page** with plain-language verdict, per-backend
  timings, run/re-run hardware check, and manual backend selection.

### Changed
- Release builds are minified (R8) and split per ABI: arm64-v8a ≈52 MB (fossOnnx) /
  ≈46 MB (mlkit), down from 208 MB-class universal builds.
- Battery: single accessibility-tree walk per scan (was two), Mode 3 skips inference
  on unchanged screens, removed the dead Samsung wake lock, screen-off-aware service
  heartbeat, cached root/lockdown checks, settings writes no longer restart the
  detection processors.
- Proper light theme in addition to the dark one; sliders persist on release instead
  of every drag tick.

