# Features

A user-facing reference for everything HaramVeil can do.

## Detection

### Layered, three-mode pipeline
HaramVeil never relies on a single technique:

1. **UI Tree Analysis** — inspects the on-screen accessibility node tree for
   blocked keywords and domains. Works in every app, with regex or substring
   rules.
2. **OCR (Optical Character Recognition)** — reads text hidden inside images and
   videos by capturing an in-memory screen clip and running OCR locally.
3. **Visual AI (NudeNet)** — runs an on-device deep-learning model via ONNX
   Runtime to detect explicit imagery and poses, even with no text present.

Modes can be toggled independently in Settings, and each is throttled to balance
protection against battery life.

### Smart targeting
- **Browsers** (Chrome, Firefox, Brave, Edge, DuckDuckGo, Samsung Internet,
  Kiwi, and more) are always scanned, including their address-bar domains.
- **High-risk apps** (Instagram, TikTok, Snapchat, X, YouTube, Reddit, dating
  apps, and others) are always scanned, and you can force them through the
  visual AI pipeline.
- Any additional app can be added to your **monitored apps** list.

### Risk-aware responses
Detection maps to `LOW` / `MEDIUM` / `HIGH` risk, and only meaningful matches
trigger the veil — reducing false positives.

## The Veil

A hardware-accelerated, native overlay window draws the protective "Veil" over
blocked content. It is themed, calm, and shows a short reminder passage rather
than the blocked material.

## Anti-Bypass Safeguards

- **PIN protection** — bcrypt-hashed, encrypted, with a 3-strike / 20-minute
  lockout that survives reboots.
- **PIN-gated controls** — disabling protection, clearing history, changing the
  PIN, and opening advanced/test screens all require the PIN.
- **Device Admin** — prevents uninstall without first entering the PIN.
- **Lockdown Mode** — automatically sends you home and blocks the offending app
  for a configurable period; re-opens are re-blocked.
- **Safe Window** — a PIN-gated, self-closing pause (default 15 min) that
  auto-resumes protection so you never stay unprotected by mistake.
- **Security-question recovery** — regain access if the PIN is forgotten.

## Insights & Stats

- **Encrypted history** — every block is logged in an SQLCipher database keyed
  to your PIN hash.
- **Dashboard** — see blocks today, this week, all-time, your most-blocked app,
  active lockdowns, and live protection/service status at a glance.
- **Trends** — review recent events and track progress over time.
- **Retention control** — choose how long history is kept (auto-cleaned by a
  background worker).

## Resilience

- Sticky foreground service with a heartbeat.
- Auto-start on boot (direct-boot-aware).
- Alarm + WorkManager health recovery if the system kills the service.
- User-unlock recovery on encrypted devices.
- Samsung-specific wake-lock keep-alive.
- Optional root-aware reliability improvements.

## Customization

- Choose the **OCR engine** (FOSS RapidOCR vs Google ML Kit per build flavor).
- Choose the **visual model** (Mobile ~6 MB for speed, Enhanced ~15 MB for
  accuracy; import your own).
- Add/remove **keywords** and **domains** to the blocklists.
- Configure **frame-skip** and **inference intervals**, capture regions, and
  lockdown duration.
- Enable **Strict Mode** for tighter pacing.

## Why not just a DNS blocker?

DNS/VPN blockers filter by domain at the network layer. They are a great first
line of defense, but they:

- **Miss embedded media** — an explicit image or video hosted on an allowed
  domain still loads.
- **Miss in-app browsers** — many apps render web content internally.
- **Are easy to switch off** — a single toggle disables them.

HaramVeil operates at the **screen layer**, analyzing what is actually displayed.
It catches content network blockers miss, and its PIN/device-admin safeguards
make it far harder to disable on impulse. The two are complementary: use a DNS
blocker (NextDNS, AdGuard) to narrow the field, and HaramVeil as the last line
of defense.

| Capability | DNS blocker | HaramVeil |
|-----------|------------|-----------|
| Blocks by domain | ✅ | ✅ (plus keywords) |
| Sees embedded images/video | ❌ | ✅ (OCR + visual AI) |
| On-device content analysis | ❌ | ✅ |
| Hard to disable impulsively | ❌ | ✅ (PIN + device admin) |
| Works fully offline | Often | ✅ (FOSS build) |

## Privacy

- **No internet.** The FOSS build has no `INTERNET` permission.
- **On-device only.** OCR and AI inference never send data off the device.
- **No account, no tracking.** Everything stays on your phone.

## Build Flavors

| Flavor | OCR Engine | External Deps | Best for |
|--------|-----------|---------------|----------|
| `fossOnnx` | RapidOCR | 100% Open Source | F-Droid, privacy advocates |
| `mlkit` | Google ML Kit | Proprietary binaries | Performance-focused users |
