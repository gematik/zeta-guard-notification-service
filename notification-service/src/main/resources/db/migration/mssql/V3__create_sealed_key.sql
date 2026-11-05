CREATE TABLE sealed_key (
    key_id      NVARCHAR(255) COLLATE Latin1_General_BIN2 NOT NULL PRIMARY KEY,
    wrapped_key VARBINARY(MAX) NOT NULL,
    kek_id      NVARCHAR(255)  NOT NULL,
    created_at  BIGINT         NOT NULL
);
