import { useEffect, useState } from 'preact/hooks';
import type { ApiClient } from '../api/client';
import { ApiError } from '../api/client';
import { ConflictModal, type ConflictFieldRow } from '../components/ConflictModal';
import type {
  PlanItem,
  PlanRepeatRule,
  PlanUpsertPayload,
  StrategyItem,
} from '../types';
import { triggerTextDownload } from '../utils/export';
import {
  formatRepeatRule,
  formatTimeShort,
  mergePlanTypes,
  resolvePlanTypeMeta,
  toBackendTime,
  todayIsoDate,
  type PlanTypeOption,
} from '../utils/format';

export interface PlansPageProps {
  api: ApiClient;
  onNotify: (text: string, tone?: 'ok' | 'warn' | 'error') => void;
}

interface PlanFormState {
  id: number | null;
  version: number | null;
  clientId: string | null;
  endedOccurrenceDate: string | null;
  statusLog: string | null;
  color: string | null;
  icon: string | null;
  remindMethods: PlanItem['remindMethods'];
  name: string;
  type: string;
  startTime: string;
  endTime: string;
  repeatType: 'daily' | 'weekly' | 'once';
  weekdays: number[];
  remindAdvanceMin: number;
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

function emptyPlanForm(): PlanFormState {
  return {
    id: null,
    version: null,
    clientId: null,
    endedOccurrenceDate: null,
    statusLog: null,
    color: null,
    icon: null,
    remindMethods: { notification: true, sound: false, multiDevice: false, repeatMultiple: false },
    name: '',
    type: '学习',
    startTime: '09:00',
    endTime: '10:30',
    repeatType: 'daily',
    weekdays: [1, 2, 3, 4, 5],
    remindAdvanceMin: 15,
  };
}

function fromPlanItem(plan: PlanItem): PlanFormState {
  const rt = plan.repeatRule?.type;
  const repeatType: 'daily' | 'weekly' | 'once' =
    rt === 'weekly' ? 'weekly' : rt === 'once' ? 'once' : 'daily';
  return {
    id: plan.id,
    version: plan.version,
    clientId: plan.clientId ?? null,
    endedOccurrenceDate: plan.endedOccurrenceDate ?? null,
    statusLog: plan.statusLog ?? null,
    color: plan.color ?? null,
    icon: plan.icon ?? null,
    remindMethods: plan.remindMethods ?? {
      notification: true,
      sound: false,
      multiDevice: false,
      repeatMultiple: false,
    },
    name: plan.name,
    type: plan.type,
    startTime: formatTimeShort(plan.startTime),
    endTime: formatTimeShort(plan.endTime),
    repeatType,
    weekdays: plan.repeatRule?.weekdays?.length ? [...plan.repeatRule.weekdays] : [1, 2, 3, 4, 5],
    remindAdvanceMin: plan.remindAdvanceMin ?? 15,
  };
}

function toPlanPayload(form: PlanFormState): PlanUpsertPayload {
  const repeatRule: PlanRepeatRule =
    form.repeatType === 'weekly'
      ? { type: 'weekly', weekdays: form.weekdays.length ? form.weekdays : [1, 2, 3, 4, 5] }
      : { type: form.repeatType };
  return {
    clientId: form.clientId || undefined,
    endedOccurrenceDate: form.endedOccurrenceDate,
    statusLog: form.statusLog,
    name: form.name.trim(),
    type: form.type,
    startTime: toBackendTime(form.startTime),
    endTime: toBackendTime(form.endTime),
    repeatRule,
    color: form.color,
    icon: form.icon,
    remindAdvanceMin: Math.max(0, Number(form.remindAdvanceMin) || 0),
    remindMethods: form.remindMethods,
  };
}

export function PlansPage({ api, onNotify }: PlansPageProps) {
  const [plans, setPlans] = useState<PlanItem[]>([]);
  const [planTypes, setPlanTypes] = useState<PlanTypeOption[]>(mergePlanTypes());
  const [filterScope, setFilterScope] = useState<'all' | 'active' | 'paused'>('all');
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(true);
  const [errorText, setErrorText] = useState<string | null>(null);

  const [editing, setEditing] = useState<PlanFormState | null>(null);
  const [saving, setSaving] = useState(false);

  // 1409 乐观锁冲突状态
  const [conflictServer, setConflictServer] = useState<PlanItem | null>(null);

  // 执行策略弹窗
  const [strategyPlan, setStrategyPlan] = useState<PlanItem | null>(null);
  const [strategyData, setStrategyData] = useState<StrategyItem | null>(null);
  const [autoDnd, setAutoDnd] = useState(false);
  const [notifySilence, setNotifySilence] = useState(false);
  const [strategyLoading, setStrategyLoading] = useState(false);
  const [strategySaving, setStrategySaving] = useState(false);
  const [strategyConflict, setStrategyConflict] = useState<StrategyItem | null>(null);

  async function loadPlans() {
    setLoading(true);
    setErrorText(null);
    try {
      const [list, syncMeta] = await Promise.all([
        api.listPlans(),
        api.pullSyncMetadata().catch(() => null),
      ]);
      setPlans(list);
      if (syncMeta?.planTypes) {
        setPlanTypes(mergePlanTypes(syncMeta.planTypes));
      }
    } catch (err) {
      if (err instanceof ApiError && !err.isUnauthorized) {
        setErrorText(err.message);
      }
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadPlans();
  }, []);

  async function handleSavePlan(evt?: Event, overrideVersion?: number) {
    evt?.preventDefault();
    if (!editing) return;
    if (!editing.name.trim()) {
      onNotify('请填写计划名称', 'warn');
      return;
    }
    setSaving(true);
    try {
      const payload = toPlanPayload(editing);
      if (editing.id === null) {
        await api.createPlan(payload);
        onNotify('已创建新计划');
      } else {
        const baseVersion = overrideVersion ?? editing.version ?? 0;
        await api.updatePlan(editing.id, baseVersion, payload);
        onNotify('计划已更新');
      }
      setEditing(null);
      setConflictServer(null);
      await loadPlans();
    } catch (err) {
      if (err instanceof ApiError && err.isConflict && editing.id !== null) {
        try {
          const latestList = await api.listPlans();
          setPlans(latestList);
          const latest = latestList.find((p) => p.id === editing.id) ?? null;
          setConflictServer(latest);
          if (!latest) {
            onNotify('该计划已在其他设备被删除', 'warn');
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

  async function handleTogglePause(plan: PlanItem) {
    try {
      const updated = await api.pausePlan(plan.id, todayIsoDate());
      setPlans((prev) => prev.map((p) => (p.id === updated.id ? updated : p)));
      onNotify(updated.status === 1 ? `已恢复「${updated.name}」` : `已暂停「${updated.name}」`);
    } catch (err) {
      if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    }
  }

  async function handleDeletePlan(plan: PlanItem) {
    try {
      await api.deletePlan(plan.id, plan.version);
      setPlans((prev) => prev.filter((p) => p.id !== plan.id));
      onNotify(`已删除「${plan.name}」`);
    } catch (err) {
      if (err instanceof ApiError && err.isConflict) {
        onNotify('该计划版本已变更（1409），已刷新最新列表', 'warn');
        await loadPlans();
      } else if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    }
  }

  async function handleExportIcs() {
    try {
      const ics = await api.exportPlansIcs();
      triggerTextDownload(`oriva-plans-${todayIsoDate()}.ics`, ics, 'text/calendar;charset=utf-8');
      onNotify('已导出计划日历（.ics）');
    } catch (err) {
      if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    }
  }

  async function openStrategyModal(plan: PlanItem) {
    setStrategyPlan(plan);
    setStrategyData(null);
    setStrategyConflict(null);
    setStrategyLoading(true);
    try {
      const st = await api.getPlanStrategy(plan.id);
      setStrategyData(st);
      setAutoDnd(Boolean(st.autoDnd));
      setNotifySilence(Boolean(st.notifySilence));
    } catch (err) {
      if (err instanceof ApiError && err.isNotFound) {
        setStrategyData(null);
        setAutoDnd(false);
        setNotifySilence(false);
      } else if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
        setStrategyPlan(null);
      }
    } finally {
      setStrategyLoading(false);
    }
  }

  async function saveStrategy(overrideVersion?: number) {
    if (!strategyPlan) return;
    setStrategySaving(true);
    try {
      // 严禁清空移动端的黑/白名单应用配置，原样透传保留
      const saved = await api.upsertPlanStrategy(
        strategyPlan.id,
        {
          blacklistApps: strategyData?.blacklistApps ?? null,
          whitelistApps: strategyData?.whitelistApps ?? null,
          autoDnd,
          notifySilence,
        },
        overrideVersion ?? strategyData?.version,
      );
      setStrategyData(saved);
      setStrategyConflict(null);
      setStrategyPlan(null);
      onNotify(`已更新「${strategyPlan.name}」执行策略`);
    } catch (err) {
      if (err instanceof ApiError && err.isConflict) {
        try {
          const latest = await api.getPlanStrategy(strategyPlan.id);
          setStrategyConflict(latest);
        } catch {
          onNotify('策略版本冲突（1409），请重新打开', 'warn');
        }
      } else if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    } finally {
      setStrategySaving(false);
    }
  }

  const filtered = plans.filter((p) => {
    if (filterScope === 'active' && p.status !== 1) return false;
    if (filterScope === 'paused' && p.status !== 0) return false;
    if (keyword.trim()) {
      const q = keyword.trim().toLowerCase();
      return p.name.toLowerCase().includes(q) || p.type.toLowerCase().includes(q);
    }
    return true;
  });

  const conflictRows: ConflictFieldRow[] =
    editing && conflictServer
      ? [
          { label: '计划名称', draftValue: editing.name, serverValue: conflictServer.name },
          {
            label: '时段',
            draftValue: `${editing.startTime}–${editing.endTime}`,
            serverValue: `${formatTimeShort(conflictServer.startTime)}–${formatTimeShort(conflictServer.endTime)}`,
          },
          { label: '类型', draftValue: editing.type, serverValue: conflictServer.type },
          {
            label: '版本号',
            draftValue: `v${editing.version ?? 0}`,
            serverValue: `v${conflictServer.version}`,
          },
        ]
      : [];

  return (
    <div class="stack-lg">
      <header class="page-header">
        <div>
          <p class="eyebrow">W3 · 计划与基础策略</p>
          <h1 class="page-title">计划</h1>
          <p class="page-subtitle">
            支持新建、编辑（乐观锁 1409 冲突保护）、暂停恢复、基础免打扰策略切换与 ICS 导出。
          </p>
        </div>
        <div class="header-actions">
          <button type="button" class="btn" onClick={() => void handleExportIcs()}>
            导出 ICS
          </button>
          <button
            type="button"
            class="btn btn-vermilion"
            onClick={() => setEditing(emptyPlanForm())}
          >
            + 新建计划
          </button>
        </div>
      </header>

      {errorText && (
        <div class="notice notice-crimson" role="alert">
          <span>{errorText}</span>
        </div>
      )}

      <div class="row-between">
        <div class="segmented" role="tablist" aria-label="计划筛选">
          <button
            type="button"
            role="tab"
            aria-selected={filterScope === 'all'}
            class={`segmented-btn${filterScope === 'all' ? ' is-active' : ''}`}
            onClick={() => setFilterScope('all')}
          >
            全部（{plans.length}）
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={filterScope === 'active'}
            class={`segmented-btn${filterScope === 'active' ? ' is-active' : ''}`}
            onClick={() => setFilterScope('active')}
          >
            启用中（{plans.filter((p) => p.status === 1).length}）
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={filterScope === 'paused'}
            class={`segmented-btn${filterScope === 'paused' ? ' is-active' : ''}`}
            onClick={() => setFilterScope('paused')}
          >
            已暂停（{plans.filter((p) => p.status === 0).length}）
          </button>
        </div>

        <input
          type="search"
          class="input"
          aria-label="搜索计划"
          placeholder="按名称或类型筛选…"
          value={keyword}
          onInput={(evt) => setKeyword((evt.currentTarget as HTMLInputElement).value)}
        />
      </div>

      {loading ? (
        <div class="paper-card muted">正在加载计划列表…</div>
      ) : filtered.length === 0 ? (
        <div class="paper-card empty-state">
          <p>当前筛选下暂无计划。</p>
        </div>
      ) : (
        <div class="ledger-list">
          {filtered.map((plan) => {
            const meta = resolvePlanTypeMeta(plan.type, planTypes);
            const isPaused = plan.status === 0;
            return (
              <article
                key={plan.id}
                class={`ledger-item${isPaused ? ' is-paused' : ''}`}
              >
                <div class={`ledger-time ${meta.hueClass}`}>
                  {formatTimeShort(plan.startTime)} – {formatTimeShort(plan.endTime)}
                </div>
                <div class="ledger-body">
                  <div class="row-wrap">
                    <h2 class="ledger-title">{plan.name}</h2>
                    <span class={`hue-pill ${meta.hueClass}`}>{meta.label}</span>
                    {isPaused && <span class="tag tag-stone">已暂停</span>}
                  </div>
                  <div class="ledger-meta">
                    {formatRepeatRule(plan.repeatRule)} · 提前 {plan.remindAdvanceMin ?? 15} 分钟提醒 · 版本 v{plan.version}
                  </div>
                </div>
                <div class="row-wrap">
                  <button
                    type="button"
                    class="btn btn-sm"
                    onClick={() => void openStrategyModal(plan)}
                  >
                    策略
                  </button>
                  <button
                    type="button"
                    class="btn btn-sm"
                    onClick={() => void handleTogglePause(plan)}
                  >
                    {isPaused ? '恢复' : '暂停'}
                  </button>
                  <button
                    type="button"
                    class="btn btn-sm"
                    onClick={() => setEditing(fromPlanItem(plan))}
                  >
                    编辑
                  </button>
                  <button
                    type="button"
                    class="btn btn-sm btn-danger"
                    onClick={() => void handleDeletePlan(plan)}
                  >
                    删除
                  </button>
                </div>
              </article>
            );
          })}
        </div>
      )}

      {editing && (
        <div class="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="plan-form-title">
          <div class="modal-panel stack-md">
            <div class="row-between">
              <h2 id="plan-form-title" class="section-title">
                {editing.id === null ? '新建计划' : `编辑计划（v${editing.version ?? 0}）`}
              </h2>
              <button type="button" class="btn btn-sm btn-ghost" onClick={() => setEditing(null)}>
                关闭
              </button>
            </div>

            <form class="stack-md" onSubmit={(evt) => void handleSavePlan(evt)}>
              <label class="field">
                <span class="field-label">计划名称 *</span>
                <input
                  type="text"
                  class="input"
                  name="planName"
                  required
                  maxLength={100}
                  value={editing.name}
                  onInput={(evt) =>
                    setEditing({ ...editing, name: (evt.currentTarget as HTMLInputElement).value })
                  }
                />
              </label>

              <div class="field">
                <span class="field-label">计划类型</span>
                <div class="row-wrap">
                  {planTypes.map((opt) => (
                    <button
                      key={opt.key}
                      type="button"
                      class={`hue-chip-btn ${opt.hueClass}${editing.type === opt.key ? ' is-selected' : ''}`}
                      onClick={() => setEditing({ ...editing, type: opt.key })}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>

              <div class="grid-2">
                <label class="field">
                  <span class="field-label">开始时间 *</span>
                  <input
                    type="time"
                    class="input"
                    name="startTime"
                    required
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
                  <span class="field-label">结束时间 *</span>
                  <input
                    type="time"
                    class="input"
                    name="endTime"
                    required
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

              <div class="grid-2">
                <label class="field">
                  <span class="field-label">重复规则</span>
                  <select
                    class="select"
                    value={editing.repeatType}
                    onChange={(evt) =>
                      setEditing({
                        ...editing,
                        repeatType: (evt.currentTarget as HTMLSelectElement).value as
                          | 'daily'
                          | 'weekly'
                          | 'once',
                      })
                    }
                  >
                    <option value="daily">每天</option>
                    <option value="weekly">按星期</option>
                    <option value="once">单次</option>
                  </select>
                </label>
                <label class="field">
                  <span class="field-label">提前提醒（分钟）</span>
                  <input
                    type="number"
                    class="input"
                    min={0}
                    max={1440}
                    value={editing.remindAdvanceMin}
                    onInput={(evt) =>
                      setEditing({
                        ...editing,
                        remindAdvanceMin: Number((evt.currentTarget as HTMLInputElement).value) || 0,
                      })
                    }
                  />
                </label>
              </div>

              {editing.repeatType === 'weekly' && (
                <div class="field">
                  <span class="field-label">选择星期</span>
                  <div class="row-wrap">
                    {WEEKDAY_LABELS.map((day) => {
                      const active = editing.weekdays.includes(day.value);
                      return (
                        <button
                          key={day.value}
                          type="button"
                          class={`btn btn-sm${active ? ' btn-primary' : ''}`}
                          onClick={() => {
                            const next = active
                              ? editing.weekdays.filter((w) => w !== day.value)
                              : [...editing.weekdays, day.value];
                            setEditing({ ...editing, weekdays: next.length ? next : [day.value] });
                          }}
                        >
                          周{day.label}
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

              <div class="row-wrap">
                <button type="submit" class="btn btn-primary" disabled={saving}>
                  {saving ? '保存中…' : '保存计划'}
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
          title={`「${conflictServer.name}」已在其他设备修改`}
          description="提交的 baseVersion 与服务端最新版本不一致（1409），请选择加载服务端最新数据或以当前草稿覆盖。"
          rows={conflictRows}
          busy={saving}
          onReloadServer={() => {
            setEditing(fromPlanItem(conflictServer));
            setConflictServer(null);
            onNotify('已加载服务端最新计划版本');
          }}
          onOverwriteWithLatest={() => {
            void handleSavePlan(undefined, conflictServer.version);
          }}
          onCancel={() => setConflictServer(null)}
        />
      )}

      {strategyPlan && (
        <div class="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="strategy-title">
          <div class="modal-panel stack-md">
            <div class="row-between">
              <h2 id="strategy-title" class="section-title">
                执行策略 · {strategyPlan.name}
              </h2>
              <button
                type="button"
                class="btn btn-sm btn-ghost"
                onClick={() => setStrategyPlan(null)}
              >
                关闭
              </button>
            </div>

            {strategyLoading ? (
              <p class="muted">正在读取策略…</p>
            ) : (
              <div class="stack-md">
                <label class="checkbox-row">
                  <input
                    type="checkbox"
                    checked={autoDnd}
                    onChange={(evt) => setAutoDnd((evt.currentTarget as HTMLInputElement).checked)}
                  />
                  <span>执行期间自动开启免打扰（autoDnd）</span>
                </label>

                <label class="checkbox-row">
                  <input
                    type="checkbox"
                    checked={notifySilence}
                    onChange={(evt) =>
                      setNotifySilence((evt.currentTarget as HTMLInputElement).checked)
                    }
                  />
                  <span>执行期间静默非必要通知（notifySilence）</span>
                </label>

                <div class="paper-card-sunk stack-sm">
                  <div class="eyebrow">移动端应用名单（只读保留）</div>
                  <div class="muted">
                    黑名单应用：{strategyData?.blacklistApps?.length ?? 0} 项 · 白名单应用：
                    {strategyData?.whitelistApps?.length ?? 0} 项
                  </div>
                  <div class="faint">
                    保存基础策略时会自动原样保留您在手机端配置的应用黑白名单，不会覆盖清空。
                  </div>
                </div>

                {strategyConflict && (
                  <div class="notice notice-brass">
                    <div class="stack-sm">
                      <strong>检测到策略版本冲突（1409）</strong>
                      <span>
                        服务端已更新至 v{strategyConflict.version}（免打扰：
                        {strategyConflict.autoDnd ? '开' : '关'}，通知静默：
                        {strategyConflict.notifySilence ? '开' : '关'}）。
                      </span>
                      <div class="row-wrap">
                        <button
                          type="button"
                          class="btn btn-sm btn-primary"
                          onClick={() => {
                            setStrategyData(strategyConflict);
                            setAutoDnd(Boolean(strategyConflict.autoDnd));
                            setNotifySilence(Boolean(strategyConflict.notifySilence));
                            setStrategyConflict(null);
                          }}
                        >
                          加载最新策略
                        </button>
                        <button
                          type="button"
                          class="btn btn-sm btn-vermilion"
                          onClick={() => void saveStrategy(strategyConflict.version)}
                        >
                          基于 v{strategyConflict.version} 覆盖保存
                        </button>
                      </div>
                    </div>
                  </div>
                )}

                <div class="row-wrap">
                  <button
                    type="button"
                    class="btn btn-primary"
                    disabled={strategySaving}
                    onClick={() => void saveStrategy()}
                  >
                    {strategySaving ? '保存中…' : '保存策略'}
                  </button>
                  <button
                    type="button"
                    class="btn btn-ghost"
                    onClick={() => setStrategyPlan(null)}
                  >
                    取消
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
