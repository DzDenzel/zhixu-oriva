import { render } from 'preact';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../src/App';
import { ApiError, type ApiClient } from '../src/api/client';
import { JournalPage } from '../src/pages/JournalPage';
import { LoginPage } from '../src/pages/LoginPage';
import { PlansPage } from '../src/pages/PlansPage';
import { PluginsPage } from '../src/pages/PluginsPage';
import { SchedulesPage } from '../src/pages/SchedulesPage';
import type { JournalItem, PlanItem, PluginCatalogDocument, ScheduleItem, StrategyItem } from '../src/types';
import {
  buildPlanUpsertPayload,
  buildScheduleUpsertPayload,
  buildStrategyUpsertPayload,
  planFormFromItem,
  scheduleFormFromItem,
  withPlanField,
  withScheduleField,
} from '../src/utils/payloads';
import { resetSessionGuardForTest, writeSessionToken } from '../src/utils/session';

async function flushUi(): Promise<void> {
  await Promise.resolve();
  await new Promise((resolve) => setTimeout(resolve, 25));
  await Promise.resolve();
  await new Promise((resolve) => setTimeout(resolve, 25));
}

function baseClientStub(overrides: Partial<ApiClient>): ApiClient {
  return {
    getSessionGeneration: () => 1,
    isGenerationCurrent: () => true,
    ...overrides,
  } as unknown as ApiClient;
}

describe('B6 & B7 payload fidelity: custom dates, multi-weekday, strategy 1409 & schedule coordinate conflict merge', () => {
  it('preserves plan custom dates on ordinary rename and merges only dirty fields on 1409 overwrite', () => {
    const plan: PlanItem = {
      id: 1,
      userId: 1,
      clientId: 'cli-1',
      name: '备考冲刺',
      type: '学习',
      startTime: '08:00:00',
      endTime: '10:00:00',
      repeatRule: { type: 'custom', dates: ['2026-12-01', '2026-12-03'] },
      color: 'study',
      icon: 'book',
      remindAdvanceMin: 15,
      status: 1,
      version: 2,
    };

    const form = withPlanField(planFormFromItem(plan), 'name', '备考冲刺（改名）', 'name');
    const normalPayload = buildPlanUpsertPayload(form);
    expect(normalPayload.name).toBe('备考冲刺（改名）');
    expect(normalPayload.repeatRule).toEqual({
      type: 'custom',
      dates: ['2026-12-01', '2026-12-03'],
    });

    // Server concurrently updated custom dates and startTime on mobile -> 1409 overwrite MUST keep server's newer dates & startTime!
    const serverLatest: PlanItem = {
      ...plan,
      startTime: '07:30:00',
      repeatRule: { type: 'custom', dates: ['2026-12-01', '2026-12-03', '2026-12-05'] },
      version: 3,
    };
    const mergedPayload = buildPlanUpsertPayload(form, serverLatest);
    expect(mergedPayload.name).toBe('备考冲刺（改名）');
    expect(mergedPayload.startTime).toBe('07:30:00');
    expect(mergedPayload.repeatRule).toEqual({
      type: 'custom',
      dates: ['2026-12-01', '2026-12-03', '2026-12-05'],
    });
  });

  it('takes latest blacklistApps/whitelistApps from 1409 strategy snapshot instead of stale state (B6)', () => {
    const latestServerStrategy: StrategyItem = {
      id: 5,
      planId: 10,
      blacklistApps: [
        { packageName: 'com.game.a', appName: 'A' },
        { packageName: 'com.video.b', appName: 'B' },
      ],
      whitelistApps: [{ packageName: 'com.notes.c', appName: 'C' }],
      autoDnd: false,
      notifySilence: true,
      version: 7,
    };

    const { payload, baseVersion } = buildStrategyUpsertPayload(latestServerStrategy, {
      autoDnd: true,
      notifySilence: false,
    });
    expect(baseVersion).toBe(7);
    expect(payload.autoDnd).toBe(true);
    expect(payload.notifySilence).toBe(false);
    expect(payload.blacklistApps).toEqual(latestServerStrategy.blacklistApps);
    expect(payload.whitelistApps).toEqual(latestServerStrategy.whitelistApps);
  });

  it('preserves multi-weekday schedule repeatRule and concurrent mobile coordinates on 1409 overwrite (B7)', () => {
    const original: ScheduleItem = {
      id: 21,
      userId: 1,
      title: '健身课',
      startDate: '2026-11-30',
      endDate: '2026-11-30',
      startTime: '18:00:00',
      endTime: '19:00:00',
      allDay: false,
      location: '旧馆',
      locationLat: 31.2304,
      locationLon: 121.4737,
      repeatRule: '{"type":"weekly","weekdays":[1,3,5]}',
      status: 1,
      version: 4,
    };

    const renamedForm = withScheduleField(scheduleFormFromItem(original), 'title', '力量训练', 'title');
    const normalPayload = buildScheduleUpsertPayload(renamedForm);
    expect(normalPayload.repeatRule).toBe('{"type":"weekly","weekdays":[1,3,5]}');
    expect(normalPayload.locationLat).toBe(31.2304);
    expect(normalPayload.locationLon).toBe(121.4737);

    // Mobile updated coordinates to new gym -> 1409 overwrite must preserve mobile's new coordinates!
    const serverLatest: ScheduleItem = {
      ...original,
      location: '新馆',
      locationLat: 39.9042,
      locationLon: 116.4074,
      repeatRule: '{"type":"weekly","weekdays":[1,3,5,6]}',
      version: 5,
    };
    const merged = buildScheduleUpsertPayload(renamedForm, serverLatest);
    expect(merged.title).toBe('力量训练');
    expect(merged.location).toBe('新馆');
    expect(merged.locationLat).toBe(39.9042);
    expect(merged.locationLon).toBe(116.4074);
    expect(merged.repeatRule).toBe('{"type":"weekly","weekdays":[1,3,5,6]}');

    // Explicit clear coords still clears on overwrite
    const clearedForm = withScheduleField(renamedForm, 'coordsCleared', true, 'coordsCleared');
    const mergedCleared = buildScheduleUpsertPayload(clearedForm, serverLatest);
    expect(mergedCleared.locationLat).toBeNull();
    expect(mergedCleared.locationLon).toBeNull();
  });

  it('preserves HH:mm:ss seconds, remindAdvanceMin=null, tzId=null on ordinary edit, and clears repeatRule dirty bit when reverted to preserve (R4)', () => {
    const planWithSecondsAndNullRemind: PlanItem = {
      id: 31,
      userId: 1,
      name: '晨间冥想',
      type: '习惯',
      startTime: '06:45:30',
      endTime: '07:15:45',
      repeatRule: { type: 'custom', dates: ['2026-12-01', '2026-12-03'] },
      remindAdvanceMin: null,
      status: 1,
      version: 2,
    };

    // Rename only -> must preserve exact seconds and remindAdvanceMin=null (not coerce to 15!)
    let planForm = withPlanField(planFormFromItem(planWithSecondsAndNullRemind), 'name', '晨间正念', 'name');
    const ordinaryPlanPayload = buildPlanUpsertPayload(planForm);
    expect(ordinaryPlanPayload.startTime).toBe('06:45:30');
    expect(ordinaryPlanPayload.endTime).toBe('07:15:45');
    expect(ordinaryPlanPayload.remindAdvanceMin).toBeNull();

    // Switch custom -> daily -> back to preserve-custom MUST clear 'repeatRule' dirty bit
    planForm = withPlanField(planForm, 'repeatType', 'daily', 'repeatRule');
    expect(planForm.dirtyFields.has('repeatRule')).toBe(true);
    planForm = withPlanField(planForm, 'repeatType', 'preserve-custom', 'repeatRule');
    expect(planForm.dirtyFields.has('repeatRule')).toBe(false);

    // On 1409 overwrite, serverLatest custom dates & remindAdvanceMin=null MUST be preserved
    const serverPlanLatest: PlanItem = {
      ...planWithSecondsAndNullRemind,
      repeatRule: { type: 'custom', dates: ['2026-12-01', '2026-12-03', '2026-12-09'] },
      remindAdvanceMin: null,
      version: 3,
    };
    const mergedPlan = buildPlanUpsertPayload(planForm, serverPlanLatest);
    expect(mergedPlan.repeatRule).toEqual({
      type: 'custom',
      dates: ['2026-12-01', '2026-12-03', '2026-12-09'],
    });
    expect(mergedPlan.remindAdvanceMin).toBeNull();
    expect(mergedPlan.startTime).toBe('06:45:30');

    // Schedule with seconds & tzId: null
    const schedWithSecondsAndNullTz: ScheduleItem = {
      id: 41,
      userId: 1,
      title: '团队周会',
      startDate: '2026-12-01',
      endDate: '2026-12-01',
      startTime: '14:05:30',
      endTime: '15:10:45',
      allDay: false,
      repeatRule: '{"type":"weekly","weekdays":[1,3,5]}',
      tzId: null,
      status: 1,
      version: 1,
    };

    let schedForm = withScheduleField(scheduleFormFromItem(schedWithSecondsAndNullTz), 'title', '产品周会', 'title');
    const ordinarySchedPayload = buildScheduleUpsertPayload(schedForm);
    expect(ordinarySchedPayload.startTime).toBe('14:05:30');
    expect(ordinarySchedPayload.endTime).toBe('15:10:45');
    expect(ordinarySchedPayload.tzId).toBeNull();

    // Switch preserve -> daily -> back to preserve MUST clear 'repeatRule' dirty bit
    schedForm = withScheduleField(schedForm, 'repeatMode', 'daily', 'repeatRule');
    expect(schedForm.dirtyFields.has('repeatRule')).toBe(true);
    schedForm = withScheduleField(schedForm, 'repeatMode', 'preserve', 'repeatRule');
    expect(schedForm.dirtyFields.has('repeatRule')).toBe(false);

    const serverSchedLatest: ScheduleItem = {
      ...schedWithSecondsAndNullTz,
      repeatRule: '{"type":"weekly","weekdays":[1,3,5,6]}',
      tzId: null,
      version: 2,
    };
    const mergedSched = buildScheduleUpsertPayload(schedForm, serverSchedLatest);
    expect(mergedSched.repeatRule).toBe('{"type":"weekly","weekdays":[1,3,5,6]}');
    expect(mergedSched.tzId).toBeNull();
    expect(mergedSched.startTime).toBe('14:05:30');
    expect(mergedSched.endTime).toBe('15:10:45');
  });

  it('compares schedule repeatRule semantically (ignoring whitespace/key order/extra backend fields) and preserves server times when both sides switch allDay->timed on 1409 (R4 v1.2)', () => {
    // 1) Non-canonical whitespace, reversed key order, unsorted weekdays, and extra backend field
    const nonCanonicalRule = '{  "weekdays" : [ 5, 1, 3 ],  "type" : "weekly",  "interval" : 2  }';
    const schedWithNonCanonical: ScheduleItem = {
      id: 51,
      userId: 1,
      title: '英语口语',
      startDate: '2026-12-01',
      endDate: '2026-12-01',
      startTime: '08:00:00',
      endTime: '09:00:00',
      allDay: false,
      repeatRule: nonCanonicalRule,
      status: 1,
      version: 3,
    };

    let form = scheduleFormFromItem(schedWithNonCanonical);
    // Change to daily (dirty), then pick weekly (with parsed [1, 3, 5], semantically identical to original!)
    form = withScheduleField(form, 'repeatMode', 'daily', 'repeatRule');
    expect(form.dirtyFields.has('repeatRule')).toBe(true);
    form = withScheduleField(form, 'repeatMode', 'weekly', 'repeatRule');
    // Semantic match MUST clear repeatRule dirty bit!
    expect(form.dirtyFields.has('repeatRule')).toBe(false);

    // Ordinary save preserves exact original JSON (including whitespace, key order, and unexposed interval=2)
    expect(buildScheduleUpsertPayload(form).repeatRule).toBe(nonCanonicalRule);

    // 1409 overwrite MUST keep server's concurrently updated repeatRule instead of overwriting with local!
    const serverUpdatedRule: ScheduleItem = {
      ...schedWithNonCanonical,
      repeatRule: '{"type":"weekly","weekdays":[1,3,5,6],"interval":2}',
      version: 4,
    };
    expect(buildScheduleUpsertPayload(form, serverUpdatedRule).repeatRule).toBe(
      '{"type":"weekly","weekdays":[1,3,5,6],"interval":2}',
    );

    // 2) Both Web and Mobile switch an allDay=true schedule to timed (allDay=false); only Mobile edits startTime/endTime
    const allDayOrig: ScheduleItem = {
      id: 52,
      userId: 1,
      title: '季度复盘',
      startDate: '2026-12-05',
      endDate: '2026-12-05',
      startTime: null,
      endTime: null,
      allDay: true,
      status: 1,
      version: 1,
    };
    const webSwitchedOnlyAllDay = withScheduleField(scheduleFormFromItem(allDayOrig), 'allDay', false, 'allDay');
    expect(webSwitchedOnlyAllDay.dirtyFields.has('allDay')).toBe(true);
    expect(webSwitchedOnlyAllDay.dirtyFields.has('startTime')).toBe(false);
    expect(webSwitchedOnlyAllDay.dirtyFields.has('endTime')).toBe(false);

    const mobileSwitchedAndSetTime: ScheduleItem = {
      ...allDayOrig,
      allDay: false,
      startTime: '14:30:00',
      endTime: '16:00:00',
      version: 2,
    };
    const mergedAllDayConflict = buildScheduleUpsertPayload(webSwitchedOnlyAllDay, mobileSwitchedAndSetTime);
    expect(mergedAllDayConflict.allDay).toBe(false);
    expect(mergedAllDayConflict.startTime).toBe('14:30:00');
    expect(mergedAllDayConflict.endTime).toBe('16:00:00');

    // If Web explicitly edited startTime only, Web's startTime wins while Mobile's endTime is still preserved
    const webAlsoEditedStart = withScheduleField(webSwitchedOnlyAllDay, 'startTime', '13:45', 'startTime');
    const mergedPartialTime = buildScheduleUpsertPayload(webAlsoEditedStart, mobileSwitchedAndSetTime);
    expect(mergedPartialTime.startTime).toBe('13:45:00');
    expect(mergedPartialTime.endTime).toBe('16:00:00');
  });

  it('preserves server extension fields when actively editing same-type repeatRule on ordinary save & 1409 retry, and drops them cleanly on type switch/none (R4 v1.3)', () => {
    // 1) Schedule weekly rule with extension fields (interval, customExt, tz)
    const schedOrig: ScheduleItem = {
      id: 61,
      userId: 1,
      title: '英语听读',
      startDate: '2026-12-01',
      endDate: '2026-12-01',
      startTime: '08:00:00',
      endTime: '09:00:00',
      allDay: false,
      repeatRule: JSON.stringify({
        type: 'weekly',
        weekdays: [1, 3],
        interval: 2,
        customExt: 'mobile-v1',
      }),
      status: 1,
      version: 2,
    };

    // UI edits weekdays from [1, 3] -> [1, 3, 5]
    let schedForm = withScheduleField(scheduleFormFromItem(schedOrig), 'repeatMode', 'weekly', 'repeatRule');
    schedForm = withScheduleField(schedForm, 'repeatWeekdays', [1, 3, 5], 'repeatRule');
    expect(schedForm.dirtyFields.has('repeatRule')).toBe(true);

    // Ordinary save MUST preserve interval=2 and customExt='mobile-v1' alongside new weekdays [1,3,5]
    const ordinaryRule = JSON.parse(buildScheduleUpsertPayload(schedForm).repeatRule ?? '{}');
    expect(ordinaryRule).toEqual({
      type: 'weekly',
      weekdays: [1, 3, 5],
      interval: 2,
      customExt: 'mobile-v1',
    });

    // 1409 conflict: mobile concurrently updated customExt -> 'mobile-v2' and added skipHolidays: true
    const serverLatestSched: ScheduleItem = {
      ...schedOrig,
      repeatRule: JSON.stringify({
        type: 'weekly',
        weekdays: [1, 3],
        interval: 2,
        customExt: 'mobile-v2',
        skipHolidays: true,
      }),
      version: 3,
    };
    const mergedConflictRule = JSON.parse(
      buildScheduleUpsertPayload(schedForm, serverLatestSched).repeatRule ?? '{}',
    );
    expect(mergedConflictRule).toEqual({
      type: 'weekly',
      weekdays: [1, 3, 5],
      interval: 2,
      customExt: 'mobile-v2',
      skipHolidays: true,
    });

    // Also verify days-alias weekly rule preserves extension fields and updates days key
    const schedDaysAlias: ScheduleItem = {
      ...schedOrig,
      id: 62,
      repeatRule: JSON.stringify({
        type: 'weekly',
        days: [1, 3],
        interval: 2,
        customExt: 'mobile-v1',
      }),
    };
    let aliasForm = withScheduleField(scheduleFormFromItem(schedDaysAlias), 'repeatMode', 'weekly', 'repeatRule');
    aliasForm = withScheduleField(aliasForm, 'repeatWeekdays', [1, 3, 5], 'repeatRule');
    expect(JSON.parse(buildScheduleUpsertPayload(aliasForm).repeatRule ?? '{}')).toEqual({
      type: 'weekly',
      days: [1, 3, 5],
      interval: 2,
      customExt: 'mobile-v1',
    });

    // Switching schedule rule to daily or none produces clean new-type contract (no leftover weekly/ext keys)
    const dailyForm = withScheduleField(schedForm, 'repeatMode', 'daily', 'repeatRule');
    expect(JSON.parse(buildScheduleUpsertPayload(dailyForm, serverLatestSched).repeatRule ?? '{}')).toEqual({
      type: 'daily',
    });
    const noneForm = withScheduleField(schedForm, 'repeatMode', 'none', 'repeatRule');
    expect(buildScheduleUpsertPayload(noneForm, serverLatestSched).repeatRule).toBeNull();

    // 2) Plan weekly & custom rules with extension fields
    const planOrig: PlanItem = {
      id: 71,
      userId: 1,
      name: '算法训练',
      type: '学习',
      startTime: '20:00:00',
      endTime: '21:30:00',
      repeatRule: {
        type: 'weekly',
        weekdays: [1, 3],
        interval: 2,
        customExt: 'mobile-v1',
      },
      remindAdvanceMin: 10,
      status: 1,
      version: 4,
    };
    const planEditedWeekly = withPlanField(planFormFromItem(planOrig), 'weekdays', [1, 3, 5], 'repeatRule');
    expect(buildPlanUpsertPayload(planEditedWeekly).repeatRule).toEqual({
      type: 'weekly',
      weekdays: [1, 3, 5],
      interval: 2,
      customExt: 'mobile-v1',
    });

    const serverLatestPlan: PlanItem = {
      ...planOrig,
      repeatRule: {
        type: 'weekly',
        weekdays: [1, 3],
        interval: 2,
        customExt: 'mobile-v2',
        endDate: '2026-12-31',
      },
      version: 5,
    };
    expect(buildPlanUpsertPayload(planEditedWeekly, serverLatestPlan).repeatRule).toEqual({
      type: 'weekly',
      weekdays: [1, 3, 5],
      interval: 2,
      customExt: 'mobile-v2',
      endDate: '2026-12-31',
    });

    // Switching Plan from weekly -> daily drops old weekly & extension keys cleanly
    const planSwitchedDaily = withPlanField(planEditedWeekly, 'repeatType', 'daily', 'repeatRule');
    expect(buildPlanUpsertPayload(planSwitchedDaily, serverLatestPlan).repeatRule).toEqual({
      type: 'daily',
    });

    // Plan custom rule with extension fields preserved intact on ordinary save and 1409 retry
    const customPlan: PlanItem = {
      ...planOrig,
      id: 72,
      repeatRule: {
        type: 'custom',
        dates: ['2026-12-01', '2026-12-03'],
        interval: 1,
        customExt: 'mobile-custom-v1',
      },
    };
    const customRenamed = withPlanField(planFormFromItem(customPlan), 'name', '算法冲刺', 'name');
    expect(buildPlanUpsertPayload(customRenamed).repeatRule).toEqual({
      type: 'custom',
      dates: ['2026-12-01', '2026-12-03'],
      interval: 1,
      customExt: 'mobile-custom-v1',
    });
  });
});

describe('Core views: XSS neutralization, 1409 conflict reload/merge, and B5 trust copy', () => {
  let container: HTMLDivElement;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
  });

  afterEach(() => {
    render(null, container);
    container.remove();
  });

  it('displays deployment rate-limit guidance on LoginPage', async () => {
    const api = baseClientStub({
      login: vi.fn().mockRejectedValue(new ApiError(1002, '登录尝试过多，请 15 分钟后重试')),
    });

    render(
      <LoginPage
        api={api}
        theme="light"
        noticeMessage={null}
        onToggleTheme={() => {}}
        onLoginSuccess={async () => {}}
      />,
      container,
    );

    const accountInput = container.querySelector('input[name="account"]') as HTMLInputElement;
    const pwdInput = container.querySelector('input[name="password"]') as HTMLInputElement;
    accountInput.value = 'owner@example.com';
    accountInput.dispatchEvent(new Event('input', { bubbles: true }));
    pwdInput.value = 'wrong';
    pwdInput.dispatchEvent(new Event('input', { bubbles: true }));
    await flushUi();

    container.querySelector('form')?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await flushUi();

    expect(container.textContent).toContain('15 分钟内最多 5 次');
  });

  it('preserves latest mobile blacklistApps when retrying strategy after 1409 conflict on PlansPage (B6)', async () => {
    const plan: PlanItem = {
      id: 10,
      userId: 1,
      name: '晨间学习',
      type: '学习',
      startTime: '08:00:00',
      endTime: '09:00:00',
      status: 1,
      version: 1,
      remindAdvanceMin: 15,
    };
    const oldStrategy: StrategyItem = {
      id: 99,
      planId: 10,
      blacklistApps: [{ packageName: 'com.old.app', appName: '旧应用' }],
      whitelistApps: [],
      autoDnd: false,
      notifySilence: false,
      version: 3,
    };
    const latestStrategy: StrategyItem = {
      id: 99,
      planId: 10,
      blacklistApps: [
        { packageName: 'com.old.app', appName: '旧应用' },
        { packageName: 'com.mobile.added', appName: '手机新增' },
      ],
      whitelistApps: [{ packageName: 'com.mobile.white', appName: '白名单' }],
      autoDnd: false,
      notifySilence: true,
      version: 4,
    };

    let getStrategyCalls = 0;
    let upsertCalls = 0;
    const upsertPlanStrategy = vi.fn(async () => {
      upsertCalls += 1;
      if (upsertCalls === 1) {
        throw new ApiError(1409, '版本冲突');
      }
      return { ...latestStrategy, autoDnd: true, version: 5 };
    });

    const api = baseClientStub({
      listPlans: vi.fn(async () => [plan]),
      pullSyncMetadata: vi.fn(async () => ({ planTypes: [] })),
      getPlanStrategy: vi.fn(async () => {
        getStrategyCalls += 1;
        return getStrategyCalls === 1 ? oldStrategy : latestStrategy;
      }),
      upsertPlanStrategy,
    });

    render(<PlansPage api={api} onNotify={() => {}} />, container);
    await flushUi();

    const strategyBtn = Array.from(container.querySelectorAll('button')).find((b) => b.textContent === '策略');
    strategyBtn?.click();
    await flushUi();

    // Toggle autoDnd and submit -> hits 1409 -> fetches latestStrategy (v4 with 2 blacklist apps)
    const checkboxes = container.querySelectorAll('input[type="checkbox"]');
    (checkboxes[0] as HTMLInputElement).checked = true;
    checkboxes[0]?.dispatchEvent(new Event('change', { bubbles: true }));
    await flushUi();

    const saveBtn = Array.from(container.querySelectorAll('button')).find((b) => b.textContent === '保存策略');
    saveBtn?.click();
    await flushUi();

    expect(container.textContent).toContain('检测到策略版本冲突（1409）');
    expect(container.textContent).toContain('黑名单：2 项');

    const retryBtn = Array.from(container.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('保留最新名单并基于 v4'),
    );
    retryBtn?.click();
    await flushUi();

    // Second PUT MUST send v4 AND latestStrategy's 2 blacklistApps + 1 whitelistApp!
    expect(upsertPlanStrategy).toHaveBeenLastCalledWith(
      10,
      {
        blacklistApps: latestStrategy.blacklistApps,
        whitelistApps: latestStrategy.whitelistApps,
        autoDnd: true,
        notifySilence: false,
      },
      4,
    );
  });

  it('preserves mobile locationLat and locationLon when editing a schedule on SchedulesPage', async () => {
    const sched: ScheduleItem = {
      id: 21,
      userId: 1,
      title: '图书馆自习',
      startDate: '2026-11-30',
      endDate: '2026-11-30',
      startTime: '10:00:00',
      endTime: '12:00:00',
      allDay: false,
      location: '市图书馆',
      locationLat: 31.2304,
      locationLon: 121.4737,
      note: '带耳机',
      repeatRule: '{"type":"weekly","weekdays":[2,4]}',
      status: 1,
      version: 5,
    };
    const updateSchedule = vi.fn(async () => ({ ...sched, title: '图书馆深度阅读', version: 6 }));
    const api = baseClientStub({
      listSchedules: vi.fn(async () => [sched]),
      listPlans: vi.fn(async () => []),
      updateSchedule,
    });

    render(<SchedulesPage api={api} onNotify={() => {}} />, container);
    await flushUi();

    const editBtn = Array.from(container.querySelectorAll('button')).find((b) => b.textContent === '编辑');
    editBtn?.click();
    await flushUi();

    expect(container.textContent).toContain('31.2304, 121.4737');

    const titleInput = container.querySelector('input[name="scheduleTitle"]') as HTMLInputElement;
    titleInput.value = '图书馆深度阅读';
    titleInput.dispatchEvent(new Event('input', { bubbles: true }));
    await flushUi();

    container.querySelector('form')?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await flushUi();

    expect(updateSchedule).toHaveBeenCalledWith(
      21,
      5,
      expect.objectContaining({
        title: '图书馆深度阅读',
        locationLat: 31.2304,
        locationLon: 121.4737,
        repeatRule: '{"type":"weekly","weekdays":[2,4]}',
      }),
    );
  });

  it('renders Journal XSS payloads strictly as inert text nodes with zero injected HTML elements', async () => {
    const xssPayload = '<img src=x onerror=alert(1)><script>window.__xss=1</script>';
    const entries: JournalItem[] = [
      {
        id: 1,
        userId: 1,
        entryDate: '2026-11-26',
        mood: '<b>平静</b>',
        tags: ['<svg onload=alert(1)>'],
        content: xssPayload,
        status: 1,
        version: 1,
      },
    ];
    const api = baseClientStub({
      listJournals: vi.fn(async () => entries),
    });

    render(<JournalPage api={api} onNotify={() => {}} />, container);
    await flushUi();

    expect(container.querySelectorAll('img')).toHaveLength(0);
    expect(container.querySelectorAll('script')).toHaveLength(0);
    expect(container.querySelectorAll('svg')).toHaveLength(0);
    expect(container.textContent).toContain(xssPayload);
  });

  it('filters unsafe links and displays honest non-verifying trust notice on PluginsPage (B5)', async () => {
    const api = baseClientStub({
      getPluginCatalog: vi.fn(async () => ({
        catalogFormatVersion: 1,
        sourceId: 'official',
        sourceType: 'OFFICIAL',
        plugins: [
          {
            id: 'xss-plugin',
            name: '安全测试插件',
            version: '1.0.0',
            contractVersion: 2,
            entryPoint: 'main',
            requiredCapabilities: ['QUERY_TODAY'],
            description: '<script>alert(1)</script>',
            homepage: 'javascript:alert(1)',
            repository: 'https://github.com/DzDenzel/PlanLock',
          },
        ],
      })),
    });

    render(<PluginsPage api={api} onNotify={() => {}} />, container);
    await flushUi();

    const links = Array.from(container.querySelectorAll('a'));
    expect(links.some((a) => a.getAttribute('href')?.startsWith('javascript:'))).toBe(false);
    expect(links.some((a) => a.getAttribute('href') === 'https://github.com/DzDenzel/PlanLock')).toBe(
      true,
    );

    const loadSampleBtn = Array.from(container.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('载入示例'),
    );
    loadSampleBtn?.click();
    await flushUi();

    expect(container.textContent).toContain('未验签插件包字节');
    expect(container.textContent).toContain('独立可信渠道核对');
    expect(container.textContent).toContain('92:70:60:2B');
  });

  it('exits bootstrapping to LoginPage when cold-start token receives 1001 or HTTP 401 on App mount (R1)', async () => {
    for (const statusCase of ['code-1001', 'http-401'] as const) {
      render(null, container);
      resetSessionGuardForTest();
      window.localStorage.clear();
      window.sessionStorage.clear();
      writeSessionToken(`expired-cold-start-${statusCase}`);
      window.history.replaceState(null, '', '/today');

      const fetchSpy = vi.spyOn(window, 'fetch').mockResolvedValue(
        statusCase === 'code-1001'
          ? new Response(JSON.stringify({ code: 1001, message: '登录会话已失效', data: null }), {
              status: 200,
              headers: { 'Content-Type': 'application/json' },
            })
          : new Response('Unauthorized', { status: 401 }),
      );

      render(<App />, container);
      await flushUi();

      expect(container.textContent).not.toContain('正在连接个人数字站…');
      expect(container.querySelector('input[name="account"]')).not.toBeNull();
      expect(container.textContent).toContain('登录会话已失效');

      fetchSpy.mockRestore();
    }
    resetSessionGuardForTest();
    window.sessionStorage.clear();
  });

  it('synchronously invalidates stale official view/request on click switch to third-party tab (R2 v1.2)', async () => {
    let resolveOfficialFirst!: (doc: PluginCatalogDocument) => void;
    let resolveThirdParty!: (doc: PluginCatalogDocument) => void;
    let officialCalls = 0;

    const getPluginCatalog = vi.fn((source: 'official' | 'third-party') => {
      if (source === 'official') {
        officialCalls += 1;
        if (officialCalls === 1) {
          return Promise.resolve<PluginCatalogDocument>({
            catalogFormatVersion: 1,
            sourceId: 'official',
            sourceType: 'OFFICIAL',
            plugins: [
              {
                id: 'official-loaded',
                name: '已加载官方插件',
                version: '1.0.0',
                contractVersion: 2,
                entryPoint: 'main',
                downloadUrl: '/api/v1/plugins/artifacts/official/official-loaded.pljs',
                sha256: 'c'.repeat(64),
                requiredCapabilities: [],
                description: 'loaded official desc',
              },
            ],
          });
        }
        return new Promise<PluginCatalogDocument>((resolve) => {
          resolveOfficialFirst = resolve;
        });
      }
      return new Promise<PluginCatalogDocument>((resolve) => {
        resolveThirdParty = resolve;
      });
    });

    const api = baseClientStub({ getPluginCatalog });
    render(<PluginsPage api={api} onNotify={() => {}} />, container);
    await flushUi();
    expect(container.textContent).toContain('已加载官方插件');

    // Click third-party tab -> MUST immediately clear previous official items & show loading state on first frame!
    const officialTab = Array.from(container.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('official'),
    );
    const thirdPartyTab = Array.from(container.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('third-party'),
    );
    expect(thirdPartyTab).toBeDefined();
    thirdPartyTab?.click();
    await flushUi();

    expect(container.textContent).not.toContain('已加载官方插件');
    expect(container.textContent).toContain('正在拉取同源公开插件目录…');

    // Now switch back to official (creates pending request #2) and immediately switch to third-party again
    officialTab?.click();
    await flushUi();
    thirdPartyTab?.click();
    await flushUi();

    // Third-party resolves first
    resolveThirdParty({
      catalogFormatVersion: 1,
      sourceId: 'third-party',
      sourceType: 'THIRD_PARTY',
      plugins: [
        {
          id: 'tp-only',
          name: '第三方专属插件',
          version: '1.0.0',
          contractVersion: 2,
          entryPoint: 'main',
          downloadUrl: '/api/v1/plugins/artifacts/third-party/tp-only.pljs',
          sha256: 'a'.repeat(64),
          requiredCapabilities: [],
          description: 'tp desc',
        },
      ],
    });
    await flushUi();
    expect(container.textContent).toContain('第三方专属插件');

    // Now delayed official response arrives -> MUST be ignored!
    resolveOfficialFirst({
      catalogFormatVersion: 1,
      sourceId: 'official',
      sourceType: 'OFFICIAL',
      plugins: [
        {
          id: 'official-stale',
          name: '延迟官方插件',
          version: '1.0.0',
          contractVersion: 2,
          entryPoint: 'main',
          downloadUrl: '/api/v1/plugins/artifacts/official/official-stale.pljs',
          sha256: 'b'.repeat(64),
          requiredCapabilities: [],
          description: 'official desc',
        },
      ],
    });
    await flushUi();

    expect(container.textContent).toContain('第三方专属插件');
    expect(container.textContent).not.toContain('延迟官方插件');
  });

  it('isolates strategy modal per planId+requestSeq so delayed Plan A GET/1409 cannot pollute Plan B (R3)', async () => {
    const planA: PlanItem = {
      id: 10,
      userId: 1,
      name: '计划A',
      type: '学习',
      startTime: '08:00:00',
      endTime: '09:00:00',
      repeatRule: { type: 'custom', dates: ['2026-12-01'] },
      remindAdvanceMin: null,
      status: 1,
      version: 1,
    };
    const planB: PlanItem = {
      id: 20,
      userId: 1,
      name: '计划B',
      type: '工作',
      startTime: '10:00:00',
      endTime: '11:00:00',
      status: 1,
      version: 1,
    };

    const strategyA: StrategyItem = {
      id: 101,
      planId: 10,
      blacklistApps: [
        { packageName: 'com.planA.one', appName: 'A1' },
        { packageName: 'com.planA.two', appName: 'A2' },
      ],
      whitelistApps: [{ packageName: 'com.planA.white', appName: 'AW' }],
      autoDnd: true,
      notifySilence: true,
      version: 3,
    };
    const strategyB: StrategyItem = {
      id: 202,
      planId: 20,
      blacklistApps: [{ packageName: 'com.planB.only', appName: 'B1' }],
      whitelistApps: [],
      autoDnd: false,
      notifySilence: false,
      version: 3,
    };

    let resolveStrategyA!: (s: StrategyItem) => void;
    const getPlanStrategy = vi.fn((planId: number) => {
      if (planId === 10) {
        return new Promise<StrategyItem>((resolve) => {
          resolveStrategyA = resolve;
        });
      }
      return Promise.resolve(strategyB);
    });
    const upsertPlanStrategy = vi.fn(async () => ({ ...strategyB, autoDnd: true, version: 4 }));

    const api = baseClientStub({
      listPlans: vi.fn(async () => [planA, planB]),
      pullSyncMetadata: vi.fn(async () => ({ planTypes: [] })),
      getPlanStrategy,
      upsertPlanStrategy,
    });

    render(<PlansPage api={api} onNotify={() => {}} />, container);
    await flushUi();

    // Open Plan A's strategy modal (GET A pending)
    const strategyBtns = Array.from(container.querySelectorAll('button')).filter(
      (b) => b.textContent === '策略',
    );
    strategyBtns[0]?.click();
    await flushUi();

    // Close Plan A's modal and immediately open Plan B's strategy modal
    const closeBtn = Array.from(container.querySelectorAll('button')).find((b) => b.textContent === '关闭');
    closeBtn?.click();
    await flushUi();
    strategyBtns[1]?.click();
    await flushUi();

    // Now delayed Plan A response arrives while Plan B modal is open
    resolveStrategyA(strategyA);
    await flushUi();

    expect(container.textContent).toContain('执行策略 · 计划B');
    expect(container.textContent).toContain('黑名单应用：1 项');

    // Toggle autoDnd and save Plan B -> MUST submit Plan B's blacklistApps, NEVER Plan A's!
    const checkboxes = container.querySelectorAll('input[type="checkbox"]');
    (checkboxes[0] as HTMLInputElement).checked = true;
    checkboxes[0]?.dispatchEvent(new Event('change', { bubbles: true }));
    await flushUi();

    const saveBtn = Array.from(container.querySelectorAll('button')).find((b) => b.textContent === '保存策略');
    saveBtn?.click();
    await flushUi();

    expect(upsertPlanStrategy).toHaveBeenCalledWith(
      20,
      {
        blacklistApps: strategyB.blacklistApps,
        whitelistApps: strategyB.whitelistApps,
        autoDnd: true,
        notifySilence: false,
      },
      3,
    );

    // Also verify R4 in PlansPage UI: custom repeat option stays selectable after switching to daily
    const editBtns = Array.from(container.querySelectorAll('button')).filter(
      (b) => b.textContent === '编辑',
    );
    editBtns[0]?.click();
    await flushUi();
    const selectEl = container.querySelector('select') as HTMLSelectElement;
    expect(selectEl.value).toBe('preserve-custom');
    selectEl.value = 'daily';
    selectEl.dispatchEvent(new Event('change', { bubbles: true }));
    await flushUi();
    const optionValues = Array.from(selectEl.options).map((o) => o.value);
    expect(optionValues).toContain('preserve-custom');
  });
});
