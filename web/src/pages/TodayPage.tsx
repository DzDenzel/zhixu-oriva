import { useEffect, useState } from 'preact/hooks';
import type { ApiClient } from '../api/client';
import { ApiError } from '../api/client';
import { SundialDial } from '../components/SundialDial';
import type { MeResponse, PlanItem, ScheduleItem, TodaySummary } from '../types';
import {
  formatTimeShort,
  getGreeting,
  mergePlanTypes,
  resolvePlanTypeMeta,
  todayIsoDate,
  type PlanTypeOption,
} from '../utils/format';
import { navigateTo } from '../utils/router';

export interface TodayPageProps {
  api: ApiClient;
  me: MeResponse | null;
}

export function TodayPage({ api, me }: TodayPageProps) {
  const [todaySummary, setTodaySummary] = useState<TodaySummary | null>(null);
  const [plans, setPlans] = useState<PlanItem[]>([]);
  const [schedules, setSchedules] = useState<ScheduleItem[]>([]);
  const [planTypes, setPlanTypes] = useState<PlanTypeOption[]>(mergePlanTypes());
  const [loading, setLoading] = useState(true);
  const [errorText, setErrorText] = useState<string | null>(null);

  async function loadAll() {
    setLoading(true);
    setErrorText(null);
    try {
      const [todayData, planList, scheduleList, syncMeta] = await Promise.all([
        api.getToday(),
        api.listPlans(),
        api.listSchedules(),
        api.pullSyncMetadata().catch(() => null),
      ]);
      setTodaySummary(todayData);
      setPlans(planList);
      setSchedules(scheduleList);
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
    void loadAll();
  }, []);

  const todayDate = todaySummary?.date || todayIsoDate();
  const activePlans = plans.filter((p) => p.status === 1);
  const todaySchedules = schedules.filter(
    (s) => s.status !== 0 && s.startDate <= todayDate && s.endDate >= todayDate,
  );
  const executions = todaySummary?.executions ?? [];
  const completedIds = new Set(executions.filter((e) => e.status === 1).map((e) => e.planId));
  const completedCount = activePlans.filter((p) => completedIds.has(p.id)).length;
  const progressPercent = activePlans.length > 0 ? Math.round((completedCount / activePlans.length) * 100) : 0;

  let zhixingBrief = '今日纸面清静，按自己的节奏安排即可。';
  if (activePlans.length > 0 && completedCount < activePlans.length) {
    const nextPlan = activePlans.find((p) => !completedIds.has(p.id));
    if (nextPlan) {
      zhixingBrief = `今日还有 ${activePlans.length - completedCount} 项计划待完成，下一项为「${nextPlan.name}」（${formatTimeShort(nextPlan.startTime)}–${formatTimeShort(nextPlan.endTime)}）。`;
    }
  } else if (activePlans.length > 0 && completedCount === activePlans.length) {
    zhixingBrief = `今日 ${activePlans.length} 项启用计划均已有完成记录，辛苦了。`;
  } else if (todaySchedules.length > 0) {
    zhixingBrief = `今天有「${todaySchedules[0].title}」等 ${todaySchedules.length} 项日程。`;
  }

  return (
    <div class="stack-lg">
      <header class="page-header">
        <div>
          <p class="eyebrow">
            {todayDate} · {me?.instanceLabel || '个人数字站'}
          </p>
          <h1 class="page-title">{getGreeting()}，今日总览</h1>
          <p class="page-subtitle">{zhixingBrief}</p>
        </div>
        <div class="header-actions">
          <button type="button" class="btn" onClick={() => void loadAll()} disabled={loading}>
            刷新数据
          </button>
          <button
            type="button"
            class="btn btn-vermilion"
            onClick={() => navigateTo('/plans')}
          >
            管理计划
          </button>
        </div>
      </header>

      {errorText && (
        <div class="notice notice-crimson" role="alert">
          <span>{errorText}</span>
        </div>
      )}

      <div class="notice notice-brass" role="note">
        <div>
          <strong>浏览器物理边界说明（ADR-022 D4）：</strong>
          <span class="muted">
            Web 数字站提供同源规划、日程、我的节奏、日常记录与设备/插件目录管理；系统级精确闹钟、前台执行胶囊、应用使用时长/免打扰观测及本地 .plp/.pljs 插件执行均由 Android / iOS 移动端 App 承载。
          </span>
        </div>
      </div>

      <section class="grid-2">
        <div class="paper-card stack-md">
          <h2 class="section-title">今日 24 小时日晷</h2>
          <SundialDial
            plans={activePlans}
            todaySchedules={todaySchedules}
            planTypes={planTypes}
          />
          <div class="stack-sm">
            <div class="row-between">
              <span class="muted">今日计划达成进度</span>
              <span class="numeral">
                {completedCount} / {activePlans.length}（{progressPercent}%）
              </span>
            </div>
            <progress class="ink-progress" value={progressPercent} max={100} />
          </div>
        </div>

        <div class="stack-md">
          <div class="grid-3">
            <div class="paper-card">
              <div class="eyebrow">今日完成记录</div>
              <div class="numeral numeral-lg">{executions.filter((e) => e.status === 1).length}</div>
            </div>
            <div class="paper-card">
              <div class="eyebrow">提前解锁</div>
              <div class="numeral numeral-lg">{todaySummary?.unlockCount ?? 0}</div>
            </div>
            <div class="paper-card">
              <div class="eyebrow">违规结束</div>
              <div class="numeral numeral-lg">{todaySummary?.violationCount ?? 0}</div>
            </div>
          </div>

          <div class="paper-card stack-sm">
            <div class="row-between">
              <h2 class="section-title">今日日程</h2>
              <button
                type="button"
                class="btn btn-sm btn-ghost"
                onClick={() => navigateTo('/schedules')}
              >
                全部日程
              </button>
            </div>
            {loading ? (
              <p class="muted">正在加载今日日程…</p>
            ) : todaySchedules.length === 0 ? (
              <p class="muted">今日暂无日程安排。</p>
            ) : (
              <div class="stack-sm">
                {todaySchedules.map((item) => (
                  <div key={item.id} class="paper-card-sunk row-between">
                    <div>
                      <strong>{item.title}</strong>
                      <div class="ledger-meta">
                        {item.allDay
                          ? '全天'
                          : `${formatTimeShort(item.startTime)} – ${formatTimeShort(item.endTime)}`}
                        {item.location ? ` · ${item.location}` : ''}
                      </div>
                    </div>
                    <span class="tag tag-brass">日程</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </section>

      <section class="paper-card stack-md">
        <div class="row-between">
          <h2 class="section-title">今日计划清单</h2>
          <span class="muted">共 {activePlans.length} 项启用中</span>
        </div>

        {loading ? (
          <p class="muted">正在加载今日计划…</p>
        ) : activePlans.length === 0 ? (
          <div class="empty-state">
            <p>暂无启用中的计划。</p>
            <button type="button" class="btn btn-primary" onClick={() => navigateTo('/plans')}>
              前往创建第一个计划
            </button>
          </div>
        ) : (
          <div class="ledger-list">
            {activePlans.map((plan) => {
              const meta = resolvePlanTypeMeta(plan.type, planTypes);
              const done = completedIds.has(plan.id);
              return (
                <div key={plan.id} class="ledger-item">
                  <div class={`ledger-time ${meta.hueClass}`}>
                    {formatTimeShort(plan.startTime)} – {formatTimeShort(plan.endTime)}
                  </div>
                  <div class="ledger-body">
                    <h3 class="ledger-title">{plan.name}</h3>
                    <div class="ledger-meta">
                      {meta.label} · 提前 {plan.remindAdvanceMin ?? 15} 分钟提醒
                    </div>
                  </div>
                  <span class={`tag ${done ? 'tag-jade' : 'tag-indigo'}`}>
                    {done ? '今日已达成' : '待执行'}
                  </span>
                </div>
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
}
