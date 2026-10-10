-- REQ-031: nullable keys retain compatibility with legacy creations.
-- MySQL 8 NO PAD binary collation preserves exact opaque client identity, including trailing spaces; uniqueness is per owner.
ALTER TABLE plans
  ADD COLUMN client_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL COMMENT '客户端稳定创建身份',
  ADD UNIQUE KEY uk_plans_user_client (user_id, client_id);
ALTER TABLE schedules
  ADD COLUMN client_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL COMMENT '客户端稳定创建身份',
  ADD UNIQUE KEY uk_schedules_user_client (user_id, client_id);
