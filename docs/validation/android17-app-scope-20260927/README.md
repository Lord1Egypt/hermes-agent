# Android 17 investigation and Android-only test ownership

## Scope and source

This follows the owner's authorization to create one hardware-accelerated Android 17 x86_64 AVD and to delete tests for unsupported desktop products. Docker/F-Droid environments continue to be reused. Tested source: `29f67301cb4ce9e1d3d32d994d939d2d340df7da`; this report is attached in a later evidence-only commit. The existing public v0.13.158 release is unchanged.

## Actual Android 17 results

One Google APIs API37 x86_64 AVD was created, with WHPX confirmed operational in its actual emulator log. Host graphics used the RTX4090 renderer, but the tested llama.cpp model inference ran on CPU. The same AVD was tested at 8 GiB and then reconfigured to 6 GiB; a second AVD or Docker environment was not created.

Model: `gemma-4-E2B-it-Q3_K_M.gguf`, 2,536,786,016 bytes, SHA-256 `086e2f5ba85057f8f19712e3160a644728f74f323c9feeac4cd73fab11b43085`, from the reported model repository at immutable revision `0314792d7f1f7e229411f620751375812bb9faf2`. The reporter has not confirmed that this exact Q3_K_M variant matches their file.

| Configured RAM | App override requested | Actual usable system RAM at admission | Result |
| --- | --- | --- | --- |
| 8 GiB | No | 6,230,020,096 bytes | Start, native canary, **Paris**, Stop passed |
| 8 GiB | Yes | 6,070,951,936 bytes | Start, native canary, **Paris**, Stop passed |
| 6 GiB | No | 4,286,836,736 bytes | Start, native canary, **Paris**, Stop passed |
| 6 GiB | Yes | 4,279,640,064 bytes | Start, native canary, **Paris**, Stop passed |

There were **four real model cases and six instrumentation test executions**: each normal-mode invocation additionally ran the telemetry test. No explicit case was skipped. Each case verified the model hash and used the real native controller and production non-thinking completion payload. The effective context was 2,048 tokens. The managed heap class was only 192 MiB, independently demonstrating that it was not the model's admission budget on this image. No `largeHeap`, permission widening, model-runtime change or OS-memory-limit bypass was added for this investigation.

**The reported 0.3 GB / ineffective-override failure was not reproduced.** All four cases had enough system headroom and returned an `ok` preflight. The override-requested runs verify the request flag and successful execution, not a bypass of an otherwise blocked low-memory case. The existing low-headroom unit test remains separate from these real-device measurements.

Android's `am memory-limiter status` reported **disabled by this system image's default** throughout. We did not switch it off. Consequently these runs do not certify enforcement on a device with Android 17's memory limiter enabled. The probe records `ApplicationExitInfo`; no `MemoryLimiter:AnonSwap` records appeared in the collected exits. No conclusion about an ARM phone/OEM build or the reporter's exact settings follows from that absence.

## App-focused test deletion

Deleted **10 desktop/site/Nix workflow files and 29 dedicated test files**, rather than marking them `continue-on-error` or suppressing their failures. The exact paths, reasons and original recoverable Git blob identities are in the [removal ledger](../../design/android-app-test-removals.json). The retained workflow dependency graph and each deletion's original blob were independently checked.

Retired surfaces include Windows/macOS host compatibility, the standalone PowerShell installer, Electron self-updates/E2E, Tauri/Rust desktop checks, upstream website publishing/tests and Nix checks. The broad Windows-footguns lint lane was removed, while its shared-runtime import-boundary check remains a separate Linux job.

The shared agent, state/database, tools, providers, Linux gateway/API and embedded Python tests remain. `hermes_cli` and `tui_gateway` packages still ship inside the app runtime, so their entire test trees were not blindly deleted. Android Full/Play/JVM/instrumentation, native/Chaquopy packaging, signing/source integrity, security, report-service, Perfetto and F-Droid checks remain. Windows-hosted Android build/AVD tooling is also retained because it serves the app. No branch-protection settings were modified.

## Validation and reuse

| Exact-source check | Result |
| --- | --- |
| Full JVM suite | 1,055 passed; zero failures/errors/skips |
| Play-scoped JVM and memory tests | 17 passed; zero failures/errors/skips |
| Scoped Android/runtime/CI-policy Python suite | 1,104 passed; zero failures; six skips across 72 files |
| Real Android17 instrumentation | Six test executions; four real model cases; all passed |
| Full / Play lint | Zero errors against unchanged baseline; 58 / 61 warnings remain |
| Reused F-Droid candidate build | Passed; all 63 tasks executed; source scanning/binding and runtime checks passed |

The [per-suite JUnit count and XML-hash ledger](junit-ledger.json) preserves the actual nonzero test counts. These are different scopes, not one aggregate count of unique tests.

The [machine-readable results](summary.json) contain exact Full/Play JUnit counts, lint findings, APK hashes and all four model records. The retained canonical Python run passed **1,104 tests with zero failures and six skips across 72 files**. This is the scoped Android/runtime/CI-policy suite, not a claim that every inherited Python test ran locally.

The existing F-Droid build container/checkout/volumes executed the private candidate recipe successfully: **one build succeeded, all 63 Gradle tasks executed**, with scanning and strict source binding enabled. The unsigned APK's source digest and genuine runtime bundle were verified. This is a warm unpublished candidate check, not a comparison with the intentionally different public signed v158 APK. Its inherited 0.13.158 /145890 recipe version must not be treated as a new published update.

No Docker containers, volumes or Python virtualenvs were created. Existing results and generated F-Droid transformations were retained before refreshing the same checkout. One missing required `sudo` package was repaired in the existing container. The legitimate F-Droid clean task removed live JUnit XML; the collector rejected zero tests, so the exact-source JVM/lint reports were regenerated and copied to retained results instead of inventing counts. No model case or F-Droid build needed repeating for that collection repair.

The new AVD and downloaded model remain available for reuse, but the owned emulator session was shut down after testing and its USB-disabled private ADB transport exited. Shared ADB, physical devices and unrelated workloads were untouched.

## Research and diagnostic limits

Both the web search tool and Devbox's native web evidence were used against primary sources:

- [Android 17 changes for all apps](https://developer.android.com/about/versions/17/behavior-changes-all): app memory limits, `ApplicationExitInfo` marker and the fact that manual limiter commands have no effect on devices not enforcing limits.
- [Official hardware acceleration guidance](https://developer.android.com/studio/run/emulator-acceleration): WHPX and graphics/VM acceleration are distinct.
- [Android system memory telemetry](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo) and [managed largeHeap setting](https://developer.android.com/guide/topics/manifest/application-element#largeHeap).

Earlier probe-only HTTP read-timeout and request-token/thinking mismatches were corrected to the production request/budget, not represented as product fixes. Failed model-transfer attempts were rejected by hash before using the real ADB binary-sync copy. The private controller PATH and typed JSON timestamp issues were fixed without relaxing process ownership, deadlines or data checks. Raw failed logs remain local. Successful model JSON records are copied byte-for-byte. Host-side telemetry copies and review transcripts normalize line endings (and transcript trailing whitespace), with raw and review hashes retained. Original capture files remain local.

Issue #24 remains open pending reporter diagnostics, exact model filename and device-specific investigation. No merge or release is performed by this PR update, and current hosted CI must be assessed separately from local results.
