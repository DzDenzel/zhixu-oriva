-- Stable custom keys include a UUID; retain every existing value while widening capacity.
ALTER TABLE plans MODIFY COLUMN type VARCHAR(64) NOT NULL COMMENT '预置类型或账号自定义类型稳定键';

CREATE TABLE plan_types (
    user_id BIGINT NOT NULL,
    type_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    name VARCHAR(24) NOT NULL,
    color VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    mutation_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (user_id,type_key),
    CONSTRAINT fk_plan_types_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
