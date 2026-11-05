-- The pseudonymization keys live in the keychain now (notification.persistence.sealing.keychain).
DROP TABLE sealed_key;

-- kek_id names the keychain entry a record was sealed under, not a KEK. dek has stayed empty since
-- the keychain came in: a sealed payload carries the salt its record key is derived with instead.
EXEC sp_rename 'storage_record.kek_id', 'keychain_id', 'COLUMN';
ALTER TABLE storage_record DROP COLUMN dek;
