-- Results of asynchronous processing. Both stay null until a document has been processed.
ALTER TABLE documents
    ADD COLUMN checksum_sha256 VARCHAR(64),
    ADD COLUMN failure_reason  VARCHAR(1000);
