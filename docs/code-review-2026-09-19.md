# Code review — 19 September 2026

Review of monitoring, alert delivery, integrations, storage, backup/restore, security boundaries, Compose settings flows and build configuration. This records the baseline findings; the working tree now contains an uncommitted combined fix batch for all findings below.

Baseline validation before the fix batch: a fresh `testDebugUnitTest --rerun` passed **267 tests, 0 failures, 0 errors, 0 skipped**. `lintDebug` succeeded; its report contains **0 errors, 133 warnings and 16 hints**. Per the requested release gate, the combined fix batch has not yet been built or tested. Android instrumentation tests and physical-device flows were not executed. Findings below are based on code-path analysis, not claims that each was reproduced on hardware. This is a broad review, not a guarantee that no other bugs remain.

**Priority:** P1 = fix before relying on the affected feature; P2 = significant correctness, privacy or reliability issue; P3 = smaller usability/maintenance issue.

1. **P1 — EcoFlow monitoring can stop updating battery-dependent protection.**

   Location: [MonitoringService.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/monitoring/MonitoringService.kt), lines 273–286 and 379–412; [MonitoringCoordinator.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/monitoring/MonitoringCoordinator.kt), `process` versus `processScheduledOnly`.

   During a stable confirmed outage, assisted-mode battery broadcasts call `processEcoFlow`. When grid availability has not changed and no confirmation deadline is due, this calls only `processScheduledOnly`. That method neither saves the battery snapshot nor evaluates low-battery alerts or audible cutoff. The Modbus battery path has the same shortcut when its cached signal is fresh. Audible timer callbacks then read the old stored snapshot. A battery crossing the warning/cutoff thresholds can therefore go unnoticed until a full state transition or another full reconciliation occurs.

   Fix: evaluate battery-dependent behavior and persist battery changes independently of whether the grid-state machine needs advancing. Test a stable outage with battery readings dropping through both thresholds for each source.

2. **P1 — A terminated EcoFlow worker remains installed as though it were alive.**

   Location: [PowerOceanAccountPowerSignalProvider.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/integrations/power/ecoflow/PowerOceanAccountPowerSignalProvider.kt), lines 135, 146, 157 and 225; [MonitoringService.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/monitoring/MonitoringService.kt), lines 207–213.

   Non-retryable login/certification failures and MQTT subscription rejection exit the worker. The service still holds a non-null provider, and ordinary reconciliation checks only for null. It therefore does not restart that worker. Its periodic signal emission also stops; charger-first verification timeout/fallback then depends on unrelated later battery broadcasts instead of a reliable timer. The monitoring switch can remain on with the account monitor dead.

   Fix: explicitly report provider termination, expose running state, and maintain the charger fail-safe independently. Permanent account errors should disable account retries with a visible error while local watching remains dependable.

3. **P1 — Restore does not isolate the delivery workers from the data being replaced.**

   Location: [BackupManager.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/configuration/BackupManager.kt), lines 105–129; [AlertDeliveryScheduler.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/integrations/alerts/AlertDeliveryScheduler.kt), line 30; [AlertDeliveryWorker.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/integrations/alerts/AlertDeliveryWorker.kt), `doWork`.

   Turning monitoring off leaves delivery workers active. Restore replaces credentials before requesting cancellation, requests cancellation only when ACTIVE_STATE is selected, does not await cancellation, and sets the restored-delivery pause flag after replacing the queue. Workers never check that pause flag or a restore generation before sending/replacing results. An old worker can send during restore, send an old queued message using newly restored credentials, or overwrite a restored item with an old completion result. Network work already started cannot be undone by cancelling WorkManager alone.

   Fix: introduce a delivery maintenance gate before any restore mutation, await cancellation/quiescence where possible, and fence claims/completions with a generation. Explain any already-in-flight delivery separately.

4. **P1 — The app can successfully export a backup it refuses to restore.**

   Location: [BackupDocument.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/configuration/BackupDocument.kt), lines 99–117 and 615–618; [AlertQueueStore.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/storage/AlertQueueStore.kt), lines 91–99.

   Encoding has no list-count validation, but decoding rejects every list exceeding 1,000 entries. The delivery store retains all unfinished items, so a long offline period with frequent heartbeats and/or multiple destinations can exceed that limit. A compact archive with 1,001 queue entries can remain below the 2 MB byte limit, be encrypted and reported as saved successfully, then fail when unlocked. Recipient lists have the same encode/decode asymmetry.

   Fix: make export and import constraints consistent and validate the captured document before reporting success. Do not silently discard pending outage deliveries to meet the limit. Add round-trip tests at and beyond each supported boundary.

5. **P2 — Changing detection delays does not reschedule an existing confirmation.**

   Location: [MainActivity.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/MainActivity.kt), lines 270–284.

   `updateSettings` writes the new delays and refreshes UI state, but does not notify the service or reschedule its Handler/AlarmManager deadline. If a pending outage has a long delay and the user reduces it, including to zero, confirmation can still wait for the original deadline or an unrelated power/battery observation. The displayed configuration and actual next wake-up disagree. Restoration timing has the same issue.

   Fix: reconcile the current observation and both deadline mechanisms immediately after timing changes.

6. **P2 — Long-outage updates bypass the delivery ordering rules.**

   Location: [ScheduledAlertMessageFactory.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/integrations/alerts/ScheduledAlertMessageFactory.kt), line 61; [AlertQueueEngine.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/integrations/alerts/AlertQueueEngine.kt), lines 40–48.

   Each update gets a distinct `outage-update-<start>-<time>` event ID. Ordering only compares messages with identical event IDs, so the OUTAGE_UPDATE ordering branch does not connect these updates to their outage/restoration messages. After connectivity returns, an update saying “POWER OUTAGE STILL ACTIVE” can arrive before the initial outage or after restoration.

   Fix: separate a message's deduplication ID from its parent incident ID. Define whether superseded status updates should be skipped after restoration.

7. **P2 — EcoFlow keeps CPU and high-performance Wi-Fi locks even while paused or idle.**

   Location: [MonitoringService.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/monitoring/MonitoringService.kt), lines 249–253 and 428–453; [PowerOceanAccountPowerSignalProvider.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/integrations/power/ecoflow/PowerOceanAccountPowerSignalProvider.kt), idle loop.

   Locks are acquired for the lifetime of the selected account provider, including hour-long gaps, manual-only operation and paused assistance. They are released only on source reload/suspension/destruction; reloading the same paused source acquires them again. The idle loop also runs every second. This prevents CPU sleep unnecessarily and reduces the monitor's battery endurance during an outage. Lint flags the unbounded CPU wake lock too.

   Fix: own bounded locks around active checks and use scheduled wake-ups between checks. Retain a short bounded lock for charger confirmation where needed; do not simply remove locks without replacing the timing guarantee.

8. **P2 — The advanced backup editor exposes all decrypted credentials through an ordinary text surface.**

   Location: [DataBackupSettingsScreen.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/ui/DataBackupSettingsScreen.kt), lines 459–483; [PrivatePasswordField.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/ui/PrivatePasswordField.kt).

   The editor includes tokens/passwords in an ordinary editable field, with none of the private-input protections used by `PrivatePasswordField`. There is also no `FLAG_SECURE` use anywhere in the app. Consequently the decrypted content is available to ordinary screenshot/screen-sharing paths, and keyboard privacy restrictions are not requested. The warning text does not change those behaviors. This is an exposure risk, not evidence that a particular keyboard uploaded data.

   Fix: secure this screen while decrypted data is visible, disable personalized learning/suggestions, and preferably redact secrets unless a specific field is deliberately revealed. Android documents the screen-capture protection in [Secure sensitive activities](https://developer.android.com/security/fraud-prevention/activities).

9. **P2 — A hidden restore option can still enable Telegram remote control.**

   Location: [DataBackupSettingsScreen.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/ui/DataBackupSettingsScreen.kt), lines 417–448; [BackupManager.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/configuration/BackupManager.kt), lines 197–200.

   Reproduction: unlock a backup with active monitoring and enabled remote control; turn on “Resume monitoring”; deselect ACTIVE_STATE while leaving ALERTS selected. The resume switch disappears but its Boolean stays true. Restore passes it through and enables the saved Telegram remote receiver, while actual monitoring remains off because ACTIVE_STATE was omitted. The success message nevertheless says monitoring resumed. This can start an unintended second receiver against the same bot.

   Fix: compute the effective resume choice from the selected categories and saved monitoring state, enforce that in the restore layer, and report the actual resulting state.

10. **P2 — Setup drafts disappear on activity recreation while the wizard retains its current step.**

    Location: [GmailEmailSetupScreen.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/ui/GmailEmailSetupScreen.kt), lines 69–78; similar local state in Telegram, SMS, Resend and backup screens.

    Account/recipient drafts use `remember`, but the wizard step uses `rememberSaveable`. Rotating or recreating the activity on the last step can leave the user on “Save and test” with earlier unsaved entries lost or reverted. In backup flows, activity recreation while the document picker is open loses the password needed by its returning callback, leading to a failed operation or an empty created file.

    Fix: retain non-secret drafts consistently, keep secrets in an appropriate in-memory state holder across configuration changes, and explicitly request password re-entry after process death. Never save passwords into an ordinary saved-state bundle.

11. **P2 — Pending Direct Boot alerts are silently evicted, including outage alerts.**

    Location: [PendingAlertEventStore.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/storage/PendingAlertEventStore.kt), lines 21–25 and its 50-event limit.

    Before first unlock, events cannot be materialized into the credential-protected queue. Every new event keeps only the last 50 entries, without prioritizing outages over heartbeats or recording drops. Leave the phone locked after reboot with frequent scheduled messages and a confirmed outage/restoration pair can disappear before delivery is possible.

    Fix: coalesce replaceable status messages, preserve incident transitions and explicitly expose any overflow/data loss.

12. **P2 — A crash between saving a scheduled-alert marker and queueing the message permanently loses that notice.**

    Location: [ScheduledAlertCoordinator.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/integrations/alerts/ScheduledAlertCoordinator.kt), lines 41–57.

    The code saves timer advancement and `sourceUnavailableAlerted` before durably enqueueing the notice. Process death or a failed write in between leaves the state claiming the warning was handled. A source-unavailable episode can then never produce its initial warning; recovery may later be sent without it. The comment about preventing duplicates trades duplicate risk for silent loss even though the queue already supports deduplication.

    Fix: use a transactional outbox, or persist a stable notice identity and enqueue idempotently before marking that notice committed.

13. **P3 — Failed automatic-backup preparation leaves a named but empty backup file.**

    Location: [ScheduledBackupWorker.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/configuration/ScheduledBackupWorker.kt), lines 31–49.

    The destination document is created before `BackupManager.create`, but cleanup starts only after archive creation succeeds. If capture/encryption throws, the newly created document is not deleted. These empty files match the retention filter and can later count as backup copies despite being unusable.

    Fix: prepare/validate the archive before creating the document, or enclose all work after document creation in the cleanup boundary. Retention should count completed backups.

14. **P2 — A settings-only backup also exports the automatic-backup password.**

    Location: [BackupManager.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/configuration/BackupManager.kt), settings capture; [DataBackupSettingsScreen.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/ui/DataBackupSettingsScreen.kt), `categoryDescription`.

    SETTINGS includes `backupSchedule.password()` even when ALERTS and POWER_SOURCES are deselected. Its description mentions names, timing, appearance, alarms and update choices, not an exported credential. Someone intentionally making a settings-only archive can therefore include a password that unlocks their full automatic backups. The outer archive is encrypted, so this is a selective-export/privacy boundary issue rather than plaintext file storage.

    Fix: make automatic-backup credentials a clearly disclosed, separate opt-in selection, or omit the password and require re-entry on restore.

15. **P2 — Save actions lack visible confirmation, or put their result outside the current viewport.**

    Locations: [MonitoringSettingsScreens.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/ui/MonitoringSettingsScreens.kt), `DeviceSettingsContent`; [SettingsComponents.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/ui/SettingsComponents.kt), lines 136–146; [ScheduledUpdatesSettingsScreen.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/ui/ScheduledUpdatesSettingsScreen.kt), lines 182–187; [PowerOceanAccountSetupScreen.kt](../app/src/main/java/com/flossypickle/poweroutagemonitor/ui/PowerOceanAccountSetupScreen.kt), lines 226–227, 273–280 and 306–310.

    This directly matches the user's observation that Save persists a setting while apparently doing nothing. “Save name” calls persistence but has no success text, changed button state or navigation. “Save custom delay” and “Save custom interval” likewise have no explicit acknowledgement. EcoFlow sampling Save buttons mostly become disabled after saving, which is the only immediate cue. Conversely, PowerOcean “Save account” sets a confirmation string, but renders it near the top of the scrolling page, above the controls. Users working farther down can miss both success and error messages. Repeating an action that assigns the same feedback string also produces no new visible acknowledgement.

    Fix: use consistent, viewport-visible feedback for explicit save actions (for example a shared snackbar plus a nearby Saved/Unsaved state), preserve errors near the triggering control, and disable unchanged saves. For longer operations show Saving, then a definite success or failure. Announce status changes accessibly. Immediate-save switches can use their changed state, but should be clearly distinguished from draft fields requiring Save. Verify on a small screen with the keyboard open and in experienced mode with several sections expanded.

**Simplification and UI improvements, separate from the defects above:**

- Centralize monitoring enable/disable/source-change operations. `MainActivity`, `TelegramRemoteActions` and restore currently orchestrate overlapping store, alarm, service and queue changes independently. A shared application-level operation would make invariants and integration tests substantially easier to maintain.
- Consolidate common alert-provider setup behavior: draft state, save/error handling, test progress, recipient editing and activation. Keep provider-specific instructions/validation separate. The current large parallel screens make fixes such as draft retention easy to apply inconsistently.
- Keep durable writes where required, but move full queue/history parsing, repeated credential reads and backup restore work off the main thread. Replacing every `commit()` with `apply()` just to silence lint would weaken durability without addressing the architecture.
- Treat queue growth and stale messages as an explicit product policy. Active deliveries retry indefinitely, while each mutation rewrites the full JSON list. Coalescing old heartbeats, exposing queue age/size, and using transactional storage would improve long-term behavior and simplify recovery.
- Setup readiness is based on any retained SENT test entry, not verification of the currently configured channel/recipients. Changing recipients does not invalidate that check; clearing/pruning delivery history can remove it. Provider setup tests also bypass that queue. A separate configuration-specific test record would be more useful to users.
- Lint's newer-dependency notices, redundant API checks, unused resources and duplicate icon notices are maintenance work, not evidence of vulnerabilities. The mutable-collection warning also needs inspection of mutations rather than automatic classification as a UI bug.

**Recommended first fixes:** battery observation handling; restore isolation; backup round-trip limits; provider termination/fail-safe behavior. Address save feedback as the first UI fix because it affects everyday confidence in configuration. Then add integration coverage for stable-outage battery decline, restore with in-flight delivery, timing edits during pending confirmation, offline update ordering, and activity recreation during setup/file selection.
