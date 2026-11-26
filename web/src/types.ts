/** 后端统一响应包装（对应 com.planlock.common.ApiResponse） */
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

/** GET /api/v1/me（对应 com.planlock.account.dto.MeResponse） */
export interface MeResponse {
  userId: number;
  deviceId: number | null;
  accountMasked: string;
  userType: string;
  guest: boolean;
  deviceName: string | null;
  devicePlatform: string | null;
  instanceLabel: string | null;
  deploymentEnabled: boolean;
  version: string;
}

/** POST /api/v1/auth/login 响应（对应 AuthResponse） */
export interface AuthResponse {
  token: string;
  userId: number;
  deviceId: number;
  guestUserId?: number | null;
}

/** GET /api/v1/devices 项（对应 Device） */
export interface DeviceItem {
  id: number;
  userId: number;
  deviceName: string | null;
  platform: string;
  deviceUid: string;
  isPrimary: boolean;
  lastOnlineAt: string | null;
  createdAt: string;
  updatedAt: string;
  online: boolean | null;
}

/** 计划重复规则（对应 RepeatRule） */
export interface PlanRepeatRule {
  type: 'daily' | 'weekly' | 'once' | 'custom' | string;
  weekdays?: number[] | null;
  dates?: string[] | null;
}

/** 提醒方式（对应 RemindMethods） */
export interface RemindMethods {
  notification: boolean;
  sound: boolean;
  multiDevice: boolean;
  repeatMultiple: boolean;
}

/** 计划响应（对应 PlanResponse） */
export interface PlanItem {
  id: number;
  clientId?: string | null;
  userId: number;
  endedOccurrenceDate?: string | null;
  statusLog?: string | null;
  name: string;
  type: string;
  startTime: string; // HH:mm:ss or HH:mm
  endTime: string;
  repeatRule?: PlanRepeatRule | null;
  color?: string | null;
  icon?: string | null;
  remindAdvanceMin?: number | null;
  remindMethods?: RemindMethods | null;
  status: number; // 1 active, 0 paused
  version: number;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** 计划新建/更新载荷（对应 PlanUpsertRequest） */
export interface PlanUpsertPayload {
  clientId?: string | null;
  endedOccurrenceDate?: string | null;
  statusLog?: string | null;
  name: string;
  type: string;
  startTime: string;
  endTime: string;
  repeatRule?: PlanRepeatRule | null;
  color?: string | null;
  icon?: string | null;
  remindAdvanceMin?: number | null;
  remindMethods?: RemindMethods | null;
}

/** 执行策略（对应 StrategyResponse / StrategyUpsertRequest） */
export interface AppItem {
  packageName?: string;
  appName?: string;
  [key: string]: unknown;
}

export interface StrategyItem {
  id: number;
  planId: number;
  blacklistApps: AppItem[] | null;
  whitelistApps: AppItem[] | null;
  autoDnd: boolean;
  notifySilence: boolean;
  version: number;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface StrategyUpsertPayload {
  blacklistApps: AppItem[] | null;
  whitelistApps: AppItem[] | null;
  autoDnd: boolean;
  notifySilence: boolean;
}

/** 日程（对应 ScheduleResponse / ScheduleUpsertRequest） */
export interface ScheduleItem {
  id: number;
  clientId?: string | null;
  userId: number;
  title: string;
  startDate: string; // YYYY-MM-DD
  startTime?: string | null;
  endDate: string;
  endTime?: string | null;
  allDay: boolean;
  location?: string | null;
  locationLat?: number | null;
  locationLon?: number | null;
  note?: string | null;
  remindAdvanceMin?: number | null;
  repeatRule?: string | null; // JSON string
  linkedPlanId?: number | null;
  tzId?: string | null;
  status: number;
  version: number;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface ScheduleUpsertPayload {
  clientId?: string | null;
  title: string;
  startDate: string;
  startTime?: string | null;
  endDate: string;
  endTime?: string | null;
  allDay: boolean;
  location?: string | null;
  locationLat?: number | null;
  locationLon?: number | null;
  note?: string | null;
  remindAdvanceMin?: number | null;
  repeatRule?: string | null;
  linkedPlanId?: number | null;
  tzId?: string | null;
}

/** 今日记录摘要（对应 TodaySummary） */
export interface ExecutionRecord {
  id: number;
  planId: number;
  deviceId: number | null;
  planDate: string;
  status: number; // 1 完成 / 2 提前解锁 / 3 违规结束
  startedAt?: string | null;
  endedAt?: string | null;
  clientId?: string | null;
}

export interface TodaySummary {
  date: string;
  executions: ExecutionRecord[];
  violationCount: number;
  unlockCount: number;
}

/** 我的节奏（对应 RhythmSummary） */
export interface RhythmRatio {
  completed: number;
  total: number;
}

export interface RhythmDayCell {
  date: string;
  planned: number;
  completed: number;
  earlyEnded: number;
  missed: number;
  focusMinutes: number;
  isToday: boolean;
}

export interface RhythmPlanPerformance {
  planId: number;
  name: string;
  type: string;
  completionRate30d: RhythmRatio;
  completed: number;
  total: number;
}

export interface RhythmDistractionSource {
  appName: string;
  minutes: number;
}

export interface RhythmDistraction {
  minutes7d: number;
  topSources: RhythmDistractionSource[];
}

export interface RhythmSummary {
  headline: string;
  streakDays: number;
  completionRate7d: RhythmRatio;
  completionRatePrev7d: RhythmRatio;
  focusMinutes30d: number;
  focusMinutesPrev30d: number;
  earlyEnds30d: number;
  earlyEndsPrev30d: number;
  days: RhythmDayCell[];
  plans: RhythmPlanPerformance[];
  distraction: RhythmDistraction | null;
  source: string;
}

/** 日常记录（对应 JournalResponse / JournalUpsertRequest） */
export interface JournalItem {
  id: number;
  clientId?: string | null;
  userId: number;
  entryDate: string;
  mood?: string | null;
  tags: string[];
  content: string;
  status: number;
  version: number;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface JournalUpsertPayload {
  clientId?: string | null;
  entryDate: string;
  mood?: string | null;
  tags: string[];
  content: string;
}

/** 自定义计划类型（来自 GET /api/v1/sync/pull） */
export interface SyncPlanTypeView {
  typeKey: string;
  userId?: number;
  name: string;
  color: string;
  version?: number;
  deleted?: boolean;
}

export interface SyncPullResponse {
  planTypes?: SyncPlanTypeView[];
}

/** 插件目录（对应 PluginCatalogController 原始 JSON 及 REQ-037 形态 B catalog.json） */
export interface PluginCatalogEntry {
  id: string;
  name: string;
  version: string;
  versionCode?: number;
  contractVersion: number;
  type?: 'js' | 'dex';
  entryPoint: string;
  requiredCapabilities: string[];
  description: string;
  downloadUrl?: string;
  artifact?: string;
  sha256?: string;
  author?: string;
  homepage?: string;
  repository?: string;
  license?: string;
}

export interface PluginCatalogDocument {
  catalogFormatVersion: number;
  sourceId: string;
  sourceType: string;
  sourceName?: string;
  sourceDescription?: string;
  trustedFingerprints?: string[];
  plugins: PluginCatalogEntry[];
}

export interface ToastMessage {
  id: number;
  text: string;
  tone: 'ok' | 'warn' | 'error';
}
