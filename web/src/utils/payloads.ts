import type {
  PlanItem,
  PlanRepeatRule,
  PlanUpsertPayload,
  ScheduleItem,
  ScheduleUpsertPayload,
  StrategyItem,
  StrategyUpsertPayload,
} from '../types';
import { formatTimeShort, toBackendTime, todayIsoDate } from './format';

export type PlanEditableField =
  | 'name'
  | 'type'
  | 'startTime'
  | 'endTime'
  | 'repeatRule'
  | 'remindAdvanceMin';

export type PlanRepeatMode = 'daily' | 'weekly' | 'once' | 'preserve-custom';

export interface PlanFormState {
  id: number | null;
  version: number | null;
  originalItem: PlanItem | null;
  clientId: string | null;
  endedOccurrenceDate: string | null;
  statusLog: string | null;
  color: string | null;
  icon: string | null;
  remindMethods: PlanItem['remindMethods'];
  originalRepeatRule: PlanRepeatRule | null;
  hasCustomOriginalRepeat: boolean;
  name: string;
  type: string;
  startTime: string;
  endTime: string;
  repeatType: PlanRepeatMode;
  weekdays: number[];
  remindAdvanceMin: string;
  dirtyFields: ReadonlySet<PlanEditableField>;
}

function clonePlanRepeatRule(rule?: PlanRepeatRule | null): PlanRepeatRule | null {
  if (!rule) return null;
  return {
    ...rule,
    weekdays: rule.weekdays ? [...rule.weekdays] : rule.weekdays,
    dates: rule.dates ? [...rule.dates] : rule.dates,
  };
}

function parseOptionalNonNegativeInt(raw: string): number | null {
  const trimmed = raw.trim();
  if (trimmed === '') return null;
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed)) return 0;
  return Math.max(0, Math.trunc(parsed));
}

function canonicalPlanRepeatRule(rule?: PlanRepeatRule | null): string {
  if (!rule) return 'null';
  const normalized: PlanRepeatRule = { type: rule.type };
  if (rule.type === 'weekly' && Array.isArray(rule.weekdays)) {
    normalized.weekdays = Array.from(new Set(rule.weekdays)).sort((a, b) => a - b);
  }
  if (Array.isArray(rule.dates)) {
    normalized.dates = Array.from(new Set(rule.dates)).sort();
  }
  return JSON.stringify(normalized);
}

export function emptyPlanForm(): PlanFormState {
  return {
    id: null,
    version: null,
    originalItem: null,
    clientId: null,
    endedOccurrenceDate: null,
    statusLog: null,
    color: null,
    icon: null,
    remindMethods: { notification: true, sound: false, multiDevice: false, repeatMultiple: false },
    originalRepeatRule: null,
    hasCustomOriginalRepeat: false,
    name: '',
    type: '学习',
    startTime: '09:00',
    endTime: '10:30',
    repeatType: 'daily',
    weekdays: [1, 2, 3, 4, 5],
    remindAdvanceMin: '15',
    dirtyFields: new Set<PlanEditableField>(),
  };
}

export function planFormFromItem(plan: PlanItem): PlanFormState {
  const rt = plan.repeatRule?.type;
  let repeatType: PlanRepeatMode;
  let hasCustomOriginalRepeat = false;
  if (rt === 'weekly') {
    repeatType = 'weekly';
  } else if (rt === 'once') {
    repeatType = 'once';
  } else if (rt === 'daily') {
    repeatType = 'daily';
  } else if (plan.repeatRule) {
    // 完整保留 custom（含 dates 数组）或其他未知后端重复规则，严禁强行投影为 daily
    repeatType = 'preserve-custom';
    hasCustomOriginalRepeat = true;
  } else {
    repeatType = 'once';
  }

  const clonedRule = clonePlanRepeatRule(plan.repeatRule);
  const originalSnapshot: PlanItem = {
    ...plan,
    repeatRule: clonedRule,
    remindMethods: plan.remindMethods ? { ...plan.remindMethods } : plan.remindMethods,
  };

  return {
    id: plan.id,
    version: plan.version,
    originalItem: originalSnapshot,
    clientId: plan.clientId ?? null,
    endedOccurrenceDate: plan.endedOccurrenceDate ?? null,
    statusLog: plan.statusLog ?? null,
    color: plan.color ?? null,
    icon: plan.icon ?? null,
    remindMethods: plan.remindMethods ? { ...plan.remindMethods } : null,
    originalRepeatRule: clonePlanRepeatRule(plan.repeatRule),
    hasCustomOriginalRepeat,
    name: plan.name,
    type: plan.type,
    startTime: formatTimeShort(plan.startTime),
    endTime: formatTimeShort(plan.endTime),
    repeatType,
    weekdays: plan.repeatRule?.weekdays?.length ? [...plan.repeatRule.weekdays] : [1, 2, 3, 4, 5],
    remindAdvanceMin:
      plan.remindAdvanceMin !== null && plan.remindAdvanceMin !== undefined
        ? String(plan.remindAdvanceMin)
        : '',
    dirtyFields: new Set<PlanEditableField>(),
  };
}

function buildEditedPlanRepeatRule(
  form: PlanFormState,
  baseRule?: PlanRepeatRule | null,
): PlanRepeatRule | null {
  const effectiveBase = baseRule !== undefined ? baseRule : form.originalRepeatRule;
  if (form.repeatType === 'preserve-custom') {
    return clonePlanRepeatRule(effectiveBase);
  }
  const sameType =
    effectiveBase !== null &&
    effectiveBase !== undefined &&
    typeof effectiveBase.type === 'string' &&
    effectiveBase.type.trim().toLowerCase() === form.repeatType;

  if (form.repeatType === 'weekly') {
    const weekdays = form.weekdays.length
      ? Array.from(new Set(form.weekdays)).sort((a, b) => a - b)
      : [1, 2, 3, 4, 5];
    if (sameType && effectiveBase) {
      return {
        ...clonePlanRepeatRule(effectiveBase)!,
        type: 'weekly',
        weekdays,
      };
    }
    return { type: 'weekly', weekdays };
  }
  if (sameType && effectiveBase) {
    return {
      ...clonePlanRepeatRule(effectiveBase)!,
      type: form.repeatType,
    };
  }
  return { type: form.repeatType };
}

function isPlanFieldModified(nextForm: PlanFormState, field: PlanEditableField): boolean {
  const orig = nextForm.originalItem;
  if (!orig) return true;
  switch (field) {
    case 'name':
      return nextForm.name !== orig.name;
    case 'type':
      return nextForm.type !== orig.type;
    case 'startTime':
      return nextForm.startTime !== formatTimeShort(orig.startTime);
    case 'endTime':
      return nextForm.endTime !== formatTimeShort(orig.endTime);
    case 'remindAdvanceMin':
      return parseOptionalNonNegativeInt(nextForm.remindAdvanceMin) !== (orig.remindAdvanceMin ?? null);
    case 'repeatRule':
      if (nextForm.repeatType === 'preserve-custom') {
        return false;
      }
      return (
        canonicalPlanRepeatRule(buildEditedPlanRepeatRule(nextForm, orig.repeatRule ?? null)) !==
        canonicalPlanRepeatRule(orig.repeatRule ?? null)
      );
  }
}

export function withPlanField<K extends keyof PlanFormState>(
  form: PlanFormState,
  key: K,
  value: PlanFormState[K],
  dirtyKey?: PlanEditableField,
): PlanFormState {
  const nextDirty = new Set(form.dirtyFields);
  const nextForm: PlanFormState = { ...form, [key]: value, dirtyFields: nextDirty };
  if (dirtyKey) {
    if (isPlanFieldModified(nextForm, dirtyKey)) {
      nextDirty.add(dirtyKey);
    } else {
      nextDirty.delete(dirtyKey);
    }
  }
  return nextForm;
}

/**
 * 构建计划提交载荷（Astra B7 / R4）：
 * - 已有计划（普通编辑或 1409 冲突覆盖）统一以 base = serverSnapshot ?? form.originalItem 为基底，
 *   仅合并 dirtyFields，完整保留未修改字段（含 HH:mm:ss 秒精度、remindAdvanceMin=null、custom dates 及扩展字段）；
 * - 显式切回 preserve-custom 时清除 repeatRule 脏标记，且兜底解析始终回退到 base.repeatRule 而非旧 originalRepeatRule。
 */
export function buildPlanUpsertPayload(
  form: PlanFormState,
  serverSnapshot?: PlanItem | null,
): PlanUpsertPayload {
  const base = serverSnapshot ?? form.originalItem;
  if (base) {
    const dirty = form.dirtyFields;
    const baseRule = base.repeatRule ?? null;
    return {
      clientId: base.clientId || undefined,
      endedOccurrenceDate: base.endedOccurrenceDate ?? null,
      statusLog: base.statusLog ?? null,
      name: dirty.has('name') ? form.name.trim() : base.name,
      type: dirty.has('type') ? form.type : base.type,
      startTime: dirty.has('startTime') ? toBackendTime(form.startTime) : base.startTime,
      endTime: dirty.has('endTime') ? toBackendTime(form.endTime) : base.endTime,
      repeatRule: dirty.has('repeatRule')
        ? buildEditedPlanRepeatRule(form, baseRule)
        : clonePlanRepeatRule(baseRule),
      color: base.color ?? null,
      icon: base.icon ?? null,
      remindAdvanceMin: dirty.has('remindAdvanceMin')
        ? parseOptionalNonNegativeInt(form.remindAdvanceMin)
        : (base.remindAdvanceMin ?? null),
      remindMethods: base.remindMethods ?? null,
    };
  }

  return {
    clientId: form.clientId || undefined,
    endedOccurrenceDate: form.endedOccurrenceDate,
    statusLog: form.statusLog,
    name: form.name.trim(),
    type: form.type,
    startTime: toBackendTime(form.startTime),
    endTime: toBackendTime(form.endTime),
    repeatRule: buildEditedPlanRepeatRule(form, form.originalRepeatRule),
    color: form.color,
    icon: form.icon,
    remindAdvanceMin: parseOptionalNonNegativeInt(form.remindAdvanceMin),
    remindMethods: form.remindMethods,
  };
}

/**
 * 构建执行策略提交载荷（Astra B6 / R3）：
 * 无论普通保存还是 1409 冲突后重试，均强制从同一份最新快照（baseSnapshot）读取
 * version、blacklistApps 与 whitelistApps，仅合并 Web 端可编辑的两个布尔开关。
 */
export function buildStrategyUpsertPayload(
  baseSnapshot: StrategyItem | null,
  edits: { autoDnd: boolean; notifySilence: boolean },
): { payload: StrategyUpsertPayload; baseVersion: number | undefined } {
  return {
    payload: {
      blacklistApps: baseSnapshot?.blacklistApps ?? null,
      whitelistApps: baseSnapshot?.whitelistApps ?? null,
      autoDnd: edits.autoDnd,
      notifySilence: edits.notifySilence,
    },
    baseVersion: baseSnapshot?.version,
  };
}

// ============================================================================
// 日程表单与 1409 冲突合并（Astra B7 / R4）
// ============================================================================

export type ScheduleEditableField =
  | 'title'
  | 'startDate'
  | 'endDate'
  | 'allDay'
  | 'startTime'
  | 'endTime'
  | 'location'
  | 'coordsCleared'
  | 'note'
  | 'remindAdvanceMin'
  | 'repeatRule'
  | 'linkedPlanId';

export type ScheduleRepeatMode = 'preserve' | 'none' | 'daily' | 'weekly' | 'monthly' | 'yearly';

export interface ScheduleFormState {
  id: number | null;
  version: number | null;
  originalItem: ScheduleItem | null;
  clientId: string | null;
  originalRepeatRule: string | null;
  title: string;
  startDate: string;
  endDate: string;
  allDay: boolean;
  startTime: string;
  endTime: string;
  location: string;
  locationLat: number | null;
  locationLon: number | null;
  coordsCleared: boolean;
  note: string;
  remindAdvanceMin: string;
  repeatMode: ScheduleRepeatMode;
  repeatWeekdays: number[];
  linkedPlanId: string;
  tzId: string | null;
  dirtyFields: ReadonlySet<ScheduleEditableField>;
}

export function emptyScheduleForm(): ScheduleFormState {
  const today = todayIsoDate();
  const dt = new Date(`${today}T00:00:00`);
  const weekday = dt.getDay() === 0 ? 7 : dt.getDay();
  return {
    id: null,
    version: null,
    originalItem: null,
    clientId: null,
    originalRepeatRule: null,
    title: '',
    startDate: today,
    endDate: today,
    allDay: false,
    startTime: '14:00',
    endTime: '15:00',
    location: '',
    locationLat: null,
    locationLon: null,
    coordsCleared: false,
    note: '',
    remindAdvanceMin: '15',
    repeatMode: 'none',
    repeatWeekdays: [weekday],
    linkedPlanId: '',
    tzId: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
    dirtyFields: new Set<ScheduleEditableField>(),
  };
}

function parseScheduleWeekdays(raw?: string | null, fallbackDate?: string): number[] {
  if (raw) {
    try {
      const parsed = JSON.parse(raw) as { type?: string; weekdays?: unknown; days?: unknown };
      const rawDays = Array.isArray(parsed.weekdays) ? parsed.weekdays : parsed.days;
      if (parsed.type === 'weekly' && Array.isArray(rawDays)) {
        const valid = rawDays.filter(
          (d): d is number => typeof d === 'number' && Number.isInteger(d) && d >= 1 && d <= 7,
        );
        if (valid.length > 0) return Array.from(new Set(valid)).sort((a, b) => a - b);
      }
    } catch {
      // ignore
    }
  }
  const dt = new Date(`${fallbackDate || todayIsoDate()}T00:00:00`);
  return [dt.getDay() === 0 ? 7 : dt.getDay()];
}

export function scheduleFormFromItem(item: ScheduleItem): ScheduleFormState {
  const hasExistingRule = Boolean(item.repeatRule && item.repeatRule.trim());
  const originalSnapshot: ScheduleItem = { ...item };
  return {
    id: item.id,
    version: item.version,
    originalItem: originalSnapshot,
    clientId: item.clientId ?? null,
    originalRepeatRule: item.repeatRule ?? null,
    title: item.title,
    startDate: item.startDate,
    endDate: item.endDate,
    allDay: Boolean(item.allDay),
    startTime: item.startTime ? formatTimeShort(item.startTime) : '09:00',
    endTime: item.endTime ? formatTimeShort(item.endTime) : '10:00',
    location: item.location ?? '',
    locationLat: item.locationLat ?? null,
    locationLon: item.locationLon ?? null,
    coordsCleared: false,
    note: item.note ?? '',
    remindAdvanceMin:
      item.remindAdvanceMin !== null && item.remindAdvanceMin !== undefined
        ? String(item.remindAdvanceMin)
        : '',
    // 已有重复规则（如多曜日 weekly [1,3,5]、自定义 monthly day、yearly month/day）默认原样保留
    repeatMode: hasExistingRule ? 'preserve' : 'none',
    repeatWeekdays: parseScheduleWeekdays(item.repeatRule, item.startDate),
    linkedPlanId: item.linkedPlanId ? String(item.linkedPlanId) : '',
    tzId: item.tzId ?? null,
    dirtyFields: new Set<ScheduleEditableField>(),
  };
}

function parseScheduleRuleObject(raw?: string | null): Record<string, unknown> | null {
  if (!raw || !raw.trim()) return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    return parsed as Record<string, unknown>;
  } catch {
    return null;
  }
}

function toPositiveWeekdayList(value: unknown): number[] {
  if (!Array.isArray(value)) return [];
  const unique = new Set<number>();
  for (const item of value) {
    const n = Number(item);
    if (Number.isInteger(n) && n >= 1 && n <= 7) {
      unique.add(n);
    }
  }
  return Array.from(unique).sort((a, b) => a - b);
}

/**
 * 无论 repeatRule 是否被界面改脏，出站日程规则若仍使用历史 days 别名（缺省/空 weekdays 或残留 days 键），
 * 均统一规范为后端 ScheduleRules.java 要求的 weekdays 键并完整保留全部非 UI 扩展字段（Astra R4 v1.5）。
 */
function normalizeOutboundScheduleRepeatRule(raw?: string | null): string | null {
  if (raw === null || raw === undefined || raw.trim() === '') {
    return raw ?? null;
  }
  const obj = parseScheduleRuleObject(raw);
  if (!obj) {
    return raw;
  }
  const type = typeof obj.type === 'string' ? obj.type.trim().toLowerCase() : '';
  if (type !== 'weekly') {
    return raw;
  }
  const existingWeekdays = toPositiveWeekdayList(obj.weekdays);
  if (existingWeekdays.length > 0 && !('days' in obj)) {
    return raw;
  }
  const legacyDays = toPositiveWeekdayList(obj.days);
  const normalizedWeekdays = existingWeekdays.length > 0 ? existingWeekdays : legacyDays;
  if (normalizedWeekdays.length === 0) {
    return raw;
  }
  const { days: _legacyDays, weekdays: _oldWeekdays, ...rest } = obj;
  return JSON.stringify({ ...rest, type: 'weekly', weekdays: normalizedWeekdays });
}

/**
 * 构建编辑后的日程重复规则（Astra R4 v1.5）：
 * - preserve 透传时同步执行历史 days -> weekdays 规范化；
 * - 切为 none 返回 null；切为不同 type 时按新类型洁净契约生成（不残留旧类型专属键）；
 * - 保持相同 type（如修改 weekly 曜日或同类型刷新）时，以目标基底解析出的对象（普通保存用原始规则，
 *   1409 冲突重试用 serverLatest.repeatRule）做浅拷贝保留全部服务端扩展字段（如 interval/endDate/tz/customExt），
 *   仅覆盖 UI 所拥有的字段。
 */
function buildEditedScheduleRepeatRule(
  form: ScheduleFormState,
  baseRule?: string | null,
): string | null {
  const effectiveBaseRaw = baseRule !== undefined ? baseRule : form.originalRepeatRule;
  if (form.repeatMode === 'preserve') {
    return normalizeOutboundScheduleRepeatRule(effectiveBaseRaw ?? null);
  }
  if (form.repeatMode === 'none') return null;

  const baseObj = parseScheduleRuleObject(effectiveBaseRaw);
  const sameType = Boolean(
    baseObj &&
      typeof baseObj.type === 'string' &&
      baseObj.type.trim().toLowerCase() === form.repeatMode,
  );
  const parts = form.startDate.split('-').map((n) => Number(n));
  const month = parts[1] || 1;
  const day = parts[2] || 1;

  if (form.repeatMode === 'daily') {
    return JSON.stringify(sameType && baseObj ? { ...baseObj, type: 'daily' } : { type: 'daily' });
  }
  if (form.repeatMode === 'weekly') {
    const days = form.repeatWeekdays.length
      ? Array.from(new Set(form.repeatWeekdays)).sort((a, b) => a - b)
      : parseScheduleWeekdays(null, form.startDate);
    if (sameType && baseObj) {
      // 保留全部服务端非 UI 扩展字段，同时清除旧别名 days，始终规范输出后端 ScheduleRules.java 唯一认可的 weekdays 字段
      const { days: _legacyDays, ...rest } = baseObj;
      return JSON.stringify({ ...rest, type: 'weekly', weekdays: days });
    }
    return JSON.stringify({ type: 'weekly', weekdays: days });
  }
  if (form.repeatMode === 'monthly') {
    return JSON.stringify(
      sameType && baseObj ? { ...baseObj, type: 'monthly', day } : { type: 'monthly', day },
    );
  }
  return JSON.stringify(
    sameType && baseObj
      ? { ...baseObj, type: 'yearly', month, day }
      : { type: 'yearly', month, day },
  );
}

/**
 * 按 UI 所拥有的日程重复规则字段（type、去重排序后的 weekdays、monthly day、yearly month/day）
 * 提取规范化语义键，用于忽略后端合法空白与键序差异，且兼顾后端可能保留的未暴露扩展字段（Astra R4 v1.2）。
 */
function canonicalScheduleRepeatRuleSemantics(raw?: string | null): string {
  if (raw === null || raw === undefined || raw.trim() === '') {
    return 'none';
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return `opaque:${raw}`;
    }
    const obj = parsed as Record<string, unknown>;
    const type = typeof obj.type === 'string' ? obj.type.trim().toLowerCase() : '';
    if (type === 'daily') {
      return JSON.stringify({ type: 'daily' });
    }
    if (type === 'weekly') {
      const rawDays = Array.isArray(obj.weekdays) ? obj.weekdays : obj.days;
      if (Array.isArray(rawDays)) {
        const days = Array.from(
          new Set(
            rawDays.filter(
              (d): d is number => typeof d === 'number' && Number.isInteger(d) && d >= 1 && d <= 7,
            ),
          ),
        ).sort((a, b) => a - b);
        if (days.length > 0) {
          return JSON.stringify({ type: 'weekly', weekdays: days });
        }
      }
    }
    if (type === 'monthly' && typeof obj.day === 'number' && Number.isInteger(obj.day)) {
      return JSON.stringify({ type: 'monthly', day: obj.day });
    }
    if (
      type === 'yearly' &&
      typeof obj.month === 'number' &&
      Number.isInteger(obj.month) &&
      typeof obj.day === 'number' &&
      Number.isInteger(obj.day)
    ) {
      return JSON.stringify({ type: 'yearly', month: obj.month, day: obj.day });
    }
    return `opaque:${raw}`;
  } catch {
    return `opaque:${raw}`;
  }
}

function isScheduleFieldModified(
  nextForm: ScheduleFormState,
  field: ScheduleEditableField,
): boolean {
  const orig = nextForm.originalItem;
  if (!orig) return true;
  switch (field) {
    case 'title':
      return nextForm.title !== orig.title;
    case 'startDate':
      return nextForm.startDate !== orig.startDate;
    case 'endDate':
      return nextForm.endDate !== orig.endDate;
    case 'allDay':
      return nextForm.allDay !== Boolean(orig.allDay);
    case 'startTime':
      return nextForm.startTime !== (orig.startTime ? formatTimeShort(orig.startTime) : '09:00');
    case 'endTime':
      return nextForm.endTime !== (orig.endTime ? formatTimeShort(orig.endTime) : '10:00');
    case 'location':
      return nextForm.location !== (orig.location ?? '');
    case 'coordsCleared':
      return nextForm.coordsCleared;
    case 'note':
      return nextForm.note !== (orig.note ?? '');
    case 'remindAdvanceMin':
      return parseOptionalNonNegativeInt(nextForm.remindAdvanceMin) !== (orig.remindAdvanceMin ?? null);
    case 'linkedPlanId':
      return (nextForm.linkedPlanId ? Number(nextForm.linkedPlanId) : null) !== (orig.linkedPlanId ?? null);
    case 'repeatRule':
      if (nextForm.repeatMode === 'preserve') {
        return false;
      }
      return (
        canonicalScheduleRepeatRuleSemantics(
          buildEditedScheduleRepeatRule(nextForm, orig.repeatRule ?? null),
        ) !== canonicalScheduleRepeatRuleSemantics(orig.repeatRule ?? null)
      );
  }
}

export function withScheduleField<K extends keyof ScheduleFormState>(
  form: ScheduleFormState,
  key: K,
  value: ScheduleFormState[K],
  dirtyKey?: ScheduleEditableField,
): ScheduleFormState {
  const nextDirty = new Set(form.dirtyFields);
  const nextForm: ScheduleFormState = { ...form, [key]: value, dirtyFields: nextDirty };
  if (dirtyKey) {
    if (isScheduleFieldModified(nextForm, dirtyKey)) {
      nextDirty.add(dirtyKey);
    } else {
      nextDirty.delete(dirtyKey);
    }
  }
  return nextForm;
}

/**
 * 构建日程提交载荷（Astra B7 / R4）：
 * - 已有日程（普通编辑或 1409 冲突覆盖）统一以 base = serverSnapshot ?? form.originalItem 为基底，
 *   仅合并 dirtyFields，完整保留未修改的 HH:mm:ss 秒精度、tzId（含 null）、repeatRule 与经纬度；
 * - 显式切回 preserve 时清除 repeatRule 脏标记，且兜底解析始终回退到 base.repeatRule 而非旧 originalRepeatRule。
 */
export function buildScheduleUpsertPayload(
  form: ScheduleFormState,
  serverSnapshot?: ScheduleItem | null,
): ScheduleUpsertPayload {
  const base = serverSnapshot ?? form.originalItem;
  if (base) {
    const dirty = form.dirtyFields;
    const allDay = dirty.has('allDay') ? form.allDay : Boolean(base.allDay);
    const baseHasActiveTimes = !base.allDay;
    const startTime = allDay
      ? null
      : dirty.has('startTime')
        ? toBackendTime(form.startTime)
        : baseHasActiveTimes && base.startTime
          ? base.startTime
          : toBackendTime(form.startTime);
    const endTime = allDay
      ? null
      : dirty.has('endTime')
        ? toBackendTime(form.endTime)
        : baseHasActiveTimes && base.endTime
          ? base.endTime
          : toBackendTime(form.endTime);

    const hasBaseCoords =
      base.locationLat !== null &&
      base.locationLat !== undefined &&
      base.locationLon !== null &&
      base.locationLon !== undefined;

    return {
      clientId: base.clientId || undefined,
      title: dirty.has('title') ? form.title.trim() : base.title,
      startDate: dirty.has('startDate') ? form.startDate : base.startDate,
      endDate: dirty.has('endDate') ? form.endDate : base.endDate,
      allDay,
      startTime,
      endTime,
      location: dirty.has('location') ? form.location.trim() || null : (base.location ?? null),
      locationLat: form.coordsCleared ? null : hasBaseCoords ? base.locationLat : null,
      locationLon: form.coordsCleared ? null : hasBaseCoords ? base.locationLon : null,
      note: dirty.has('note') ? form.note.trim() || null : (base.note ?? null),
      remindAdvanceMin: dirty.has('remindAdvanceMin')
        ? parseOptionalNonNegativeInt(form.remindAdvanceMin)
        : (base.remindAdvanceMin ?? null),
      repeatRule: dirty.has('repeatRule')
        ? buildEditedScheduleRepeatRule(form, base.repeatRule ?? null)
        : normalizeOutboundScheduleRepeatRule(base.repeatRule ?? null),
      linkedPlanId: dirty.has('linkedPlanId')
        ? form.linkedPlanId
          ? Number(form.linkedPlanId)
          : null
        : (base.linkedPlanId ?? null),
      tzId: base.tzId ?? null,
    };
  }

  const hasCoords = !form.coordsCleared && form.locationLat !== null && form.locationLon !== null;

  return {
    clientId: form.clientId || undefined,
    title: form.title.trim(),
    startDate: form.startDate,
    endDate: form.endDate,
    allDay: form.allDay,
    startTime: form.allDay ? null : toBackendTime(form.startTime),
    endTime: form.allDay ? null : toBackendTime(form.endTime),
    location: form.location.trim() || null,
    locationLat: hasCoords ? form.locationLat : null,
    locationLon: hasCoords ? form.locationLon : null,
    note: form.note.trim() || null,
    remindAdvanceMin: parseOptionalNonNegativeInt(form.remindAdvanceMin),
    repeatRule: buildEditedScheduleRepeatRule(form, form.originalRepeatRule),
    linkedPlanId: form.linkedPlanId ? Number(form.linkedPlanId) : null,
    tzId: form.tzId || Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
  };
}
