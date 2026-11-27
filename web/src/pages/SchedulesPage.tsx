import { useEffect, useState } from 'preact/hooks';
import type { ApiClient } from '../api/client';
import { ApiError, StaleSessionError } from '../api/client';
import { ConflictModal, type ConflictFieldRow } from '../components/ConflictModal';
import type { PlanItem, ScheduleItem } from '../types';
import { buildSchedulesIcs, triggerTextDownload } from '../utils/export';
import { formatScheduleRepeat, formatTimeShort, todayIsoDate } from '../utils/format';
import {
  buildScheduleUpsertPayload,
  emptyScheduleForm,
  scheduleFormFromItem,
  withScheduleField,
  type ScheduleFormState,
  type ScheduleRepeatMode,
} from '../utils/payloads';

export interface SchedulesPageProps {
  api: ApiClient;
  onNotify: (text: string, tone?: 'ok' | 'warn' | 'error') => void;
}

const WEEKDAY_LABELS = [
  { value: 1, label: '一' },
  { value: 2, label: '二' },
  { value: 3, label: '三' },
  { value: 4, label: '四' },
  { value: 5, label: '五' },
  { value: 6, label: '六' },
  { value: 7, label: '日' },
];

export function SchedulesPage({ api, onNotify }: SchedulesPageProps) {
  const [schedules, setSchedules] = useState<ScheduleItem[]>([]);
  const [plans, setPlans] = useState<PlanItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [showPast, setShowPast] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [errorText, setErrorText] = useState<string | null>(null);

  const [editing, setEditing] = useState<ScheduleFormState | null>(null);
  const [saving, setSaving] = useState(false);
  const [conflictServer, setConflictServer] = useState<ScheduleItem | null>(null);

  async function loadAll() {
    const gen = api.getSessionGeneration();
    setLoading(true);
    setErrorText(null);
    try {
      const [schedList, planList] = await Promise.all([
        api.listSchedules(),
        api.listPlans().catch(() => []),
      ]);
      if (!api.isGenerationCurrent(gen)) return;
      setSchedules(schedList);
      setPlans(planList);
    } catch (err) {
      if (err instanceof StaleSessionError) return;
      if (err instanceof ApiError && !err.isUnauthorized) {
        setErrorText(err.message);
      }
    } finally {
      if (api.isGenerationCurrent(gen)) {
        setLoading(false);
      }
    }
  }

  useEffect(() => {
    void loadAll();
  }, []);

  async function handleSave(evt?: Event, mergeServerSnapshot?: ScheduleItem) {
    evt?.preventDefault();
    if (!editing) return;
    if (!editing.title.trim()) {
      onNotify('请填写日程标题', 'warn');
      return;
    }
    const payload = buildScheduleUpsertPayload(editing, mergeServerSnapshot ?? null);
    if (payload.endDate < payload.startDate) {
      onNotify('结束日期不能早于开始日期', 'warn');
      return;
    }
    if (
      !payload.allDay &&
      payload.startDate === payload.endDate &&
      payload.startTime &&
      payload.endTime &&
      payload.endTime <= payload.startTime
    ) {
      onNotify('同日定时日程的结束时间必须晚于开始时间', 'warn');
      return;
    }

    const gen = api.getSessionGeneration();
    setSaving(true);
    try {
      if (editing.id === null) {
        await api.createSchedule(payload);
        if (!api.isGenerationCurrent(gen)) return;
        onNotify('已新建日程');
      } else {
        await api.updateSchedule(
          editing.id,
          mergeServerSnapshot?.version ?? editing.version ?? 0,
          payload,
        );
        if (!api.isGenerationCurrent(gen)) return;
        onNotify('日程已保存');
      }
      setEditing(null);
      setConflictServer(null);
      await loadAll();
    } catch (err) {
      if (err instanceof StaleSessionError) return;
      if (err instanceof ApiError && err.isConflict && editing.id !== null) {
        try {
          const latestList = await api.listSchedules();
          if (!api.isGenerationCurrent(gen)) return;
          setSchedules(latestList);
          const latest = latestList.find((s) => s.id === editing.id) ?? null;
          setConflictServer(latest);
          if (!latest) {
            onNotify('该日程已在其他设备被删除', 'warn');
            setEditing(null);
          }
        } catch (reloadErr) {
          if (reloadErr instanceof StaleSessionError) return;
          onNotify('检测到 1409 版本冲突，请刷新后重试', 'warn');
        }
      } else if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    } finally {
      if (api.isGenerationCurrent(gen)) {
        setSaving(false);
      }
    }
  }

  async function handleDelete(item: ScheduleItem) {
    const gen = api.getSessionGeneration();
    try {
      await api.deleteSchedule(item.id, item.version);
      if (!api.isGenerationCurrent(gen)) return;
      setSchedules((prev) => prev.filter((s) => s.id !== item.id));
      onNotify(`已删除日程「${item.title}」`);
    } catch (err) {
      if (err instanceof StaleSessionError) return;
      if (err instanceof ApiError && err.isConflict) {
        onNotify('该日程版本已变化（1409），已刷新最新列表', 'warn');
        await loadAll();
      } else if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    }
  }

  function handleExportIcs() {
    const ics = buildSchedulesIcs(schedules);
    triggerTextDownload(`oriva-schedules-${todayIsoDate()}.ics`, ics, 'text/calendar;charset=utf-8');
    onNotify('已导出日程日历（.ics）');
  }

  const today = todayIsoDate();
  const visible = schedules.filter((s) => {
    if (!keyword.trim()) return true;
    const q = keyword.trim().toLowerCase();
    return (
      s.title.toLowerCase().includes(q) ||
      (s.location ?? '').toLowerCase().includes(q) ||
      (s.note ?? '').toLowerCase().includes(q)
    );
  });

  const upcoming = visible
    .filter((s) => s.endDate >= today || Boolean(s.repeatRule))
    .sort((a, b) => a.startDate.localeCompare(b.startDate));
  const past = visible
    .filter((s) => s.endDate < today && !s.repeatRule)
    .sort((a, b) => b.startDate.localeCompare(a.startDate));

  const conflictRows: ConflictFieldRow[] =
    editing && conflictServer
      ? [
          { label: '标题', draftValue: editing.title, serverValue: conflictServer.title },
          {
            label: '日期',
            draftValue: `${editing.startDate} ~ ${editing.endDate}`,
            serverValue: `${conflictServer.startDate} ~ ${conflictServer.endDate}`,
          },
          {
            label: '地点',
            draftValue: editing.location || '（空）',
            serverValue: conflictServer.location || '（空）',
          },
          {
            label: '地图坐标',
            draftValue: editing.coordsCleared
              ? '（草稿已主动清除坐标）'
              : '（未修改，合并时保留服务端最新坐标）',
            serverValue:
              conflictServer.locationLat !== null &&
              conflictServer.locationLat !== undefined &&
              conflictServer.locationLon !== null &&
              conflictServer.locationLon !== undefined
                ? `${conflictServer.locationLat}, ${conflictServer.locationLon}`
                : '（无）',
          },
          {
            label: '版本',
            draftValue: `v${editing.version ?? 0}`,
            serverValue: `v${conflictServer.version}`,
          },
        ]
      : [];

  return (
    <div class="stack-lg">
      <header class="page-header">
        <div>
          <p class="eyebrow">W3 · 日程管理</p>
          <h1 class="page-title">日程</h1>
          <p class="page-subtitle">
            编辑日程时完整保留移动端采集的地点经纬度与多曜日/月/年重复规则，并支持 ICS 导出与 1409 冲突保护。
          </p>
        </div>
        <div class="header-actions">
          <button type="button" class="btn" onClick={handleExportIcs}>
            导出日程 ICS
          </button>
          <button
            type="button"
            class="btn btn-vermilion"
            onClick={() => setEditing(emptyScheduleForm())}
          >
            + 新建日程
          </button>
        </div>
      </header>

      {errorText && (
        <div class="notice notice-crimson" role="alert">
          <span>{errorText}</span>
        </div>
      )}

      <div>
        <input
          type="search"
          class="input"
          aria-label="搜索日程"
          placeholder="按标题、地点或备注搜索日程…"
          value={keyword}
          onInput={(evt) => setKeyword((evt.currentTarget as HTMLInputElement).value)}
        />
      </div>

      {loading ? (
        <div class="paper-card muted">正在加载日程…</div>
      ) : (
        <div class="stack-lg">
          <section class="stack-sm">
            <h2 class="section-title">进行中与即将到来（{upcoming.length}）</h2>
            {upcoming.length === 0 ? (
              <div class="paper-card muted">暂无未结束的日程。</div>
            ) : (
              <div class="ledger-list">
                {upcoming.map((item) => (
                  <article key={item.id} class="ledger-item">
                    <div class="ledger-time hue-amber">
                      <div>{item.startDate}</div>
                      <div class="faint">
                        {item.allDay
                          ? '全天'
                          : `${formatTimeShort(item.startTime)}–${formatTimeShort(item.endTime)}`}
                      </div>
                    </div>
                    <div class="ledger-body">
                      <div class="row-wrap">
                        <h3 class="ledger-title">{item.title}</h3>
                        {item.repeatRule && (
                          <span class="tag tag-brass">{formatScheduleRepeat(item.repeatRule)}</span>
                        )}
                        {item.locationLat !== null &&
                          item.locationLat !== undefined &&
                          item.locationLon !== null &&
                          item.locationLon !== undefined && (
                            <span class="tag tag-indigo">
                              坐标 {item.locationLat.toFixed(4)}, {item.locationLon.toFixed(4)}
                            </span>
                          )}
                      </div>
                      <div class="ledger-meta">
                        {item.location ? `地点：${item.location} · ` : ''}
                        {item.note ? `${item.note} · ` : ''}
                        版本 v{item.version}
                      </div>
                    </div>
                    <div class="row-wrap">
                      <button
                        type="button"
                        class="btn btn-sm"
                        onClick={() => setEditing(scheduleFormFromItem(item))}
                      >
                        编辑
                      </button>
                      <button
                        type="button"
                        class="btn btn-sm btn-danger"
                        onClick={() => void handleDelete(item)}
                      >
                        删除
                      </button>
                    </div>
                  </article>
                ))}
              </div>
            )}
          </section>

          {past.length > 0 && (
            <section class="stack-sm">
              <button
                type="button"
                class="btn btn-ghost"
                aria-expanded={showPast || upcoming.length === 0}
                onClick={() => setShowPast((v) => !v)}
              >
                {showPast || upcoming.length === 0
                  ? `收起已过去日程（${past.length} 项）`
                  : `展开已过去日程（${past.length} 项）`}
              </button>

              {(showPast || upcoming.length === 0) && (
                <div class="ledger-list">
                  {past.map((item) => (
                    <article key={item.id} class="ledger-item is-paused">
                      <div class="ledger-time muted">{item.startDate}</div>
                      <div class="ledger-body">
                        <h3 class="ledger-title">{item.title}</h3>
                        <div class="ledger-meta">
                          {item.location || '无地点'} · 版本 v{item.version}
                        </div>
                      </div>
                      <div class="row-wrap">
                        <button
                          type="button"
                          class="btn btn-sm"
                          onClick={() => setEditing(scheduleFormFromItem(item))}
                        >
                          查看/编辑
                        </button>
                        <button
                          type="button"
                          class="btn btn-sm btn-danger"
                          onClick={() => void handleDelete(item)}
                        >
                          删除
                        </button>
                      </div>
                    </article>
                  ))}
                </div>
              )}
            </section>
          )}
        </div>
      )}

      {editing && (
        <div
          class="modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-labelledby="schedule-form-title"
        >
          <div class="modal-panel stack-md">
            <div class="row-between">
              <h2 id="schedule-form-title" class="section-title">
                {editing.id === null ? '新建日程' : `编辑日程（v${editing.version ?? 0}）`}
              </h2>
              <button type="button" class="btn btn-sm btn-ghost" onClick={() => setEditing(null)}>
                关闭
              </button>
            </div>

            <form class="stack-md" onSubmit={(evt) => void handleSave(evt)}>
              <label class="field">
                <span class="field-label">日程标题 *</span>
                <input
                  type="text"
                  class="input"
                  name="scheduleTitle"
                  required
                  maxLength={100}
                  value={editing.title}
                  onInput={(evt) =>
                    setEditing(
                      withScheduleField(
                        editing,
                        'title',
                        (evt.currentTarget as HTMLInputElement).value,
                        'title',
                      ),
                    )
                  }
                />
              </label>

              <div class="grid-2">
                <label class="field">
                  <span class="field-label">开始日期 *</span>
                  <input
                    type="date"
                    class="input"
                    required
                    value={editing.startDate}
                    onInput={(evt) => {
                      const val = (evt.currentTarget as HTMLInputElement).value;
                      let next = withScheduleField(editing, 'startDate', val, 'startDate');
                      if (next.endDate < val) {
                        next = withScheduleField(next, 'endDate', val, 'endDate');
                      }
                      setEditing(next);
                    }}
                  />
                </label>
                <label class="field">
                  <span class="field-label">结束日期 *</span>
                  <input
                    type="date"
                    class="input"
                    required
                    value={editing.endDate}
                    onInput={(evt) =>
                      setEditing(
                        withScheduleField(
                          editing,
                          'endDate',
                          (evt.currentTarget as HTMLInputElement).value,
                          'endDate',
                        ),
                      )
                    }
                  />
                </label>
              </div>

              <label class="checkbox-row">
                <input
                  type="checkbox"
                  checked={editing.allDay}
                  onChange={(evt) =>
                    setEditing(
                      withScheduleField(
                        editing,
                        'allDay',
                        (evt.currentTarget as HTMLInputElement).checked,
                        'allDay',
                      ),
                    )
                  }
                />
                <span>全天日程</span>
              </label>

              {!editing.allDay && (
                <div class="grid-2">
                  <label class="field">
                    <span class="field-label">开始时间</span>
                    <input
                      type="time"
                      class="input"
                      value={editing.startTime}
                      onInput={(evt) =>
                        setEditing(
                          withScheduleField(
                            editing,
                            'startTime',
                            (evt.currentTarget as HTMLInputElement).value,
                            'startTime',
                          ),
                        )
                      }
                    />
                  </label>
                  <label class="field">
                    <span class="field-label">结束时间</span>
                    <input
                      type="time"
                      class="input"
                      value={editing.endTime}
                      onInput={(evt) =>
                        setEditing(
                          withScheduleField(
                            editing,
                            'endTime',
                            (evt.currentTarget as HTMLInputElement).value,
                            'endTime',
                          ),
                        )
                      }
                    />
                  </label>
                </div>
              )}

              <div class="grid-2">
                <label class="field">
                  <span class="field-label">重复</span>
                  <select
                    class="select"
                    value={editing.repeatMode}
                    onChange={(evt) =>
                      setEditing(
                        withScheduleField(
                          editing,
                          'repeatMode',
                          (evt.currentTarget as HTMLSelectElement).value as ScheduleRepeatMode,
                          'repeatRule',
                        ),
                      )
                    }
                  >
                    {editing.originalRepeatRule && (
                      <option value="preserve">
                        保留原有规则（{formatScheduleRepeat(editing.originalRepeatRule)}）
                      </option>
                    )}
                    <option value="none">不重复</option>
                    <option value="daily">每天</option>
                    <option value="weekly">每周（可选多星期）</option>
                    <option value="monthly">每月</option>
                    <option value="yearly">每年</option>
                  </select>
                </label>
                <label class="field">
                  <span class="field-label">提前提醒（分钟，可留空）</span>
                  <input
                    type="number"
                    class="input"
                    min={0}
                    value={editing.remindAdvanceMin}
                    onInput={(evt) =>
                      setEditing(
                        withScheduleField(
                          editing,
                          'remindAdvanceMin',
                          (evt.currentTarget as HTMLInputElement).value,
                          'remindAdvanceMin',
                        ),
                      )
                    }
                  />
                </label>
              </div>

              {editing.repeatMode === 'weekly' && (
                <div class="field">
                  <span class="field-label">重复星期</span>
                  <div class="row-wrap">
                    {WEEKDAY_LABELS.map((day) => {
                      const active = editing.repeatWeekdays.includes(day.value);
                      return (
                        <button
                          key={day.value}
                          type="button"
                          class={`btn btn-sm${active ? ' btn-primary' : ''}`}
                          onClick={() => {
                            const next = active
                              ? editing.repeatWeekdays.filter((w) => w !== day.value)
                              : [...editing.repeatWeekdays, day.value];
                            setEditing(
                              withScheduleField(
                                editing,
                                'repeatWeekdays',
                                next.length ? next : [day.value],
                                'repeatRule',
                              ),
                            );
                          }}
                        >
                          周{day.label}
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

              <label class="field">
                <span class="field-label">地点</span>
                <input
                  type="text"
                  class="input"
                  maxLength={200}
                  value={editing.location}
                  onInput={(evt) =>
                    setEditing(
                      withScheduleField(
                        editing,
                        'location',
                        (evt.currentTarget as HTMLInputElement).value,
                        'location',
                      ),
                    )
                  }
                />
              </label>

              {!editing.coordsCleared && editing.locationLat !== null && editing.locationLon !== null && (
                <div class="paper-card-sunk row-between">
                  <span class="muted">
                    已保留移动端地图坐标：
                    <span class="mono">
                      {editing.locationLat}, {editing.locationLon}
                    </span>
                  </span>
                  <button
                    type="button"
                    class="btn btn-sm btn-ghost"
                    onClick={() =>
                      setEditing({
                        ...withScheduleField(editing, 'coordsCleared', true, 'coordsCleared'),
                        locationLat: null,
                        locationLon: null,
                      })
                    }
                  >
                    清除坐标
                  </button>
                </div>
              )}

              <label class="field">
                <span class="field-label">关联计划（可选）</span>
                <select
                  class="select"
                  value={editing.linkedPlanId}
                  onChange={(evt) =>
                    setEditing(
                      withScheduleField(
                        editing,
                        'linkedPlanId',
                        (evt.currentTarget as HTMLSelectElement).value,
                        'linkedPlanId',
                      ),
                    )
                  }
                >
                  <option value="">不关联</option>
                  {plans.map((p) => (
                    <option key={p.id} value={String(p.id)}>
                      {p.name}
                    </option>
                  ))}
                </select>
              </label>

              <label class="field">
                <span class="field-label">备注</span>
                <textarea
                  class="textarea"
                  maxLength={1000}
                  value={editing.note}
                  onInput={(evt) =>
                    setEditing(
                      withScheduleField(
                        editing,
                        'note',
                        (evt.currentTarget as HTMLTextAreaElement).value,
                        'note',
                      ),
                    )
                  }
                />
              </label>

              <div class="row-wrap">
                <button type="submit" class="btn btn-primary" disabled={saving}>
                  {saving ? '保存中…' : '保存日程'}
                </button>
                <button
                  type="button"
                  class="btn btn-ghost"
                  disabled={saving}
                  onClick={() => setEditing(null)}
                >
                  取消
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {editing && conflictServer && (
        <ConflictModal
          title={`日程「${conflictServer.title}」已在其他设备修改`}
          description="检测到 1409 版本冲突。覆盖保存将以服务端最新快照为基底，仅合并您明确修改的字段，并完整保留移动端并发更新的经纬度与重复规则。"
          rows={conflictRows}
          busy={saving}
          onReloadServer={() => {
            setEditing(scheduleFormFromItem(conflictServer));
            setConflictServer(null);
            onNotify('已重载服务端最新日程');
          }}
          onOverwriteWithLatest={() => {
            void handleSave(undefined, conflictServer);
          }}
          onCancel={() => setConflictServer(null)}
        />
      )}
    </div>
  );
}
