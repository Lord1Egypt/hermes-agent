# Shared-folder access and model-memory diagnostics (#23 / #24)

## Findings and selected fixes

Issue [#23](https://github.com/adybag14-cyber/hermes-agent/issues/23) correctly identifies a missing bridge: saving a Storage Access Framework (SAF) tree permission does not populate the application workspace. The app already has provider-level file tools, but a POSIX terminal cannot use a `content://` URI as a directory. This change chooses an independent, bounded workspace copy rather than a FUSE mount, fabricated filesystem path, broader storage permission, or implicit two-way synchronization.

After the user selects a folder, Agent retains its read permission, recursively copies readable provider documents into a private staging directory, and publishes the complete tree as a new `shared-<uuid>` directory in `files/hermes-home/workspace`. Repeating the copy creates another snapshot and never overwrites terminal edits or provider originals. Empty folders and Unicode names are preserved. Failed queries are not silently interpreted as empty directories. Unknown file sizes are bounded by the bytes actually read. Virtual documents requiring format conversion fail explicitly.

Each request is limited to 512 MiB, 10,000 entries and 32 directory levels. Select a subfolder for a larger vault. Interrupted, failed or quota-exceeding copies remove their own staging directory. Cancellation cannot undo a copy already committed, and a provider which ignores thread interruption may remain blocked until it returns. A process kill can leave an unpublished staging directory; this implementation does not claim crash-time garbage collection or a transactional snapshot of a remotely changing provider. A cancelled request cannot overlap its replacement's copy within the same ViewModel.

The workspace has a real path for the app's native shell. The existing proot command builder additionally binds only that app-owned workspace to `/workspace` and exports `HERMES_WORKSPACE=/workspace`. This is a userspace proot bind of copied files, **not** a mount of the original content URI. Existing Full-edition restrictions, sandbox admission and tool consent remain. Clearing the folder grant does not delete already imported files or release unrelated grants. Users see both native and guest paths, copy/cancel actions, limits and the no-sync explanation in all six supported languages.

Issue [#24](https://github.com/adybag14-cyber/hermes-agent/issues/24) reports 0.3 GB available RAM and an ineffective override. The existing admission implementation already uses `ActivityManager.MemoryInfo.availMem - threshold`. Java `memoryClass` and `largeMemoryClass` are diagnostic fields, not the model budget. Consequently `android:largeHeap` is **not** added: it changes the managed-heap category and does not establish native-model headroom or a remedy for this report. Neither Android's newer memory-budget API nor a more permissive manifest is used to manufacture additional RAM.

A separate false-rejection case is reproducible: a 4.2 GB GGUF model on a 6 GB device with 4.0 GB usable headroom fails the unchanged 4,096-token estimate, but fits the existing 2,048-token estimate. The fix considers smaller supported context bands before allocating native resources, using the existing conservative model/context coefficients. It does not weaken active low-memory, total-RAM, empty-file, source-integrity or native-startup checks. It changes only llama.cpp admission, not LiteRT-LM. A low-headroom test also proves that the already-existing explicit RAM bypass accepts an individually authorized attempt and does not accept an empty model or become a permanent setting.

**The reporter's exact device-specific 0.3 GB reading and override failure are not yet reproduced.** This change provides actionable diagnostics rather than claiming that speculative root cause is solved. Settings → Models → Runtime and performance now offers **Copy memory diagnostics**, without starting a model or asking the agent to execute `/debug`. The JSON distinguishes a fresh system-memory reading from the last startup attempt, its stage, estimate, context and requested RAM-bypass flag. Chat errors and settings status can be selected/copied; Device's existing diagnostic export also includes this record. No provider credentials or raw expert argv are added to this report. Review the last model filename and diagnostic detail before sharing it publicly.

## Primary-source research

Both the native web tool and Devbox's native web research were used. The Devbox run retrieved 28 usable official documents, not its target of 50; the useful primary sources are listed below rather than claiming 50 independent confirmations. Research job: `job-op-850b665fbdf1fb9c3de20a6a025016a988a0c6339701ff8507fc625430c47401`, checked 2026-09-27.

- [Android shared documents and tree access](https://developer.android.com/training/data-storage/shared/documents-files): user-selected URI grants, persisted permissions, stream access, unknown sizes and virtual-document handling.
- [ActivityManager.MemoryInfo](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo): system available memory and low-memory threshold are not the Java heap limit.
- [Application manifest: largeHeap](https://developer.android.com/guide/topics/manifest/application-element#largeHeap): large Dalvik heap request, without a guarantee of more available memory.
- [Managing app memory](https://developer.android.com/topic/performance/memory/manage-app-memory): real system memory pressure still matters for native allocations.

The exact packaged proot-distro 5.4.0 source in the retained build container was also inspected. Its login parser supports custom binds; its default home/prefix bindings do not automatically expose this separate app workspace. The command change uses its existing `--bind` interface, with a quoted absolute app-owned path, rather than creating a new filesystem service.

## Additional proot execution defect found by the installed test

After the real SAF grant, recursive copy and native `cat` succeeded, the packaged
proot command failed with `execve(...): Permission denied`. Both the process
environment and generated shell prelude selected `prefix/libexec/proot/loader`,
a regular executable file in writable app storage. The existing `native-exec`
loader shims instead resolve to package-manager-extracted APK libraries. Both
execution paths now use those existing trusted shims; no Android execute policy,
filesystem permissions, loader binary or signature requirement is relaxed.

The regression test was red on the old environment mapping. The installed test
retains the actual proot command, original-document verification and repeat-copy
checks. Its earlier test-only errors (standalone provider Kotlin dependency,
wrong `stdout` key and long-option syntax) are recorded separately, not treated
as product fixes or passing runs. Android documents the restriction on executing
writable app-home files in [Android 10 behavior changes](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission).

## Validation scope

The original memory regression was red before the estimate fallback and passed afterward. Provider-backed JVM tests exercise real ContentResolver queries and streams, nested copies, preservation of originals/previous edits, null queries, read failures, invalid names, duplicates, byte/entry/depth caps, cycles and cancellation. Diagnostic tests distinguish live memory from a historical attempt and verify that export does not start a backend. Command tests keep legacy calls unchanged and reject content URIs or relative/ambiguous bind paths.

Installed tests use an instrumentation-only, read-only DocumentsProvider through the real Android picker. The provider is absent from production APKs. The integration test exercises the actual packaged proot binary using a retained distro when supplied, otherwise the existing Android root. The latter proves the userspace workspace bind, not every Linux-distribution integration. It does not create another Docker, AVD, Linux guest or other execution environment. Exact executed results and any remaining limits are recorded in the PR after validation; the presence of a test file alone is not a passing run.

## Retained build infrastructure

Per the owner's 2026-09-27 instruction, reuse the existing updater and build containers, source checkout, SDKs and dependency caches. Archive prior results before restoring known generated source transformations. Do not allocate new containers, volumes, virtualenvs or AVDs for each release or retry. Cache reuse is explicitly reported; a warm check is never described as a cold-cache certification. `fdroid/LOCAL_TOOLCHAIN.md` documents this operating mode. The source scanner, exact metadata/source binding, public signer and public-binary comparison policies remain intact.

This is a development fix, not a rewrite of the published v0.13.158 tag or evidence, and does not publish a new release. Physical-phone validation is not claimed.
