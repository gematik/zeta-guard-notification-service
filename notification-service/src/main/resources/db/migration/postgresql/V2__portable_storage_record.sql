-- Brings the Postgres schema onto the shape every supported engine can hold, so the DAO can issue
-- one vendor-neutral set of statements. V1 is left untouched: deployments upgrading from 1.3.0
-- still validate its checksum.
--
-- The id narrowing fails loudly if a deployed row already exceeds 450 characters. That is
-- deliberate - 450 is the hard ceiling for a clustered primary key on SQL Server (900 bytes of
-- NVARCHAR), so an id longer than that could never be stored on every engine anyway.
ALTER TABLE storage_record
    ALTER COLUMN id TYPE VARCHAR(450),
    ALTER COLUMN kek_id TYPE VARCHAR(255),
    -- Oracle folds the empty string to NULL, and NoOpPayloadSealer writes an empty kek_id and dek
    -- on every record. NOT NULL would therefore reject every insert there.
    ALTER COLUMN kek_id DROP NOT NULL,
    ALTER COLUMN dek DROP NOT NULL;

-- Epoch milliseconds rather than a timestamp: MySQL/MariaDB have no timezone-aware type, DATETIME
-- defaults to second precision, and every driver applies its own session-timezone conversion.
-- A BIGINT means the same thing everywhere, and makes the application the single clock authority.
ALTER TABLE storage_record
    ALTER COLUMN expires_at TYPE BIGINT
        USING (EXTRACT(EPOCH FROM expires_at) * 1000)::BIGINT;

-- The partial predicate is not portable (MySQL/MariaDB/Oracle have no partial indexes).
DROP INDEX storage_record_expires_at_idx;
CREATE INDEX storage_record_expires_at_idx ON storage_record (expires_at);

-- The scheduled purge no longer calls a stored function, and takes a lease through the ordinary
-- compare-and-set write instead of a Postgres advisory lock.
DROP FUNCTION IF EXISTS purge_expired_storage_records();
