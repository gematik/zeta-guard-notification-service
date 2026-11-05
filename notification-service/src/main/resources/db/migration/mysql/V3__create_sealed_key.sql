CREATE TABLE sealed_key (
    key_id      VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    wrapped_key BLOB         NOT NULL,
    kek_id      VARCHAR(255) NOT NULL,
    created_at  BIGINT       NOT NULL,
    PRIMARY KEY (key_id)
);
