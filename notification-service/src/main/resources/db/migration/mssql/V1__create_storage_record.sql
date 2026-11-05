-- Latin1_General_BIN2, not the server default: SQL Server's default collation is case-INSENSITIVE,
-- which would silently merge two storage ids differing only in case.
-- NVARCHAR(450) is 900 bytes, exactly SQL Server's ceiling for a clustered index key - and a
-- PRIMARY KEY is clustered by default. This is what fixes 450 as the id limit on every engine.
CREATE TABLE storage_record (
    id             NVARCHAR(450) COLLATE Latin1_General_BIN2 NOT NULL PRIMARY KEY,
    payload        VARBINARY(MAX) NOT NULL,
    -- Nullable so Oracle's empty-string-is-NULL folding stays representable on every engine.
    dek            VARBINARY(MAX),
    kek_id         NVARCHAR(255),
    trans_verifier INT            NOT NULL,
    -- Epoch milliseconds; see the Postgres V2 migration for why this is not a timestamp.
    expires_at     BIGINT
);

CREATE INDEX storage_record_expires_at_idx ON storage_record (expires_at);
