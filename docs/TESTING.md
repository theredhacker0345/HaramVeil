# Device Smoke-Test Checklist

All CI verification is compile/test/static-analysis level. This checklist is the
**minimum manual pass** before shipping a release APK. Everything below should be
done on a real device (emulators exaggerate NNAPI behaviour and screenshot paths).

## 1. Install & onboarding
- [ ] Install the arm64 release APK for your flavor; it installs and launches.
- [ ] Onboarding completes: accessibility service granted, overlay permission granted, PIN + security questions set.
- [ ] Dashboard shows protection ACTIVE; the persistent notification is present.

## 2. Mode 1 — keyword/domain scan
- [ ] Open a browser to a site whose address contains a blocked domain → veil appears.
- [ ] Lockdown engages for that app; reopening the app within the window re-triggers the veil.

## 3. Mode 2 — OCR
- [ ] `fossOnnx` flavor: with an eligible app in the foreground showing large text with a blocked keyword (e.g. an image caption), a block is logged in Stats.
- [ ] No "Finish FOSS OCR setup" notification appears (the model is bundled now).

## 4. Mode 3 — visual AI
- [ ] Open an eligible app showing known adult imagery (test responsibly) → veil appears within a couple of seconds.
- [ ] Stats logs a Mode 3 block; scanning a normal app produces no false veil.

## 5. Acceleration
- [ ] Advanced Settings → **Visual AI acceleration** opens behind the PIN gate.
- [ ] **Run hardware check** completes in a few seconds and shows per-backend timings + a verdict.
- [ ] On a flagship/recent device: NNAPI timings should beat CPU; on a budget device: verdict should honestly pick XNNPACK/CPU.
- [ ] Toggling AI chip / Fast CPU / Standard / Auto switches backends without breaking Mode 3 (re-test step 4 afterwards).
- [ ] Long heavy scrolling for a few minutes: no thermal spiral; if the guard fires, scanning continues on the CPU.

## 6. Security
- [ ] Disabling protection, opening acceleration, changing the PIN all require the PIN.
- [ ] Device admin: deactivating requires the PIN (if enabled).
- [ ] Safe window: pausing protection resumes automatically after the timeout.

## 7. Battery sanity (an afternoon of normal use)
- [ ] Settings → Battery shows HaramVeil using a modest share (not top-3 unless you browsed heavily).
- [ ] No wake-lock/keep-awake entries in battery details.
- [ ] adb: `adb shell dumpsys deviceidle | grep -i haramveil` shows no held wake locks.

## 8. Release artifacts
- [ ] Per-ABI APKs install on arm64 and (if available) a 32-bit device.
- [ ] R8 release build: exercise onboarding, scanning, stats, PIN, and the acceleration page — shrinkage can only break runtime reflection paths, which is exactly what this pass catches.
