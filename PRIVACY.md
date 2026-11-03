# Privacy Policy

**HaramVeil processes everything on your device. This policy is short because there
is very little to say — by design.**

## What the app does with your data

| Data | Where it goes |
|---|---|
| Screen content (accessibility tree text, screenshots, OCR text, AI detections) | Processed **in memory on your device**, used to decide whether to show the veil. Never stored as images; never sent anywhere. |
| Block statistics (which app, when, which mode) | Stored **locally** in a SQLCipher-encrypted database keyed to your PIN. |
| Your PIN | Stored **locally** as a bcrypt hash. Never leaves the device; we cannot recover it. |
| Acceleration benchmark results (timings per backend) | Stored **locally** to pick the fastest detection backend. Contains no personal data. |
| Settings and blocklists | Stored **locally** in DataStore. |

## Network access

None. Release builds **strip the INTERNET permission** from the merged manifest
(`tools:node="remove"`), so the app is technically incapable of contacting any
server. The optional `mlkit` flavor relies on Google Play Services for OCR model
delivery under Google's own terms; the `fossOnnx` flavor needs no network at all.

## Third parties

The `fossOnnx` flavor contains no analytics, tracking, advertising, crash-reporting,
or API-key services. See [LICENSES.md](LICENSES.md) for the bundled components.

## Permissions

See the permission table in the [README](README.md#-permissions-explained) — each one
is tied to a core protection feature and none is used for data collection.

## Contact

Open an issue on the project repository.
