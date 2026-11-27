import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, createApiClient, StaleSessionError } from '../src/api/client';
import { PORTAL_ROUTES, resolveRoute } from '../src/utils/router';
import {
  clearSessionToken,
  DEVICE_UID_KEY,
  digestTokenNonSecret,
  getOrCreateDeviceUid,
  readSessionToken,
  resetSessionGuardForTest,
  REVOKED_DIGEST_PREFIX,
  REVOKED_DIGEST_SET_KEY,
  REVOKED_TOKEN_SENTINEL,
  TOKEN_SESSION_KEY,
  writeSessionToken,
} from '../src/utils/session';

describe('session & deviceUid lifecycle (B3)', () => {
  beforeEach(() => {
    resetSessionGuardForTest();
    window.localStorage.clear();
    window.sessionStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    resetSessionGuardForTest();
  });

  it('generates and persists stable web-<UUID> deviceUid', () => {
    const first = getOrCreateDeviceUid();
    const second = getOrCreateDeviceUid();
    expect(first).toMatch(/^web-[0-9a-f-]{36}$/i);
    expect(second).toBe(first);
    expect(window.localStorage.getItem(DEVICE_UID_KEY)).toBe(first);
  });

  it('stores JWT strictly in sessionStorage and never re-reads stale token if removeItem fails', () => {
    writeSessionToken('jwt-token-xyz');
    expect(readSessionToken()).toBe('jwt-token-xyz');
    expect(window.sessionStorage.getItem(TOKEN_SESSION_KEY)).toBe('jwt-token-xyz');
    expect(window.localStorage.getItem(TOKEN_SESSION_KEY)).toBeNull();

    // Simulate Storage.removeItem throwing while setItem still works -> sentinel fallback clears token
    const removeSpy = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('storage locked');
    });
    const result = clearSessionToken();
    expect(result.storageCleared).toBe(true);
    expect(window.sessionStorage.getItem(TOKEN_SESSION_KEY)).toBe(REVOKED_TOKEN_SENTINEL);
    expect(readSessionToken()).toBeNull();
    removeSpy.mockRestore();

    // Writing a brand new token lifts the fence
    expect(writeSessionToken('jwt-new-session')).toBe(true);
    expect(readSessionToken()).toBe('jwt-new-session');
  });

  it('isolates persisted revocation digests across tabs and fails closed on revocation storage/parse errors (R1)', () => {
    const tabAToken = 'stale-tab-a-session-token-r1';
    const tabADigest = digestTokenNonSecret(tabAToken);
    expect(writeSessionToken(tabAToken)).toBe(true);

    // Tab A experiences sessionStorage removeItem + setItem double failure while localStorage works
    const origRemove = Storage.prototype.removeItem;
    const origSet = Storage.prototype.setItem;
    const origGet = Storage.prototype.getItem;
    const removeSpy = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(function (
      this: Storage,
      key: string,
    ) {
      if (this === window.sessionStorage) throw new Error('tab A sessionStorage remove blocked');
      return origRemove.call(this, key);
    });
    const setSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
      this: Storage,
      key: string,
      val: string,
    ) {
      if (this === window.sessionStorage) throw new Error('tab A sessionStorage set blocked');
      return origSet.call(this, key, val);
    });

    const clearedA = clearSessionToken();
    expect(clearedA.storageCleared).toBe(false);
    expect(clearedA.revocationPersisted).toBe(true);
    expect(window.sessionStorage.getItem(TOKEN_SESSION_KEY)).toBe(tabAToken);
    expect(readSessionToken()).toBeNull();

    // Persisted record stores only non-secret 16-hex digest (NEVER raw JWT)
    expect(window.localStorage.getItem(`${REVOKED_DIGEST_PREFIX}${tabADigest}`)).toBe('1');
    const savedSetRaw = window.localStorage.getItem(REVOKED_DIGEST_SET_KEY) ?? '';
    expect(savedSetRaw).toContain(tabADigest);
    expect(savedSetRaw).not.toContain(tabAToken);

    // Failed writeSessionToken MUST NOT lift the fence
    expect(writeSessionToken('replacement-token')).toBe(false);
    expect(readSessionToken()).toBeNull();

    removeSpy.mockRestore();
    setSpy.mockRestore();

    // Simulate same-origin Tab B (separate working sessionStorage, shared localStorage) logging in,
    // cleanly logging out, and also encountering another failed clear -> MUST NEVER delete/overwrite Tab A's digest!
    resetSessionGuardForTest(true);
    expect(writeSessionToken('tab-b-valid-token')).toBe(true);
    expect(readSessionToken()).toBe('tab-b-valid-token');
    expect(clearSessionToken().storageCleared).toBe(true);
    expect(window.localStorage.getItem(`${REVOKED_DIGEST_PREFIX}${tabADigest}`)).toBe('1');

    // Tab B also has a failed clear on another token -> appends without evicting Tab A's digest
    expect(writeSessionToken('tab-b-stale-token')).toBe(true);
    const removeSpyB = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(function (
      this: Storage,
      key: string,
    ) {
      if (this === window.sessionStorage) throw new Error('tab B remove blocked');
      return origRemove.call(this, key);
    });
    const setSpyB = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
      this: Storage,
      key: string,
      val: string,
    ) {
      if (this === window.sessionStorage) throw new Error('tab B set blocked');
      return origSet.call(this, key, val);
    });
    expect(clearSessionToken().storageCleared).toBe(false);
    removeSpyB.mockRestore();
    setSpyB.mockRestore();

    // Now simulate Tab A refreshing with lingering tabAToken still in Tab A's sessionStorage!
    window.sessionStorage.setItem(TOKEN_SESSION_KEY, tabAToken);
    resetSessionGuardForTest(true);
    expect(readSessionToken()).toBeNull();
    expect(createApiClient().getToken()).toBeNull();

    // Genuine fresh token written after storage recovers still works, while tabADigest remains revoked
    expect(writeSessionToken('tab-a-fresh-after-recovery')).toBe(true);
    expect(readSessionToken()).toBe('tab-a-fresh-after-recovery');
    expect(window.localStorage.getItem(`${REVOKED_DIGEST_PREFIX}${tabADigest}`)).toBe('1');

    // Corrupted revocation set in localStorage => fail-closed (never allow suspect token)
    window.localStorage.setItem(REVOKED_DIGEST_SET_KEY, '{corrupted-json');
    expect(readSessionToken()).toBeNull();

    // Storage read permission error on localStorage => fail-closed
    window.localStorage.removeItem(REVOKED_DIGEST_SET_KEY);
    const getSpy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(function (
      this: Storage,
      key: string,
    ) {
      if (this === window.localStorage) throw new Error('localStorage read denied');
      return origGet.call(this, key);
    });
    expect(readSessionToken()).toBeNull();
    getSpy.mockRestore();
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

describe('API client generation isolation, 1001 auto-logout, public catalog, and 1409 conflict (B3)', () => {
  beforeEach(() => {
    resetSessionGuardForTest();
    window.localStorage.clear();
    window.sessionStorage.clear();
  });

  it('drops in-flight export response after logout and prevents old 401/1001 from clearing a new session', async () => {
    writeSessionToken('session-A');
    const onUnauthorized = vi.fn();

    let resolveExport!: (res: Response) => void;
    let resolveStale401!: (res: Response) => void;
    const mockFetch = vi.fn((url: RequestInfo | URL) => {
      const s = String(url);
      if (s.includes('/records/export')) {
        return new Promise<Response>((r) => {
          resolveExport = r;
        });
      }
      return new Promise<Response>((r) => {
        resolveStale401 = r;
      });
    });

    const api = createApiClient({
      fetchImpl: mockFetch as unknown as typeof fetch,
      onUnauthorized,
    });

    // Start in-flight export and in-flight /me under session-A
    const exportPromise = api.exportRecords('json');
    const staleMePromise = api.getMe();

    // User logs out and logs into session-B before those responses return
    api.clearSession();
    api.setToken('session-B');

    // Old export finishes -> MUST reject with StaleSessionError (never download)
    resolveExport(new Response('{"old":true}', { status: 200 }));
    await expect(exportPromise).rejects.toBeInstanceOf(StaleSessionError);

    // Old /me returns 401 -> MUST reject with StaleSessionError and NOT clear session-B!
    resolveStale401(
      new Response(JSON.stringify({ code: 1001, message: 'expired', data: null }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    await expect(staleMePromise).rejects.toBeInstanceOf(StaleSessionError);
    expect(api.getToken()).toBe('session-B');
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('sends web-<UUID> deviceUid and falls back to windows platform if backend rejects web', async () => {
    const sentBodies: Array<Record<string, unknown>> = [];
    const mockFetch = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>;
      sentBodies.push(body);
      if (body.platform === 'web') {
        return new Response(
          JSON.stringify({
            code: 1002,
            message: 'platform 平台取值仅支持 android/ios/windows',
            data: null,
          }),
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
    expect(api.getToken()).toBeNull();
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('surfaces 1409 version conflict as ApiError with isConflict=true', async () => {
    writeSessionToken('valid-jwt');
    const mockFetch = vi.fn(async () =>
      new Response(
        JSON.stringify({ code: 1409, message: '版本冲突（其他设备有更改）', data: null }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
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

  it('passes IANA timezone to rhythm endpoint and fetches public plugin catalog WITHOUT Bearer header', async () => {
    writeSessionToken('valid-jwt');
    const seenHeaders: Array<HeadersInit | undefined> = [];
    const requestedUrls: string[] = [];
    const mockFetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      requestedUrls.push(url);
      seenHeaders.push(init?.headers);
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
    // Public catalog request must NOT carry Authorization header (Astra B3)
    expect(seenHeaders[1]).toBeUndefined();
  });

  it('aborts and cancels chunked plugin catalog stream exceeding 2 MiB without Content-Length header (R2)', async () => {
    let pulledChunks = 0;
    let cancelled = false;
    const chunk = new Uint8Array(512 * 1024).fill(0x61); // 512 KiB per chunk

    const oversizedStream = new ReadableStream<Uint8Array>({
      pull(controller) {
        pulledChunks += 1;
        if (pulledChunks <= 20) {
          controller.enqueue(chunk);
        } else {
          controller.close();
        }
      },
      cancel() {
        cancelled = true;
      },
    });

    const mockFetch = vi.fn(async () => {
      // Deliberately omit Content-Length header to simulate chunked transfer encoding
      return new Response(oversizedStream, {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    });

    const api = createApiClient({ fetchImpl: mockFetch as unknown as typeof fetch });
    await expect(api.getPluginCatalog('official')).rejects.toThrow(/超出 2 MiB 安全上限/);
    expect(cancelled).toBe(true);
    // 2 MiB = 4 * 512 KiB; stream must stop at chunk 5 (well before 20 chunks)
    expect(pulledChunks).toBeLessThanOrEqual(6);
  });

  it('rejects plugin catalog response lacking readable stream body WITHOUT calling response.text() (R2)', async () => {
    const textSpy = vi.fn(async () => '{"catalogFormatVersion":1,"sourceId":"official","sourceType":"OFFICIAL","plugins":[]}');
    const mockFetch = vi.fn(async () => ({
      ok: true,
      status: 200,
      headers: new Headers({ 'Content-Type': 'application/json' }),
      body: null,
      text: textSpy,
    })) as unknown as typeof fetch;

    const api = createApiClient({ fetchImpl: mockFetch });
    await expect(api.getPluginCatalog('official')).rejects.toThrow(/缺少可读流/);
    expect(textSpy).not.toHaveBeenCalled();
  });
});
