CREATE TABLE external_credentials (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id         BIGINT       NOT NULL,
  provider        VARCHAR(64)  NOT NULL,
  credential_data JSON         NOT NULL,
  expires_at      DATETIME(3)  NULL,
  -- scopes is intentionally stored in plaintext: OAuth scopes are not secret and are needed
  -- for display in the UI (e.g. showing what access has been granted) without decryption.
  scopes          TEXT         NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_user_provider (user_id, provider),
  CONSTRAINT fk_extcred_user FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
);
