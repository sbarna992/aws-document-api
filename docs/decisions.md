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
