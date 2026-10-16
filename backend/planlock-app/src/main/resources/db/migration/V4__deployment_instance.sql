-- Singleton row serializes initial owner creation and persists identity across restarts/backups.
CREATE TABLE deployment_instance (
    id TINYINT NOT NULL PRIMARY KEY,
    instance_id CHAR(36) NOT NULL,
    initialized BOOLEAN NOT NULL DEFAULT FALSE,
    instance_label VARCHAR(80) NULL,
    owner_user_id BIGINT NULL,
    initialized_at DATETIME NULL,
    CONSTRAINT ck_deployment_instance_singleton CHECK (id = 1),
    CONSTRAINT fk_deployment_instance_owner FOREIGN KEY (owner_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO deployment_instance (id, instance_id, initialized) VALUES (1, UUID(), FALSE);
