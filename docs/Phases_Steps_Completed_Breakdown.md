# Phases & Steps Completed — Running Breakdown

*Running log of what has been accomplished, phase by phase, task by task. Updated at the end of each
working day. Step IDs (`P1.S1.2` etc.) and exam-topic mappings follow
`SAA-C03_Replatforming_Roadmap_Final_Document.md`. "Who" = **Sandeep** (console / manual work) or
**Claude** (code, CLI, documentation).*

*Last updated: 2026-09-30. Exam: to be rescheduled (was 2026-10-31); the calendar shifted +9 days after Sep 16–25 were lost.*

| Phase | Status | Dates | Commits |
|---|---|---|---|
| Local build (legacy Stages 1–2) | ✅ Done | 2026-09-02 | `4516aaa` … `02ff2c2` (7 commits) |
| Phase 0 — Readiness check | ✅ Done | 2026-09-14 | `a30018a`, tag `v0-local-baseline` |
| Phase 1 — AWS account foundation | ✅ Done (2 items deferred ~24h) | 2026-09-14 → 15 | `ea7ab1b`, `389d384` |
| Phase 2 — Files to Amazon S3 | ✅ Done | 2026-09-26 → 30 | `889ec98` … (day 4) |
| Phase 3 — Metadata to Amazon RDS PostgreSQL | ⏳ Next | from 2026-10-01 | — |

---

## Local build — the Document API on the laptop (2026-09-02)

**What the phase is.** Build the smallest realistic Spring Boot REST service that has storage,
metadata, an authorisation boundary and an asynchronous step — so that every later AWS phase replaces
one concern of a *working* system rather than designing in the abstract. Everything runs on one
laptop: PostgreSQL for metadata, a local directory for bytes.

```
Client ──HTTP──▶ Spring Boot document-api ──JDBC──▶ PostgreSQL 18.6   (metadata)
                          └──────────────────────▶ ./local-storage/  (bytes)
```

### Tasks

**1. Project skeleton, profiles, health endpoint** — *Claude* · `4516aaa`
Exam topic: groundwork for D2 Task 2.2 (health checks) and D1 Task 1.2 (externalised configuration).
- Added `spring-boot-starter-validation` (missing from the Initializr pom) and `spring-boot-starter-actuator`.
- `application.properties` + `application-local.properties`: profile-based config, `ddl-auto=validate`, `/actuator/health` exposed — the endpoint an ALB target group will later poll.
- `git init`, `.gitignore` for `local-storage/`.

**2. Schema, entity, repository** — *Claude* · `f6f2741`
Exam topic: D3 Task 3.3 (relational data modelling; same migrations later run on RDS).
- Flyway `V1__create_documents.sql`: `documents` table with `owner_id`, nullable `file_size`, unique `storage_key`, `status`, timestamps, index on `(owner_id, created_at DESC)`.
- `Document` JPA entity with app-assigned UUID and `Persistable` (avoids a SELECT before every INSERT); `DocumentStatus` enum; owner-scoped repository methods.
- Sandeep connected IntelliJ's Database tool to `documentdb`.

**3. Storage abstraction** — *Claude* · `4812f03`
Exam topic: D3 Task 3.1 / D2 Task 2.1 — object storage vs local disk; the seam that makes the S3 swap a one-class change.
```java
public interface DocumentStorage {
    long store(String storageKey, InputStream content);
    Resource load(String storageKey);
    void delete(String storageKey);      // idempotent
    boolean exists(String storageKey);
}
```
- `LocalFileSystemStorage`: writes to a `.part` file then atomic rename; rejects keys that escape the root (`../`).
- `StorageKeys`: `<uuid>-<sanitised-filename>` — the same key format works as a file path and as an S3 object key.

**4. REST endpoints, two-phase upload, service layer** — *Claude* · `8efce92`
Exam topic: D1 Task 1.2 (presigned-URL pattern, modelled locally) · D2 Task 2.1 (stateless design).
- `POST /documents` returns an **upload URL**; `PUT /documents/{id}/content` receives bytes; `GET /documents/{id}` returns a **download URL** once bytes exist. Locally both URLs point back at the API; on AWS they become presigned S3 URLs and the client contract does not change.
- Owner from an `X-User-Id` header; another owner's document → `404`, never `403`.
- IntelliJ HTTP-client file `http/documents.http` with environment `local`.

```
 Client                 API                    Storage
   │ POST /documents     │                        │
   │────────────────────▶│ row: PENDING_UPLOAD    │
   │ 201 + uploadUrl     │                        │
   │◀────────────────────│                        │
   │ PUT <uploadUrl>     │──── store(bytes) ─────▶│
   │ 200 UPLOADED        │                        │
```

**5. RFC 9457 problem details** — *Claude* · `e249ec6`
Exam topic: D1 Task 1.2 (never leak internals) — and the answer to the very first question of the project, "how do I log an error?": log once, at the boundary.
- `ApiExceptionHandler extends ResponseEntityExceptionHandler`: `404`/`409` for domain exceptions, field errors on `400`, generic `500` with the stack trace only in the log. `server.error.include-*=never`.

**6. Asynchronous processing** — *Claude* · `97809bb`
Exam topic: D2 Task 2.1 (event-driven decoupling; the local stand-in for SQS + a worker).
- Flyway `V2__add_processing_columns.sql` (`checksum_sha256`, `failure_reason`).
- `POST /documents/{id}/process` → `202`; `@Async @TransactionalEventListener` worker runs **only after commit**, computes SHA-256, writes `PROCESSED` / `FAILED`.
- Three bugs found and fixed on the way: `CHAR(64)` vs Hibernate `VARCHAR` (schema validation caught it); an empty checksum caused by Spring's `FormContentFilter` eating a PUT body sent as `x-www-form-urlencoded` (filter disabled); a unit test that only used an in-memory resource (now hashes a real file).

**7. End-to-end test, README, decision log** — *Claude* · `02ff2c2`
Exam topic: none directly; the regression harness every later phase's VERIFY step relies on.
- `DocumentApiEndToEndTest` (`@SpringBootTest`, random port, `RestTestClient`): create → 409s → upload to the returned URL → download → process → await `PROCESSED` → list → cross-owner 404 → delete → empty directory. 30 tests total.
- `README.md`, `docs/decisions.md` (10 ADRs), `C:\AWS\Document_API_Replatforming_High_Level.md` briefing document.

---

## Phase 0 — Readiness check (2026-09-14)

**What the phase is.** Verify, with evidence, that the as-built API is ready to move: pinned runtime,
externalised secret-free configuration, statelessness, a health/error contract, and a rerunnable
regression baseline. Nothing is built; one ADR and one git tag come out of it.

### Tasks

**P0.S1 — Inventory dependencies, freeze the runtime contract** — *Claude*
Exam topic: D3 Task 3.2 · `EX-3.2-K01/K05` — which compute fits a runtime constraint (Lambda = managed runtimes only; EC2 = anything).
- `./mvnw dependency:tree` + `help:effective-pom` → compile `--release 25`, run JDK 26.0.2.1, no preview features. Boot 4.1.1 · Hibernate 7.4.5 · Jackson 3.1.5 (`tools.jackson.*`) · HikariCP 7.0.2 · Flyway 12.4.0.
- DevTools confirmed `runtime`+`optional` → not in the deployable JAR.

**P0.S2 — Configuration externalised, no secrets** — *Claude*
Exam topic: D1 Task 1.2 · `EX-1.2-K01` credentials security · `EX-1.2-S03` Secrets Manager vs Parameter Store.
- **Finding:** `spring.datasource.password=${DOCAPI_DB_PASSWORD:docapi}` — a committed default.
- Fix: default removed; local Postgres role password rotated (`ALTER ROLE`); value stored only as a user-level Windows env var (`setx`); README updated.
- Proof: the packaged JAR started with a wrong password exits 1 with `password authentication failed`.
- Created `application-aws.properties` stub (health only, `show-details=never`, datasource from env).

**P0.S3 — Statelessness check** — *Claude*
Exam topic: D2 Task 2.1 · `EX-2.1-K04/K06` stateless vs stateful, horizontal vs vertical scaling.
- Grep for `HttpSession`, static maps, caches → none. Identity is per request; ids are app-assigned UUIDs.
- **The one stateful thing:** bytes in `./local-storage/documents` (Phase 2 removes it). In-flight-job gap (crash mid-processing leaves `PROCESSING`) recorded for Phase 9.

**P0.S4 — Health and error contracts** — *Claude*
Exam topic: D2 Task 2.2 · `EX-2.2-K08` load-balancer health checks · `EX-2.2-S04` single points of failure.
- `/actuator/health` → `200` with a `db` component; only `health`,`info` exposed. `DOWN→503` is the Boot default (not demonstrated — stopping the Windows service needs elevation).

**P0.S5 — Baseline, tag, readiness ADR** — *Claude* (tag push, GitHub repo creation: *Sandeep*)
Exam topic: `EX-1.1-K05` shared responsibility model (starting point: today everything is ours).
- `./mvnw clean verify` → 30 green; tag `v0-local-baseline`; repo `github.com/sbarna992/aws-document-api`.
- **ADR 11** in `docs/decisions.md`: findings table, the dossier-assumptions table (✓/✗), and "what each phase changes".
- Sandeep ran `http/documents.http` top to bottom after restarting IntelliJ — all requests passed.

**P0.S6 — Container / mock notes** — *Claude*
Exam topic: D2 Task 2.1 · `EX-2.1-K08/K14` containers, ECS vs EKS vs Fargate (conceptual; hands-on is post-exam).
- Notes in ADR 11: how the JAR would be containerised; why LocalStack/Testcontainers are unavailable (no Docker) and how Phase 2 tests S3 instead.

---

## Phase 1 — AWS account foundation (2026-09-14 → 15)

**What the phase is.** Make the account safe to experiment in and unable to surprise on a bill:
protected root, daily admin through IAM Identity Center, budget alarms, an audit trail, a configuration
recorder, one Region, a tagging convention, a working CLI, a cleanup ledger.

```
 root ── MFA ×2, no keys ── root-only tasks
   │
   ▼
 IAM Identity Center ── AdministratorAccess (4 h sessions, MFA)
   │  STS temporary credentials
   ▼
 ┌── AWS account 234178676885 (us-east-1) ───────────────────────────┐
 │ every API call ──▶ CloudTrail docapi-lab-trail ──▶ audit bucket   │
 │ every resource ──▶ AWS Config recorder + 3 rules ──▶ audit bucket │
 │ every dollar   ──▶ Budgets $10 / $25 ──▶ email                    │
 └────────────────────────────────────────────────────────────────────┘
 laptop: AWS CLI v2, profile document-api (SSO, no static keys)
```

**Discovery that reshaped the cost plan** (*Claude*, from the console evidence Sandeep provided): the
account is not new — a dormant root access key was 5015 days old, i.e. the account dates from
**~December 2012**. No free tier, no credits: every resource bills at list price. Consequences: RDS will
be `db.t4g.micro` rather than `small`; ALB and interface endpoints are deleted whenever idle.

### Tasks

**Account sign-in, Region, plan check** — *Sandeep*
Exam topic: `EX-1.1-K03` global infrastructure (Regions) · D4 cost-management tools.
- Switched the console from Ohio (us-east-2) to **us-east-1**; verified Credits ($0.00) and Bills (September 2026 only) pages.

**P1.S1.1 — Protect root; daily admin via Identity Center** — *Sandeep* (guided)
Exam topic: D1 Task 1.1 · `EX-1.1-S01` MFA/root best practices · `EX-1.1-K02` federation · `EX-1.1-S03` STS role sessions · `EX-1.1-K04` least privilege.
- Root MFA (authenticator app) assigned; **13-year-old unused root access key deleted** (needed a fresh MFA-backed session — the Actions button was disabled until re-login).
- Identity Center enabled with Organizations (one-account org); user created; **MFA required at every sign-in**; permission set `AdministratorAccess`, 4-hour session; assigned to the account; portal `https://d-90667f1516.awsapps.com/start`.
- Gotchas met and resolved: the Identity Center user has its own password (set via the invitation / reset link, not the root password); the permission set must exist before the assign wizard lists it.

**P1.S1.2 — Budgets before anything can cost money** — *Claude* (budgets) · *Sandeep* (Cost Explorer, invoices)
Exam topic: D4 Tasks 4.1–4.4 · `EX-4.x-K02/K03` cost-management tools — Budgets *alerts*, Cost Explorer *analyses*, CUR *is data*, Anomaly Detection *finds the unexpected*.
```bash
aws budgets create-budget --account-id 234178676885 \
  --budget '{"BudgetName":"docapi-lab-monthly-10usd","BudgetLimit":{"Amount":"10","Unit":"USD"},
             "TimeUnit":"MONTHLY","BudgetType":"COST"}' \
  --notifications-with-subscribers '[ {ACTUAL ≥100% → email}, {FORECASTED ≥100% → email} ]'
# and the same for 25 USD
```
- Verified via `describe-budgets` / `describe-notifications-for-budget`: two budgets, four alerts.
- Sandeep enabled Cost Explorer (first visit = enable; 24 h to prepare) and invoice delivery by email.
- ⏳ *Deferred until Cost Explorer is ready:* Cost Anomaly Detection monitor.

**P1.S1.5-REF — Region and tagging convention** — *Claude*
Exam topic: `EX-1.1-K03` Regions/AZs · cost allocation tags (`EX-4.x-K01`).
- README: Region `us-east-1`; tag set `Project / Environment / Owner / ManagedBy / Stage / DeleteAfter=2026-10-22`. `AWS_DEFAULT_REGION` in `~/.bashrc`.
- ⏳ *Deferred:* activate `Project` and `Stage` as cost allocation tags (they appear in the console only after billing has processed tagged resources).

**P1.S1.6-REF — AWS CLI v2 as the Identity Center administrator** — *Claude* (install, config) · *Sandeep* (`aws sso login`)
Exam topic: D1 Task 1.2 · `EX-1.2-K01` — the **default credentials provider chain**: same code finds the SSO profile on the laptop and the instance role on EC2; nobody writes a key into code.
- `winget install Amazon.AWSCLI` → 2.36.45. Wrote `~/.aws/config` directly (what `aws configure sso` would produce):
```ini
[sso-session document-api]
sso_start_url = https://d-90667f1516.awsapps.com/start
sso_region = us-east-1
sso_registration_scopes = sso:account:access

[profile document-api]
sso_session = document-api
sso_account_id = 234178676885
sso_role_name = AdministratorAccess
region = us-east-1
output = json
```
- Lesson: `aws sso login` needs a live localhost callback; handing the authorisation URL across chat turns expires it, so Sandeep runs the login in his own terminal.
- Proof: `aws sts get-caller-identity` → `arn:aws:sts::…:assumed-role/AWSReservedSSO_AdministratorAccess_…/sbarna992@gmail.com`; no `~/.aws/credentials`; `grep AKIA` empty.

**P1.S1.3 — CloudTrail and AWS Config** — *Claude*
Exam topic: `EX-1.3-K01` governance · `EX-1.2-K05` security services · `EX-2.2-S01` infrastructure integrity — CloudTrail = *who called what*; Config = *what does it look like, is it compliant*; CloudWatch = *how is it performing*; GuardDuty = *is it malicious*.
- Audit bucket `docapi-lab-audit-ae9c73ce`: Block Public Access, SSE-S3, tagged, bucket policy with CloudTrail + Config service principals scoped by `aws:SourceArn` / `aws:SourceAccount`, and a `DenyInsecureTransport` statement.
- Trail `docapi-lab-trail`: multi-Region, global service events, log-file validation, management events read+write, **no data events** (billable). `IsLogging: true`; first delivery confirmed; logs seen for us-east-1 and us-east-2.
- Config: service-linked role, recorder `default` (all supported types + global, continuous), delivery channel to the audit bucket, rules `root-account-mfa-enabled`, `cloudtrail-enabled`, `s3-bucket-public-read-prohibited` — **all COMPLIANT**.

```
 API call ──▶ CloudTrail ──▶ s3://docapi-lab-audit-…/cloudtrail/AWSLogs/…   (+ digest files)
 resource change ──▶ Config recorder ──▶ …/config/AWSLogs/…  ──▶ 3 rules ──▶ COMPLIANT
```

**P1.S1.7-REF — Cleanup ledger** — *Claude*
Exam topic: D4 · Well-Architected cost pillar ("decommission resources"); the design version on the exam is scheduled stop/start, lifecycle policies, Trusted Advisor.
- `docs/cleanup.md`: one row per resource with Region, creator, idle cost, exact teardown command, delete-by date. Rule: **no resource without a row.** Teardown deadline 2026-10-22, billing verified 2026-10-24.

**P1.S1.4 — Organizations / SCPs on paper** — *Claude*
Exam topic: D1 Task 1.1 · `EX-1.1-K01` multi-account access · `EX-1.1-S04` Control Tower, SCPs.
- **ADR 12**: OU design (Security / Sandbox / Dev / Prod), an example guardrail SCP, and the two sentences — *SCPs bound, never grant; they apply to every principal in the OU including member roots, never to the management account.* No accounts or SCPs created.

**Account hardening (beyond the exit criteria)** — *Sandeep* (guided)
Exam topic: `EX-1.1-S01` root best practices; root-only tasks.
- **IAM user and role access to Billing information** activated as root (without it `AdministratorAccess` cannot open Billing preferences — a classic exam trick).
- **Second root MFA device**: a Windows Hello passkey on the laptop (`root-backup-windows-hello-1`), so the phone and the laptop are independent factors. AWS issues no recovery codes; the fallback is email + phone verification, so contact information was checked and a **security alternate contact** set.
- Passwords stored in Google Password Manager: root, Identity Center portal, local `docapi` DB user.

**Documentation and commits** — *Claude*
- README "AWS account" section (plan facts, root protection, account settings, CLI, budgets, audit, tags); commits `ea7ab1b`, `389d384`; memory notes updated for future sessions.

### Phase 1 — still open (both wait on Cost Explorer initialisation, ~24 h from 2026-09-14)
- [ ] Cost Anomaly Detection monitor (daily email summary)
- [ ] Activate `Project` and `Stage` as cost allocation tags

---

## Tooling installed along the way

| Tool | Version | By | Why |
|---|---|---|---|
| AWS CLI v2 | 2.36.45 | Claude | every AWS phase |
| `jq` | 1.8.2 | Claude | Phase 2+ verification commands parse JSON |
| PostgreSQL | 18.6 (native Windows service) | Sandeep | local metadata store |
| IntelliJ IDEA Ultimate | 2025.3 | Sandeep | IDE, Database tool, HTTP client |

---

## Phase 2 — Files to Amazon S3 (2026-09-26 → 30)

**What the phase is.** Move the one stateful thing Phase 0 found — bytes on the laptop's disk — into a
private, encrypted, versioned S3 bucket, and change the API so it brokers access with presigned URLs
and never touches bytes. The API stays on the laptop with local PostgreSQL ("prove it from the laptop
before moving it"). Four working days: infrastructure → code → confirm-and-test → S3 feature tour.

**Schedule note.** Planned for Sep 17–20; nine days were lost, so the whole calendar shifted +9 days.
Day 1 was Saturday 2026-09-26.

### Day 1 (2026-09-26) — infrastructure, no code

```
 Sandeep (console / CLI)                            Claude (CLI verification)
 ───────────────────────                            ─────────────────────────
 KMS key alias/document-api ──────────────────────▶ describe-key, rotation status, key policy
 bucket docapi-documents-7fb3fd47 ────────────────▶ BPA x4, versioning, SSE-KMS + Bucket Key, tags
 IAM policy DocumentApiS3Access (wrote the JSON) ─▶ simulate-custom-policy x9; created policy == repo file
 bucket policy applied ───────────────────────────▶ authenticated plain-HTTP HeadObject → 403
 legacy IAM policy simulator driven
```

**Phase 1 leftovers closed** — *Sandeep*
Exam topic: D4 · Cost Anomaly Detection vs Budgets (`EX-4.x-K02/K03`).
- Cost Anomaly Detection monitor created (AWS services; alert threshold **$5**, not the $400 default — on a $25–50/month account $400 would never fire; $5 catches a forgotten ALB within about ten days).
- `Project` and `Stage` activated as cost allocation tags.

**P2.S2.1 — Private, encrypted, versioned bucket** — *Sandeep* (console) · *Claude* (verification)
Exam topic: D1 Task 1.3 · `EX-1.3-K04` encryption and key management · `EX-1.3-S02` at rest with KMS · `EX-1.3-K02` versioning as recovery · D3 Task 3.1 · `EX-3.1-K02/K03` object vs file vs block.
- KMS: symmetric customer-managed key, alias `document-api`, rotation on (365 days), tagged. The wizard was run with **no key administrators and no key users**, so the generated key policy has exactly one statement — `kms:*` to the account root — the delegation that lets IAM policies govern the key. The application's KMS rights therefore live only in its IAM policy.
- Bucket: `docapi-documents-7fb3fd47`, us-east-1, ACLs disabled (`BucketOwnerEnforced`), Block Public Access ×4, versioning **Enabled**, default encryption `aws:kms` with the key, Bucket Key on, tagged.
- Verified: test object `documents/hello.txt` → `head-object` shows `ServerSideEncryption: aws:kms`, the key ARN, `BucketKeyEnabled: true` and a **VersionId**; anonymous HTTPS GET → `403`; anonymous listing → `403`; authenticated GET returns the content.
- Lesson recorded: a versioned bucket is never "empty" after `aws s3 rm` — delete markers and noncurrent versions remain — so the teardown row says *delete all versions and delete markers*.

**P2.S2.4 — Least-privilege S3 + KMS permissions** — *Sandeep* (wrote the policy from requirements, created it, applied the bucket policy, drove the simulator) · *Claude* (review, CLI simulation, TLS-only bucket policy)
Exam topic: D1 Task 1.1 · `EX-1.1-K04` least privilege · `EX-1.1-S02` identity policies · `EX-1.1-S05` resource policies · Task 1.3 · `EX-1.3-S04` key access policies · `EX-1.3-S03` encryption in transit.
- Identity policy `infra/iam/document-api-s3-access.json`:

```json
{ "Sid": "DocumentObjectAccess", "Effect": "Allow",
  "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
  "Resource": "arn:aws:s3:::docapi-documents-7fb3fd47/documents/*" },
{ "Sid": "DocumentKeyUse", "Effect": "Allow",
  "Action": ["kms:GenerateDataKey", "kms:Decrypt"],
  "Resource": "arn:aws:kms:us-east-1:234178676885:key/07a7a487-…" }
```

  Created as `DocumentApiS3Access`; attached to nothing until Phase 4. No `ListBucket`: `HeadObject` is authorised by `GetObject`, and bucket-level actions would belong on the bucket ARN, not `/*`.
- CLI simulation (`aws iam simulate-custom-policy`; the policy must be passed as a string — `file://` is rejected for this parameter), nine cases:

| Action | Resource | Decision |
|---|---|---|
| Get / Put / DeleteObject | `…/documents/x` | allowed |
| GetObject | `…/other/x` | implicitDeny |
| PutBucketPolicy, ListBucket | bucket | implicitDeny |
| kms:Decrypt, kms:GenerateDataKey | key | allowed |
| kms:ScheduleKeyDeletion | key | implicitDeny |

  Every denial is **implicit** — the absence of an Allow. An explicit deny overrides allows from other policies; an implicit one does not.
- Bucket policy `infra/s3/bucket-policy.json`: resource-based (it has a `Principal`), an explicit `Deny s3:*` for `Principal: "*"` when `aws:SecureTransport` is `false`, on **both** the bucket ARN and `/*`.
- **Proof that an explicit deny wins:** an authenticated `HeadObject` as `AdministratorAccess` over `http://` → `403`; over `https://` → success. The identity allows everything; the resource policy still refuses.
- Simulator UI: the legacy simulator (policysim.aws.amazon.com) in *New Policy* mode kept evaluating against resource `*` regardless of the ARN entered (`Resource Type: not required`), a known limitation of that mode; `*` answers "allowed on *any* resource?". Driven far enough to see allowed vs implicitly denied and the bucket's resource policy pulled into the evaluation. The CLI results are authoritative.

**Close-out** — *Claude*: cleanup ledger rows for the key (delete *after* the bucket; objects become unreadable the moment the key is disabled), bucket, policy and bucket policy; README section "Document storage on S3"; this entry.

**Day 1 exit state:** bucket, key and least-privilege policy exist and are proven; nothing in the application has changed yet. **Next (day 2): P2.S2.2** — AWS SDK for Java v2, `DocumentStorage` gains presigned-URL methods, `S3DocumentStorage`, and the API hands out presigned PUT/GET URLs from the laptop.

### Day 2 (2026-09-27, verification run 2026-09-28 morning) — the code: P2.S2.2

```
 documents.storage.type=local (default)            documents.storage.type=s3
 ───────────────────────────────────────            ──────────────────────────────────────────────
 LocalFileSystemStorage                             S3DocumentStorage ── S3Client + S3Presigner
   presignedUploadUrl   → empty                       presignedUploadUrl   → https://<bucket>.s3…?X-Amz-…
   → controller falls back to the API's              → client PUTs to S3 directly; Content-Type is
     own PUT /documents/{id}/content                    a signed header; the API never sees bytes
 30 tests unchanged, green                          run config "AwsDocumentApiApplication (S3)"
```

**P2.S2.2 — Implement `S3DocumentStorage` and generate presigned URLs** — *Claude* (SDK, interface, adapter, wiring, ADR) · *Sandeep* (run configuration, the three decisions, `exists()`/`delete()`, the verification run, explain-it-back)
Exam topic: D1 Task 1.2 · `EX-1.2-K04` secure application access (presigned URLs) · `EX-1.2-K01` credentials security (default credentials provider chain) · D3 Task 3.1 · `EX-3.1-S01` direct client-to-S3 transfer · D2 · `EX-2.1-K04` stateless workloads · D4 · `EX-4.1-S01` individual vs multipart uploads.

- **SDK** (*Claude*): `software.amazon.awssdk:bom` **2.55.6** (looked up on Maven Central, not copied from a tutorial) in `dependencyManagement`; modules `s3`, `sso`, `ssooidc` (the last two read the Identity Center profile on the laptop; EC2's instance role needs neither). Dependency tree checked: the SDK brings only its shaded `third-party-jackson-core`; Boot's Jackson 3 (`tools.jackson`) stays the single `jackson-databind`.
- **Interface** (*Claude*): `DocumentStorage` gains `Optional<URI> presignedUploadUrl(key, contentType)` and `Optional<URI> presignedDownloadUrl(key)`. The local adapter returns empty and the controller falls back to the API's own `/content` endpoints — "a storage that can presign returns a URL; otherwise the API serves the bytes". The TTL is the adapter's own configuration, so callers never know S3 exists.
- **Adapter** (*Claude* + *Sandeep*): `S3DocumentStorage` behind `@ConditionalOnProperty(documents.storage.type=s3)`; `S3StorageProperties` (bucket, prefix `documents/`, TTL `PT15M`); `S3Config` creating `S3Client.create()` and `S3Presigner.create()` — **no credential, no Region in code**. `store` spools to a temp file (S3 needs a length; real uploads never pass through it); `load` wraps the response stream with S3's reported length. **Sandeep wrote `delete()`** (`DeleteObjectRequest`, idempotent, adds a delete marker on a versioned bucket) **and `exists()`** (`HeadObjectRequest`, `false` only on `NoSuchKeyException` — an `AccessDenied` must propagate, never masquerade as "not found"). Selection by **property, not profile**, so the plain local run and the test suite are untouched.
- **Decisions** (*Sandeep*, ADR 13): **A** the adapter prepends `documents/`, database rows unchanged; **B** 15-minute URLs, overridable by `DOCAPI_PRESIGN_TTL`; **C** presigned **PUT** (fits the contract, one SDK call) — accepting that only presigned **POST** can make S3 enforce a size limit (`content-length-range`), so size is enforced after the fact by the confirm endpoint / event consumer.
- **A leftover found and fixed** (*Claude*): the first `verify` failed on a repository test because a `proposal.txt` row from the Sept 14 manual walkthrough was still in the shared local database. Row and orphan file removed; the repository test now uses a unique owner per run, as the end-to-end test already did (ADR 8's trade-off, felt).
- **Verification run** (*Sandeep*, via `scripts/s3-smoke.sh`, which prints the expectation before each check):

| # | Check | Result |
|---|---|---|
| 0 | `POST /documents` | `201`; upload URL on the bucket host, `X-Amz-Expires=900`, `SignedHeaders=content-type;host`, and an `X-Amz-Security-Token` — the signer's credentials are temporary (SSO) |
| 2 | `PUT` bytes to the presigned URL | `200` — the API was never involved |
| 3 | Listing under `documents/` | the new object present |
| 4 | `head-object` | `aws:kms`, the lab key, a `VersionId` |
| 5 | **The gap** | `["PENDING_UPLOAD", null]` — bytes in S3, the API does not know; closed tomorrow (S2.8) |
| 6 | Same URL, `Content-Type: image/png` | `403 SignatureDoesNotMatch` — the signed header pins the declared type |
| 7 | Same URL over plain `http://` | **`400 InvalidArgument`**, not the expected `403` — see below |
| 8 | Expired URL (`DOCAPI_PRESIGN_TTL=PT10S`, PUT after 15 s) | `403` — the request has expired; a URL is worthless after its TTL even with a valid signature |
| 9 | `DELETE /documents/{id}` (Sandeep's `delete()`) | `204`; the key gone from the listing, a **delete marker** left behind |

- **Check 7, investigated** (*Claude*): two independent gates refuse plain HTTP, and the order decides the code. A plain-HTTP **GET** of any object in the bucket → `403 AccessDenied` *"explicit deny in a resource-based policy"* — the Day-1 bucket policy, working. A plain-HTTP **PUT** that will be SSE-KMS encrypted → `400 InvalidArgument` *"Requests specifying Server Side Encryption with AWS KMS managed keys must be made over a secure connection"* — S3's own rule, checked during request validation, **before** authorization. Proof: the same PUT with explicit `--sse AES256` (no KMS) → `403` from the bucket policy. Lesson: the TLS-only bucket policy is belt-and-braces on top of a KMS-specific rule S3 enforces itself; a different status code is not a weaker refusal.
- **Explain-it-back** (*Sandeep*, all three essentially right; notes recorded): (1) credentials come from the **default credentials provider chain** — `AWS_PROFILE` only *names* the profile; the profile's SSO session token (cached by `aws sso login`) is exchanged for short-lived role credentials, which is why the URL carries a security token and dies with the session; on EC2 the chain ends at the **instance profile** via IMDS. (2) A presigned URL does **not** bypass IAM: it runs as the signer, inside all four gates, and only until it expires. (3) The API never sees the bytes, so it cannot enforce a size limit; only a presigned **POST** policy's `content-length-range` makes S3 do it.

**Day 2 exit state:** the API on the laptop hands out presigned S3 URLs; bytes never touch it; 30 tests green with the default local adapter; the upload-confirmation gap is visible and deliberate. Bucket left with only `documents/hello.txt` (smoke-test object and its delete marker removed). **Next (day 3): P2.S2.8** temporary confirm endpoint, **P2.S2.3** presign unit test + opt-in integration test + `.http` walkthrough against S3.

### Day 3 (2026-09-29) — close the gap, test without Docker: P2.S2.8 + P2.S2.3

```
 Client ──1 POST /documents ──────▶ API ── row PENDING_UPLOAD
   │──2 PUT bytes ──────────────────────────▶ S3 (presigned)
   │──3 POST /documents/{id}/uploaded ─▶ API ── sizeOf() = HeadObject ──▶ S3
   │                                         none → 409 · > 10 MB → delete + FAILED · else UPLOADED
   │◀── 200 + downloadUrl ─────────────┘     (the client decides WHEN; storage decides WHAT)
```

**P2.S2.8-REF — Temporary confirm endpoint** — *Claude* (interface, service, controller, errors, `.http`) · *Sandeep* (`sizeOf()` on S3, size decision, walkthroughs, oversized-upload demo)
Exam topic: D2 Task 2.1 · `EX-2.1-K05` event-driven architectures · `EX-2.1-S03` loose coupling — "does not trust the client".
- `DocumentStorage.sizeOf(key)` → `Optional<Long>`. Local: the file's length. **S3: written by Sandeep** — one `HeadObject`, `contentLength()` on success, empty only on `NoSuchKeyException`, anything else propagates (so an `AccessDenied` is never reported as "not uploaded yet"). Its Javadoc's claim that `HeadObject` needs no `kms:Decrypt` is to be verified in Phase 5.
- `POST /documents/{id}/uploaded`: owner-scoped; state must be `PENDING_UPLOAD`/`UPLOADED` else `409`; no bytes → `409` (`UploadNotFoundException`); size > `documents.max-file-size` (**10 MB**) → delete + `markRejected` (`FAILED`, file size cleared, no download URL); else `markUploaded(size)` with *storage's* size.
- Local `PUT /content` enforces the same limit → `413`. Confirm is idempotent locally, so **one client flow** serves both adapters.
- `http/documents.http` rewritten for both storages (environments `local`, `s3`), with a "confirm before upload → 409" check.

**P2.S2.3 — Test the S3 adapter without Docker** — *Claude* (tests) · *Sandeep* (ran them)
Exam topic: D1 Task 1.2 · `EX-1.2-K01` credentials security (no real credential in any test) · "an emulator proves wiring, not AWS behaviour".
- `S3DocumentStorageTest` (offline, fake static credentials passed explicitly): bucket host, `documents/` prefix, `X-Amz-Expires=900`, `content-type` signed on uploads, only `host` signed on downloads, TTL from configuration.
- `DocumentServiceTest` +4 confirm cases (success, no bytes, oversized, wrong state); end-to-end test gains the confirm step and a confirm-before-upload `409`.
- `S3DocumentStorageIntegrationTest` (real bucket, `test/<run-id>/`, deletes every version and marker; `@EnabledIfEnvironmentVariable DOCAPI_S3_IT=true`).

| # | Run | Result |
|---|---|---|
| 1 | `./mvnw verify` | 38 tests green, integration test **skipped** |
| 2 | `DOCAPI_S3_IT=true ./mvnw verify` | green; integration test `Tests run: 1, Skipped: 0` against the real bucket |
| 3 | versions under `test/` | none — the test cleaned up after itself |
| 4 | after `aws sso logout` | integration test fails on credentials — what a test with no AWS access looks like |
| 7a | `.http` Run All, env `s3` | all green; upload/download via presigned S3 URLs |
| 7b | `.http` Run All, env `local` | all green; same file, URLs point at the API |
| 7c | 11,000,000-byte upload | S3 `200` → confirm `FAILED` "file exceeds 10485760 bytes" → listing shows a **noncurrent 11 MB version under a delete marker** |

- **Gotchas met:** a command copied from a rendered Markdown preview carried curly quotes, which Bash doesn't treat as quotes (`Unknown token “`) — copy from the source view; the native Windows `aws.exe` cannot read Git Bash's `/tmp` in `file://` paths.
- **Every S3 delete leaves residue on a versioned bucket:** the six `.http` runs left six noncurrent 67-byte versions plus delete markers — the same shape as the rejected 11 MB file, and exactly what tomorrow's lifecycle rule expires automatically.

**Explain-it-back** (*Sandeep*): (1) excellent — verifies existence and size via `HeadObject`; a client can still never confirm, upload big and never confirm, **overwrite after confirming within the URL's TTL** (only Phase 9's events close this), send mislabelled bytes, leak the URL, or confirm too early (`409`, retry). (2) *answered by Claude*: skipped by default because it needs a live SSO session, the network and a real bucket, while the default build must run offline anywhere; LocalStack would let it run every build but proves only wiring — never IAM, the bucket policy, SSE-KMS's TLS rule, real presign expiry or event-destination validation. (3) correct — the delete is a marker; the bytes live on as a noncurrent version until the lifecycle rule's `NoncurrentVersionExpiration`.

**Close-out** — *Claude*: bucket back to `documents/hello.txt` (14 versions and markers removed with one `delete-objects`), local database emptied; ADR 14; README (confirm endpoint row, how to run the S3 tests).

**Day 3 exit state:** the gap is closed (temporarily, verified not trusted); size enforced after the fact and its cost made visible; S3 adapter tested three ways with no Docker. **Next (day 4): P2.S2.5** lifecycle + storage classes, **P2.S2.6** conditional write, **P2.S2.7** S3 → SQS event proof, then the Phase 2 checkpoint.

### Day 4 (2026-09-30) — the S3 feature tour: P2.S2.5, P2.S2.6, P2.S2.7 + Phase 2 checkpoint

```
 S2.5  documents/…  ──30 d──▶ STANDARD_IA ──90 d──▶ GLACIER_IR       noncurrent ──7 d──▶ gone; lone markers removed
 S2.6  PUT If-None-Match: *   first ──▶ 200 + version      second ──▶ 412 PreconditionFailed (still one version)
 S2.7  PUT documents/x ──▶ S3 ──ObjectCreated──▶ SQS ──▶ {"event":"ObjectCreated:Put","key":"documents/event-demo.txt"}
                             ▲ fails ("Unable to validate…") until the QUEUE's policy lets s3.amazonaws.com send
```

**P2.S2.5 — Lifecycle rule and storage classes** — *Sandeep* (wrote and applied the rule, the storage-class demo, the decision) · *Claude* (review; suggested the delete-marker cleanup)
Exam topic: D4 Task 4.1 · `EX-4.1-K07/K09/K10` lifecycles, access patterns, tiering · `EX-4.1-S05/S08/S09/S10` · D1 `EX-1.3-K03` retention.
- `infra/s3/lifecycle.json`, rule `documents-tiering-and-hygiene` on `documents/`: Standard-IA at 30 d, Glacier IR at 90 d, `NoncurrentVersionExpiration` 7 d, `AbortIncompleteMultipartUpload` 3 d, plus `ExpiredObjectDeleteMarker: true` so the delete markers left behind (Day 3's residue shape) are removed too.
- Read-back showed a field nobody wrote: **`TransitionDefaultMinimumObjectSize: all_storage_classes_128K`** — objects under 128 KB are never transitioned (cheaper to leave them). Live rule verified byte-for-byte equal to the repo file.
- Demo: an object put straight into `STANDARD_IA` by hand (`head-object` → `STANDARD_IA`, still `aws:kms`) — allowed, but billed as 128 KB for 30 days.
- Sandeep's decision, recorded in ADR 15: 30 d because documents are read right after upload (and 30 d is S3's minimum before an IA transition); Standard-IA then Glacier IR because both return in **milliseconds** while storage gets cheaper; 7 d for noncurrent versions to stop paying for superseded data (and as the recovery window); never Deep Archive — hours to restore.

**P2.S2.6 — Conditional write and overwrite policy** — *Sandeep*
Exam topic: D2 `EX-2.2-K05` idempotency / optimistic concurrency · D3 `EX-3.1-K02` S3 strong consistency.
- `put-object --if-none-match "*"` twice: **first → version `_QH5…`; second → `412 PreconditionFailed`; one version stored.** Prediction correct. *Versioning records a concurrent overwrite; a precondition prevents it.*
- Policy (ADR 15): the API does not use `If-None-Match` — re-uploads are intended and recoverable for 7 days. Side notes: uploads now carry a default `CRC64NVME` integrity checksum; an SSE-KMS object's ETag is not its MD5.

**P2.S2.7 — S3 event delivery proved, then torn down** — *Sandeep* (queue, notification, **wrote the queue policy**, read the event, teardown) · *Claude* (templates, explanation)
Exam topic: D2 Task 2.1 · `EX-2.1-K05/K11` event-driven, queuing · `EX-1.1-S05` resource policies with a service principal · D3 `EX-3.5-K02` ingestion.
- First attempt, deliberately without a queue policy → **`InvalidArgument — Unable to validate the following destination configurations`**: S3 test-delivers when the configuration is saved, the queue refused, nothing was saved.
- `infra/sqs/s3-events-queue-policy.json`: `Principal {"Service":"s3.amazonaws.com"}`, only `sqs:SendMessage`, conditions `aws:SourceArn` = the bucket and `aws:SourceAccount` = the account (confused-deputy guard). Attached via `jq -c '{Policy: tojson}'` (SQS wants the policy as an escaped string).
- Second attempt saved; an upload produced `{"event":"ObjectCreated:Put","key":"documents/event-demo.txt","size":4}`. The key **includes `documents/`** — Phase 9's consumer strips it (ADR 13 A). The `s3:TestEvent` stayed in the queue (standard queues return a sample per receive).
- Teardown: notification configuration set to `{}` *before* deleting the queue; queue deleted; both verified empty.

**Phase 2 exam checkpoint** — *Sandeep* (answers) · *Claude* (marking)
- Strong: storage-service choice (S3/EFS/EBS/FSx/Storage Gateway), the four gates, encryption options, the storage-class map, versioning vs Object Lock vs replication.
- Corrected: the bonus meant check 7's `400` (S3's own SSE-KMS-requires-TLS validation, before any gate), not today's notification error; how to tell the gates apart from the `AccessDenied` message text; a presigned PUT also pins its **signed headers** and has no size condition at all.
- To revisit: **event destinations** — pick by requirement (SQS buffer, SNS fan-out, Lambda code, EventBridge routing/replay), principal written in full, and EventBridge's targets still need their own policies; **the ten recognize-it cards** — one cue per card (table kept in the chat notes; reread the Final Document's Phase 2 checkpoint).

**Close-out** — *Claude*: demo objects removed (all versions); bucket back to `documents/hello.txt`, now with bucket policy **and** lifecycle rule; no queue, no notification; ADR 15; cleanup ledger (lifecycle row; queue row created-and-deleted); README lifecycle row.

### ✅ Phase 2 closed (2026-09-30)

| Exit criterion | Evidence |
|---|---|
| Private, SSE-KMS-encrypted, versioned bucket with Block Public Access | Day 1 verification |
| `S3DocumentStorage` behind the existing interface; tests green with the local adapter | Day 2; 38 tests, integration test opt-in |
| Presigned PUT/GET; full walkthrough against the real bucket | Day 2 smoke script, Day 3 `.http` (`s3` env) |
| Upload-confirmation gap closed by a temporary endpoint | Day 3, `POST /documents/{id}/uploaded` (verify, don't trust) |
| Least-privilege policy verified in the simulator | Day 1, 9 CLI simulations + UI |
| Lifecycle rule with transitions; storage-class decision written | Day 4, ADR 15 |
| One `ObjectCreated` event observed in a queue | Day 4 |
| `docs/cleanup.md` rows added | 6 Phase 2 rows |

**Carried forward:** reap unconfirmed uploads and replace the confirm endpoint with events (Phase 9); verify `HeadObject` needs no `kms:Decrypt` (Phase 5); attach `DocumentApiS3Access` to the instance role (Phase 4). **Next: Phase 3 — metadata to Amazon RDS for PostgreSQL.**
