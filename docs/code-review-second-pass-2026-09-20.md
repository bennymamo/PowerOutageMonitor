# Second-pass review — 20 September 2026

Scope: the uncommitted combined batch described in `code-review-2026-09-19.md`, its surrounding monitoring, delivery, backup and UI code, and the Samsung outage experiment on 19 September. This report supplements the baseline report; it does not replace or repeat its historical test results.

No Gradle invocation, APK/AAB build, version change, commit, push, release or ADB installation was performed during this review. Corrections and tests are in the working tree. They have **not** passed compilation or runtime testing yet.

## Findings and corrections, by severity

### P1 — Sampled EcoFlow outage evidence was rejected asymmetrically

The connected-state bootstrap accepted a request reply followed by live device activity, but an off-grid reply could not establish an off-grid correlation. When the inverter changed state between bounded connections, the dashboard could show code 1 and zero meter power while the decision logic retained an old connected correlation or remained unknown. A connected correlation with zero meter power then failed the evidence qualification check.

Added a bounded-check corroboration path in `PowerOceanGridInspection`, `PowerOceanGridCorrelation` and `PowerOceanPushProbe`. It requires the selected profile's off-grid code, zero meter power received during this request, enough changing non-retained live power reports, and a live report newer than both observations. Receipt freshness is checked. Packet order does not matter. A later actual connected-state push wins. Previously established return evidence is preserved so zero net flow after return does not create another outage while the inverter's code still lags.

This is corroborated inference, not proof that every cloud reply contains a fresh measurement. A silent change in the upstream protocol or cached fields remains a device-validation concern. The calibrated mapping remains connected=0, off-grid=1.

### P1 — Compilation defects in the batch

`saveConfirmed` was declared outside the duration editor that referenced it. The password-field signature also put optional arguments after its callback, breaking trailing-lambda call sites. Moved the state into its owning composable and restored callback-last compatibility. Checked changed notice constructors and password-field references, and updated the remote-controller test callback after extending its generation argument. Compilation still requires the deferred build gate.

### P1 — Restore isolation and generation fencing were incomplete

Added a process-wide delivery maintenance gate and active-send reservations. Restore refuses before mutation while a provider send is active. Claim, scheduling, materialization and completion check the durable pause/generation boundary; unique WorkManager names include generation. Removed asynchronous cancellation that could race with newly scheduled work. Queue completion compares the full claimed lease before replacing it.

Restore leaves monitoring disabled until its writes complete. Failure keeps monitoring, delivery and remote control paused and explicitly reports that some data may already have changed. Active-state selection and the saved enabled state determine whether resume is effective. Settings-only restores preserve the existing delivery pause.

A further remote-control race was found: capturing generation at dispatch time could bless a pre-restore command with the new generation. The receiver now carries its original generation into main-thread execution. Checkpoint writes are fenced, and the receiver's refresh signature includes configuration generation.

This is coordinated, fenced restore, not a multi-file rollback transaction. In-flight external sends cannot be undone; restore waits for the user to retry once they finish, rather than pretending cancellation retracts them.

### P1 — Backup message ordering did not round-trip

The new ordering field was written as a plain property but read with the optional-property helper, which requires an additional presence property. Corrected the decoder and retained legacy inference for old outage-update IDs. Encoding now enforces the same nine record-list limits and byte/field validation as decoding; temporary encoded bytes are cleared on failure. The maximum is 10,000 records per list, still subject to the overall 2 MiB payload limit. Export fails explicitly rather than discarding pending alerts.

### P1/P2 — Battery protection and monitoring deadlines

The scheduled-only monitoring path now saves changed battery observations, evaluates one-per-outage battery-low messages, and applies audible cutoff. Cached signal receipt times no longer serve as the current time for state deadlines. Timing edits reconcile immediately. The service maintains charger-verification fallback deadlines through both Handler and AlarmManager, independently of periodic provider emissions.

### P2 — EcoFlow idle lifecycle, retry and lock handling

Removed account-provider lifetime CPU/Wi-Fi locks. Active checks use bounded locks and scheduled wakeups; a short service handoff lock covers receiver-to-worker dispatch. Idle waiting now uses deadlines and a conflated change channel instead of waking every second. Charger/settings/manual changes wake that wait. Alarm delivery no longer impersonates a manual request and bypasses retry policy. Permanent account failures remain visible with automatic retries blocked and local charger watching intact. Running-state checks allow replacement of a stopped provider; unexpected coroutine failures publish unknown status and schedule recovery.

OEM alarm deferral remains possible, especially without exact-alarm permission and during Doze. Verify the actual latency and battery behavior on Samsung.

### P2 — Incident ordering and obsolete updates

Separated deduplication identity from incident ordering, including migration of legacy outage-update IDs. Added deterministic ordering within equal-ranked messages. Restoration suppresses unsent obsolete outage updates; an already in-flight update completes before restoration can proceed. Completion and failed-item retry apply the same suppression policy. Outage, battery-low and restoration messages remain linked to their incident and destination.

External delivery remains at-least-once across a crash after provider acceptance and before local completion. It is not an exactly-once transport.

### P2 — Scheduled-alert crash consistency and pending overflow

Scheduled messages use stable due-time IDs and are durably enqueued before timer advancement. A retry after a crash can reuse the same identity. Pending retention coalesces replaceable status messages, preserves essential transitions, and records a visible overflow warning when replaceable entries are omitted. The essential-event limit is intentionally soft; a long backlog can exceed it and eventually hit backup size limits.

### P2 — Secrets, draft lifetime and restore choices

Added shared, reference-counted `FLAG_SECURE` handling for revealed passwords and the decrypted editor, preserving an existing secure-window flag. The editor uses private-input interception as well as password fields. Drafts and wizard steps use in-memory ViewModels across activity recreation; no secret is placed in a saved-state bundle. Backup file operations use the ViewModel scope. Process-death callbacks explicitly require password re-entry and attempt to remove newly created failed files.

Automatic-backup passwords are omitted from new portable captures, with explanatory UI text. Restore enforces the effective resume choice rather than a hidden stale switch value. In-memory Strings cannot be reliably wiped; drafts live for their ViewModel lifetime. Keyboard privacy flags are requests to the IME, not protection against a malicious keyboard.

### P2/P3 — Backup finalization and save feedback

Automatic backups are prepared before creating a destination document and written with a `.partial` suffix. Only a successfully closed write is renamed to the completed archive name, so a crash cannot make an unfinished file count toward retention. Failed writes/finalization attempt cleanup. Retention excludes empty/unknown-size candidates. Document providers must support rename; unsupported finalization is reported as failure, not success. Old partial files from earlier versions cannot be authenticated by filename/size alone.

Save feedback is colocated, has a polite accessibility live region, and requests visibility after layout. Unchanged saves are disabled. Editing presets, units or values clears stale saved feedback. Small-screen, keyboard-open and TalkBack behavior remains a runtime check.

## Samsung evidence captured

The installed 1.1.6 build was not replaced. During the roughly 20:14–20:20 test, the Samsung's charger was already unplugged. The 20:14:57 and subsequent outage checks displayed grid code 1, meter power 0, and changing live power reports. Telegram reported uncertainty beginning at 20:15 and issued its five-minute unknown warning at 20:20.

After mains was restored, the 20:21 check displayed approximately -3,308 W with code still 1, followed by grid-readable/recovered messages. The user confirmed that code subsequently settles to 0 during inverter reconnection. The app reported 6 minutes 7 seconds of unknown status and never produced a confirmed outage/restoration pair for this test.

Repeated phone HTTP 204 checks and Android network validation were captured in `device-tests/samsung-connectivity-2026-09-19.jsonl`. These establish sampled phone connectivity, not uninterrupted connectivity or direct internet reachability of the inverter. Live EcoFlow power reports establish communication at those receipt times. The older 15:28–15:35 incident does not have enough evidence to assert an identical cause.

Added bounded diagnostics for charger changes, phone validation/transport, check receipt and completion, numeric grid/meter observations and their provenance, and alert attempts/results. They omit credentials, destination addresses and SSIDs. These app-side diagnostics take effect only after the corrected build is installed. Screenshots remain local under `.review-evidence`; do not include private attachments in a release.

## Tests and checks

Added or extended tests for sampled EcoFlow outage/return, retained/stale/unsupported or insufficient evidence, meter-before-code ordering, fresh connected overrides, alert sequencing and suppression, legacy ordering, backup count/byte symmetry, stable scheduled IDs, pending coalescing/overflow, the maintenance gate, password reveal protection, and stable-outage battery decline for account and Modbus sources. Updated the remote client fixture signature.

The tests are meaningful regression coverage but are not complete integration coverage. In particular, the gate tests do not exercise a full WorkManager-plus-restore interleaving, the battery test targets the coordinator shortcut rather than the entire service, and the sampled outage test is a synthetic reconstruction from dashboard evidence, not a replay of raw MQTT packets. Do not interpret them as physical-device proof.

Performed: `git diff --check` (passed), Android manifest XML parse (passed), connectivity logger Python syntax parse (passed), static call-site and code-path review. No new unit-test counts or lint results are claimed.

Run afterward, from the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:compileDebugAndroidTestKotlin
```

On a dedicated test device/emulator, when installation is authorized:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

The instrumentation suite can exercise alarms and settings; do not run it against the active household monitor without preparing an isolated test setup. The normal release build/signing procedure follows successful validation and separate release authorization.

## Remaining verification and readiness

- Repeat grid loss/return on the corrected Samsung build: fresh charger disconnect and charger already disconnected; also a charger protected by EcoFlow. Confirm initial outage timing, restored timing, and message order.
- Verify code 1/zero flow with live reports, code 1/nonzero flow during reconnect, eventual code 0, steady loads, retained replies, internet loss/recovery and EcoFlow-only connectivity failure.
- Check screen-off/Doze, Wi-Fi roaming, pause/resume, manual-only, reboot before first unlock, retry/backoff and timed lock release.
- Exercise restore with queued/leased/sending work, an old remote poll/command, activity recreation and forced partial failure; verify disabled state and successful retry/resume.
- Exercise document providers, failed writes/rename, rotation while a picker is open, process-death password re-entry, multiple revealed fields, keyboard-open small screens and TalkBack feedback.
- Existing JSON queue storage still rewrites full lists, and its existing corruption fallback can return an empty list. This review did not redesign corruption recovery or convert restore/storage to transactional databases. Treat storage corruption and extreme queue growth as unresolved hardening risks.

**Ready for the combined build/test gate, with the limitations above. Not approved for release or relied-on outage monitoring until compilation, tests and the corrected-build physical outage experiment succeed.**

## Validation and release decision — 20 September 2026

The combined automated gate subsequently passed:

- `:app:testDebugUnitTest`: 285 tests passed with no failures, errors or skips.
- `:app:lintDebug`: completed with no errors. Non-blocking warnings remain documented in the lint report.
- `:app:compileDebugAndroidTestKotlin`: passed.
- Full Android instrumentation: 30/30 tests passed on Android 16/API 36 and 30/30 passed on the minimum supported Android 6/API 23.
- Signed release assembly and signature verification are part of the 1.1.7 release procedure.

One instrumentation test initially failed because its setup did not establish the configured powered-failure threshold before expecting a routine warning. The fixture was corrected to model the intended precondition; the affected test and both complete instrumentation runs then passed.

The owner accepted release and installation of 1.1.7 while deferring the corrected-build physical grid-loss test. Automated tests reconstruct the observed code-1, zero-meter and changing-live-report sequence, but they cannot prove the real inverter, phone networking or Samsung background behavior during an actual outage. PowerOcean-assisted detection therefore still requires a future controlled grid-loss and restoration test before it should be relied on.
