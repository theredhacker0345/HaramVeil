# FAQ

Common questions about HaramVeil.

## Will this slow down my phone?

HaramVeil is heavily optimized. Visual AI uses hardware acceleration where
available, all scanning is throttled (`frameSkipIntervalMs`, the Mode 3
interval clamp), and a "Battery vs. Protection" pacing control lets you trade
scanning frequency for battery. A **thermal guard** in `Mode3Processor`
automatically raises the visual-scan interval if inference gets slow (3 strikes
over ~1.5 s → 2 s pacing).

## Is my screen data uploaded?

**Never.** All processing — OCR, AI inference, and keyword/domain matching —
happens strictly on your device. The FOSS production build does not even declare
the `INTERNET` permission.

## Can I use this with a VPN or DNS blocker?

Yes, and it is recommended. HaramVeil is the "last line of defense" that catches
content network-level blockers miss (embedded media, in-app browsers, images
with text). See the [comparison in FEATURES.md](FEATURES.md#why-not-just-a-dns-blocker).

## What if I forget my PIN?

Recover access through the **security questions** you set during onboarding.
They are stored locally and encrypted (`SecurityQuestionsManager`).

## How do I temporarily disable protection?

Open a **Safe Window** with your PIN. It pauses protection for a chosen duration
(default 15 minutes) and auto-resumes afterward, so you never stay unprotected
by accident. Disabling protection outright also requires the PIN.

## Why does it need Accessibility and Overlay permissions?

- **Accessibility (`BIND_ACCESSIBILITY_SERVICE`)** — lets HaramVeil read the
  on-screen UI tree for detection. It cannot function without this.
- **Overlay (`SYSTEM_ALERT_WINDOW`)** — lets it draw the protective Veil over
  other apps.

Both are requested during onboarding and are required.

## Which apps get scanned?

- **Browsers** (Chrome, Firefox, Brave, Edge, DuckDuckGo, Samsung Internet, Kiwi,
  and more) are always scanned, including their address-bar domains.
- **High-risk apps** (Instagram, TikTok, Snapchat, X, YouTube, Reddit, dating
  apps, and others) are always scanned.
- Any other app can be added to your **monitored apps** list in Settings.

## Does Mode 3 (visual AI) run on every app?

No. To save battery, Mode 3 is woken for browser, high-risk, and monitored apps
(and any app you *force* into the visual pipeline). It never scans on its own —
it is always triggered by a Mode 1 window event.

## What is "Visual AI acceleration"?

Mode 3 is the heaviest part of the pipeline. HaramVeil can run its detector on
your phone's AI chip (GPU/DSP/NPU via NNAPI) or with XNNPACK-optimized CPU
kernels. **Auto mode measures first**: the hardware check (Settings → Advanced →
Visual AI acceleration) times every backend and enables the AI chip only when it
is at least 20% faster than the CPU — so on phones without a usable AI chip it
stays off automatically. You can always pin a backend manually on that page, and
everything falls back to the plain CPU if a driver misbehaves.

## How accurate is detection?

Mode 1 is deterministic (keyword/domain rules). Mode 3 uses a NudeNet model with
an unsafe-score threshold of `0.35`; you can keep the bundled Mobile model
(~12 MB, 320 input) or import the Enhanced model (~100 MB, 640 input) from the
NudeNet v3.4 release. False positives are reduced by only veiling on
`MEDIUM`/`HIGH` risk matches.

## Is it on F-Droid?

The project is structured for F-Droid (FOSS `fossOnnx` flavor, no proprietary
deps). Check the [Releases](https://github.com/darkmaster0345/HaramVeil/releases)
page and F-Droid for the latest build status.

## What Android versions are supported?

Android 10 (API 29) and above, targeting Android 16 (API 36).
