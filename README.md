# Document API

A small Spring Boot service for storing documents and their metadata. It exists as a
**progressive AWS learning lab**: it is built and proven locally first, then replatformed to AWS one
architectural concern at a time (S3, RDS, EC2, IAM, CloudWatch, VPC, ALB, SQS/Lambda, CDK), with the
application staying recognisable throughout. Design choices and their trade-offs are recorded in
[`docs/decisions.md`](docs/decisions.md).

## Current architecture (local)

```
Client ──HTTP──▶ Spring Boot document-api
                   ├──▶ PostgreSQL          documents table (metadata, status, checksum)
                   └──▶ ./local-storage/    document bytes
```

Target architecture after replatforming:

```
Client ──▶ ALB ──▶ document-api on EC2 (IAM role)
                     ├──▶ RDS PostgreSQL (private subnet)
                     └──▶ S3 (private, encrypted, versioned) ──event──▶ SQS ──▶ Lambda worker
```

## API

Every request carries an `X-User-Id` header naming the caller. This is a deliberate stand-in for
real authentication; documents are always scoped to that owner.

| Method | Path | Purpose | Success |
|---|---|---|---|
| `POST` | `/documents` | Register a document; returns metadata and an **upload URL** | `201` + `Location` |
| `PUT` | `/documents/{id}/content` | Upload the raw bytes (the local upload URL) | `200` |
| `GET` | `/documents/{id}` | Metadata, plus a **download URL** once bytes exist | `200` |
| `GET` | `/documents/{id}/content` | Download the bytes (the local download URL) | `200` |
| `GET` | `/documents` | The caller's documents, newest first | `200` |
| `POST` | `/documents/{id}/process` | Start asynchronous processing (SHA-256 today) | `202` |
| `DELETE` | `/documents/{id}` | Delete metadata and bytes | `204` |
| `GET` | `/actuator/health` | Liveness, including the database | `200` |

Document lifecycle: `PENDING_UPLOAD → UPLOADED → PROCESSING → PROCESSED | FAILED`.

Errors are RFC 9457 problem details (`application/problem+json`): `400` for invalid input, `404` for
unknown *or someone else's* document, `409` for an operation the current status does not allow.

## Running locally

Prerequisites: JDK 25+ and PostgreSQL 17+ on `localhost:5432`.

1. Create the role and database once:
   ```sql
   CREATE ROLE docapi WITH LOGIN PASSWORD '<choose-a-password>';
   CREATE DATABASE documentdb OWNER docapi;
   ```
2. Start the API (the `local` profile is the default):
   ```
   .\mvnw spring-boot:run
   ```
   Flyway creates the schema on first start; document bytes land in `./local-storage/documents/`.
3. Check http://localhost:8080/actuator/health.

Configuration lives in `src/main/resources/application-local.properties`. The database password is
**not** in the file: set the `DOCAPI_DB_PASSWORD` environment variable (a user-level variable via
`setx DOCAPI_DB_PASSWORD <value>` is simplest on Windows; restart IntelliJ afterwards). A missing or wrong
value makes the application fail at startup by design.

### Trying it out

Open [`http/documents.http`](http/documents.http) in IntelliJ, select the `local` environment, and
run the requests top to bottom; they chain the document id and URLs automatically.

## Tests

```
.\mvnw verify
```

Tests run against the real local PostgreSQL (no Docker on the development machine). Repository tests
roll back; the end-to-end test uses a unique owner and a temporary storage directory and cleans up.

## Project layout

```
document/     entity, repository, service, controller, DTOs, domain exceptions
storage/      DocumentStorage abstraction + LocalFileSystemStorage (S3DocumentStorage later)
processing/   asynchronous worker triggered after commit (SQS + Lambda later)
web/          ApiExceptionHandler: every error as a problem detail
db/migration/ Flyway schema history (V1 documents table, V2 processing columns)
http/         IntelliJ HTTP client requests
docs/         architecture decisions
```

## What changes when moving to AWS

| Concern | Local | AWS |
|---|---|---|
| Bytes | `LocalFileSystemStorage` | `S3DocumentStorage`, same interface |
| Upload / download URLs | point back at this API | presigned S3 URLs |
| "Bytes have arrived" | `PUT …/content` flips status | S3 event notification |
| Metadata | local PostgreSQL | RDS PostgreSQL, same Flyway migrations |
| Configuration / secrets | properties + env vars | env vars → SSM Parameter Store / Secrets Manager |
| Async processing | in-process `@Async` listener | SQS queue + Lambda worker, DLQ |
| Identity | `X-User-Id` header | API Gateway / Cognito |
| Health | `/actuator/health` | ALB target group health check |

## AWS account (Phase 1)

| Item | Value |
|---|---|
| Region | `us-east-1` — everything lives here; ACM certificates for CloudFront must be here anyway |
| Account plan | **Pay-as-you-go, no credits, no free tier.** Verified 2026-09-14: Credits $0.00 / 0 active; not on the credits-based plan model; the account dates from **~December 2012** (a dormant root access key was 5015 days old), so the classic 12-month free tier expired in 2013. **Every resource bills at list price from the first hour.** Free regardless: IAM, Identity Center, STS, Organizations, Budgets, S3 gateway endpoint. Sizing follows from this — RDS `db.t4g.micro` (not `small`), EC2 `t4g.small` for JVM headroom, ALB and interface endpoints deleted whenever idle. Budget alerts and same-day teardown are the only guardrails. |
| Daily access | IAM Identity Center user with the `AdministratorAccess` permission set (4-hour sessions). Identity Center requires MFA at every sign-in. No IAM users exist. |
| Root protection | No root access keys. **Two MFA devices** — an authenticator app on the phone and a Windows Hello passkey on the development laptop — so losing either device alone does not lock the account. AWS issues **no recovery codes** for root MFA: if every device is lost, the only path back is the "Troubleshoot MFA" flow, which verifies by email to the root address **and** a phone call to the registered number. Contact information and a **security alternate contact** are set and must stay current. Root is used only for root-only tasks. |
| Account settings | **IAM user and role access to Billing information** is activated. This is a root-only setting; without it even `AdministratorAccess` cannot open Billing preferences or Cost Allocation Tags. Invoices are delivered by email. Cost Explorer enabled 2026-09-14. |
| CLI profile | `document-api` in `~/.aws/config` — SSO only, no static credentials anywhere. Sign in with `aws sso login --profile document-api`. Set `AWS_PROFILE=document-api` and `AWS_DEFAULT_REGION=us-east-1`. |
| Budgets | `docapi-lab-monthly-10usd` and `docapi-lab-monthly-25usd`, each emailing at 100% actual and 100% forecasted spend. Cost Anomaly Detection monitor: *pending Cost Explorer initialisation*. |
| Audit | CloudTrail `docapi-lab-trail` (multi-Region, management events, log-file validation) and AWS Config recorder `default` (all supported resources) with rules `root-account-mfa-enabled`, `cloudtrail-enabled`, `s3-bucket-public-read-prohibited` — all compliant. Both deliver to the private bucket `docapi-lab-audit-ae9c73ce`. |
| Cleanup ledger | [`docs/cleanup.md`](docs/cleanup.md) — one row per resource, teardown **2026-10-22** |

### Tags — every resource carries these

```
Project     = document-api-lab
Environment = dev
Owner       = sandeep
ManagedBy   = console | cli | cdk
Stage       = 01-account | 02-s3 | 03-rds | 04-ec2 | 05-identity | 06-observability | 07-network | 08-resilience | 09-async | 10-serverless | 11-data | 12-iac
DeleteAfter = 2026-10-22
```

`Project` and `Stage` are to be activated as cost allocation tags so Cost Explorer can group spend by
phase (*pending: they appear in Billing → Cost Allocation Tags once billing has processed tagged resources*).
Tag keys are case-sensitive. Untagged = missed on teardown day.
