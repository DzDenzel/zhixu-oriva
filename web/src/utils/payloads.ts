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
  clientId: string | null;
  endedOccurrenceDate: string | null;
  statusLog: string | null;
  color: string | null;
  icon: string | null;
  remindMethods: PlanItem['remindMethods'];
  originalRepeatRule: PlanRepeatRule | null;
  name: string;
  type: string;
  startTime: string;
  endTime: string;
  repeatType: PlanRepeatMode;
  weekdays: number[];
  remindAdvanceMin: number;
  dirtyFields: ReadonlySet<PlanEditableField>;
}

export function emptyPlanForm(): PlanFormState {
  return {
    id: null,
    version: null,
    clientId: null,
    endedOccurrenceDate: null,
    statusLog: null,
    color: null,
    icon: null,
    remindMethods: { notification: true, sound: false, multiDevice: false, repeatMultiple: false },
    originalRepeatRule: null,
    name: '',
    type: '学习',
    startTime: '09:00',
    endTime: '10:30',
    repeatType: 'daily',
    weekdays: [1, 2, 3, 4, 5],
    remindAdvanceMin: 15,
    dirtyFields: new Set<PlanEditableField>(),
  };
}

export function planFormFromItem(plan: PlanItem): PlanFormState {
  const rt = plan.repeatRule?.type;
  let repeatType: PlanRepeatMode;
  if (rt === 'weekly') {
    repeatType = 'weekly';
  } else if (rt === 'once') {
    repeatType = 'once';
  } else if (rt === 'daily') {
    repeatType = 'daily';
  } else if (plan.repeatRule) {
    // 完整保留 custom（含 dates 数组）或其他未知后端重复规则，严禁强行投影为 daily
    repeatType = 'preserve-custom';
  } else {
    repeatType = 'once';
  }

  return {
    id: plan.id,
    version: plan.version,
    clientId: plan.clientId ?? null,
    endedOccurrenceDate: plan.endedOccurrenceDate ?? null,
    statusLog: plan.statusLog ?? null,
    color: plan.color ?? null,
    icon: plan.icon ?? null,
    remindMethods: plan.remindMethods ?? {
      notification: true,
      sound: false,
      multiDevice: false,
      repeatMultiple: false,
    },
    originalRepeatRule: plan.repeatRule ? { ...plan.repeatRule, weekdays: plan.repeatRule.weekdays ? [...plan.repeatRule.weekdays] : plan.repeatRule.weekdays, dates: plan.repeatRule.dates ? [...plan.repeatRule.dates] : plan.repeatRule.dates } : null,
    name: plan.name,
    type: plan.type,
    startTime: formatTimeShort(plan.startTime),
    endTime: formatTimeShort(plan.endTime),
    repeatType,
    weekdays: plan.repeatRule?.weekdays?.length ? [...plan.repeatRule.weekdays] : [1, 2, 3, 4, 5],
    remindAdvanceMin: plan.remindAdvanceMin ?? 15,
    dirtyFields: new Set<PlanEditableField>(),
  };
}

export function withPlanField<K extends keyof PlanFormState>(
  form: PlanFormState,
  key: K,
  value: PlanFormState[K],
  dirtyKey?: PlanEditableField,
): PlanFormState {
  const nextDirty = new Set(form.dirtyFields);
  if (dirtyKey) nextDirty.add(dirtyKey);
  return { ...form, [key]: value, dirtyFields: nextDirty };
}

function buildEditedPlanRepeatRule(form: PlanFormState): PlanRepeatRule | null {
  if (form.repeatType === 'preserve-custom') {
    return form.originalRepeatRule;
  }
  if (form.repeatType === 'weekly') {
    return {
      type: 'weekly',
      weekdays: form.weekdays.length ? [...form.weekdays].sort((a, b) => a - b) : [1, 2, 3, 4, 5],
    };
  }
  return { type: form.repeatType };
}

/**
 * 构建计划提交载荷（Astra B7）：
 * - 普通编辑：保留未修改的 custom 日期列表与全部移动端扩展字段；
 * - 1409 冲突覆盖（传入 serverSnapshot）：以服务端最新快照为基底，仅合并用户明确改动的字段，
 *   绝不以旧草稿覆写移动端并发更新的隐藏字段或未编辑字段。
 */
export function buildPlanUpsertPayload(
  form: PlanFormState,
  serverSnapshot?: PlanItem | null,
): PlanUpsertPayload {
  if (serverSnapshot) {
    const dirty = form.dirtyFields;
    return {
      clientId: serverSnapshot.clientId || undefined,
      endedOccurrenceDate: serverSnapshot.endedOccurrenceDate ?? null,
      statusLog: serverSnapshot.statusLog ?? null,
      name: dirty.has('name') ? form.name.trim() : serverSnapshot.name,
      type: dirty.has('type') ? form.type : serverSnapshot.type,
      startTime: dirty.has('startTime') ? toBackendTime(form.startTime) : serverSnapshot.startTime,
      endTime: dirty.has('endTime') ? toBackendTime(form.endTime) : serverSnapshot.endTime,
      repeatRule: dirty.has('repeatRule')
        ? buildEditedPlanRepeatRule(form)
        : (serverSnapshot.repeatRule ?? null),
      color: serverSnapshot.color ?? null,
      icon: serverSnapshot.icon ?? null,
      remindAdvanceMin: dirty.has('remindAdvanceMin')
        ? Math.max(0, Number(form.remindAdvanceMin) || 0)
        : (serverSnapshot.remindAdvanceMin ?? 15),
      remindMethods: serverSnapshot.remindMethods ?? null,
    };
  }

  const repeatRule =
    form.id !== null && !form.dirtyFields.has('repeatRule')
      ? form.originalRepeatRule
      : buildEditedPlanRepeatRule(form);

  return {
    clientId: form.clientId || undefined,
    endedOccurrenceDate: form.endedOccurrenceDate,
    statusLog: form.statusLog,
    name: form.name.trim(),
    type: form.type,
    startTime: toBackendTime(form.startTime),
    endTime: toBackendTime(form.endTime),
    repeatRule,
    color: form.color,
    icon: form.icon,
    remindAdvanceMin: Math.max(0, Number(form.remindAdvanceMin) || 0),
    remindMethods: form.remindMethods,
  };
}

/**
 * 构建执行策略提交载荷（Astra B6）：
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
// 日程表单与 1409 冲突合并（Astra B7）
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
      const parsed = JSON.parse(raw) as { type?: string; weekdays?: unknown };
      if (parsed.type === 'weekly' && Array.isArray(parsed.weekdays)) {
        const valid = parsed.weekdays.filter(
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
  return {
    id: item.id,
    version: item.version,
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

export function withScheduleField<K extends keyof ScheduleFormState>(
  form: ScheduleFormState,
  key: K,
  value: ScheduleFormState[K],
  dirtyKey?: ScheduleEditableField,
): ScheduleFormState {
  const nextDirty = new Set(form.dirtyFields);
  if (dirtyKey) nextDirty.add(dirtyKey);
  return { ...form, [key]: value, dirtyFields: nextDirty };
}

function buildEditedScheduleRepeatRule(form: ScheduleFormState): string | null {
  if (form.repeatMode === 'preserve') return form.originalRepeatRule;
  if (form.repeatMode === 'none') return null;
  const parts = form.startDate.split('-').map((n) => Number(n));
  const month = parts[1] || 1;
  const day = parts[2] || 1;
  if (form.repeatMode === 'daily') return JSON.stringify({ type: 'daily' });
  if (form.repeatMode === 'weekly') {
    const days = form.repeatWeekdays.length
      ? Array.from(new Set(form.repeatWeekdays)).sort((a, b) => a - b)
      : parseScheduleWeekdays(null, form.startDate);
    return JSON.stringify({ type: 'weekly', weekdays: days });
  }
  if (form.repeatMode === 'monthly') return JSON.stringify({ type: 'monthly', day });
  return JSON.stringify({ type: 'yearly', month, day });
}

/**
 * 构建日程提交载荷（Astra B7）：
 * - 普通编辑：原样保留未编辑的 repeatRule（含多曜日 weekly / monthly / yearly）与成对经纬度；
 * - 1409 冲突覆盖（传入 serverSnapshot）：以服务端最新快照为基底，仅合并 dirtyFields，
 *   且仅在用户明确点击「清除坐标」（coordsCleared=true）时才清空经纬度，绝不用过时旧坐标覆写移动端并发更新。
 */
export function buildScheduleUpsertPayload(
  form: ScheduleFormState,
  serverSnapshot?: ScheduleItem | null,
): ScheduleUpsertPayload {
  if (serverSnapshot) {
    const dirty = form.dirtyFields;
    const allDay = dirty.has('allDay') ? form.allDay : Boolean(serverSnapshot.allDay);
    const startTime = allDay
      ? null
      : dirty.has('startTime') || (dirty.has('allDay') && !allDay)
        ? toBackendTime(form.startTime)
        : (serverSnapshot.startTime ?? toBackendTime(form.startTime));
    const endTime = allDay
      ? null
      : dirty.has('endTime') || (dirty.has('allDay') && !allDay)
        ? toBackendTime(form.endTime)
        : (serverSnapshot.endTime ?? toBackendTime(form.endTime));

    const hasServerCoords =
      serverSnapshot.locationLat !== null &&
      serverSnapshot.locationLat !== undefined &&
      serverSnapshot.locationLon !== null &&
      serverSnapshot.locationLon !== undefined;

    const remindNum =
      form.remindAdvanceMin.trim() === ''
        ? null
        : Math.max(0, Number(form.remindAdvanceMin) || 0);

    return {
      clientId: serverSnapshot.clientId || undefined,
      title: dirty.has('title') ? form.title.trim() : serverSnapshot.title,
      startDate: dirty.has('startDate') ? form.startDate : serverSnapshot.startDate,
      endDate: dirty.has('endDate') ? form.endDate : serverSnapshot.endDate,
      allDay,
      startTime,
      endTime,
      location: dirty.has('location') ? form.location.trim() || null : (serverSnapshot.location ?? null),
      locationLat: form.coordsCleared ? null : hasServerCoords ? serverSnapshot.locationLat : null,
      locationLon: form.coordsCleared ? null : hasServerCoords ? serverSnapshot.locationLon : null,
      note: dirty.has('note') ? form.note.trim() || null : (serverSnapshot.note ?? null),
      remindAdvanceMin: dirty.has('remindAdvanceMin')
        ? remindNum
        : (serverSnapshot.remindAdvanceMin ?? null),
      repeatRule: dirty.has('repeatRule')
        ? buildEditedScheduleRepeatRule(form)
        : (serverSnapshot.repeatRule ?? null),
      linkedPlanId: dirty.has('linkedPlanId')
        ? form.linkedPlanId
          ? Number(form.linkedPlanId)
          : null
        : (serverSnapshot.linkedPlanId ?? null),
      tzId: serverSnapshot.tzId || Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
    };
  }

  const hasCoords = !form.coordsCleared && form.locationLat !== null && form.locationLon !== null;
  const remindNum =
    form.remindAdvanceMin.trim() === ''
      ? null
      : Math.max(0, Number(form.remindAdvanceMin) || 0);
  const repeatRule =
    form.id !== null && !form.dirtyFields.has('repeatRule')
      ? form.originalRepeatRule
      : buildEditedScheduleRepeatRule(form);

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
    remindAdvanceMin: remindNum,
    repeatRule,
    linkedPlanId: form.linkedPlanId ? Number(form.linkedPlanId) : null,
    tzId: form.tzId || Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
  };
}
