CREATE TABLE sealed_key (
    key_id      VARCHAR2(255 CHAR) NOT NULL PRIMARY KEY,
    wrapped_key BLOB               NOT NULL,
    kek_id      VARCHAR2(255 CHAR) NOT NULL,
    created_at  NUMBER(19)         NOT NULL
);
