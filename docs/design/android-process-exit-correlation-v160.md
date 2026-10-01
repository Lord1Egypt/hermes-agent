# Next-release process-exit diagnostics

The supplied Pixel 8 report was generated on 2026-09-30 by Full v0.13.159,
version code 145990, source digest
`28f08ed64fea27dbfdf233f3600272ebafed842fa5bc47e58284f149a546d2ce`.
Its latest Gemma E2B IQ3_XXS attempt was blocked at memory preflight:
1,032,687,616 bytes usable system RAM versus a conservative additional-memory
estimate of 2,105,645,528 bytes for the 2,372,993,120-byte model. The report
explicitly says RAM bypass was not requested. This is system memory, not the
268,435,456-byte Java heap category.

The saved Android low-memory exit occurred on September 27. The saved model
attempt was already blocked about 26 minutes earlier. Neither that timing nor
the last sampled 176,560 KiB RSS establishes that the model caused the exit.
Android documents RSS/PSS as the last sample, not exact memory at death.
The old report lacks an OS-bound attempt marker, so its model association
cannot be verified retrospectively.

## Selected change

At the start of each local-model attempt, write a small non-secret attempt
marker through `ActivityManager.setProcessStateSummary`. Android retains that
marker in the exited process's `ApplicationExitInfo`. It contains only a
schema prefix and the random attempt UUID, stays below Android's 128-byte
limit, and contains no model path, prompt, credentials or device identifier.
Failure or throttling of this diagnostic API must not block model admission.

Historical capture takes a snapshot before launching its background worker.
It attaches an attempt as `local_model_runtime` only when the OS marker
matches that attempt and the attempt's timestamps do not follow the exit.
An unverified saved attempt remains available separately as
`last_saved_runtime_attempt`, with explicit correlation status. A matching
blocked attempt is described as blocked before native allocation; matching
identities establish association, not causation. Missing, malformed or
mismatched markers never become an inferred match.

The numeric-redaction defect visible in the old saved event was fixed in
v0.13.159. A persistence/export regression protects numeric counters while
retaining secret, email and phone redaction. Already-redacted legacy values
remain untouched because their original numbers cannot be recovered.
Historical-exit payloads now pass through the structured redactor before
writing the crash record as well as before appending/exporting events. The
regression exposed a raw nested-runtime detail in the saved crash file and
now checks all three surfaces.

## Release scope and remaining evidence

This is development for planned v0.13.160. It does not establish a new
release-ready freeze or a solution to the exact physical-device Gemma
compatibility report. RAM coefficients, manifest heap flags and the existing
per-attempt bypass policy remain governed by their current contracts.

Before publication, freeze the final source and version, then execute the
canonical isolated Python gate, Full/Play build and unit/instrumentation
gates, required headed AVD/model/language/performance evidence and trace
round trip, hosted signing/public-asset checks, and post-publication
F-Droid updater and public-APK reproduction gates. Reuse the retained
environments and preserve their receipts under the standing owner policy.
Physical phone checks remain optional; central F-Droid pickup and Google
approval are separate external states.

## Primary sources

- [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo): process-specific summary, PID, epoch exit timestamp and sampled memory limitations.
- [ActivityManager.setProcessStateSummary](https://developer.android.com/reference/android/app/ActivityManager#setProcessStateSummary(byte[])): process-scoped diagnostic state, 128-byte cap and possible throttling.
