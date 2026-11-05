CREATE TABLE storage_record (
    id             TEXT        NOT NULL PRIMARY KEY,
    payload        BYTEA       NOT NULL,
    dek            BYTEA       NOT NULL,
    kek_id         TEXT        NOT NULL,
    trans_verifier INTEGER     NOT NULL,
    expires_at     TIMESTAMPTZ
);

CREATE INDEX storage_record_expires_at_idx
    ON storage_record (expires_at) WHERE expires_at IS NOT NULL;

CREATE FUNCTION purge_expired_storage_records() RETURNS bigint
    LANGUAGE plpgsql AS $fn$
DECLARE
    removed bigint;
BEGIN
    IF NOT pg_try_advisory_xact_lock(hashtext('storage_record_purge')) THEN
        RETURN 0;
    END IF;

    DELETE FROM storage_record WHERE expires_at <= now();
    GET DIAGNOSTICS removed = ROW_COUNT;
    RETURN removed;
END
$fn$;
