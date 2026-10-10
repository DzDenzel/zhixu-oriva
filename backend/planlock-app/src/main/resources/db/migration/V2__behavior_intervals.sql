-- REQ-018: 兼容性增列，不删除 v1 字段；已存在列时由部署脚本按实际数据库跳过。
ALTER TABLE violation_records
  ADD COLUMN started_at DATETIME NULL COMMENT '进入前台时刻（v2，可空）' AFTER package_name,
  ADD COLUMN ended_at DATETIME NULL COMMENT '离开前台时刻（v2，可空）' AFTER started_at,
  ADD COLUMN duration_ms BIGINT NULL COMMENT '前台使用时长毫秒（v2，可空）' AFTER ended_at,
  ADD COLUMN source_level VARCHAR(32) NULL COMMENT '观测来源等级（v2，可空）' AFTER duration_ms;
