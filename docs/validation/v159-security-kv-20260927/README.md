# Agent v0.13.159 — security review and Gemma KV qualification

## Later 8K follow-up

The historical report below records the earlier 4K acceptance and failed 8K attempts. A later, separately source-bound diagnostic has now accepted **independent F16/F16 and true Turbo3/Turbo3 8K-context runs**, each with 6,478 actual prompt tokens. Their original job logs and final records show the same boot and system-server lifetime; an earlier between-run-restart narrative was corrected, and no causal watchdog fix, product-default change or blanket model-quality guarantee is claimed. See the [8K follow-up report](8k-followup/README.md) and [structured evidence](8k-followup/summary.json). The original failures and source identities below are preserved.

## Earlier result and release boundary

The app-relevant dependency fixes are implemented and validated in the existing Android/F-Droid infrastructure. The real installed terminal reproduced the unsafe old pip filename behavior and verified the patched behavior after an APK upgrade. The actual v159 F-Droid candidate built successfully with scanning and source binding enabled. No vulnerability ignore was added.

**Gemma 4 E2B Q3_K_M passed the explicit Turbo3 K/V 4,096-context test, but 8K is not qualified.** The native engine normally promotes K to Q8_0 for this model when both requested cache types are Turbo3. The explicit symmetric diagnostic passed the factual and long-prompt retrieval checks at 4K, with a modest whole-process memory reduction. The 8K attempts ended in an unfinished prefill timeout and a later Android system-server watchdog abort, respectively. Neither is a quality or memory-safety pass.

This is preparation for **Agent 0.13.159 / code 145990**, not publication or release approval. PR #25 remains unmerged, public v158 is unchanged, and no signed v159 APK or public F-Droid update is claimed. Release-specific evidence, review/signing, and post-publication comparison remain their own gates.

## Exact sources

| Evidence | Source |
| --- | --- |
| Final Full/Play APKs, unit tests, broader security suite and installed upgrade | `d5732582aaf737a303ec7f39605b0ef27f0eace4` |
| Final F-Droid candidate and archive CLI invocation | `72c7257bae8b79a7e90ecbd0f13153710807c62a` |
| Final archive CLI regression fixture | `42fcd2d33a7db739265a8d39520f66966c6d1fd6` |
| Effective-cache/symmetric KV experiment | `86c17dcea1ecc3245ac8f4c0a931dc43f4d51962` |

The delta after `d5732582` is limited to the release workflow's required archive CLI argument and the archive test fixture. It changes no application code or dependency pin. The native experimental engine in the final APK is **byte-identical** to the one used by the KV experiments. The terminal dependency update happened after those experiments; this report does not falsely present their whole-app measurements as a rerun on the later APK. The documentation commit carrying this report is not relabelled as the tested source.

## Security findings that apply to shipped code

### Main Android HTTP stack

The Android SDK/Chaquopy payload contained `httpx2` and `httpcore2` **2.7.0**. Both are updated to **2.13.1**, using independently verified official wheels. This covers five distinct advisories, represented by six package/advisory rows in the initial scan:

| Advisory | Reported issue |
| --- | --- |
| CVE-2026-84378 / GHSA-f2fp-rgf2-35cp | SSE buffering can grow inefficiently under hostile input |
| CVE-2026-84379 / GHSA-h4x7-gw46-3wm6 | Multipart metadata injection |
| CVE-2026-84380 / GHSA-pf96-p4fj-6566 | Ambiguous HTTP framing |
| CVE-2026-84381 / GHSA-7mj9-2mp8-4m2p | WSS over SOCKS TLS handling |
| CVE-2026-84382 / GHSA-8xx6-hgc6-gc2m | Streaming decompression can allocate a large intermediate buffer |

These libraries are actually shipped; exploitation still depends on the transport/API path and hostile input. Presence of an affected dependency is not evidence that every app operation exercises every issue. The real serialization and bounded-decompression regression tests were red on the old pair and passed with the replacement.

The retained genuine Python bundle was refreshed through a checked pure-wheel transaction. The old complete bundle is preserved, native/bootstrap inputs are unchanged, and both ABI offline dependency closures and wheel audits were revalidated before publication of the new local bundle. This was not a manual edit of a receipt to make old bytes look patched.

### Separate native terminal Python prefix

A second inspection found that scanning the SDK's 78 distribution records was **not enough**: the Full edition's terminal prefix carried its own pip and vendored dependencies. The official Termux `python-pip` package is now **26.2.1** for both ARM64 and x86-64, replacing 26.1.2. Its 1,190,440-byte package has SHA-256 `92a9cb4b3c99dd20086f90fedc95c604eefc5bd0f88d1862690884474f82de44`. The official Termux patches were preserved; an upstream wheel was not substituted for the platform package.

The update includes vendored **idna 3.18**, **urllib3 2.7.0**, and **Pygments 2.20.0**, addressing relevant old-version findings. Pip's CVE-2026-13346 requires a malicious package index; it is not automatically exploitable merely by opening the app. The URL parser was exercised without any malicious network request or filesystem overwrite:

| Real terminal runtime | `Link("https://example.invalid/a%252Fb.whl").filename` |
| --- | --- |
| Before APK upgrade: pip 26.1.2 | `a/b.whl` — decoded into a path separator |
| After APK upgrade: pip 26.2.1 | `a%2Fb.whl` — remains one safe filename component |

The same Android 17 AVD was upgraded with `adb install -r`; application data was preserved. The actual native interpreter reported the new pip/idna/urllib3 versions, and four real workspace/settings integration tests passed after the asset-fingerprint migration. Both Full APK ABIs and the F-Droid release artifact were inspected independently for the new prefix. The Play edition ships the patched main SDK but **does not contain the terminal prefix**.

### Findings retained rather than suppressed

The final OSV query covered **93 distinct package/version pairs**, including SDK distributions and terminal pip vendor declarations. Its remaining matches were the fork's own `hermes-agent` identity and pip's `msgpack`/`setuptools` vendor declarations. This is a scoped version query, not a full C/C++/JVM/OS audit or proof that no unknown vulnerability exists.

The reported msgpack crash is in its C extension; this bundled vendor has no `.so`/`.pyd` extension and uses the pure-Python fallback. The setuptools advisories concern `package_index` and `FileList`, which are absent from the bundled `pkg_resources` subset. These module-presence findings are documented in the [terminal package inspection](terminal-pip-inspection.json), not hidden with scanner ignores. They do not declare the entire upstream packages universally safe.

The nine first-party version matches were inspected against current source and retained behavioral tests. Existing controls include WebSocket Host/Origin checks, owner-only secret-file handling, strict project-plugin opt-in, opaque environment-variable values, bounded Feishu webhook reads, and centralized skill/context/memory scanning. The [first-party review](first-party-review.json) states the exact sources, evidence and limits for each. In particular, heuristic prompt-injection defenses and model-generated compression summaries are **not claimed universally secure**. A fork version bump is not proof of upstream patch identity.

Root website/desktop/WhatsApp Node dependency installations are not bundled as installed `node_modules` trees in the APK. The [surface inventory](apk-optional-surface.json) separately records the actual static JavaScript/templates that are present. Optional software installed later and host/website dependencies remain outside this APK-only conclusion. Security checks were retained; the earlier desktop-test removals were not used to conceal Android-relevant CVEs.

## Immutable dependency archive and F-Droid

The package archive was rebuilt from verified historical package bytes plus the exact new pip package. Both the Windows preparation and an independent Linux rebuild produced the same **79,516,344-byte**, **141-package** archive:

```text
SHA-256: a0e59dbbeeafb6e74574a8744a4f76f9daee8cad60fe93c1c6529361ef4cedfb
Future asset: agent-termux-packages-v159.zip
```

Its whole hash, closed inventory, duplicate/missing/extra entries, and every package hash are validated. A single hash-pinned previous release archive seeds unchanged inputs while the new release is unpublished; genuinely new package pins still require their exact official bytes. The archive-generation tests verify deterministic output and reject tampering, rather than silently falling back to unchecked downloads.

The release workflow will build and verify the archive when the new release owns its URL, then include it and its checksum in the draft upload and complete asset manifest. The required CLI `--output-dir` argument is exercised by a real CLI regression. The actual workflow URL-selection code was also executed for v159 and a future v160 reuse case; see [step validation](release-step-validation.json). No archive was uploaded, tag created, or signing gate bypassed during this task.

The existing F-Droid builder executed the private v159 candidate recipe successfully: **one build succeeded, all 63 Gradle tasks executed**. Source scanning, strict binding, the APK DEX source digest, and the genuine Python/native runtime verification all passed. The unsigned candidate is:

```text
Source: 72c7257bae8b79a7e90ecbd0f13153710807c62a
Source digest: 6910595b073118e76d6a9b6b1aef94998f1ce72fd069d069cccfc8c241119074
Bytes: 349497948
APK SHA-256: 857cf0c951386ce1e11df466d94e0eef70bc7eea04ba9ea235e7356e06b13a0e
```

The Full/Play developer APKs retain the repository's tag-less **0.13.146 / 144690** fallback and debug signature. They are not production updates; no installed production copy should be removed to bypass a signing mismatch. The F-Droid artifact above is the separately source-bound **unsigned v159 candidate**.

This is a **warm-cache unpublished candidate**, not a comparison to a public signed v159 APK. The current public updater still sees published v158; no public metadata, signer, or release history was rewritten. The F-Droid [receipt](fdroid-candidate.json) and [source verification](fdroid-source-digest.log) are attached.

## Gemma 4: requested versus effective KV cache

The single tested model is **Gemma 4 E2B-it Q3_K_M**, 2,536,786,016 bytes, SHA-256 `086e2f5ba85057f8f19712e3160a644728f74f323c9feeac4cd73fab11b43085`. Its weight quantization is separate from the runtime cache quantization. Other Gemma 4 sizes/quantizations, physical ARM phones and GPU/NPU inference were not certified.

The pinned experimental engine is TheTom/llama-cpp-turboquant revision `e30664a710b62aaf13c6b12e39e74500e6ce21ef`, with executable SHA-256 `52abba7fdf2b100653c9f79e4fd61cc5018000b6e46954ff0b818eed441678c8`. For this model's 8:1 grouped-query attention, its default quality policy **promotes requested Turbo3 K to Q8_0**, leaving V as Turbo3. A requested setting alone therefore cannot establish the actual allocation type.

A separate, explicitly requested diagnostic set `TURBO_AUTO_ASYMMETRIC=0` **only on that owned test process**, recorded native allocation logs, and required positive evidence of Turbo3 on both K and V. The application default guard, context policy and OS protections remain unchanged.

All three accepted 4K cases used the same model/engine/CPU path, a **3,198-token actual input**, a three-code retrieval check, a factual Paris canary and verified process teardown:

| Actual K / V | Peak native RSS, KiB | Peak PSS, KiB | Long-request elapsed |
| --- | ---: | ---: | ---: |
| F16 / F16 | 2,646,976 | 2,644,030 | 387.7 s |
| Q8_0 / Turbo3, default guarded request | 2,614,664 | 2,611,699 | 446.8 s |
| Turbo3 / Turbo3, explicit diagnostic | 2,608,576 | 2,605,714 | 596.9 s |

True Turbo3 saved **37.5 MiB, about 1.45% of whole-process peak RSS**, versus the single F16 run. Model weights and other allocations dominate at this context. This is not a many-fold reduction in total app RAM, not a throughput win on this CPU, and not a statistical performance benchmark. The three-code result is useful evidence, not a comprehensive language-quality evaluation. See [structured measurements](kv-4k-results.json).

### Why higher context is not approved yet

The guarded 8K attempt had 6,469 tokenized input tokens but hit its read deadline after processing 6,272 (97%). That was unfinished work, not a bad-answer or OOM proof. The follow-up explicit symmetric 8K attempt was interrupted by an **Android system_server watchdog**: instrumentation reported “System has crashed.” The watchdog recorded the main handler blocked for 61 seconds, high CPU pressure and zero memory-pressure averages at that snapshot. Those observations do not prove that cache quantization, RAM exhaustion, or any particular application defect caused the system restart.

The failed case remains failed; the matching 8K F16 reference did not execute, and 16K was not attempted after that failure. No watchdog, memory limiter or quality assertion was disabled to force a pass. The native process was absent after the guest's system-server restart. [Failure boundaries](kv-failed-and-unrun.json) and a [watchdog excerpt](8k-watchdog-excerpt.txt) are retained. Raw failure logs remain private.

The app's ordinary automatic context policy still defaults to 2,048 tokens where applicable. This PR does not silently advertise or enable unqualified 8K/16K operation. The observed 4K direct-engine success is distinct from a tested user-facing high-context configuration.

## Final checks and resource use

| Gate | Result |
| --- | --- |
| Full JVM suite | 1,055 passed, no failures/errors/skips |
| Play-scoped JVM/memory suite | 17 passed, no failures/errors/skips |
| Android/shared-security/CI Python suite | 1,790 passed, zero failures, 28 skips across 89 files |
| Final archive/asset/CLI checks | 29 passed, zero failures across three files |
| Installed upgraded Full APK | Four workspace/settings tests passed; native pip regression verified |
| Full/Play native APK and test-APK builds | Passed; exact bytes inventoried |
| Full/Play lint | Zero errors against unchanged baseline; 58/61 warnings remain |
| F-Droid candidate and 141-package archive | Passed, exact source and hashes verified |
| 4K cache experiment | Three completed cases passed |
| 8K/16K | Not qualified / not run |

The suites overlap and are not summed into a fabricated unique-test total. Source-specific [summary](summary.json), [JUnit ledger](junit-ledger.json), [final Python version scan](final-python-scan.json), [initial repository dispositions](initial-repository-dispositions.json), [installed regression](installed-terminal-regression.json), and [evidence hash ledger](evidence-hashes.json) are attached.

No Docker container, volume, virtual environment, or AVD was created. The existing AVD and private USB-disabled ADB session were retired after the successful upgrade check; the previous scheduled-task action was restored. Models, retained environments, raw failures and canonical evidence remain. No physical phone, shared ADB server, production Devbox service, branch protection, review policy or public v158 asset was modified.

Private-controller errors are not disguised as product fixes: the initial `env` probe treated `=` signs in an APK install path as assignments, the initial archive invocation omitted its required output argument, and a new CLI fixture omitted its lock schema version. Their corrected invocations retained all assertions. The abandoned zero-byte Git status lock was archived only after identity and open-handle verification; the index and Git processes were not killed or overwritten.

## Primary sources

- [HTTPX2 decompression advisory](https://github.com/pydantic/httpx2/security/advisories/GHSA-8xx6-hgc6-gc2m)
- [Pip filename advisory](https://github.com/advisories/GHSA-qwm4-qh6w-59xr) and [upstream fix](https://github.com/pypa/pip/pull/14110)
- [Official Termux package source](https://packages.termux.dev/apt/termux-main/)
- [Pinned native cache implementation](https://github.com/TheTom/llama-cpp-turboquant/blob/e30664a710b62aaf13c6b12e39e74500e6ce21ef/src/llama-kv-cache.cpp)
- [Android 17 memory behavior](https://developer.android.com/about/versions/17/behavior-changes-all)

Native Devbox research and the web search tool were both used. Mutable advisory query dates and exact artifact hashes are recorded separately; a search result count is not treated as evidence quality or vulnerability closure.
