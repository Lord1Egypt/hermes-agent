# Agent Android: test and CI ownership

The owner has explicitly selected the Android app, not upstream desktop parity,
as the product boundary. This PR starts that separation with actual deletions,
not `continue-on-error`, hidden failures or blanket skips.

## Removed

The exact files and recoverable original Git blob identities are in
[the removal ledger](android-app-test-removals.json). The first pass removes
Windows/macOS-only CI, the standalone PowerShell installer tests, Electron
self-update tests and UI lane, Tauri/Rust desktop lane, website validation and
publication, and Nix validation. It removes only dedicated product-only files;
it does not strip Windows-looking branches from shared runtime modules.

## Retained

All Android workflows (including Full/Play, source-bound signing, device and
Perfetto evidence), Java/Kotlin tests, native/Python runtime packaging, report
service tests, F-Droid updater/build checks, supply-chain and review policy remain.
The Linux Python suite continues testing the retained runtime modules, including
agent execution, tools, state, gateway/API, plugins and configuration. The wheel
currently includes `hermes_cli` and `tui_gateway` as shared packages, so those
entire test trees are **not** safe to delete on their names alone. A later runtime
extraction needs its own import and packaged-artifact qualification first.

Desktop Windows/macOS behavior is no longer a supported release prerequisite.
Windows-hosted **Android tooling** is different: the AVD, hardware acceleration,
ADB transport and Android benchmark drivers are retained and validated because
they serve the app.

No branch-protection setting, signing credential, security-scanner result or
existing published release is changed by these deletions. Any required-check
configuration referring to a retired job must be reviewed explicitly, not
silently treated as a successful check. The CI aggregate retains its existing
fail-on-error evaluation over the remaining jobs.

The broad Windows-only Python compatibility lint gate is also removed. Its separate plugin-import-boundary check is retained in an explicitly named Linux job because it protects shared packages embedded in the APK.
