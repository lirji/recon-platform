# M5 Code Review Report

## Verdict

- Result: pass for the M5 code scope; no unresolved high-severity finding.
- Production qualification remains conditional on real MySQL/PostgreSQL and a real alert-channel test.
- Scope reviewed: handler chain, state machine, JDBC persistence, rerun convergence, outbox relay, launch/scheduler, REST, configuration, tests, documentation and CI.

## Findings Resolved

| Severity | Scenario and evidence | Resolution | Regression evidence |
| --- | --- | --- | --- |
| High | `fingerprint` was also used as the global `discrepancy_id`; the same business discrepancy in a second Run collided with the first Run primary key and silently lost the new Run row. | `JdbcDiscrepancyStore` now derives a stable run-local UUID from `runId + fingerprint`, while `fingerprint` remains the cross-rerun identity. | `PersistenceStoreIntegrationTest.sameFingerprintInDifferentRunsGetsDistinctLedgerRows`; `DiscrepancyControllerTest.repeatedLaunchesKeepRunLocalDiscrepanciesAndGlobalHandlerIdempotency`. |
| High | PostgreSQL marks a transaction aborted after a unique-key violation. Catching `DuplicateKeyException` and continuing inside the same chunk/online transaction could make handler, report and rerun idempotency fail on PostgreSQL. | Added `JdbcDuplicateSafeInsert`, which executes idempotent insert attempts under `PROPAGATION_NESTED`/savepoints. Applied it to action, reversal, outbox, discrepancy fallback and existing report/partial upserts. Sequence first-insert races now retry only after the short transaction rolls back. | Handler rerun integration, persistence integration and 32-way sequence concurrency tests pass. |
| High | Two sequence numbers for one scenario/period could finish out of order. A late older Run could overwrite `last_seen_run_id` or mark a disposition STALE after a newer machine view existed. | Convergence now locks same-period Run rows in stable sequence order and only the maximum sequence may converge, including when the newer Run is still loading. All re-link/STALE writes retain version guards. | `PersistenceStoreIntegrationTest.latestRunRejectsOlderAsSoonAsNewerRunExists`; all A1 convergence scenarios pass. |
| Medium | A late failed relay could downgrade a row already marked SENT by another relay. | `markFailed` is conditional on `status <> 'SENT'`. At-least-once duplicate dispatch remains an explicit contract. | `PersistenceStoreIntegrationTest.alertOutboxInsertIfAbsentAndRelayLifecycle`. |
| Medium | The alert relay was called by a transactional Batch tasklet, so network I/O could inherit the tasklet database transaction despite per-status `REQUIRES_NEW` updates. | `relayOnce` now uses `NOT_SUPPORTED`; dispatcher I/O runs without an active DB transaction and status updates remain one short transaction per entry. | `AlertRelayServiceTest.pendingDispatchedToSent` invokes relay inside an outer transaction and asserts the dispatcher sees no active transaction. |
| Medium | REST allowed bucket count zero, arbitrary scenario/Job combinations, and oversized operator/note values, leading to invalid runs or storage exceptions reported as 500. | Validate bucket count 1..4096, configured scenario/Job mapping, scenario length, match-window ordering, operator length 64, note length 512 and nonnegative expected version before persistence. Scheduler no longer supplies a separate Job override. | MockMvc 400 cases plus sequential launch/rerun coverage in `DiscrepancyControllerTest`. |
| Medium | Rerun convergence loaded all machine discrepancies into memory and its updates could overwrite a concurrent manual version. | Convergence performs indexed `(run_id,fingerprint)` existence checks and conditional version updates; stale audit is written only after a successful state update. | A1 integration tests and conditional-update persistence tests pass. |

## Rejected Suspicions

- The default matching window from T 00:00 through T+1 23:59:59 looks wider than a conventional daily window, but it matches the approved A4/A8 look-ahead design and existing tests; it was not changed.
- Concurrent relays may dispatch the same idempotency key more than once. This is expected for at-least-once delivery; downstream consumers must deduplicate by `idempotency_key`.
- No authentication/authorization was added. The request-body operator is an explicit MVP limitation and remains outside M5 scope.

## Residual Risks

- H2 exercises the SQL and transaction behavior locally, but real MySQL 8/PostgreSQL execution was not available in this run. In particular, savepoint behavior, collation migration and streaming fetch should be rechecked in CI with service containers.
- `LoggingAlertDispatcher` is a safe default placeholder, not a production alert integration.
- Outbox relay currently reads all retryable entries in one pass and has no distributed claim/lease. It is correct under at-least-once semantics but should gain bounded claiming before high-volume multi-instance deployment.

## Evidence Files

- `recon-batch/src/main/java/com/lrj/recon/batch/persistence/JdbcDuplicateSafeInsert.java`
- `recon-batch/src/main/java/com/lrj/recon/batch/persistence/JdbcDiscrepancyStore.java`
- `recon-batch/src/main/java/com/lrj/recon/batch/service/DispositionConvergenceService.java`
- `recon-batch/src/main/java/com/lrj/recon/batch/alert/AlertRelayService.java`
- `recon-batch/src/main/java/com/lrj/recon/batch/job/ReconLaunchService.java`
