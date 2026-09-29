# Architecture decisions

Lightweight decision records. Each entry: the situation, what was decided, what it costs, and which
stage of the AWS roadmap will revisit it. Add a new entry rather than rewriting an old one when a
decision changes; the history is the point.

---

## 1. Two-phase upload with upload/download URLs

**Context.** The target design hands clients a presigned S3 URL so bytes never pass through the API.
Locally there is no S3.

**Decision.** Keep the two-phase contract from day one. `POST /documents` creates metadata in
`PENDING_UPLOAD` and returns an `uploadUrl`; `GET /documents/{id}` returns a `downloadUrl`. Locally
both URLs point back at this API (`PUT|GET /documents/{id}/content`).

**Consequences.** The client-facing API does not change when S3 arrives; only the URL host does.
Locally the `PUT …/content` endpoint also flips the status to `UPLOADED`; on S3 nothing tells the API
that the upload happened, which is the problem S3 event notifications solve.

**Revisited in.** Stage 4 (S3, presigned URLs), stage 10 (S3 events).

## 2. `DocumentStorage` abstraction

**Decision.** The service layer depends on a four-method interface (`store`, `load`, `delete`,
`exists`). `LocalFileSystemStorage` is the only implementation today.

**Consequences.** Replatforming file storage is a new class plus configuration, with no change above
the interface. The interface deliberately does not yet include "produce a presigned URL"; that is
added when there is a second implementation to shape it.

**Revisited in.** Stage 4.

## 3. Caller identity from an `X-User-Id` header

**Context.** "List a user's documents" needs an owner, but authentication is out of scope locally.

**Decision.** Trust a header. Every repository lookup is scoped by owner, and a document that exists
but belongs to someone else returns `404`, never `403`, so ids cannot be probed.

**Consequences.** Zero security locally, by design. The `owner_id` column and owner-scoped queries are
already in place, so real identity slots in without a schema change.

**Revisited in.** Stage 11 (API Gateway / Cognito).

## 4. Application-assigned UUID primary keys

**Decision.** The service generates the UUID before insert (it is needed for the storage key). The
entity implements `Persistable` so Spring Data knows a fresh entity is new without a `SELECT` first.

**Consequences.** Ids are opaque and globally unique, which suits S3 keys and distributed workers.
The `Persistable` boilerplate is the price of avoiding one round-trip per insert.

## 5. Flyway owns the schema; Hibernate only validates

**Decision.** `spring.jpa.hibernate.ddl-auto=validate`. Every schema change is a versioned SQL
migration. The same migrations will run against RDS.

**Consequences.** A mismatch between entity and table fails fast at startup (this caught a `CHAR(64)`
vs `VARCHAR` mistake in V2). A migration that has been committed is never edited; the fix is a new
version.

**Revisited in.** Stage 5 (RDS).

## 6. Asynchronous processing via an after-commit event

**Decision.** `POST /documents/{id}/process` marks the row `PROCESSING` and publishes a Spring event
inside the transaction. The worker is an `@Async @TransactionalEventListener`, so it runs on another
thread only after the commit.

**Consequences.** The worker can never observe the pre-commit state. Work is lost if the JVM dies
between commit and completion; there are no retries and no dead-letter handling. Those are exactly
the guarantees a queue adds.

**Revisited in.** Stage 10 (SQS, Lambda, retries, DLQ).

## 7. RFC 9457 problem details for every error

**Decision.** One `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`. Domain
exceptions map to `404`/`409`, validation failures list field errors, anything unexpected is logged
with its stack trace and returned as a generic `500`.

**Consequences.** Clients get one error shape. Internals (exception classes, messages, stack traces)
never reach the response; `server.error.include-*` is set to `never` for the fallback path too.

## 8. Tests use the real local PostgreSQL

**Context.** Docker Desktop is not installed, so Testcontainers is not an option.

**Decision.** Repository tests run in rolled-back transactions against `documentdb`; the end-to-end
test uses a unique owner per run and a temporary storage directory.

**Consequences.** Tests need a running database and share it with development data. Acceptable for a
single-developer lab; a dedicated test database or Testcontainers would be the next step.

## 9. Form-content filter disabled

**Context.** Spring's `FormContentFilter` consumes the body of a `PUT` sent as
`application/x-www-form-urlencoded` (curl's default) as form fields, leaving the upload endpoint an
empty stream. The symptom was a stored file of zero bytes and a checksum of the empty string.

**Decision.** `spring.mvc.formcontent.filter.enabled=false`. The API has no form endpoints, and a
raw-bytes upload must not depend on the client's content type.

## 10. Virtual threads

**Decision.** `spring.threads.virtual.enabled=true`: Tomcat request handling and the `@Async`
executor use virtual threads.

**Consequences.** No thread-pool sizing to tune; blocking I/O (JDBC, file system) is cheap to park.
Requires Java 21+, which the project already targets.

## 11. AWS readiness assessment (Phase 0, 2026-09-14)

**Context.** The local build is done and the replatforming plan was re-baselined into
`SAA-C03_Replatforming_Roadmap_Final_Document.md` (phases 0–15, exam 2026-10-31). Phase 0 verifies
the as-built API is ready to move and records what each later phase will change.

### Findings (P0.S1–P0.S4)

| Check | Result |
|---|---|
| Runtime contract | Compile `--release 25` (via `java.version=25`), run on JDK 26.0.2.1. No preview features. One source level serves the laptop, EC2 (JDK 26, Phase 4) and Lambda (managed `java25`, Phases 9–10). |
| Resolved versions | Spring Boot 4.1.1 · Hibernate ORM 7.4.5 · Jackson 3.1.5 (`tools.jackson.*`) with the Jackson 2 annotations compat line 2.21 · HikariCP 7.0.2 · pgJDBC 42.7.x · Flyway 12.4.0 · Micrometer 1.17.1 · JUnit 6. Recorded in `target/dependency-tree.txt` (regenerate with `./mvnw dependency:tree`). |
| DevTools | `runtime` + `optional`; excluded from the repackaged JAR by the Boot plugin. |
| Configuration | All environment-specific values live in `application-<profile>.properties`; `local` is the default profile, `aws` (new, stub) is for Phases 4+. |
| Secrets | **Finding:** the local DB password had a committed default (`${DOCAPI_DB_PASSWORD:docapi}`). Fixed: default removed, local role password rotated, value held only in a user-level environment variable. A wrong value fails startup with `password authentication failed` (exit 1) — proof the value is read from outside. |
| State | No `HttpSession`, static maps, or caches. Identity is per request (`X-User-Id`), ids are app-assigned UUIDs. **The one stateful thing:** document bytes in `./local-storage/documents` — Phase 2 moves them to S3. |
| In-flight jobs | The `@Async` worker holds queued work in memory; a crash mid-processing leaves the row stuck in `PROCESSING`. Known gap; Phase 9's SQS visibility timeout and retries close it. Not fixed now. |
| Health contract | `GET /actuator/health` → `200 {"status":"UP"}` with a `db` component; only `health` and `info` are exposed. `DOWN` → `503` is Boot's default and is not overridden (not demonstrated: stopping the Windows service needs elevation). Decision for the `aws` profile: expose `health` only, `show-details=never`. |
| Error contract | Every error is `application/problem+json` (type, title, status, detail, instance); no class names, SQL, or paths. Covered by `ApiExceptionHandlerTest`. |
| Regression harness | `./mvnw clean verify` → 30 tests green (the Initializr `contextLoads` test was removed when the end-to-end test replaced it). `http/documents.http` is the manual walkthrough; `DocumentApiEndToEndTest` is its automated twin. Baseline tag: `v0-local-baseline`. |
| Environment | Two PostgreSQL Windows services run (`postgresql-x64-17`, `postgresql-x64-18`); the API uses 18.6 on 5432. Not on PATH: `psql`, `aws`, `jq` (Phase 1/2 install the last two). |

### Dossier assumptions, checked

| Dossier assumed | As built | Check |
|---|---|---|
| Files in `C:\data` | `./local-storage/documents`, key `<uuid>-<filename>` | ✗ dossier wrong |
| Maven `-Plocal` profile | Spring profile `local`, no Maven profiles | ✗ |
| Logback logging to files | Console logging (Boot default); CloudWatch captures the console/JSON file in Phase 6 | ✗ |
| Secrets in `application.yml` | None in code after this phase (see finding above) | ✓ after fix |
| "Not stateless; no externalised config" | Stateless except bytes on disk; config externalised | ✗ |
| Health limited to `/actuator/health` | True, intended | ✓ |
| Testcontainers / LocalStack available | No Docker on the dev box | ✗ |
| Exit state "testable in containers" | Exit is a tagged JAR; containers are post-exam (D1) | ✗ |
| "Dev AWS account created" | Not yet — Phase 1 | ✗ |

### What each phase changes

| Phase | In the application | Outside it |
|---|---|---|
| 1 | nothing | account, Identity Center, Budgets, CloudTrail, Config, region, tags, CLI, `docs/cleanup.md` |
| 2 | `S3DocumentStorage` behind `DocumentStorage`; interface gains presigned-URL methods; temporary `POST /documents/{id}/uploaded` | S3 bucket (SSE-KMS, versioning, lifecycle), IAM policy, KMS key |
| 3 | datasource URL/TLS/pool; password from Secrets Manager | RDS instance, secret, snapshots |
| 4 | `aws` profile; JAR checksum | EC2 + instance role, Session Manager, systemd, launch template, AMI |
| 5 | OAuth 2.0 resource server replaces `X-User-Id`; owner = JWT `sub`; scopes | Cognito user pool, KMS negative test |
| 6 | JSON logs, correlation ID, Micrometer → CloudWatch | log group, metrics, alarms, SNS, dashboard, runbook |
| 7 | nothing (port stays 8080) | VPC tiers, endpoints (no NAT), private RDS re-home, ALB, ACM, Route 53, WAF |
| 8 | JVM DNS TTL for failover | ASG 2 AZs, scaling policy, RDS Multi-AZ, AWS Backup |
| 9 | `/process` → job resource; outbox; worker mode; `@Async` worker removed; confirm endpoint removed | SQS + DLQs, S3 events, SNS, notifier Lambda |
| C | nothing | Cost Explorer, priced teardown list |
| 10 | one route as a Lambda handler (new module) | HTTP API, JWT authorizer, SnapStart, alias |
| 11 | `DynamoDbIdempotencyStore` | DynamoDB table |
| 12 | nothing | one CDK stack |
| 15 | README, diagrams, ADR ledger final | teardown Oct 22, billing verified Oct 24 |

### Containers and local AWS mocks (P0.S6, notes only)

- **Containerising later:** `./mvnw spring-boot:build-image` (Buildpacks) or a Dockerfile over the
  layered JAR (`java -Djarmode=tools -jar app.jar extract --layers` in Boot 4 — confirm syntax);
  needs a Docker daemon; pin base image digest and `arm64` to match Graviton; set the JVM's
  container memory limit explicitly. Post-exam (decision D1).
- *(Superseded in detail by ADR 14.)*
- **Testing S3 without Docker:** Testcontainers 2.x + LocalStack are unavailable here and would not
  prove IAM, KMS, bucket policies, or event delivery anyway. Phase 2 uses (a) an opt-in integration
  test against the real bucket under a `test/` prefix and (b) an offline unit test of presigned-URL
  generation with fake static credentials.

**Exam lens.** Shared responsibility: today everything is ours; RDS takes PostgreSQL patching and
backups (Phase 3), Lambda takes OS and runtime (Phase 10), EC2 leaves the OS with us (Phase 4).
This project is a **replatform** (lift-and-optimise) with a refactored slice (Phases 9–10), not a
rehost and not a full refactor.

## 12. Multi-account design on paper; one account in practice (Phase 1, P1.S1.4)

**Context.** Enabling IAM Identity Center creates a one-account AWS Organization with this account as
the management account. The exam (Task 1.1) expects a multi-account strategy; a solo lab with strict
cost discipline does not benefit from extra accounts.

**Decision.** No additional accounts, OUs, or SCPs are created. The design that *would* be used for a
company is recorded here so the vocabulary is exercised:

```
Root
├── Security OU        log-archive account (CloudTrail org trail, Config aggregator), audit account
├── Sandbox OU         one account per engineer; SCP: deny everything outside us-east-1 except global services
├── Dev OU             document-api-dev
└── Prod OU            document-api-prod; SCP: deny CloudTrail StopLogging/DeleteTrail/UpdateTrail,
                       deny LeaveOrganization, deny disabling GuardDuty/Config
```

Example guardrail SCP (never attached in this lab; attach to an OU, never to the organization root):

```json
{ "Version": "2012-10-17", "Statement": [ { "Sid": "ProtectAuditTrail", "Effect": "Deny",
  "Action": ["cloudtrail:StopLogging", "cloudtrail:DeleteTrail", "cloudtrail:UpdateTrail"],
  "Resource": "*" } ] }
```

**The two sentences that matter.** *IAM policies grant permissions; SCPs only set the maximum
permissions available (a guardrail) and grant nothing.* *An SCP attached to the root, an OU, or an
account applies to every principal in it — including the member account's root user — but never to
the management account.* Evaluation: allowed only if the SCP allows **and** the identity policy allows
(**and** any resource policy / permission boundary allows); an explicit deny anywhere wins.
Resource control policies (RCPs, 2024) do the same for resources ("no bucket in this organisation may
be read from outside it"). Control Tower is the answer to "set up a multi-account landing zone with
best-practice guardrails quickly"; it builds on Organizations.

**Consequences.** Zero cost, zero teardown. Revisited in Phase 15's Task 1.1 close-out.

## 13. S3 storage adapter and presigned URLs (Phase 2, P2.S2.2, 2026-09-27)

**Context.** Bytes move from the laptop's disk to the bucket built in P2.S2.1. The API must stop
touching bytes: clients upload to and download from S3 directly through presigned URLs, and the API
only decides who may do what. `DocumentStorage` gains a second implementation, which is the moment
ADR 2 said the interface would grow.

**Decisions.**

- **A — key layout: the S3 adapter prepends `documents/`.** The database keeps `<uuid>-<filename>`
  with no prefix, so rows are identical whichever adapter is active and the local adapter is
  untouched. The prefix is an S3 concern (the IAM policy, the lifecycle rule and event filters are
  scoped to it), not a domain concern. Consequence: Phase 9's event consumer strips the prefix from
  the S3 key before looking the row up. Rejected: generating `documents/<uuid>-<filename>` in the
  service for both adapters (a bigger change; the local directory would grow a subfolder).
- **B — presigned URL lifetime: 15 minutes** (`documents.storage.s3.presign-ttl`, overridable by
  `DOCAPI_PRESIGN_TTL`). Long enough for a slow upload, short enough that a leaked URL is worthless
  quickly. A URL is also bounded by the *signer's* credential lifetime, whichever ends first.
- **C — presigned PUT, not presigned POST.** The two-phase contract already assumes a PUT and the
  SDK's `S3Presigner` produces one directly. What is given up: only presigned POST can make S3 enforce
  a **maximum size** (`content-length-range`). Size is therefore enforced *after the fact* — the
  confirm endpoint (P2.S2.8) and later the S3 event consumer (Phase 9) read the object's size and
  fail oversized documents. Exam framing for the other case: "reject oversized uploads *before* they
  are stored" → presigned POST with `content-length-range`.
- **Adapter selection by property, not profile:** `documents.storage.type=local|s3`
  (`DOCAPI_STORAGE_TYPE`), defaulting to `local`, so the test suite and the plain local run are
  unchanged and the S3 run is a separate IntelliJ run configuration.
- **`Optional<URI>` on the interface.** `presignedUploadUrl` / `presignedDownloadUrl` return empty
  from the local adapter and the controller falls back to the API's own `/content` endpoints. "A
  storage that can presign returns a URL; otherwise the API serves the bytes." The TTL is the
  adapter's own configuration, so callers never know S3 exists.
- **No credential and no Region in code.** `S3Client.create()` and `S3Presigner.create()` use the
  SDK's default provider chains: the Identity Center SSO profile and `AWS_REGION` on the laptop, the
  instance role and instance Region on EC2 (Phase 4) — the same JAR, no change.
- **`Content-Type` is a signed header** on the presigned PUT, so the client must send exactly the
  type it declared at `POST /documents`.

**Consequences.** After a presigned upload the row stays `PENDING_UPLOAD`: nothing tells the API the
bytes arrived (the "upload-confirmation gap"). Deliberately left open today; P2.S2.8 adds a temporary
confirm endpoint, Phase 9 replaces it with S3 events that do not trust the client. `store()` on the
S3 adapter spools to a temp file because the interface hands it a bare stream; real uploads never
pass through it.

**Revisited in.** P2.S2.8 (confirm endpoint), Phase 9 (events, prefix stripping), Phase 4 (instance
role exercises the KMS gate for real).

## 14. Temporary confirm endpoint; S3 testing without Docker (Phase 2, P2.S2.8 + P2.S2.3, 2026-09-29)

**Context.** With presigned PUTs (ADR 13) the bytes go straight to S3 and nothing tells the API they
arrived: the row stays `PENDING_UPLOAD` and no download URL is ever issued. Separately, the S3 adapter
needs automated tests on a machine with no Docker, so Testcontainers + LocalStack are unavailable.

**Decisions.**

- **`POST /documents/{id}/uploaded` — scaffolding, removed in Phase 9.** The client says "my upload
  finished"; the API **verifies, it does not trust**: it asks storage for the object's size
  (`DocumentStorage.sizeOf`, a `HeadObject` on S3), refuses with `409` if there are no bytes, and only
  then moves the row to `UPLOADED` with *storage's* size. The client controls *when* the check runs,
  never *what* it finds. Phase 9 replaces it with an S3 `ObjectCreated` event, which needs no client
  at all and also fires on a second PUT to a still-valid URL (a gap this endpoint cannot close).
- **Maximum size 10 MB** (`documents.max-file-size`, `DOCAPI_MAX_FILE_SIZE`). On S3 it is enforced
  *after the fact* by the confirm step (ADR 13, C): an oversized object is deleted and the document
  becomes `FAILED` with no file size (`markRejected`), so it never gets a download URL. On a versioned
  bucket that delete is only a delete marker — the bytes stay stored and billed as a noncurrent
  version until the lifecycle rule (P2.S2.5, `NoncurrentVersionExpiration`) removes them. Observed:
  an 11,000,000-byte upload accepted by S3 (`200`), rejected at confirm, still present underneath its
  delete marker. On the local adapter the API sees the bytes and refuses them directly (`413`).
- **One client flow for both adapters.** Locally the confirm call is harmless and idempotent (the PUT
  already marked the row `UPLOADED`; confirm re-reads the size), so `http/documents.http` and the
  end-to-end test use create → PUT to `uploadUrl` → confirm for both storages.
- **Testing without Docker:**
  - *Offline unit test* (`S3DocumentStorageTest`): presigning is pure local computation, so URLs are
    tested with deliberately fake static credentials and no network. The production beans pass no
    credential and let the default provider chain decide — the contrast is the lesson.
  - *Opt-in integration test* (`S3DocumentStorageIntegrationTest`): the real bucket, as the SSO
    session, under `test/<run-id>/` (outside `documents/`, so lifecycle and event filters ignore it);
    removes every version and delete marker it creates. Runs only with `DOCAPI_S3_IT=true`, so the
    default build stays offline, free and runnable without an AWS account.
  - *LocalStack* is the post-exam option. It would let the integration test run on every build, but an
    emulator proves **wiring, not AWS behaviour**: it could not have shown the IAM policy's `documents/*`
    scope, the bucket policy's explicit deny, SSE-KMS's own TLS rule (`400 InvalidArgument`, ADR 13's
    check 7), real presign expiry, or event-destination validation. Those are only proven against AWS.

**Consequences.** Every create against S3 that is never confirmed leaves a `PENDING_UPLOAD` row and
possibly an unreferenced object; nothing reaps them yet (Phase 9's event consumer and a sweep job are
the fix). The `HeadObject`-needs-no-`kms:Decrypt` assumption in `sizeOf`'s Javadoc is to be verified
in Phase 5's KMS negative test.

**Revisited in.** P2.S2.5 (lifecycle expires the rejected bytes), Phase 5 (KMS negative test),
Phase 9 (events replace the endpoint; prefix stripping; reaping unconfirmed uploads).
