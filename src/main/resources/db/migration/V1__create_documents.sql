CREATE TABLE documents (
    id                UUID         PRIMARY KEY,
    owner_id          VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(255) NOT NULL,
    file_size         BIGINT,                      -- null until the bytes have actually arrived
    storage_key       VARCHAR(512) NOT NULL UNIQUE, -- local path today, S3 object key tomorrow
    status            VARCHAR(32)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL
);

-- "List a user's documents, newest first" is the only query pattern we have.
CREATE INDEX idx_documents_owner_created ON documents (owner_id, created_at DESC);
