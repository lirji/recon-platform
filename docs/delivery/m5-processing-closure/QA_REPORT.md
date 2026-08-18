# M5 QA Report

## Verdict

- Local/CI-ready verdict: pass.
- Production integration verdict: conditional-pass until real MySQL 8, PostgreSQL and the chosen alert channel are exercised.

## Environment

- Java: 21.0.11
- Build: repository Maven wrapper
- Integration database: H2 2.2 in MySQL compatibility mode with Flyway V1-V3
- Test reports: 51 suites, 189 tests, 0 failures, 0 errors, 0 skipped

## Acceptance Matrix

| Acceptance criterion | Result | Evidence |
| --- | --- | --- |
| AC-01 handler chain/idempotency/reversal/outbox | pass | `DiscrepancyHandlerChainTest`, `HandlerChainJobTest`, repeated REST launch test |
| AC-02 relay status/retry/transaction boundary | pass | `AlertRelayServiceTest`, SENT downgrade persistence regression |
| AC-03 state machine/version/idempotent manual action | pass | `DiscrepancyStateMachineTest`, `ManualClearingServiceTest`, MVC 409 cases |
| AC-04 rerun preservation and A1 convergence | pass | `DispositionConvergenceA1Test`, latest-sequence guard tests |
| AC-05 REST 2xx/400/404/409 contract | pass | `DiscrepancyControllerTest` |
| AC-06 atomic sequence allocation | pass | `ReconRunSeqConcurrencyTest` and sequential REST launches |
| AC-07 single/two-segment Job regression | pass | full Maven suite including all M0-M4 Job tests |
| AC-08 architecture and clean package | pass | all module ArchUnit tests and five generated JARs |
| AC-09 docs and CI | pass | README/CLAUDE/Known Issues synchronized; workflow YAML parses and its exact build command passes locally |

## Commands

| Command/check | Result |
| --- | --- |
| Targeted state/handler/sequence/manual tests | pass |
| Targeted persistence/convergence/REST/relay tests | pass |
| `./mvnw -q test` | pass |
| `./mvnw -q clean package` | pass |
| Surefire XML aggregation | 189/189 passed; 0 skipped |
| `git diff --check` | pass |
| Ruby YAML safe load of `.github/workflows/ci.yml` | pass |

## Build Artifacts

- `recon-core/target/recon-core-0.0.1-SNAPSHOT.jar`
- `recon-source-db/target/recon-source-db-0.0.1-SNAPSHOT.jar`
- `recon-scenario/target/recon-scenario-0.0.1-SNAPSHOT.jar`
- `recon-handler/target/recon-handler-0.0.1-SNAPSHOT.jar`
- `recon-batch/target/recon-batch-0.0.1-SNAPSHOT.jar`

## Not Executed

- Real MySQL 8 and PostgreSQL Testcontainers/integration run (no external database environment was used).
- Real email/DingTalk/PagerDuty dispatch and downstream idempotency verification.
- Authentication, authorization, deployment and production load tests; these are outside the approved M5 scope.
