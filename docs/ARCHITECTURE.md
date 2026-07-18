# Architecture

This document describes the internal design of HaramVeil: how the Android
components fit together, how detection events flow through the system, and the
role of each package.

## Design Principles

1. **Everything runs on-device.** No network calls in production. OCR and visual
   AI inference are local. The `fossOnnx` flavor does not even declare
   `INTERNET`.
2. **Layered defense.** Three independent detection modes feed a single
   decision point (the **Veil**) so that a miss in one layer can still be caught
   by another.
3. **Resilient protection.** A sticky foreground service, a boot receiver, an
   alarm-based health check, and a WorkManager worker all cooperate to keep
   protection alive across reboots, kills, and Doze mode.
4. **Sensitive by default.** Disabling protection, clearing history, and
   changing the PIN are all gated behind the local PIN.

## Component Map

```
                         ┌─────────────────────────────┐
                         │         MainActivity          │
                         │   (Compose NavHost shell)     │
                         └───────────────┬───────────────┘
                                         │ starts
                                         ▼
                       ┌──────────────────────────────────┐
                       │   HaramVeilForegroundService       │  (sticky, specialUse)
                       │  - heartbeat loop                  │
                       │  - boot / user-unlock recovery     │
                       │  - Samsung wake-lock keep-alive     │
                       └───────┬──────────────────────┬─────┘
                               │ starts                │ starts
                               ▼                        ▼
                ┌──────────────────────────┐   ┌────────────────────────────┐
                │ HaramVeilAccessibilitySvc│   │     VeilOverlayService      │
                │  - listens to window     │   │  - draws the protective veil│
                │    events                 │   │  - native hardware-accel.   │
                │  - Mode 1 scan driver     │   └──────────────┬─────────────┘
                └───────────┬──────────────┘                  │
                            │ publishes scan triggers         │ DetectionBus
                            ▼                                  │ (veil requests)
                ┌───────────────────────────────┐            │
                │  Mode2Processor  Mode3Processor │◄───────────┘
                │  (OCR)            (Visual AI)   │
                └───────────┬───────────────────┘
                            │ log blocks
                            ▼
                ┌──────────────────────────────┐
                │  StatsRepository (SQLCipher)  │
                └──────────────────────────────┘
```

## Key Packages

### `accessibility`
The **entry point for all monitoring**.
`HaramVeilAccessibilityService` subscribes to `TYPE_WINDOW_CONTENT_CHANGED`
and `TYPE_WINDOW_STATE_CHANGED` events. For each eligible package it builds a
throttled scan trigger (`ThrottleManager`) and runs the Mode 1 UI-tree scan.
When Mode 1 matches, or when it wants to wake the visual pipeline, it publishes
to the `DetectionBus`.

Relevant files:
- `HaramVeilAccessibilityService.kt` — event loop, lockdown re-trigger, scan orchestration
- `AccessibilityServiceUtils.kt` — enable/open helpers, status checks

### `detection`
Core logic for the three detection modes.

- `mode1/UITreeScanner.kt` — traverses the accessibility node tree, matches
  keywords (regex or substring) and domains, classifies `RiskLevel`.
- `mode2/` — OCR engines (`FossOcrEngine`, `MLKitOcrEngine`, selector) plus
  `Mode2Processor`, which captures a clip and extracts hidden text.
- `mode3/` — `NudeNetInferenceEngine` (ONNX Runtime), `NudeNetPreprocessor`,
  `ModelSelector`, and `Mode3Processor` (visual AI).

See [DETECTION.md](DETECTION.md) for details.

### `overlay`
`VeilOverlayService` draws the protective "Veil" over offending content using a
native hardware-accelerated window. `VeilOverlayController` is the lifecycle
 façade used by the accessibility service and foreground service.
`VeilContentRepository` / `VeilPassage` supply the content shown on the veil
(themed, with a Quranic passage).

### `security`
Hardened PIN management and anti-bypass controls.

- `PinManager.kt` — bcrypt (cost 12) PIN hashing, lockout after 3 failed
  attempts (20-minute cooldown), boot-aware lockout timing.
- `PinSecurityStore.kt`, `EncryptedPrefsFactory.kt` — AndroidX Security
  encrypted shared preferences.
- `DeviceAdminController.kt` + `HaramVeilDeviceAdminReceiver` — prevents
  uninstall without the PIN.
- `AppLockdownManager.kt` — enforces per-app lockdown windows.
- `SecurityQuestionsManager.kt` — PIN recovery.

See [SECURITY.md](SECURITY.md) for details.

### `service`
Background lifecycle and resilience.

- `HaramVeilForegroundService.kt` — sticky service, 30s heartbeat, user-unlock
  recovery, Samsung wake-lock.
- `HaramVeilProtectionController.kt` — façade to start/stop protection.
- `BootReceiver.kt` + `ServiceHealthAlarmReceiver.kt` — restart protection after
  boot and on alarm.
- `ProtectionHealthWorker.kt` / `ProtectionResumeWorker.kt` — WorkManager-based
  health and scheduled resume (used by Safe Window).
- `ProtectionBootstrapStore.kt` — direct-boot-aware persistence of service state
  and heartbeats.
- `ProtectionNotificationHelper.kt` — persistent status notification + channels.

### `data`
State and persistence.

- `ProtectionPreferencesRepository.kt` — single source of truth for
  `ProtectionSettings` via DataStore.
- `StatsRepository.kt` — encrypted block history (SQLCipher), `todayCount`,
  `thisWeekCount`, `allTimeCount`, `mostBlockedAppRecord`.
- `BlockEventDao.kt` / `HaramVeilDatabase.kt` — Room + SQLCipher schema, PIN-keyed
  re-keying.
- `models/` — `ProtectionSettings`, `VisualModelOption`, `TextRecognitionEngine`,
  `LockdownModels`, model download/import managers, onboarding models.

### `ui`
Jetpack Compose interface:
- `HaramVeilMainShell.kt` — root `Scaffold` with bottom navigation (Dashboard,
  Stats, Settings) and global PIN gates for sensitive actions.
- `dashboard/`, `settings/`, `stats/`, `security/` — feature screens.
- `theme/HaramVeilTheme.kt` — Material 3 theming.

### `utils`
Cross-cutting helpers: `DetectionBus` (a `SharedFlow` event bus),
`ThrottleManager`, `ScreenCaptureManager`, `DispatcherProvider`,
`DebugLog`, `RootAccessHelper`, `BatteryOptimizationHelper`, `DetectionBus`.

## Data Flow: A Detection Lifecycle

1. The user opens an app. `HaramVeilAccessibilityService` receives a window
   event and submits a throttled scan trigger.
2. `UITreeScanner.scan()` inspects the node tree. If it matches a keyword or
   domain, it publishes a `VeilRequested` event (Mode 1) and a
   `Mode1Triggered` wake event.
3. Even on a *clean* Mode 1 scan, a `Mode1Triggered` wake is still published for
   eligible (browser / high-risk / monitored) apps so the visual pipeline can
   catch purely graphical content.
4. `Mode2Processor` and `Mode3Processor` subscribe to `Mode1Triggered`, capture a
   screen clip, and run OCR / ONNX inference respectively.
5. Any layer that detects a violation publishes a `VeilRequested` event.
6. `VeilOverlayService` renders the veil; `AppLockdownManager` may extend a
   lockdown; `StatsRepository` logs the block to the encrypted database.

## Build Variants

Two product flavors share `ocrFlavour` dimension:

| Flavor | OCR Engine | External Deps | `INCLUDE_MLKIT` |
|--------|-----------|---------------|-----------------|
| `fossOnnx` | RapidOCR (ONNX) | 100% open source | `false` |
| `mlkit` | Google ML Kit | Proprietary binaries | `true` |

See [DEVELOPMENT.md](DEVELOPMENT.md).
