-- 本文件 = 冻结的 v0.1 结构；violation_records 的四个 v2 列由
-- V2__behavior_intervals.sql 添加，不得在此补齐。
-- 仅对 JDBC URL 指定的 schema 迁移；建库由部署 runbook 承担。

CREATE TABLE IF NOT EXISTS users (
  id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户 ID',
  phone                VARCHAR(20)  NULL COMMENT '手机号（可空）',
  email                VARCHAR(100) NULL COMMENT '邮箱（可空）',
  password_hash        VARCHAR(128) NULL COMMENT '登录密码哈希（可空，验证码登录可无密码）',
  backup_password_hash VARCHAR(128) NULL COMMENT '备用密码哈希（低复杂度）',
  security_question    VARCHAR(200) NULL COMMENT '密保问题（找回备用密码用）',
  security_answer_hash VARCHAR(128) NULL COMMENT '密保答案哈希（找回备用密码用）',
  created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '服务端创建时间',
  updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_users_phone (phone),
  KEY idx_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户';

CREATE TABLE IF NOT EXISTS devices (
  id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '设备 ID',
  user_id        BIGINT      NOT NULL COMMENT '所属用户（游客设备绑定匿名用户）',
  device_name    VARCHAR(50) NULL COMMENT '设备名',
  platform       VARCHAR(20) NOT NULL COMMENT 'android / ios / windows',
  device_uid     VARCHAR(100) NOT NULL COMMENT '设备唯一标识',
  is_primary     TINYINT     NOT NULL DEFAULT 0 COMMENT '是否主设备',
  last_online_at DATETIME    NULL COMMENT '最近在线时间',
  created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_devices_device_uid (device_uid),
  KEY idx_devices_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备';

CREATE TABLE IF NOT EXISTS plans (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  user_id            BIGINT       NOT NULL COMMENT '所属用户',
  name               VARCHAR(100) NOT NULL COMMENT '计划名',
  type               VARCHAR(20)  NOT NULL COMMENT '学习/运动/睡眠/工作/自定义',
  start_time         TIME         NOT NULL COMMENT '开始时间',
  end_time           TIME         NOT NULL COMMENT '结束时间（end < start 即跨天计划）',
  repeat_rule        JSON         NULL COMMENT '重复规则：每天/指定星期/单次/自定义日期',
  color              VARCHAR(20)  NULL COMMENT '颜色',
  icon               VARCHAR(20)  NULL COMMENT '图标',
  remind_advance_min INT          NOT NULL DEFAULT 15 COMMENT '提醒提前量（分钟）',
  remind_methods     JSON         NULL COMMENT '提醒方式：通知/声音/多端/多次',
  status             TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 暂停',
  version            BIGINT       NOT NULL DEFAULT 0 COMMENT '同步版本号（冲突检测）',
  created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_plans_user_updated (user_id, updated_at),
  KEY idx_plans_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计划';

CREATE TABLE IF NOT EXISTS schedules (
  id                   BIGINT       NOT NULL AUTO_INCREMENT,
  user_id              BIGINT       NOT NULL COMMENT '所属用户',
  title                VARCHAR(100) NOT NULL COMMENT '日程标题',
  start_date           DATE         NOT NULL COMMENT '本地开始日期',
  start_time           TIME         NULL COMMENT '本地墙上开始时间；全天为空',
  end_date             DATE         NOT NULL COMMENT '本地结束日期',
  end_time             TIME         NULL COMMENT '本地墙上结束时间（开区间）；全天为空',
  all_day              TINYINT      NOT NULL DEFAULT 0 COMMENT '全天日程；日期区间为闭区间',
  location             VARCHAR(200) NULL COMMENT '地点',
  note                 VARCHAR(1000) NULL COMMENT '备注',
  remind_advance_min   INT          NULL COMMENT '本地通知提前量（分钟）',
  repeat_rule          JSON         NULL COMMENT '复用计划规则词表',
  linked_plan_id       BIGINT       NULL COMMENT '可选关联计划，不传递执行语义',
  tz_id                VARCHAR(64)  NULL COMMENT '创建时设备时区，V0.1 不参与判定',
  status               TINYINT      NOT NULL DEFAULT 1,
  version              BIGINT       NOT NULL DEFAULT 0,
  created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_schedules_user_dates (user_id, start_date, end_date),
  KEY idx_schedules_linked_plan (linked_plan_id),
  CONSTRAINT fk_schedules_linked_plan FOREIGN KEY (linked_plan_id) REFERENCES plans(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='日程（独立聚合）';

CREATE TABLE IF NOT EXISTS plan_strategies (
  id              BIGINT   NOT NULL AUTO_INCREMENT,
  plan_id         BIGINT   NOT NULL COMMENT '关联计划（1:1）',
  blacklist_apps  JSON     NULL COMMENT '黑名单（包名/应用名）',
  whitelist_apps  JSON     NULL COMMENT '白名单',
  auto_dnd        TINYINT  NOT NULL DEFAULT 0 COMMENT '自动免打扰',
  notify_silence  TINYINT  NOT NULL DEFAULT 0 COMMENT '通知静默',
  version         BIGINT   NOT NULL DEFAULT 0 COMMENT '同步版本号',
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_plan_strategies_plan (plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计划执行策略';

CREATE TABLE IF NOT EXISTS execution_records (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  plan_id    BIGINT      NOT NULL COMMENT '所属计划',
  device_id  BIGINT      NOT NULL COMMENT '所属设备',
  plan_date  DATE        NOT NULL COMMENT '计划日期',
  status     TINYINT     NOT NULL COMMENT '1 完成 / 2 提前解锁 / 3 违规结束',
  started_at DATETIME    NULL COMMENT '开始时间',
  ended_at   DATETIME    NULL COMMENT '结束时间',
  client_id  VARCHAR(64) NOT NULL COMMENT '幂等去重（客户端生成）',
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_execution_client (client_id),
  KEY idx_execution_plan_date (plan_id, plan_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行记录';

CREATE TABLE IF NOT EXISTS violation_records (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  plan_id      BIGINT      NOT NULL COMMENT '所属计划',
  device_id    BIGINT      NOT NULL COMMENT '所属设备',
  record_at    DATETIME    NOT NULL COMMENT '违规时间',
  app_name     VARCHAR(100) NULL COMMENT '执行期使用的应用名',
  package_name VARCHAR(200) NULL COMMENT '应用包名',
  client_id    VARCHAR(64) NOT NULL COMMENT '幂等去重',
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_violation_client (client_id),
  KEY idx_violation_plan_at (plan_id, record_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行期其他应用使用区间（保留表名兼容 v1）';

CREATE TABLE IF NOT EXISTS unlock_records (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  plan_id    BIGINT      NOT NULL COMMENT '所属计划',
  device_id  BIGINT      NOT NULL COMMENT '所属设备',
  unlock_at  DATETIME    NOT NULL COMMENT '解锁时间',
  method     VARCHAR(20) NOT NULL DEFAULT 'backup_password' COMMENT '解锁方式',
  client_id  VARCHAR(64) NOT NULL COMMENT '幂等去重',
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_unlock_client (client_id),
  KEY idx_unlock_plan_at (plan_id, unlock_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='解锁记录';

