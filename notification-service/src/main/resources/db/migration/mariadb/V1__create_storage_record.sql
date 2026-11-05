-- utf8mb4_bin, not the server default: MySQL's default collation is case-INSENSITIVE, which would
-- silently merge two storage ids differing only in case - and storage ids derive from user
-- identifiers. 450 characters of utf8mb4 is 1800 bytes, well inside InnoDB's 3072-byte index limit.
CREATE TABLE storage_record (
    id             VARCHAR(450) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    payload        LONGBLOB     NOT NULL,
    -- Nullable so Oracle's empty-string-is-NULL folding stays representable on every engine.
    dek            LONGBLOB,
    kek_id         VARCHAR(255),
    trans_verifier INT          NOT NULL,
    -- Epoch milliseconds; see the Postgres V2 migration for why this is not a timestamp.
    expires_at     BIGINT,
    PRIMARY KEY (id)
);

CREATE INDEX storage_record_expires_at_idx ON storage_record (expires_at);
