-- REQ-039 & ADR-021 D2: 日常记录（小日记）主表，支持 clientId 幂等与乐观锁版本控制。
CREATE TABLE journal_entries (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '记录主键',
    user_id    BIGINT      NOT NULL COMMENT '所属用户',
    client_id  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL COMMENT '客户端稳定创建身份',
    entry_date DATE        NOT NULL COMMENT '记录日期（yyyy-MM-dd）',
    mood       VARCHAR(24) NULL COMMENT '心情标签（可选，≤24字符）',
    tags       JSON        NULL COMMENT '标签列表 JSON 数组（≤8项，每项≤20字符）',
    content    TEXT        NOT NULL COMMENT '日记正文（1~10000字符）',
    status     TINYINT     NOT NULL DEFAULT 1 COMMENT '1=正常, 0=软删',
    version    BIGINT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '服务端创建时间',
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '服务端更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_journal_user_client (user_id, client_id),
    KEY idx_journal_user_date (user_id, status, entry_date, id),
    CONSTRAINT fk_journal_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='日常记录';
