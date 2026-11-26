import { useEffect, useState } from 'preact/hooks';
import type { ApiClient } from '../api/client';
import { ApiError, StaleSessionError } from '../api/client';
import { ConflictModal, type ConflictFieldRow } from '../components/ConflictModal';
import type { PlanItem, StrategyItem } from '../types';
import { triggerTextDownload } from '../utils/export';
import {
  formatRepeatRule,
  formatTimeShort,
  mergePlanTypes,
  resolvePlanTypeMeta,
  todayIsoDate,
  type PlanTypeOption,
} from '../utils/format';
import {
  buildPlanUpsertPayload,
  buildStrategyUpsertPayload,
  emptyPlanForm,
  planFormFromItem,
  withPlanField,
  type PlanFormState,
  type PlanRepeatMode,
} from '../utils/payloads';

export interface PlansPageProps {
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
    const gen = api.getSessionGeneration();
    setLoading(true);
    setErrorText(null);
    try {
      const [list, syncMeta] = await Promise.all([
        api.listPlans(),
        api.pullSyncMetadata().catch(() => null),
      ]);
      if (!api.isGenerationCurrent(gen)) return;
      setPlans(list);
      if (syncMeta?.planTypes) {
        setPlanTypes(mergePlanTypes(syncMeta.planTypes));
      }
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
    void loadPlans();
  }, []);

  async function handleSavePlan(evt?: Event, mergeServerSnapshot?: PlanItem) {
    evt?.preventDefault();
    if (!editing) return;
    if (!editing.name.trim()) {
      onNotify('请填写计划名称', 'warn');
      return;
    }
    const gen = api.getSessionGeneration();
    setSaving(true);
    try {
      const payload = buildPlanUpsertPayload(editing, mergeServerSnapshot ?? null);
      if (editing.id === null) {
        await api.createPlan(payload);
        if (!api.isGenerationCurrent(gen)) return;
        onNotify('已创建新计划');
      } else {
        const baseVersion = mergeServerSnapshot?.version ?? editing.version ?? 0;
        await api.updatePlan(editing.id, baseVersion, payload);
        if (!api.isGenerationCurrent(gen)) return;
        onNotify('计划已更新');
      }
      setEditing(null);
      setConflictServer(null);
      await loadPlans();
    } catch (err) {
      if (err instanceof StaleSessionError) return;
      if (err instanceof ApiError && err.isConflict && editing.id !== null) {
        try {
          const latestList = await api.listPlans();
          if (!api.isGenerationCurrent(gen)) return;
          setPlans(latestList);
          const latest = latestList.find((p) => p.id === editing.id) ?? null;
          setConflictServer(latest);
          if (!latest) {
            onNotify('该计划已在其他设备被删除', 'warn');
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

  async function handleTogglePause(plan: PlanItem) {
    const gen = api.getSessionGeneration();
    try {
      const updated = await api.pausePlan(plan.id, todayIsoDate());
      if (!api.isGenerationCurrent(gen)) return;
      setPlans((prev) => prev.map((p) => (p.id === updated.id ? updated : p)));
      onNotify(updated.status === 1 ? `已恢复「${updated.name}」` : `已暂停「${updated.name}」`);
    } catch (err) {
      if (err instanceof StaleSessionError) return;
      if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    }
  }

  async function handleDeletePlan(plan: PlanItem) {
    const gen = api.getSessionGeneration();
    try {
      await api.deletePlan(plan.id, plan.version);
      if (!api.isGenerationCurrent(gen)) return;
      setPlans((prev) => prev.filter((p) => p.id !== plan.id));
      onNotify(`已删除「${plan.name}」`);
    } catch (err) {
      if (err instanceof StaleSessionError) return;
      if (err instanceof ApiError && err.isConflict) {
        onNotify('该计划版本已变更（1409），已刷新最新列表', 'warn');
        await loadPlans();
      } else if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    }
  }

  async function handleExportIcs() {
    const gen = api.getSessionGeneration();
    try {
      const ics = await api.exportPlansIcs();
      if (!api.isGenerationCurrent(gen)) return;
      triggerTextDownload(`oriva-plans-${todayIsoDate()}.ics`, ics, 'text/calendar;charset=utf-8');
      onNotify('已导出计划日历（.ics）');
    } catch (err) {
      if (err instanceof StaleSessionError) return;
      if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    }
  }

  async function openStrategyModal(plan: PlanItem) {
    const gen = api.getSessionGeneration();
    setStrategyPlan(plan);
    setStrategyData(null);
    setStrategyConflict(null);
    setStrategyLoading(true);
    try {
      const st = await api.getPlanStrategy(plan.id);
      if (!api.isGenerationCurrent(gen)) return;
      setStrategyData(st);
      setAutoDnd(Boolean(st.autoDnd));
      setNotifySilence(Boolean(st.notifySilence));
    } catch (err) {
      if (err instanceof StaleSessionError) return;
      if (err instanceof ApiError && err.isNotFound) {
        setStrategyData(null);
        setAutoDnd(false);
        setNotifySilence(false);
      } else if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
        setStrategyPlan(null);
      }
    } finally {
      if (api.isGenerationCurrent(gen)) {
        setStrategyLoading(false);
      }
    }
  }

  /**
   * 保存策略（Astra B6）：
   * 1409 冲突重试时强制传入 strategyConflict 最新快照，同时从该最新快照取 version、
   * blacklistApps 与 whitelistApps，仅合并两个布尔开关，绝不写回旧黑白名单。
   */
  async function saveStrategy(latestSnapshotOverride?: StrategyItem) {
    if (!strategyPlan) return;
    const gen = api.getSessionGeneration();
    const baseSnapshot = latestSnapshotOverride ?? strategyData;
    const { payload, baseVersion } = buildStrategyUpsertPayload(baseSnapshot, {
      autoDnd,
      notifySilence,
    });
    setStrategySaving(true);
    try {
      const saved = await api.upsertPlanStrategy(strategyPlan.id, payload, baseVersion);
      if (!api.isGenerationCurrent(gen)) return;
      setStrategyData(saved);
      setStrategyConflict(null);
      setStrategyPlan(null);
      onNotify(`已更新「${strategyPlan.name}」执行策略`);
    } catch (err) {
      if (err instanceof StaleSessionError) return;
      if (err instanceof ApiError && err.isConflict) {
        try {
          const latest = await api.getPlanStrategy(strategyPlan.id);
          if (!api.isGenerationCurrent(gen)) return;
          setStrategyConflict(latest);
        } catch (reloadErr) {
          if (reloadErr instanceof StaleSessionError) return;
          onNotify('策略版本冲突（1409），请重新打开', 'warn');
        }
      } else if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    } finally {
      if (api.isGenerationCurrent(gen)) {
        setStrategySaving(false);
      }
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
            label: '重复规则',
            draftValue: editing.dirtyFields.has('repeatRule')
              ? formatRepeatRule(buildPlanUpsertPayload(editing).repeatRule)
              : '（未修改，合并时保留服务端最新）',
            serverValue: formatRepeatRule(conflictServer.repeatRule),
          },
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
              <article key={plan.id} class={`ledger-item${isPaused ? ' is-paused' : ''}`}>
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
                    onClick={() => setEditing(planFormFromItem(plan))}
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
                    setEditing(
                      withPlanField(
                        editing,
                        'name',
                        (evt.currentTarget as HTMLInputElement).value,
                        'name',
                      ),
                    )
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
                      onClick={() => setEditing(withPlanField(editing, 'type', opt.key, 'type'))}
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
                      setEditing(
                        withPlanField(
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
                  <span class="field-label">结束时间 *</span>
                  <input
                    type="time"
                    class="input"
                    name="endTime"
                    required
                    value={editing.endTime}
                    onInput={(evt) =>
                      setEditing(
                        withPlanField(
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

              <div class="grid-2">
                <label class="field">
                  <span class="field-label">重复规则</span>
                  <select
                    class="select"
                    value={editing.repeatType}
                    onChange={(evt) =>
                      setEditing(
                        withPlanField(
                          editing,
                          'repeatType',
                          (evt.currentTarget as HTMLSelectElement).value as PlanRepeatMode,
                          'repeatRule',
                        ),
                      )
                    }
                  >
                    {editing.originalRepeatRule && editing.repeatType === 'preserve-custom' && (
                      <option value="preserve-custom">
                        保留原有规则（{formatRepeatRule(editing.originalRepeatRule)}）
                      </option>
                    )}
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
                      setEditing(
                        withPlanField(
                          editing,
                          'remindAdvanceMin',
                          Number((evt.currentTarget as HTMLInputElement).value) || 0,
                          'remindAdvanceMin',
                        ),
                      )
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
                            setEditing(
                              withPlanField(
                                editing,
                                'weekdays',
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
          description="提交的 baseVersion 与服务端最新版本不一致（1409）。选择「以我的草稿覆盖最新版本」时，系统将基于服务端最新快照仅合并您明确改动的字段，保留移动端未修改字段与自定义日期。"
          rows={conflictRows}
          busy={saving}
          onReloadServer={() => {
            setEditing(planFormFromItem(conflictServer));
            setConflictServer(null);
            onNotify('已加载服务端最新计划版本');
          }}
          onOverwriteWithLatest={() => {
            void handleSavePlan(undefined, conflictServer);
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
                    黑名单应用：{(strategyConflict ?? strategyData)?.blacklistApps?.length ?? 0} 项 ·
                    白名单应用：{(strategyConflict ?? strategyData)?.whitelistApps?.length ?? 0} 项
                  </div>
                  <div class="faint">
                    保存或冲突重试时均会基于服务端最新快照原样透传移动端应用黑白名单，仅更新上述两个开关。
                  </div>
                </div>

                {strategyConflict && (
                  <div class="notice notice-brass">
                    <div class="stack-sm">
                      <strong>检测到策略版本冲突（1409）</strong>
                      <span>
                        服务端已更新至 v{strategyConflict.version}（免打扰：
                        {strategyConflict.autoDnd ? '开' : '关'}，通知静默：
                        {strategyConflict.notifySilence ? '开' : '关'}，黑名单：
                        {strategyConflict.blacklistApps?.length ?? 0} 项，白名单：
                        {strategyConflict.whitelistApps?.length ?? 0} 项）。
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
                          onClick={() => void saveStrategy(strategyConflict)}
                        >
                          保留最新名单并基于 v{strategyConflict.version} 保存开关
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
