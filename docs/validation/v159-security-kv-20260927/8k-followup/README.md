# Gemma 4 E2B: independently verified 8K-context follow-up

## Outcome and scope

**Both F16/F16 and explicitly symmetric Turbo3/Turbo3 completed the 8,192-context diagnostic with 6,478 actual prompt tokens, all three reference codes, a correct Paris canary, recorded native memory, and verified native-process teardown.** These are two single-run functional results for one pinned model, not a replicated performance or comprehensive language-quality benchmark.

**Correction to an earlier progress update:** the original job logs and final records show the **same** kernel boot ID and system-server PID/start time before and after both accepted runs. The earlier assertion of a restart between these two accepted runs was unsupported and is withdrawn. These remain independently executed, single-run functional checks with non-exclusive host load, not a controlled or replicated performance benchmark. The older four-worker watchdog failure is preserved and is **not claimed causally fixed** by this follow-up.

No application defaults were changed: the usual 2,048-token automatic context policy and the engine's native K-precision quality guard remain in place. The diagnostic used three inference workers and measured native main-process niceness +5. Its relative `nice -n 13` adjustment is recorded explicitly; the number 13 must not be mistaken for the measured absolute nice level.

## Measured results

| Effective K / V | Actual prompt tokens | Peak native RSS | Peak native PSS | Observed long-request time | Accepted |
| --- | ---: | ---: | ---: | ---: | --- |
| F16 / F16 | 6,478 | 2,609.38 MiB | 2,606.56 MiB | 978.8 s | Yes |
| Turbo3 / Turbo3 | 6,478 | 2,552.94 MiB | 2,550.08 MiB | 1,903.9 s | Yes |

The observed peak-RSS difference is **56.44 MiB (2.16%)**. This is native-engine RSS, not total application, device or Java-heap usage. The measurements were taken in separate executions with non-exclusive host load; they are not a causal estimate of an application's guaranteed memory or speed improvement.

The native allocation logs separately report **57.00 MiB of F16 KV tensors versus 11.14 MiB of Turbo3 KV tensors** (rounded logger output, deduplicating the model-fitting dry run). That is the relevant cache-storage reduction, which is much larger proportionally than the reduction in the process containing model weights and other buffers.

The pinned implementation's active Turbo3 block stores 128 values using a two-byte norm, 32 bytes of two-bit indices, and 16 bytes of additional bits: **50 bytes, or 3.125 stored bits per value**. Relative to 256 bytes of F16 values, that is **5.12× KV-tensor storage compression**, not 5.12× compression of the model or process. Some adjacent historical source comments still describe an older layout; the active macro, fields and static assertion are the evidence.

Both inputs contained 6,469 tokenizer tokens before chat-template overhead and 6,478 actual prompt tokens reported by the engine. The test exercised roughly 79% of the configured window, not a full 8,192-token user prompt. The three codes were placed at separated positions in the reference text and all were returned exactly. 16K and other Gemma model sizes/quantizations were not tested here.

## Exact experiment identity

- Model: Gemma 4 E2B-it Q3_K_M, 2,536,786,016 bytes, SHA-256 `086e2f5ba85057f8f19712e3160a644728f74f323c9feeac4cd73fab11b43085`.
- Native engine: TheTom/llama-cpp-turboquant `e30664a710b62aaf13c6b12e39e74500e6ce21ef`, executable SHA-256 `52abba7fdf2b100653c9f79e4fd61cc5018000b6e46954ff0b818eed441678c8`.
- Installed Full debug APK: SHA-256 `0cecf60272157aca1e8bf038828f734790d16f9d82869a8773db768a66c16005`, previously qualified application source `d5732582aaf737a303ec7f39605b0ef27f0eace4`.
- New instrumentation source: `ca9b2eb05c936c880bda0b3d3884f92f7d6b49ce`. Its only source change is the explicit research test; it does not change production model loading, context or security policy.
- Device: existing Android 17/API37 x86_64 hardware-accelerated AVD, 6 GiB RAM and four virtual CPUs; three inference workers for both runs. No physical ARM phone, GPU/NPU inference or new execution environment was used.

A rebuilt APK had different container bytes, so it was **not** substituted for the installed application. All 4,906 decompressed ZIP entries were independently SHA-256 compared and matched, but that is not byte-identical APK evidence. The exact previously verified APK remained installed; only the new instrumentation package was installed. See [payload equivalence](payload-equivalence.json).

The pinned native engine normally upgrades requested Turbo3 K to Q8_0 for this model's high GQA ratio. The explicit symmetric diagnostic disabled that promotion only in its own child process and positively checked the resulting native K and V allocation types. The product's default guard was not disabled.

## Evidence validation and failures retained

Every accepted case requires the original JUnit success, producer success, unique probe ID, positive native context/cache evidence, actual three-worker startup line, measured niceness, nonzero RSS/PSS samples, all exact retrieval codes, the factual canary, and native stop confirmation. Native progress is checkpointed every five seconds so an abort does not erase the partial evidence.

The completed F16 case initially failed the **host evidence parser**, not JUnit or retrieval: the parser looked for the startup thread-count line in a rolling log tail, from which the header had been evicted. The same run's separate startup-prefix log retained that positive line. The offline validator rechecked the producer, JUnit, matching native PID/run ID, exact startup thread count and sampled priority; the original failed host result remains beside the accepted record. No assertion was replaced by a default value or waived.

Other retained setup failures include the whole-APK container-hash mismatch, an initially incorrect assumption about relative niceness, a busy-host admission rejection, and an installed-APK hashing timeout before inference. The initial interpretation that the accepted runs spanned a restart was checked against their original job logs and withdrawn: both accepted runs report the same identity tuple. The misleading older private filename `separate-boot-results.json` is a harness label, not evidence of a restart. These setup records are not relabelled as model quality failures or proof of an OOM. Earlier four-worker watchdog/transport failures remain in the [original report](../README.md).

The host was not exclusive: sampled peak host CPU was 48.1% during F16 and 41.7% during Turbo3. Other authorized user workloads were not killed to improve the numbers. Request times above are observations, not an isolated speed comparison.

## CI, security and release boundary

At the preceding evidence head `6b0162b08e99a0dbcaf467c72523dd9f69531cf7`, hosted Android validation completed successfully, including Full/Play builds and runtime/report-service checks. The full hosted Python job completed **46,513 passed, zero failed and 463 skipped in 3,775 files**. Those hosted scopes overlap the earlier local checks and must not be summed into a unique total. [Recorded checks](hosted-checks-final-before-push.json) and [exact Python summary](hosted-python-summary.log) identify the source-specific results.

The maintainer review-label gate and its dependent aggregate remained red; no review label, branch protection or security suppression was added. A later report/instrumentation push has its own CI status and is not automatically certified by an older successful run.

The previously verified HTTP stack, separate Termux pip upgrade, immutable dependency archive and actual unsigned F-Droid v159 candidate are unchanged; see the [security and release report](../README.md). This follow-up is not release publication, an installed signed-v159 upgrade, blanket prompt-injection safety, or a claim that the reporter-specific 0.3 GB issue is resolved.

The owned AVD was retired after the run, the private USB-disabled ADB supervisor stopped successfully, and the previous scheduled-task action was restored. Models, AVD files and existing Docker/WSL environments were retained. [Retirement receipt](session-retired.json).

## Evidence files

[Structured summary](summary.json), [F16 case](f16/case.json), [Turbo3 case](turbo3/case.json), and the [raw-artifact hash manifest](raw-artifact-manifest.json) carry the exact run and source identities. Each case directory retains JUnit output, host observations, accepted results and a native startup excerpt. Published text-log copies normalize line endings and trailing whitespace only; the raw-artifact manifest hashes the unchanged original files. Full native logs and APKs remain in the original Devbox results folder. Archived operator scripts are evidence of the executed procedure and require those original paths and retained inputs; they are not an installed app component.

Primary implementation: [pinned cache logic](https://github.com/TheTom/llama-cpp-turboquant/blob/e30664a710b62aaf13c6b12e39e74500e6ce21ef/src/llama-kv-cache.cpp) and [active Turbo3 storage layout](https://github.com/TheTom/llama-cpp-turboquant/blob/e30664a710b62aaf13c6b12e39e74500e6ce21ef/ggml/src/ggml-common.h).
