import type {
  ApiResponse,
  AuthResponse,
  DeviceItem,
  JournalItem,
  JournalUpsertPayload,
  MeResponse,
  PlanItem,
  PlanUpsertPayload,
  PluginCatalogDocument,
  RhythmSummary,
  ScheduleItem,
  ScheduleUpsertPayload,
  StrategyItem,
  StrategyUpsertPayload,
  SyncPullResponse,
  TodaySummary,
} from '../types';
import { MAX_CATALOG_BYTES, validateSameOriginCatalog } from '../utils/catalog';
import {
  buildWebDeviceName,
  clearSessionToken,
  getOrCreateDeviceUid,
  readSessionToken,
  writeSessionTokenDetailed,
  type ClearSessionResult,
} from '../utils/session';

export const ERROR_UNAUTHORIZED = 1001;
export const ERROR_INVALID_PARAM = 1002;
export const ERROR_NOT_FOUND = 1003;
export const ERROR_VERSION_CONFLICT = 1409;

export class ApiError extends Error {
  readonly code: number;
  readonly httpStatus: number;

  constructor(code: number, message: string, httpStatus = 200) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.httpStatus = httpStatus;
  }

  get isUnauthorized(): boolean {
    return this.code === ERROR_UNAUTHORIZED || this.httpStatus === 401;
  }

  get isConflict(): boolean {
    return this.code === ERROR_VERSION_CONFLICT;
  }

  get isNotFound(): boolean {
    return this.code === ERROR_NOT_FOUND || this.httpStatus === 404;
  }
}

/**
 * 会话已登出或切换后，旧代际在途请求返回时抛出的静默中止异常（Astra B3）：
 * 调用方应静默丢弃响应，绝不触发导出下载、覆盖新会话状态或误报错误。
 */
export class StaleSessionError extends Error {
  constructor() {
    super('会话已切换或已退出，已丢弃过期的在途响应');
    this.name = 'StaleSessionError';
  }
}

export interface ApiClientOptions {
  fetchImpl?: typeof fetch;
  onUnauthorized?: (reason: string) => void;
}

export function createApiClient(options: ApiClientOptions = {}) {
  const fetchFn = options.fetchImpl ?? ((...args: Parameters<typeof fetch>) => window.fetch(...args));
  let memoryToken: string | null = readSessionToken();
  let sessionGeneration = 0;

  function getSessionGeneration(): number {
    return sessionGeneration;
  }

  function isGenerationCurrent(gen: number): boolean {
    return gen === sessionGeneration;
  }

  function getToken(): string | null {
    return memoryToken;
  }

  function setToken(token: string | null): ClearSessionResult {
    const previousToken = memoryToken;
    sessionGeneration += 1;
    if (token !== null) {
      const writeRes = writeSessionTokenDetailed(token);
      if (
        writeRes.reason === 'invalid_token' ||
        writeRes.reason === 'expired_token' ||
        writeRes.reason === 'revoked_token'
      ) {
        // 结构不合法、已过期或已撤销的令牌严禁进入内存会话
        memoryToken = null;
        return {
          storageCleared: false,
          revocationPersisted: false,
          durableRevocationPersisted: false,
        };
      }
      // persisted（含正向 lease）或仅浏览器存储只读退回当前内存会话（storage_failed）
      memoryToken = token.trim();
      return {
        storageCleared: true,
        revocationPersisted: false,
        durableRevocationPersisted: false,
      };
    }
    memoryToken = null;
    return clearSessionToken(previousToken ?? undefined);
  }

  function assertGeneration(requestGen: number): void {
    if (requestGen !== sessionGeneration) {
      throw new StaleSessionError();
    }
  }

  function handleUnauthorized(message: string, requestGen: number): never {
    // 若在途旧请求在登出/重新登录后才返回 401/1001，绝不可误清新会话！
    assertGeneration(requestGen);
    setToken(null);
    options.onUnauthorized?.(message);
    throw new ApiError(ERROR_UNAUTHORIZED, message, 401);
  }

  async function requestJson<T>(
    path: string,
    init: RequestInit = {},
    requireAuth = true,
  ): Promise<T> {
    const requestGen = sessionGeneration;
    const headers = new Headers(init.headers ?? {});
    if (init.body !== undefined && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    if (requireAuth) {
      const token = getToken();
      if (!token) {
        handleUnauthorized('请先登录您的知序实例', requestGen);
      }
      headers.set('Authorization', `Bearer ${token}`);
    }

    let response: Response;
    try {
      response = await fetchFn(path, {
        ...init,
        headers,
        cache: 'no-store',
        credentials: 'same-origin',
      });
    } catch {
      assertGeneration(requestGen);
      throw new ApiError(2001, '网络请求失败，请检查个人后端连接是否正常', 0);
    }

    assertGeneration(requestGen);

    if (response.status === 401) {
      handleUnauthorized('登录会话已失效，请重新登录', requestGen);
    }

    let payload: ApiResponse<T>;
    try {
      payload = (await response.json()) as ApiResponse<T>;
    } catch {
      assertGeneration(requestGen);
      throw new ApiError(2001, `服务端返回了非 JSON 响应（HTTP ${response.status}）`, response.status);
    }

    assertGeneration(requestGen);

    if (payload.code === ERROR_UNAUTHORIZED) {
      handleUnauthorized(payload.message || '登录状态已过期，请重新登录', requestGen);
    }
    if (payload.code !== 0) {
      throw new ApiError(payload.code, payload.message || '请求失败', response.status);
    }
    return payload.data;
  }

  async function requestRawText(path: string, requireAuth = true): Promise<string> {
    const requestGen = sessionGeneration;
    const headers = new Headers();
    if (requireAuth) {
      const token = getToken();
      if (!token) {
        handleUnauthorized('请先登录您的知序实例', requestGen);
      }
      headers.set('Authorization', `Bearer ${token}`);
    }
    let response: Response;
    try {
      response = await fetchFn(path, {
        method: 'GET',
        headers,
        cache: 'no-store',
        credentials: 'same-origin',
      });
    } catch {
      assertGeneration(requestGen);
      throw new ApiError(2001, '导出请求失败，请检查网络连接', 0);
    }

    assertGeneration(requestGen);

    if (response.status === 401) {
      handleUnauthorized('登录会话已失效，请重新登录', requestGen);
    }
    const contentType = response.headers.get('content-type') || '';
    const text = await response.text();

    assertGeneration(requestGen);

    if (contentType.includes('application/json')) {
      try {
        const parsed = JSON.parse(text) as Partial<ApiResponse<unknown>>;
        if (typeof parsed.code === 'number' && parsed.code !== 0) {
          if (parsed.code === ERROR_UNAUTHORIZED) {
            handleUnauthorized(parsed.message || '登录状态已过期，请重新登录', requestGen);
          }
          throw new ApiError(parsed.code, parsed.message || '导出失败', response.status);
        }
      } catch (err) {
        if (err instanceof ApiError || err instanceof StaleSessionError) throw err;
      }
    }
    if (!response.ok) {
      throw new ApiError(2001, `导出失败（HTTP ${response.status}）`, response.status);
    }
    return text;
  }

  return {
    getSessionGeneration,
    isGenerationCurrent,
    getToken,
    setToken,
    clearSession: (): ClearSessionResult => setToken(null),

    /**
     * 登录：优先发送 ADR-022 D2 规定的 platform="web"。
     * TODO(req041-integration): remove platform="windows" fallback once Package A v1.1 (9fdcda9, supporting platform="web") merges to main.
     */
    async login(account: string, password: string): Promise<AuthResponse> {
      const deviceUid = getOrCreateDeviceUid();
      const deviceName = buildWebDeviceName();

      const submit = (platform: string) =>
        requestJson<AuthResponse>(
          '/api/v1/auth/login',
          {
            method: 'POST',
            body: JSON.stringify({
              account: account.trim(),
              password,
              deviceUid,
              deviceName,
              platform,
            }),
          },
          false,
        );

      let result: AuthResponse;
      try {
        result = await submit('web');
      } catch (err) {
        if (
          err instanceof ApiError &&
          err.code === ERROR_INVALID_PARAM &&
          /platform|平台/i.test(err.message)
        ) {
          result = await submit('windows');
        } else {
          throw err;
        }
      }
      setToken(result.token);
      if (!getToken()) {
        throw new ApiError(ERROR_UNAUTHORIZED, '服务端返回了无效、已过期或已撤销的会话令牌', 401);
      }
      return result;
    },

    getMe(): Promise<MeResponse> {
      return requestJson<MeResponse>('/api/v1/me');
    },

    getToday(): Promise<TodaySummary> {
      return requestJson<TodaySummary>('/api/v1/records/today');
    },

    pullSyncMetadata(): Promise<SyncPullResponse> {
      return requestJson<SyncPullResponse>('/api/v1/sync/pull');
    },

    // Plans
    listPlans(): Promise<PlanItem[]> {
      return requestJson<PlanItem[]>('/api/v1/plans');
    },

    createPlan(payload: PlanUpsertPayload): Promise<PlanItem> {
      return requestJson<PlanItem>('/api/v1/plans', {
        method: 'POST',
        body: JSON.stringify(payload),
      });
    },

    updatePlan(id: number, baseVersion: number, payload: PlanUpsertPayload): Promise<PlanItem> {
      return requestJson<PlanItem>(
        `/api/v1/plans/${id}?baseVersion=${encodeURIComponent(String(baseVersion))}`,
        {
          method: 'PUT',
          body: JSON.stringify(payload),
        },
      );
    },

    deletePlan(id: number, baseVersion?: number): Promise<void> {
      const suffix =
        baseVersion !== undefined ? `?baseVersion=${encodeURIComponent(String(baseVersion))}` : '';
      return requestJson<void>(`/api/v1/plans/${id}${suffix}`, { method: 'DELETE' });
    },

    pausePlan(id: number, date?: string): Promise<PlanItem> {
      const suffix = date ? `?date=${encodeURIComponent(date)}` : '';
      return requestJson<PlanItem>(`/api/v1/plans/${id}/pause${suffix}`, { method: 'POST' });
    },

    exportPlansIcs(): Promise<string> {
      return requestRawText('/api/v1/plans/export');
    },

    // Plan Strategy
    getPlanStrategy(planId: number): Promise<StrategyItem> {
      return requestJson<StrategyItem>(`/api/v1/plans/${planId}/strategy`);
    },

    upsertPlanStrategy(
      planId: number,
      payload: StrategyUpsertPayload,
      baseVersion?: number,
    ): Promise<StrategyItem> {
      const suffix =
        baseVersion !== undefined ? `?baseVersion=${encodeURIComponent(String(baseVersion))}` : '';
      return requestJson<StrategyItem>(`/api/v1/plans/${planId}/strategy${suffix}`, {
        method: 'PUT',
        body: JSON.stringify(payload),
      });
    },

    // Schedules
    listSchedules(): Promise<ScheduleItem[]> {
      return requestJson<ScheduleItem[]>('/api/v1/schedules');
    },

    createSchedule(payload: ScheduleUpsertPayload): Promise<ScheduleItem> {
      return requestJson<ScheduleItem>('/api/v1/schedules', {
        method: 'POST',
        body: JSON.stringify(payload),
      });
    },

    updateSchedule(
      id: number,
      baseVersion: number,
      payload: ScheduleUpsertPayload,
    ): Promise<ScheduleItem> {
      return requestJson<ScheduleItem>(
        `/api/v1/schedules/${id}?baseVersion=${encodeURIComponent(String(baseVersion))}`,
        {
          method: 'PUT',
          body: JSON.stringify(payload),
        },
      );
    },

    deleteSchedule(id: number, baseVersion?: number): Promise<void> {
      const suffix =
        baseVersion !== undefined ? `?baseVersion=${encodeURIComponent(String(baseVersion))}` : '';
      return requestJson<void>(`/api/v1/schedules/${id}${suffix}`, { method: 'DELETE' });
    },

    // Rhythm & Records Export
    getRhythm(days = 62, tz?: string): Promise<RhythmSummary> {
      const resolvedTz = tz || Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
      const qs = new URLSearchParams({
        days: String(days),
        tz: resolvedTz,
      });
      return requestJson<RhythmSummary>(`/api/v1/stats/rhythm?${qs.toString()}`);
    },

    exportRecords(format: 'json' | 'csv'): Promise<string> {
      return requestRawText(`/api/v1/records/export?format=${format}`);
    },

    // Journals
    listJournals(params: { from?: string; to?: string; mood?: string } = {}): Promise<JournalItem[]> {
      const qs = new URLSearchParams();
      if (params.from) qs.set('from', params.from);
      if (params.to) qs.set('to', params.to);
      if (params.mood) qs.set('mood', params.mood);
      const query = qs.toString();
      return requestJson<JournalItem[]>(`/api/v1/journals${query ? `?${query}` : ''}`);
    },

    createJournal(payload: JournalUpsertPayload): Promise<JournalItem> {
      return requestJson<JournalItem>('/api/v1/journals', {
        method: 'POST',
        body: JSON.stringify(payload),
      });
    },

    updateJournal(
      id: number,
      baseVersion: number,
      payload: JournalUpsertPayload,
    ): Promise<JournalItem> {
      return requestJson<JournalItem>(
        `/api/v1/journals/${id}?baseVersion=${encodeURIComponent(String(baseVersion))}`,
        {
          method: 'PUT',
          body: JSON.stringify(payload),
        },
      );
    },

    deleteJournal(id: number, baseVersion?: number): Promise<void> {
      const suffix =
        baseVersion !== undefined ? `?baseVersion=${encodeURIComponent(String(baseVersion))}` : '';
      return requestJson<void>(`/api/v1/journals/${id}${suffix}`, { method: 'DELETE' });
    },

    exportJournalsMarkdown(): Promise<string> {
      return requestRawText('/api/v1/journals/export?format=markdown');
    },

    // Devices
    listDevices(): Promise<DeviceItem[]> {
      return requestJson<DeviceItem[]>('/api/v1/devices');
    },

    setPrimaryDevice(id: number): Promise<void> {
      return requestJson<void>(`/api/v1/devices/${id}/primary`, { method: 'PUT' });
    },

    unbindDevice(id: number): Promise<void> {
      return requestJson<void>(`/api/v1/devices/${id}`, { method: 'DELETE' });
    },

    /**
     * 同源公开插件目录（WebConfig PUBLIC_API_PATTERNS 明确放行官方/第三方目录端点）：
     * - 不携带 Bearer JWT（遵循最小权限原则，Astra B3）；
     * - 通过 ReadableStream 逐块按解码后字节限读（<= 2 MiB），超限立即 reader.cancel()（Astra R2）；
     * - 校验会话代际防止过时响应回写页面，并对正文执行有界 schema 校验（Astra B5）。
     */
    async getPluginCatalog(source: 'official' | 'third-party'): Promise<PluginCatalogDocument> {
      const requestGen = sessionGeneration;
      let response: Response;
      try {
        response = await fetchFn(`/api/v1/plugins/catalog/${source}`, {
          method: 'GET',
          cache: 'no-store',
          credentials: 'same-origin',
        });
      } catch {
        assertGeneration(requestGen);
        throw new ApiError(2001, '无法连接插件目录接口', 0);
      }

      assertGeneration(requestGen);

      if (!response.ok) {
        await response.body?.cancel().catch(() => {});
        throw new ApiError(2001, `插件目录加载失败（HTTP ${response.status}）`, response.status);
      }

      const contentLength = Number(response.headers.get('content-length') || '0');
      if (Number.isFinite(contentLength) && contentLength > MAX_CATALOG_BYTES) {
        await response.body?.cancel().catch(() => {});
        throw new ApiError(2001, '插件目录响应体积超出 2 MiB 安全上限', response.status);
      }

      const rawText = await readResponseTextBounded(
        response,
        MAX_CATALOG_BYTES,
        () => assertGeneration(requestGen),
      );
      assertGeneration(requestGen);

      const validated = validateSameOriginCatalog(rawText, source);
      if (!validated.valid || !validated.catalog) {
        throw new ApiError(
          2001,
          `插件目录响应未通过安全结构校验：${validated.errors[0] || '结构无效'}`,
          response.status,
        );
      }
      return validated.catalog;
    },
  };
}

async function readResponseTextBounded(
  response: Response,
  maxBytes: number,
  assertStillValid: () => void,
): Promise<string> {
  if (!response.body || typeof response.body.getReader !== 'function') {
    throw new ApiError(
      2001,
      '插件目录响应缺少可读流，无法在读取前执行 2 MiB 安全上限校验，已拒绝解析',
      response.status,
    );
  }
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      try {
        assertStillValid();
      } catch (err) {
        await reader.cancel().catch(() => {});
        throw err;
      }
      if (done) break;
      if (!value || value.byteLength === 0) continue;
      totalBytes += value.byteLength;
      if (totalBytes > maxBytes) {
        await reader.cancel().catch(() => {});
        throw new ApiError(2001, '插件目录响应体积超出 2 MiB 安全上限', response.status);
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const merged = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder('utf-8').decode(merged);
}

export type ApiClient = ReturnType<typeof createApiClient>;
