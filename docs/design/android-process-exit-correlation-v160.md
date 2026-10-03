# Agent app v0.13.160 memory diagnostics

The Pixel 8 export shows a normal Gemma start blocked before native loading:
1.03 GB usable RAM versus a 2.11 GB estimate, with bypass requested false.
It cannot explain an earlier overridden attempt because it only retained the
latest startup. The September 27 low-memory exit has importance 400: Agent was
cached in the background and eligible for Android memory reclamation.

Keep confirmation, startup and result events in the existing bounded log,
including bypass authority and process importance. Bind historical exits to
Android process-state markers; otherwise label the saved attempt unverified.
Redact nested runtime details before persistence and preserve numeric counters.
The exact override failure on the physical phone remains unconfirmed.

Development checks are recorded outside the repo. Final release certification
still needs the current Agent app source, package, device and signing gates.
[Android process priority](https://developer.android.com/guide/components/activities/process-lifecycle)

The 64K Limite LiteRT CPU probe exhausted the 12 GB AVD plus swap while Agent had foreground importance (125, oom_score_adj=0); Android killed background apps before killing Agent. Extended 64K LiteRT admission now reserves 16 GB for native buffers and rejects insufficient headroom before initialization. The 64K Nanbeige Turbo3 GGUF probe passed.
