-- Oracle compares VARCHAR2 case-sensitively by default, so no explicit collation is needed here.
-- 450 CHAR matches the ceiling SQL Server imposes, keeping ids interchangeable across engines.
CREATE TABLE storage_record (
    id             VARCHAR2(450 CHAR) NOT NULL PRIMARY KEY,
    payload        BLOB               NOT NULL,
    -- Oracle folds the empty string to NULL, and NoOpPayloadSealer writes an empty kek_id and dek
    -- on every record; NOT NULL here would reject every insert. The DAO maps NULL back to empty.
    dek            BLOB,
    kek_id         VARCHAR2(255 CHAR),
    trans_verifier NUMBER(10)         NOT NULL,
    -- Epoch milliseconds; see the Postgres V2 migration for why this is not a timestamp.
    expires_at     NUMBER(19)
);

CREATE INDEX storage_record_expires_at_idx ON storage_record (expires_at);
