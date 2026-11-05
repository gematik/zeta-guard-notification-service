CREATE TABLE sealed_key (
    key_id      VARCHAR(255) NOT NULL PRIMARY KEY,
    wrapped_key BYTEA        NOT NULL,
    kek_id      VARCHAR(255) NOT NULL,
    created_at  BIGINT       NOT NULL
);
