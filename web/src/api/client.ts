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
import {
  buildWebDeviceName,
  clearSessionToken,
  getOrCreateDeviceUid,
  readSessionToken,
  writeSessionToken,
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

export interface ApiClientOptions {
  fetchImpl?: typeof fetch;
  onUnauthorized?: (reason: string) => void;
}

export function createApiClient(options: ApiClientOptions = {}) {
  const fetchFn = options.fetchImpl ?? ((...args: Parameters<typeof fetch>) => window.fetch(...args));
  let memoryToken: string | null = readSessionToken();

  function getToken(): string | null {
    return readSessionToken() ?? memoryToken;
  }

  function setToken(token: string | null): void {
    memoryToken = token;
    if (token) {
      writeSessionToken(token);
    } else {
      clearSessionToken();
    }
  }

  function handleUnauthorized(message: string): never {
    setToken(null);
    options.onUnauthorized?.(message);
    throw new ApiError(ERROR_UNAUTHORIZED, message, 401);
  }

  async function requestJson<T>(
    path: string,
    init: RequestInit = {},
    requireAuth = true,
  ): Promise<T> {
    const headers = new Headers(init.headers ?? {});
    if (init.body !== undefined && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    if (requireAuth) {
      const token = getToken();
      if (!token) {
        handleUnauthorized('请先登录您的知序实例');
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
      throw new ApiError(2001, '网络请求失败，请检查个人后端连接是否正常', 0);
    }

    if (response.status === 401) {
      handleUnauthorized('登录会话已失效，请重新登录');
    }

    let payload: ApiResponse<T>;
    try {
      payload = (await response.json()) as ApiResponse<T>;
    } catch {
      throw new ApiError(2001, `服务端返回了非 JSON 响应（HTTP ${response.status}）`, response.status);
    }

    if (payload.code === ERROR_UNAUTHORIZED) {
      handleUnauthorized(payload.message || '登录状态已过期，请重新登录');
    }
    if (payload.code !== 0) {
      throw new ApiError(payload.code, payload.message || '请求失败', response.status);
    }
    return payload.data;
  }

  async function requestRawText(path: string, requireAuth = true): Promise<string> {
    const headers = new Headers();
    if (requireAuth) {
      const token = getToken();
      if (!token) {
        handleUnauthorized('请先登录您的知序实例');
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
      throw new ApiError(2001, '导出请求失败，请检查网络连接', 0);
    }
    if (response.status === 401) {
      handleUnauthorized('登录会话已失效，请重新登录');
    }
    const contentType = response.headers.get('content-type') || '';
    const text = await response.text();
    if (contentType.includes('application/json')) {
      try {
        const parsed = JSON.parse(text) as Partial<ApiResponse<unknown>>;
        if (typeof parsed.code === 'number' && parsed.code !== 0) {
          if (parsed.code === ERROR_UNAUTHORIZED) {
            handleUnauthorized(parsed.message || '登录状态已过期，请重新登录');
          }
          throw new ApiError(parsed.code, parsed.message || '导出失败', response.status);
        }
      } catch (err) {
        if (err instanceof ApiError) throw err;
      }
    }
    if (!response.ok) {
      throw new ApiError(2001, `导出失败（HTTP ${response.status}）`, response.status);
    }
    return text;
  }

  return {
    getToken,
    setToken,
    clearSession: () => setToken(null),

    /**
     * 登录：优先发送 ADR-022 D2 规定的 platform="web"；
     * 若后端尚未放宽 LoginRequest.platform 正则（当前仅允许 android|ios|windows），
     * Jakarta 校验会在限流计数前返回 1002，此时自动以 platform="windows" 重试一次（仍保持 web-<UUID> deviceUid）。
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
      return requestJson<PlanItem>(`/api/v1/plans/${id}?baseVersion=${encodeURIComponent(String(baseVersion))}`, {
        method: 'PUT',
        body: JSON.stringify(payload),
      });
    },

    deletePlan(id: number, baseVersion?: number): Promise<void> {
      const suffix = baseVersion !== undefined ? `?baseVersion=${encodeURIComponent(String(baseVersion))}` : '';
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
      const suffix = baseVersion !== undefined ? `?baseVersion=${encodeURIComponent(String(baseVersion))}` : '';
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

    updateSchedule(id: number, baseVersion: number, payload: ScheduleUpsertPayload): Promise<ScheduleItem> {
      return requestJson<ScheduleItem>(
        `/api/v1/schedules/${id}?baseVersion=${encodeURIComponent(String(baseVersion))}`,
        {
          method: 'PUT',
          body: JSON.stringify(payload),
        },
      );
    },

    deleteSchedule(id: number, baseVersion?: number): Promise<void> {
      const suffix = baseVersion !== undefined ? `?baseVersion=${encodeURIComponent(String(baseVersion))}` : '';
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

    updateJournal(id: number, baseVersion: number, payload: JournalUpsertPayload): Promise<JournalItem> {
      return requestJson<JournalItem>(
        `/api/v1/journals/${id}?baseVersion=${encodeURIComponent(String(baseVersion))}`,
        {
          method: 'PUT',
          body: JSON.stringify(payload),
        },
      );
    },

    deleteJournal(id: number, baseVersion?: number): Promise<void> {
      const suffix = baseVersion !== undefined ? `?baseVersion=${encodeURIComponent(String(baseVersion))}` : '';
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

    // Same-origin Plugin Catalog（注意：PluginCatalogController 返回原始 JSON Map，不包 ApiResponse）
    async getPluginCatalog(source: 'official' | 'third-party'): Promise<PluginCatalogDocument> {
      const headers = new Headers();
      const token = getToken();
      if (token) headers.set('Authorization', `Bearer ${token}`);
      let response: Response;
      try {
        response = await fetchFn(`/api/v1/plugins/catalog/${source}`, {
          method: 'GET',
          headers,
          cache: 'no-store',
          credentials: 'same-origin',
        });
      } catch {
        throw new ApiError(2001, '无法连接插件目录接口', 0);
      }
      if (!response.ok) {
        throw new ApiError(2001, `插件目录加载失败（HTTP ${response.status}）`, response.status);
      }
      const data = (await response.json()) as PluginCatalogDocument;
      if (!data || !Array.isArray(data.plugins)) {
        throw new ApiError(2001, '插件目录响应格式无效', response.status);
      }
      return data;
    },
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;
