# Security & Privacy

HaramVeil is designed to be difficult to bypass impulsively and to protect the
user's data. This document covers the safeguards and the privacy guarantees.

## Local-Only by Design

- **No network.** Production builds do not declare `INTERNET`. OCR and AI
  inference run entirely on-device via ONNX Runtime and (optionally) bundled
  ML Kit. Screen data never leaves the device.
- **No analytics, no telemetry servers.** All operational stats stay on-device.

## PIN Protection

**File:** `security/PinManager.kt`

- PINs are hashed with **bcrypt** at cost **12** (`at.favre.lib:bcrypt`). The
  hash is stored in AndroidX Security **encrypted** shared preferences
  (`EncryptedPrefsFactory`), not plaintext.
- **Brute-force resistance:** after **3** failed attempts the PIN is locked out
  for **20 minutes**. Lockout timing survives reboots by combining wall-clock
  time with `SystemClock.elapsedRealtime()` and the device `BOOT_COUNT`, so
  changing the system clock cannot shorten it.
- The PIN hash is also the **key material** for re-keying the encrypted stats
  database (see below).

### PIN-gated actions

Opening Settings, Advanced Settings, the PIN-change screen, the test/detection
screen, **disabling protection**, **clearing history**, and starting a **Safe
Window** all require the PIN (see `PinActionGateDialog` / `PinGateComposable` in
`ui/`). Disabling protection cannot be done casually or impulsively.

## PIN Recovery

**File:** `security/SecurityQuestionsManager.kt`

If the PIN is forgotten, the user can recover via pre-set **security
questions** (defined in `res/values/strings.xml`, e.g. "Do you wear glasses?",
"Were you born in the same city where you live now?"). These are local and
stored encrypted.

## Lockdown Mode

**File:** `security/AppLockdownManager.kt`

When high-risk content is detected, HaramVeil can trigger a persistent
**lockdown** that:
- sends the user to the home screen,
- prevents returning to the offending app for a configurable duration
  (default **15 minutes**, adjustable in Settings), and
- re-extends the lockdown automatically if the app is reopened while still
  locked (handled in `HaramVeilAccessibilityService.onAccessibilityEvent`).

Active lockdowns are shown in the Dashboard.

## Device Admin Protection

**Files:** `security/DeviceAdminController.kt`,
`security/HaramVeilDeviceAdminReceiver.kt`

HaramVeil registers a `DeviceAdminReceiver`. Activating device admin prevents
**uninstallation** without first deactivating it — which itself requires the
PIN. This stops impulsive removal of the guardian.

## Safe Window

A "Safe Window" lets the user temporarily pause protection (e.g. to change
sensitive settings or add an allowlist entry). It:
- requires the PIN to start,
- auto-resumes protection after the selected window (default 15 minutes, via
  `ProtectionResumeScheduler` / `ProtectionResumeWorker`), and
- is clearly surfaced in the Dashboard so the user is never silently unprotected.

## Encrypted Stats

**Files:** `data/local/StatsRepository.kt`, `HaramVeilDatabase.kt`,
`StatsDatabasePassphrase.kt`

Block history (number of blocks, timestamps, per-app counts) is stored in an
**SQLCipher**-encrypted SQLite database. The database passphrase is derived from
the PIN hash. When the PIN changes, `HaramVeilDatabase.rekeyIfNeeded()`
re-encrypts the database with the new key. Stats retention is configurable
(`statsRetentionDays`, default 90) and cleaned by `StatsCleanupWorker`.

## Permissions

| Permission | Necessity |
|------------|-----------|
| `BIND_ACCESSIBILITY_SERVICE` (service-enforced) | Read the UI tree for detection — required for operation |
| `SYSTEM_ALERT_WINDOW` | Draw the protective veil over other apps |
| `BIND_DEVICE_ADMIN` (receiver-enforced) | Prevent uninstall without the PIN |
| `RECEIVE_BOOT_COMPLETED` | Auto-start protection on boot |
| `FOREGROUND_SERVICE` / `…_SPECIAL_USE` | Keep protection alive (sticky, direct-boot-aware) |
| `POST_NOTIFICATIONS` | Persistent protection-status notification |
| `KILL_BACKGROUND_PROCESSES` | Help close offending apps during lockdown |
| `USE_BIOMETRIC` | Optional biometric unlock of protected actions |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Avoid Doze killing protection |
| `REQUEST_INSTALL_PACKAGES` | Install model/APK updates |

## Resilience Against Kills & Doze

- **Sticky foreground service** (`HaramVeilForegroundService`, `START_STICKY`)
  with a 30-second heartbeat.
- **Boot receiver** restarts protection after reboot (direct-boot-aware).
- **Alarm-based health receiver** (`ServiceHealthAlarmReceiver`) and a
  **WorkManager** `ProtectionHealthWorker` re-launch the runtime if it dies.
- **User-unlock recovery** — on encrypted devices, protection starts once the
  user unlocks.
- **Samsung keep-alive** — a partial wake-lock is acquired on Samsung devices
  where the service is prone to being killed.
- **Root-aware** (`RootAccessHelper`) — on rooted devices, protection can
  restore the accessibility service and lower its nice value for reliability.
