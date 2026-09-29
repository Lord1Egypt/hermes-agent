# Issues #23 and #24 — implementation qualification

Tested source: `022eb5ce5dfba67be09ae25e39c91ba5839aac88`, based on the unchanged public v0.13.158 release. This report is attached by a later documentation-only commit. It does not label this development branch as a new release or retrospectively certify the report commit's source digest.

## Findings and chosen fixes

**#23:** A persisted SAF tree permission is not a POSIX mount. The fix streams the selected documents into an operation-owned staging tree and publishes a unique `shared-<uuid>` workspace snapshot. It handles nested and Unicode paths and empty directories; validates names/cycles; bounds bytes, entries and depth; and preserves original documents and previous local edits. The screen explicitly describes independent copies, not live synchronization. Repeat copy and cancellation are supported. A failed durable grant is no longer reported as successful. Existing proot-distro runs bind only the application workspace to `/workspace`.

The installed integration test exposed another real defect: both shell setup paths exported `PROOT_LOADER` from writable prefix storage. Android rejected execution. The already-packaged loaders were present behind the existing trusted `native-exec/libexec` shims. Both environment builders now select those APK-backed shims. A new unit regression was red before the fix and green afterward; the actual installed proot command now reads the imported document. No new rootfs, downloaded executable, permission relaxation or Android security bypass was introduced.

**#24:** The existing admission check already uses `ActivityManager.MemoryInfo.availMem` minus Android's reserve. Java heap-class values are telemetry, not that admission limit, so the proposed `largeHeap` change is not justified and was not made. A separate reproducible false rejection was addressed by considering smaller supported llama.cpp context estimates before allocating the native runtime, without changing the existing model/reserve coefficients or low-memory/total-RAM/artifact guards. One-shot override tests remain explicit and reject empty models.

Memory diagnostics can now be copied before starting a model. The snapshot distinguishes current system memory from the last launch attempt and records whether a RAM override was requested. Chat error text is selectable/copyable without `/debug`. **The reporter's exact Android 17 / 0.3 GB / override failure has not been reproduced and issue #24 remains open.** This work does not claim that an oversized model can always run or that the new estimate guarantees no OOM.

## Executed checks

| Check | Result |
| --- | --- |
| Full JVM suite on the retained Linux builder | 1,055 passed; no failures/errors/skips |
| Play-scoped JVM plus memory regressions | 15 passed; no failures/errors/skips |
| Canonical Android Python isolated-file suite | 900 passed, zero failures, one skip across 60 files |
| Installed Full APK regression suite on retained API36 x86_64 AVD | 4 passed; no skipped tests |
| Complete native Full and Play APK/test pairs | Passed; no native-assets skip flag |
| Full runtime bundle and Play debug package policy verification | Passed |
| Actual application manifests | API24 minimum/API36 target; no `largeHeap`, test DocumentsProvider or test document permission |
| Lint against existing unchanged baseline | Zero errors; 58 Full / 61 Play warnings remain |
| Existing public F-Droid updater checkout | Passed for public v158; history/Binaries/signer preserved |
| Reused F-Droid candidate build | One build succeeded; all 63 Gradle tasks executed; source scan/binding enabled |
| Candidate APK DEX source digest and genuine runtime payload | Verified |

The installed cases cover real DocumentsUI selection and persisted access, nested copy, native-shell and packaged-proot reads, repeat-copy preservation of edits/originals, diagnostics clipboard export before startup, offline model-picker cancellation and six-language model navigation. The proot test used the existing Android root with the actual packaged binary, not a newly provisioned Linux distribution. Unit tests also verify the generated proot-distro workspace bind. Full guest-distribution coverage, every possible provider, physical phones, and the reporter's Android17 memory behavior are not claimed tested.

The one additional lint suggestion is `UseKtx` for `Uri.parse`, not a correctness error; a pre-existing world-readable warning elsewhere in the sandbox bridge remains. No lint baseline was changed to hide findings.

## Reuse and artifact boundaries

All Docker containers, build/Gradle volumes, source checkouts, Python virtualenvs and the AVD were reused. Existing updater metadata and generated build transforms were preserved before controlled refresh. A missing `sudo` executable was added to the retained buildserver; no new environment was created to bypass that setup failure. Existing compatible toolchain and SDK pins remain unchanged. These are **warm reused-environment results**, not cold-cache or new-release certification.

The real public updater still checks published v158. The private candidate recipe is pinned to the tested bugfix commit, omits public-binary/signer claims and inherits version 0.13.158 / 145890 only for rehearsal. It was not submitted or published and was not compared to the deliberately different public APK. Its unsigned artifact SHA-256 is recorded in the [results](summary.json). The Full/Play developer APKs use the repository's existing tag-less fallback **0.13.146 / 144690** and debug signing. Do not install them as normal production updates or remove an existing installation to bypass a signing mismatch.

The reused AVD session was stopped after testing and its original Windows scheduled-task definition restored. Original app data/model backups and every failed attempt remain local. The private USB-disabled ADB transport was retired; no shared ADB server or physical device was used.

## Evidence and research

[Machine-readable results and artifact hashes](summary.json), [JUnit suite ledger](junit-ledger.json), [installed-test transcript](installed-tests.log), [F-Droid milestones](fdroid-build-milestones.log), [Python milestones](python-milestones.log) and [raw-input hashes](input-hashes.json) are attached. Full build/logcat/failure records remain private; milestone files are explicitly excerpts. The initial provider, assertion-field and CLI-argument mistakes are not counted as successful tests or presented as product fixes.

Research used both the web-search tool and Devbox's native research results, with official sources rather than blindly accepting either suggested workaround:

- [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [Android application manifest: largeHeap](https://developer.android.com/guide/topics/manifest/application-element#largeHeap)
- [ActivityManager.MemoryInfo](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo)
- [Android's writable-app-home execution restriction](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)
- [PRoot bind/rootfs documentation](https://proot-me.github.io/)

The [design note](../../design/android-workspace-memory-23-24.md) details bounds and failure behavior. No release, merge, public metadata submission or automatic issue closure was performed.
