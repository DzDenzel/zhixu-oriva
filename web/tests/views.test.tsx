import { render } from 'preact';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, type ApiClient } from '../src/api/client';
import { JournalPage } from '../src/pages/JournalPage';
import { LoginPage } from '../src/pages/LoginPage';
import { PlansPage } from '../src/pages/PlansPage';
import { PluginsPage } from '../src/pages/PluginsPage';
import { SchedulesPage } from '../src/pages/SchedulesPage';
import type { JournalItem, PlanItem, ScheduleItem, StrategyItem } from '../src/types';

async function flushUi(): Promise<void> {
  await Promise.resolve();
  await new Promise((resolve) => setTimeout(resolve, 25));
  await Promise.resolve();
  await new Promise((resolve) => setTimeout(resolve, 25));
}

describe('Core views: XSS neutralization, 1409 conflict reload, coordinate & strategy preservation', () => {
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
    const api = {
      login: vi.fn().mockRejectedValue(new ApiError(1002, '登录尝试过多，请 15 分钟后重试')),
    } as unknown as ApiClient;

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

  it('shows 1409 conflict modal on PlansPage and reloads server latest version + preserves strategy app lists', async () => {
    const initialPlan: PlanItem = {
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
    const latestPlan: PlanItem = { ...initialPlan, name: '手机端已改名', version: 2 };
    const existingStrategy: StrategyItem = {
      id: 99,
      planId: 10,
      blacklistApps: [{ packageName: 'com.game.app', appName: '某游戏' }],
      whitelistApps: [{ packageName: 'com.dict.app', appName: '词典' }],
      autoDnd: false,
      notifySilence: false,
      version: 3,
    };

    let listCallCount = 0;
    const upsertPlanStrategy = vi.fn(async () => ({ ...existingStrategy, autoDnd: true, version: 4 }));
    const api = {
      listPlans: vi.fn(async () => {
        listCallCount += 1;
        return listCallCount === 1 ? [initialPlan] : [latestPlan];
      }),
      pullSyncMetadata: vi.fn(async () => ({ planTypes: [] })),
      updatePlan: vi.fn().mockRejectedValue(new ApiError(1409, '版本冲突（其他设备有更改）')),
      getPlanStrategy: vi.fn(async () => existingStrategy),
      upsertPlanStrategy,
    } as unknown as ApiClient;

    render(<PlansPage api={api} onNotify={() => {}} />, container);
    await flushUi();

    // Trigger edit -> submit -> 1409 conflict modal
    const editBtn = Array.from(container.querySelectorAll('button')).find((b) => b.textContent === '编辑');
    editBtn?.click();
    await flushUi();

    const form = container.querySelector('form');
    form?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await flushUi();

    expect(container.textContent).toContain('版本冲突 · 1409');
    expect(container.textContent).toContain('手机端已改名');

    const reloadBtn = Array.from(container.querySelectorAll('button')).find(
      (b) => b.textContent === '加载服务端最新数据',
    );
    reloadBtn?.click();
    await flushUi();

    const nameInput = container.querySelector('input[name="planName"]') as HTMLInputElement;
    expect(nameInput.value).toBe('手机端已改名');

    // Close edit modal and verify Strategy preserves blacklistApps & whitelistApps
    const cancelBtn = Array.from(container.querySelectorAll('button')).find((b) => b.textContent === '取消');
    cancelBtn?.click();
    await flushUi();

    const strategyBtn = Array.from(container.querySelectorAll('button')).find((b) => b.textContent === '策略');
    strategyBtn?.click();
    await flushUi();

    const saveStrategyBtn = Array.from(container.querySelectorAll('button')).find(
      (b) => b.textContent === '保存策略',
    );
    saveStrategyBtn?.click();
    await flushUi();

    expect(upsertPlanStrategy).toHaveBeenCalledWith(
      10,
      expect.objectContaining({
        blacklistApps: existingStrategy.blacklistApps,
        whitelistApps: existingStrategy.whitelistApps,
      }),
      3,
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
      status: 1,
      version: 5,
    };
    const updateSchedule = vi.fn(async () => ({ ...sched, title: '图书馆深度阅读', version: 6 }));
    const api = {
      listSchedules: vi.fn(async () => [sched]),
      listPlans: vi.fn(async () => []),
      updateSchedule,
    } as unknown as ApiClient;

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
    const api = {
      listJournals: vi.fn(async () => entries),
    } as unknown as ApiClient;

    render(<JournalPage api={api} onNotify={() => {}} />, container);
    await flushUi();

    expect(container.querySelectorAll('img')).toHaveLength(0);
    expect(container.querySelectorAll('script')).toHaveLength(0);
    expect(container.querySelectorAll('svg')).toHaveLength(0);
    expect(container.textContent).toContain(xssPayload);
  });

  it('filters out unsafe plugin links on PluginsPage and previews Form B catalog fingerprints', async () => {
    const api = {
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
    } as unknown as ApiClient;

    render(<PluginsPage api={api} onNotify={() => {}} />, container);
    await flushUi();

    const links = Array.from(container.querySelectorAll('a'));
    expect(links.some((a) => a.getAttribute('href')?.startsWith('javascript:'))).toBe(false);
    expect(links.some((a) => a.getAttribute('href') === 'https://github.com/DzDenzel/PlanLock')).toBe(true);

    // Load Form B sample catalog and verify fingerprint rendering
    const loadSampleBtn = Array.from(container.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('载入示例'),
    );
    loadSampleBtn?.click();
    await flushUi();

    expect(container.textContent).toContain('校验通过');
    expect(container.textContent).toContain('92:70:60:2B');
  });
});
