# CHANGE-057 / BUG-101 后端契约

## 计划快照

CRUD 计划响应、同步 pull 的 plans 与同步 push 的 PlanItem 均包含：

| 字段 | 类型 | 更新规则 |
|---|---|---|
| endedOccurrenceDate | ISO `yyyy-MM-dd` 字符串，可空 | 发生次开始所在的用户日期；省略或 null 保留旧值 |
| statusLog | JSON 字符串，可空 | `[{"date":"2026-10-16","status":0}]`；省略或 null 保留旧值，`"[]"` 清空日志 |

客户端使用已有 plan version/baseVersion 乐观冲突协议。发生次日期由客户端按用户时区确定。旧客户端编辑计划不会清除两个新字段。

`POST /api/v1/plans/{id}/pause?date=2026-10-16` 在同一事务锁定计划，切换status并追加启停日志、仅保留最新50条、version加1；可选date是用户日期，省略时兼容旧调用并回退服务器当天，跨时区客户端应传date。

statusLog 入参必须是最多 50 条的 JSON 数组，每条 date 必须是可解析的 ISO 日期、status 必须是整数 0 或 1；非法结构或超过上限均返回 1002，服务端不静默截尾。

## 历史增量下行

`GET /api/v1/records/history?executionAfterId=0&unlockAfterId=0&limit=200`

需要既有 Bearer 认证，结果使用既有 `ApiResponse` 包装。仅由客户端既有同步周期调用，页面只读本地历史。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "executions": [],
    "unlocks": [],
    "executionCursor": 0,
    "unlockCursor": 0,
    "hasMore": false
  }
}
```

- executions 字段沿用 ExecutionRecord：`id, planId, deviceId, planDate, status, startedAt, endedAt, clientId, createdAt`。
- unlocks 字段为 `id, planId, unlockAt, method, clientId`。
- 各表独立按 `id > 对应游标` 升序返回，每表最多 limit 条；limit 裁剪至 1..1000，负游标按 0。
- 输出游标是各表实际返回的末行 ID；空页保留输入游标。任一表仍有下一行则 hasMore=true。
- 按当前 plans.user_id 过滤，保持现有记录归属口径。已删计划的孤立记录不在该归属集合内。
- 明细为追加式读取，不承担记录修订或删除同步；上行继续复用 clientId 唯一键幂等。历史库中的 null clientId 透传，由客户端退回服务端 ID 去重。
- 执行/解锁上报事务先锁定users所属用户行，再分配记录ID，使同一owner的分配与提交顺序一致，防止较小ID迟提交而被游标漏过；不同owner的ID空洞无需扫描。

## 统计夹具

`planlock-app/src/test/resources/stats-golden.json` 是服务端与 Android 共同读取的一份黄金夹具。固定 today，包含 executions、unlocks 和按 day/week/month 的 expected 执行统计字段。服务端通过真实 MySQL 聚合验证它，不改动现行统计公式。

现状边界：week 指最近 7 个自然日；连续达成仅在当前尺度内；解锁查询保留既有 BETWEEN 包含次日零点的口径；提前结束列表最多 50 条。未执行与 statusLog 的日期投影属于客户端逻辑，服务端没有对应汇总字段。
