import { useEffect, useState } from 'preact/hooks';
import type { ApiClient } from '../api/client';
import { ApiError } from '../api/client';
import { ConflictModal, type ConflictFieldRow } from '../components/ConflictModal';
import type { PlanItem, ScheduleItem, ScheduleUpsertPayload } from '../types';
import { buildSchedulesIcs, triggerTextDownload } from '../utils/export';
import {
  formatScheduleRepeat,
  formatTimeShort,
  toBackendTime,
  todayIsoDate,
} from '../utils/format';

export interface SchedulesPageProps {
  api: ApiClient;
  onNotify: (text: string, tone?: 'ok' | 'warn' | 'error') => void;
}

interface ScheduleFormState {
  id: number | null;
  version: number | null;
  clientId: string | null;
  title: string;
  startDate: string;
  endDate: string;
  allDay: boolean;
  startTime: string;
  endTime: string;
  location: string;
  locationLat: number | null;
  locationLon: number | null;
  note: string;
  remindAdvanceMin: string;
  repeatMode: 'none' | 'daily' | 'weekly' | 'monthly' | 'yearly';
  linkedPlanId: string;
  tzId: string | null;
}

function emptyScheduleForm(): ScheduleFormState {
  const today = todayIsoDate();
  return {
    id: null,
    version: null,
    clientId: null,
    title: '',
    startDate: today,
    endDate: today,
    allDay: false,
    startTime: '14:00',
    endTime: '15:00',
    location: '',
    locationLat: null,
    locationLon: null,
    note: '',
    remindAdvanceMin: '15',
    repeatMode: 'none',
    linkedPlanId: '',
    tzId: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
  };
}

function parseRepeatMode(raw?: string | null): ScheduleFormState['repeatMode'] {
  if (!raw) return 'none';
  try {
    const t = (JSON.parse(raw) as { type?: string }).type;
    if (t === 'daily' || t === 'weekly' || t === 'monthly' || t === 'yearly') return t;
  } catch {
    // ignore
  }
  return 'none';
}

function fromScheduleItem(item: ScheduleItem): ScheduleFormState {
  return {
    id: item.id,
    version: item.version,
    clientId: item.clientId ?? null,
    title: item.title,
    startDate: item.startDate,
    endDate: item.endDate,
    allDay: Boolean(item.allDay),
    startTime: item.startTime ? formatTimeShort(item.startTime) : '09:00',
    endTime: item.endTime ? formatTimeShort(item.endTime) : '10:00',
    location: item.location ?? '',
    locationLat: item.locationLat ?? null,
    locationLon: item.locationLon ?? null,
    note: item.note ?? '',
    remindAdvanceMin: item.remindAdvanceMin !== null && item.remindAdvanceMin !== undefined ? String(item.remindAdvanceMin) : '',
    repeatMode: parseRepeatMode(item.repeatRule),
    linkedPlanId: item.linkedPlanId ? String(item.linkedPlanId) : '',
    tzId: item.tzId ?? null,
  };
}

function buildRepeatRuleJson(form: ScheduleFormState): string | null {
  if (form.repeatMode === 'none') return null;
  const parts = form.startDate.split('-').map((n) => Number(n));
  const month = parts[1] || 1;
  const day = parts[2] || 1;
  if (form.repeatMode === 'daily') return JSON.stringify({ type: 'daily' });
  if (form.repeatMode === 'weekly') {
    const dt = new Date(`${form.startDate}T00:00:00`);
    const weekday = dt.getDay() === 0 ? 7 : dt.getDay();
    return JSON.stringify({ type: 'weekly', weekdays: [weekday] });
  }
  if (form.repeatMode === 'monthly') return JSON.stringify({ type: 'monthly', day });
  return JSON.stringify({ type: 'yearly', month, day });
}

function toSchedulePayload(form: ScheduleFormState): ScheduleUpsertPayload {
  const hasCoords = form.locationLat !== null && form.locationLon !== null;
  const remindNum = form.remindAdvanceMin.trim() === '' ? null : Math.max(0, Number(form.remindAdvanceMin) || 0);
  return {
    clientId: form.clientId || undefined,
    title: form.title.trim(),
    startDate: form.startDate,
    endDate: form.endDate,
    allDay: form.allDay,
    startTime: form.allDay ? null : toBackendTime(form.startTime),
    endTime: form.allDay ? null : toBackendTime(form.endTime),
    location: form.location.trim() || null,
    locationLat: hasCoords ? form.locationLat : null,
    locationLon: hasCoords ? form.locationLon : null,
    note: form.note.trim() || null,
    remindAdvanceMin: remindNum,
    repeatRule: buildRepeatRuleJson(form),
    linkedPlanId: form.linkedPlanId ? Number(form.linkedPlanId) : null,
    tzId: form.tzId || Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
  };
}

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
    setLoading(true);
    setErrorText(null);
    try {
      const [schedList, planList] = await Promise.all([
        api.listSchedules(),
        api.listPlans().catch(() => []),
      ]);
      setSchedules(schedList);
      setPlans(planList);
    } catch (err) {
      if (err instanceof ApiError && !err.isUnauthorized) {
        setErrorText(err.message);
      }
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadAll();
  }, []);

  async function handleSave(evt?: Event, overrideVersion?: number) {
    evt?.preventDefault();
    if (!editing) return;
    if (!editing.title.trim()) {
      onNotify('请填写日程标题', 'warn');
      return;
    }
    if (editing.endDate < editing.startDate) {
      onNotify('结束日期不能早于开始日期', 'warn');
      return;
    }
    if (!editing.allDay && editing.startDate === editing.endDate && editing.endTime <= editing.startTime) {
      onNotify('同日定时日程的结束时间必须晚于开始时间', 'warn');
      return;
    }

    setSaving(true);
    try {
      const payload = toSchedulePayload(editing);
      if (editing.id === null) {
        await api.createSchedule(payload);
        onNotify('已新建日程');
      } else {
        await api.updateSchedule(editing.id, overrideVersion ?? editing.version ?? 0, payload);
        onNotify('日程已保存');
      }
      setEditing(null);
      setConflictServer(null);
      await loadAll();
    } catch (err) {
      if (err instanceof ApiError && err.isConflict && editing.id !== null) {
        try {
          const latestList = await api.listSchedules();
          setSchedules(latestList);
          const latest = latestList.find((s) => s.id === editing.id) ?? null;
          setConflictServer(latest);
          if (!latest) {
            onNotify('该日程已在其他设备被删除', 'warn');
            setEditing(null);
          }
        } catch {
          onNotify('检测到 1409 版本冲突，请刷新后重试', 'warn');
        }
      } else if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete(item: ScheduleItem) {
    try {
      await api.deleteSchedule(item.id, item.version);
      setSchedules((prev) => prev.filter((s) => s.id !== item.id));
      onNotify(`已删除日程「${item.title}」`);
    } catch (err) {
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
          { label: '地点', draftValue: editing.location || '（空）', serverValue: conflictServer.location || '（空）' },
          { label: '版本', draftValue: `v${editing.version ?? 0}`, serverValue: `v${conflictServer.version}` },
        ]
      : [];

  return (
    <div class="stack-lg">
      <header class="page-header">
        <div>
          <p class="eyebrow">W3 · 日程管理</p>
          <h1 class="page-title">日程</h1>
          <p class="page-subtitle">
            编辑日程时完整保留移动端采集的地点经纬度（locationLat / locationLon），并支持 ICS 导出与 1409 冲突保护。
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
                        onClick={() => setEditing(fromScheduleItem(item))}
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
                          onClick={() => setEditing(fromScheduleItem(item))}
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
                    setEditing({ ...editing, title: (evt.currentTarget as HTMLInputElement).value })
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
                      setEditing({
                        ...editing,
                        startDate: val,
                        endDate: editing.endDate < val ? val : editing.endDate,
                      });
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
                      setEditing({
                        ...editing,
                        endDate: (evt.currentTarget as HTMLInputElement).value,
                      })
                    }
                  />
                </label>
              </div>

              <label class="checkbox-row">
                <input
                  type="checkbox"
                  checked={editing.allDay}
                  onChange={(evt) =>
                    setEditing({
                      ...editing,
                      allDay: (evt.currentTarget as HTMLInputElement).checked,
                    })
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
                        setEditing({
                          ...editing,
                          startTime: (evt.currentTarget as HTMLInputElement).value,
                        })
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
                        setEditing({
                          ...editing,
                          endTime: (evt.currentTarget as HTMLInputElement).value,
                        })
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
                      setEditing({
                        ...editing,
                        repeatMode: (evt.currentTarget as HTMLSelectElement)
                          .value as ScheduleFormState['repeatMode'],
                      })
                    }
                  >
                    <option value="none">不重复</option>
                    <option value="daily">每天</option>
                    <option value="weekly">每周</option>
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
                      setEditing({
                        ...editing,
                        remindAdvanceMin: (evt.currentTarget as HTMLInputElement).value,
                      })
                    }
                  />
                </label>
              </div>

              <label class="field">
                <span class="field-label">地点</span>
                <input
                  type="text"
                  class="input"
                  maxLength={200}
                  value={editing.location}
                  onInput={(evt) =>
                    setEditing({
                      ...editing,
                      location: (evt.currentTarget as HTMLInputElement).value,
                    })
                  }
                />
              </label>

              {editing.locationLat !== null && editing.locationLon !== null && (
                <div class="paper-card-sunk row-between">
                  <span class="muted">
                    已保留移动端地图坐标：<span class="mono">{editing.locationLat}, {editing.locationLon}</span>
                  </span>
                  <button
                    type="button"
                    class="btn btn-sm btn-ghost"
                    onClick={() =>
                      setEditing({ ...editing, locationLat: null, locationLon: null })
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
                    setEditing({
                      ...editing,
                      linkedPlanId: (evt.currentTarget as HTMLSelectElement).value,
                    })
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
                    setEditing({
                      ...editing,
                      note: (evt.currentTarget as HTMLTextAreaElement).value,
                    })
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
          description="检测到 1409 版本冲突，加载服务端最新数据可完整保留最新地点坐标与备注。"
          rows={conflictRows}
          busy={saving}
          onReloadServer={() => {
            setEditing(fromScheduleItem(conflictServer));
            setConflictServer(null);
            onNotify('已重载服务端最新日程');
          }}
          onOverwriteWithLatest={() => {
            void handleSave(undefined, conflictServer.version);
          }}
          onCancel={() => setConflictServer(null)}
        />
      )}
    </div>
  );
}
