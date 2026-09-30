# Cleanup ledger

Every AWS resource this project creates gets a row **when it is created** — no resource without a row.
Rows with *Idle cost? = yes* get a `Delete by` that is the end of their phase unless the next phase
needs them. The master teardown runs on **2026-10-22**; billing is verified on **2026-10-24**.
Phase C sorts this table into dependency order and adds a monthly rate to every row.

Rules that keep the bill at zero (High-Level document §4): no NAT Gateway, ever · stop RDS when idle ·
delete the ALB and interface endpoints when unused for more than two days · budgets at $10 and $25 ·
a versioned bucket is *not* empty until all versions and delete markers are gone.

| Phase | Resource | Region | Created by | Idle cost? | Teardown (command or console path) | Delete by |
|-------|----------|--------|------------|------------|-------------------------------------|-----------|
| 01 | IAM Identity Center instance, user, `AdministratorAccess` permission set (creates a one-account Organization) | us-east-1 | console | no | keep — the administrative path; never a teardown step | never |
| 01 | Budgets `docapi-lab-monthly-10usd` and `docapi-lab-monthly-25usd` (monthly cost; 100% actual + 100% forecasted; email) | global | cli | no | keep while the account is open (`aws budgets delete-budget --account-id 234178676885 --budget-name <name>`) | never |
| 01 | Cost Explorer (enabled) and Cost Anomaly Detection monitor | global | console | no | keep while the account is open | never |
| 01 | CloudTrail trail `docapi-lab-trail` (multi-Region, management events read+write, log-file validation, no data events) | all (home us-east-1) | cli | no (S3 ¢) | `aws cloudtrail stop-logging --name docapi-lab-trail` → `aws cloudtrail delete-trail --name docapi-lab-trail` | Phase 15 (keep if the account stays open) |
| 01 | Audit bucket `docapi-lab-audit-ae9c73ce` (Block Public Access, SSE-S3, TLS-only; prefixes `cloudtrail/`, `config/`; **not versioned**) | us-east-1 | cli | ¢ | after trail + Config are deleted: `aws s3 rm s3://docapi-lab-audit-ae9c73ce --recursive` → `aws s3api delete-bucket --bucket docapi-lab-audit-ae9c73ce` | Phase 15 |
| 01 | AWS Config service-linked role `AWSServiceRoleForConfig` | global | cli | no | deleted by AWS after the recorder is gone (`aws iam delete-service-linked-role --role-name AWSServiceRoleForConfig`) | Phase 15 |
| 01 | AWS Config recorder `default` (all supported types + global, continuous) and delivery channel `default` | us-east-1 | cli | ¢ per configuration item — **grows when VPC resources churn (Phase 7+)** | `aws configservice stop-configuration-recorder --configuration-recorder-name default` → `delete-delivery-channel --delivery-channel-name default` → `delete-configuration-recorder --configuration-recorder-name default` | Phase 15 |
| 01 | Config rules `root-account-mfa-enabled`, `cloudtrail-enabled`, `s3-bucket-public-read-prohibited` | us-east-1 | cli | ¢ per evaluation | `aws configservice delete-config-rule --config-rule-name <name>` (before the recorder) | Phase 15 |
| 02 | KMS customer-managed key `alias/document-api` (`07a7a487-ef46-43cd-a0e5-d636c19e40e5`), rotation on | us-east-1 | console | **yes — ~$1/month** + per-request | **after the bucket is deleted:** `aws kms schedule-key-deletion --key-id 07a7a487-ef46-43cd-a0e5-d636c19e40e5 --pending-window-in-days 7` (objects become unreadable the moment the key is disabled) | Phase 15 |
| 02 | Document bucket `docapi-documents-7fb3fd47` (Block Public Access, versioned, SSE-KMS + Bucket Key, prefix `documents/`) | us-east-1 | console | ¢ (storage + requests) | **versioned — delete all object versions AND delete markers, abort multipart uploads, then** `aws s3api delete-bucket --bucket docapi-documents-7fb3fd47` | Phase 15 |
| 02 | IAM customer-managed policy `DocumentApiS3Access` (`arn:aws:iam::234178676885:policy/DocumentApiS3Access`; `documents/*` object actions + `kms:GenerateDataKey`/`Decrypt` on the key) | global | cli (Sandeep) | no | after the roles that use it (Phases 4, 10) are gone: `aws iam delete-policy --policy-arn arn:aws:iam::234178676885:policy/DocumentApiS3Access` | Phase 15 |
| 02 | Bucket policy `DenyInsecureTransport` on `docapi-documents-7fb3fd47` (`infra/s3/bucket-policy.json`) | us-east-1 | cli (Sandeep) | no | removed with the bucket | Phase 15 |
| 02 | Lifecycle rule `documents-tiering-and-hygiene` on the bucket (`infra/s3/lifecycle.json`) | us-east-1 | cli (Sandeep) | no — it *reduces* cost | removed with the bucket (`aws s3api delete-bucket-lifecycle --bucket docapi-documents-7fb3fd47` if needed earlier) | Phase 15 |
| 02 | ~~SQS queue `docapi-s3-events-test` + bucket notification configuration~~ — created and **deleted the same day** (2026-09-30, P2.S2.7 proof) | us-east-1 | cli (Sandeep) | — | done: notification set to `{}`, queue deleted | ✅ 2026-09-30 |
