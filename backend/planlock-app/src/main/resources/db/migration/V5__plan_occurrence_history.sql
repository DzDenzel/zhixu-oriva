-- CHANGE-057 / BUG-101: plan snapshot carries occurrence suppression and status history.
ALTER TABLE plans
  ADD COLUMN ended_occurrence_date DATE NULL COMMENT '已提前结束的发生次日期（用户时区）',
  ADD COLUMN status_log JSON NULL COMMENT '启停日志：最多50条date/status（用户时区）';
