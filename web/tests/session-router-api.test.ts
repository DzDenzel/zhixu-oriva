import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, createApiClient } from '../src/api/client';
import { PORTAL_ROUTES, resolveRoute } from '../src/utils/router';
import {
  clearSessionToken,
  DEVICE_UID_KEY,
  getOrCreateDeviceUid,
  readSessionToken,
  TOKEN_SESSION_KEY,
  writeSessionToken,
} from '../src/utils/session';

describe('session & deviceUid lifecycle', () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
  });

  it('generates and persists stable web-<UUID> deviceUid', () => {
    const first = getOrCreateDeviceUid();
    const second = getOrCreateDeviceUid();
    expect(first).toMatch(/^web-[0-9a-f-]{36}$/i);
    expect(second).toBe(first);
    expect(window.localStorage.getItem(DEVICE_UID_KEY)).toBe(first);
  });

  it('stores JWT strictly in sessionStorage and never in localStorage', () => {
    writeSessionToken('jwt-token-xyz');
    expect(readSessionToken()).toBe('jwt-token-xyz');
    expect(window.sessionStorage.getItem(TOKEN_SESSION_KEY)).toBe('jwt-token-xyz');
    expect(window.localStorage.getItem(TOKEN_SESSION_KEY)).toBeNull();

    clearSessionToken();
    expect(readSessionToken()).toBeNull();
  });
});

describe('SPA explicit 9-route contract', () => {
  it('recognizes all 9 WebPortalController routes and rejects unknown/api routes', () => {
    expect(PORTAL_ROUTES).toHaveLength(9);
    for (const route of PORTAL_ROUTES) {
      expect(resolveRoute(route)).toEqual({ kind: 'known', path: route });
    }
    expect(resolveRoute('/plans/')).toEqual({ kind: 'known', path: '/plans' });
    expect(resolveRoute('/rhythm?days=62')).toEqual({ kind: 'known', path: '/rhythm' });

    expect(resolveRoute('/api/v1/not-exists')).toEqual({
      kind: 'unknown',
      rawPath: '/api/v1/not-exists',
    });
    expect(resolveRoute('/setup')).toEqual({ kind: 'unknown', rawPath: '/setup' });
    expect(resolveRoute('/random-page')).toEqual({ kind: 'unknown', rawPath: '/random-page' });
  });
});

describe('API client auth, 1001 auto-logout, platform fallback, and 1409 conflict', () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
  });

  it('sends web-<UUID> deviceUid and falls back to windows platform if backend rejects web', async () => {
    const sentBodies: Array<Record<string, unknown>> = [];
    const mockFetch = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>;
      sentBodies.push(body);
      if (body.platform === 'web') {
        return new Response(
          JSON.stringify({ code: 1002, message: 'platform 平台取值仅支持 android/ios/windows', data: null }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response(
        JSON.stringify({ code: 0, message: 'ok', data: { token: 'jwt-ok', userId: 7, deviceId: 12 } }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      );
    });

    const api = createApiClient({ fetchImpl: mockFetch as unknown as typeof fetch });
    const auth = await api.login('owner@example.com', 'secret123');
    expect(auth.token).toBe('jwt-ok');
    expect(readSessionToken()).toBe('jwt-ok');
    expect(sentBodies).toHaveLength(2);
    expect(sentBodies[0].platform).toBe('web');
    expect(sentBodies[1].platform).toBe('windows');
    expect(String(sentBodies[0].deviceUid)).toMatch(/^web-/);
  });

  it('clears sessionStorage token and triggers onUnauthorized on business code 1001', async () => {
    writeSessionToken('stale-jwt');
    const onUnauthorized = vi.fn();
    const mockFetch = vi.fn(async () =>
      new Response(JSON.stringify({ code: 1001, message: '未认证', data: null }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    const api = createApiClient({
      fetchImpl: mockFetch as unknown as typeof fetch,
      onUnauthorized,
    });

    await expect(api.getMe()).rejects.toThrow(ApiError);
    expect(readSessionToken()).toBeNull();
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('surfaces 1409 version conflict as ApiError with isConflict=true', async () => {
    writeSessionToken('valid-jwt');
    const mockFetch = vi.fn(async () =>
      new Response(JSON.stringify({ code: 1409, message: '版本冲突（其他设备有更改）', data: null }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    const api = createApiClient({ fetchImpl: mockFetch as unknown as typeof fetch });

    try {
      await api.updatePlan(1, 0, {
        name: '晨读',
        type: '阅读',
        startTime: '07:00:00',
        endTime: '07:30:00',
      });
      expect.unreachable('should throw 1409');
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).isConflict).toBe(true);
      expect((err as ApiError).code).toBe(1409);
    }
  });

  it('passes IANA timezone to rhythm endpoint and reads raw JSON for plugin catalog', async () => {
    writeSessionToken('valid-jwt');
    const requestedUrls: string[] = [];
    const mockFetch = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      requestedUrls.push(url);
      if (url.startsWith('/api/v1/stats/rhythm')) {
        return new Response(
          JSON.stringify({
            code: 0,
            message: 'ok',
            data: {
              headline: 'ok',
              streakDays: 3,
              completionRate7d: { completed: 5, total: 7 },
              completionRatePrev7d: { completed: 4, total: 7 },
              focusMinutes30d: 180,
              focusMinutesPrev30d: 120,
              earlyEnds30d: 1,
              earlyEndsPrev30d: 2,
              days: [],
              plans: [],
              distraction: { minutes7d: 10, topSources: [] },
              source: 'server',
            },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response(
        JSON.stringify({
          catalogFormatVersion: 1,
          sourceId: 'official',
          sourceType: 'OFFICIAL',
          plugins: [],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      );
    });

    const api = createApiClient({ fetchImpl: mockFetch as unknown as typeof fetch });
    const rhythm = await api.getRhythm(62, 'Asia/Shanghai');
    expect(rhythm.streakDays).toBe(3);
    expect(requestedUrls[0]).toBe('/api/v1/stats/rhythm?days=62&tz=Asia%2FShanghai');

    const catalog = await api.getPluginCatalog('official');
    expect(catalog.sourceId).toBe('official');
    expect(catalog.plugins).toEqual([]);
  });
});
