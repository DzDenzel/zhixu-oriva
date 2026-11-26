import { render } from 'preact';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, type ApiClient } from '../src/api/client';
import { JournalPage } from '../src/pages/JournalPage';
import { LoginPage } from '../src/pages/LoginPage';
import { PlansPage } from '../src/pages/PlansPage';
import { PluginsPage } from '../src/pages/PluginsPage';
import { SchedulesPage } from '../src/pages/SchedulesPage';
import type { JournalItem, PlanItem, ScheduleItem, StrategyItem } from '../src/types';
import {
  buildPlanUpsertPayload,
  buildScheduleUpsertPayload,
  buildStrategyUpsertPayload,
  planFormFromItem,
  scheduleFormFromItem,
  withPlanField,
  withScheduleField,
} from '../src/utils/payloads';

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
});
