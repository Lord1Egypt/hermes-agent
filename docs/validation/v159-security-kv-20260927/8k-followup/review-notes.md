# Final follow-up review notes

The original native/JUnit results and durable job logs accept both F16/F16 and genuine Turbo3/Turbo3 at a configured 8,192 context, with 6,478 actual prompt tokens, all three exact retrieval codes, Paris and native-process teardown. These are separate single-run functional checks, not a statistical speed or comprehensive quality benchmark.

The original F16 job log and final Turbo3 job log both report kernel boot ID `3c2ff861-c842-4295-8b7e-bad4321f5ce5`, system_server PID 693, start tick 1648. An earlier progress narrative and working checkpoint incorrectly described a between-run restart and different F16 memory numbers. Those intermediate statements are superseded by the original job outputs and run-bound producer files. The report is generated from those verified files, not the working checkpoint. The old four-worker watchdog abort is separate and remains a failed experiment.

Both accepted runs use the same exact installed application APK, pinned model and native engine; three inference workers; and actual measured native process niceness +5. The relative nice adjustment is 13 because the process inherits Android scheduling priority. The first adjustment5 attempt failed the actual-priority assertion and was cleaned up; it is preserved.

The app's newly packed APK was rejected by the whole-file hash assertion. All4,906 decompressed entries were subsequently hash-compared and matched, but the exact older validated APK remained installed; only the new test APK was installed. Container-byte identity was not claimed.

The completed F16 model/JUnit test passed, but its host validator initially searched the rolling tail for a startup thread-count line which had been evicted. The separate native startup-prefix log retained the positive evidence. The offline validator rechecked the run ID, PID, JUnit, producer success, exact worker count, sampled priority, cache types, context, retrieval, memory and stop proof without repeating inference. The original failed host acceptance record remains beside the corrected acceptance. No producer or quality failure was changed into a pass.

A later busy-host admission rejection and a bounded installed-APK hashing timeout happened before inference. Their failed records remain. Readiness retries did not reduce the40% quiet-admission threshold or bypass hash checks. Other authorized host workloads were not killed.

During the successful Turbo3 run, a temporary pause in progress led to a read-only check of the owned process and app state. The app was reported as instrumentation/foreground service, not frozen; an unprivileged debuggerd attempt returned root-required and was not escalated. No stack trace or causal deadlock diagnosis is claimed. The test subsequently passed and stopped under its existing deadline.

Production context defaults, native automatic K-precision promotion, watchdog and Android memory policy were unchanged. The owned AVD and private ADB session were retired and the original task restored after the terminal evidence was collected. No release, merge, tag, public dependency upload or new execution environment was created.
