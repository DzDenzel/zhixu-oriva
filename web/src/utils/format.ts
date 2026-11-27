import type { PlanRepeatRule, SyncPlanTypeView } from '../types';

export interface PlanTypeOption {
  key: string;
  label: string;
  hueClass: string;
  arcClass: string;
}

export const PRESET_PLAN_TYPES: PlanTypeOption[] = [
  { key: '学习', label: '学习', hueClass: 'hue-study', arcClass: 'arc-study' },
  { key: '工作', label: '工作', hueClass: 'hue-work', arcClass: 'arc-work' },
  { key: '运动', label: '运动', hueClass: 'hue-sport', arcClass: 'arc-sport' },
  { key: '阅读', label: '阅读', hueClass: 'hue-amber', arcClass: 'arc-amber' },
  { key: '休息', label: '休息', hueClass: 'hue-sleep', arcClass: 'arc-sleep' },
  { key: '生活', label: '生活', hueClass: 'hue-rose', arcClass: 'arc-rose' },
  { key: '兴趣', label: '兴趣', hueClass: 'hue-ochre', arcClass: 'arc-ochre' },
  { key: '其他', label: '其他', hueClass: 'hue-slate', arcClass: 'arc-slate' },
];

const COLOR_KEY_TO_CLASS: Record<string, { hue: string; arc: string }> = {
  study: { hue: 'hue-study', arc: 'arc-study' },
  work: { hue: 'hue-work', arc: 'arc-work' },
  sport: { hue: 'hue-sport', arc: 'arc-sport' },
  amber: { hue: 'hue-amber', arc: 'arc-amber' },
  sleep: { hue: 'hue-sleep', arc: 'arc-sleep' },
  rose: { hue: 'hue-rose', arc: 'arc-rose' },
  ochre: { hue: 'hue-ochre', arc: 'arc-ochre' },
  slate: { hue: 'hue-slate', arc: 'arc-slate' },
};

export function mergePlanTypes(customTypes?: SyncPlanTypeView[]): PlanTypeOption[] {
  const result = [...PRESET_PLAN_TYPES];
  if (!customTypes) return result;
  for (const item of customTypes) {
    if (item.deleted || !item.typeKey || !item.name) continue;
    const mapped = COLOR_KEY_TO_CLASS[item.color] ?? { hue: 'hue-slate', arc: 'arc-slate' };
    result.push({
      key: item.typeKey,
      label: item.name,
      hueClass: mapped.hue,
      arcClass: mapped.arc,
    });
  }
  return result;
}

export function resolvePlanTypeMeta(typeKey: string, allTypes: PlanTypeOption[] = PRESET_PLAN_TYPES): PlanTypeOption {
  const found = allTypes.find((item) => item.key === typeKey || item.label === typeKey);
  if (found) return found;
  if (typeKey === '睡眠') return PRESET_PLAN_TYPES[4];
  return PRESET_PLAN_TYPES[7];
}

export function formatTimeShort(value?: string | null): string {
  if (!value) return '--:--';
  return value.length >= 5 ? value.slice(0, 5) : value;
}

export function toBackendTime(value: string): string {
  const trimmed = value.trim();
  if (/^\d{2}:\d{2}$/.test(trimmed)) return `${trimmed}:00`;
  return trimmed;
}

export function todayIsoDate(now = new Date()): string {
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

export function formatMinutes(minutes: number): string {
  const safe = Math.max(0, Math.round(minutes));
  const h = Math.floor(safe / 60);
  const m = safe % 60;
  if (h === 0) return `${m} 分钟`;
  if (m === 0) return `${h} 小时`;
  return `${h} 小时 ${m} 分`;
}

export function formatRepeatRule(rule?: PlanRepeatRule | null): string {
  if (!rule || !rule.type) return '单次';
  switch (rule.type) {
    case 'daily':
      return '每天';
    case 'once':
      return '单次';
    case 'weekly': {
      const names = ['', '一', '二', '三', '四', '五', '六', '日'];
      const days = (rule.weekdays ?? [])
        .filter((d) => d >= 1 && d <= 7)
        .sort((a, b) => a - b)
        .map((d) => `周${names[d]}`);
      return days.length ? `每周 ${days.join('、')}` : '每周';
    }
    case 'custom':
      return `自定义（${rule.dates?.length ?? 0} 天）`;
    default:
      return rule.type;
  }
}

export function formatScheduleRepeat(raw?: string | null): string {
  if (!raw) return '单次';
  try {
    const parsed = JSON.parse(raw) as {
      type?: string;
      weekdays?: number[];
      days?: number[];
      day?: number;
      month?: number;
    };
    switch (parsed.type) {
      case 'daily':
        return '每天';
      case 'weekly':
        return formatRepeatRule({ type: 'weekly', weekdays: parsed.weekdays ?? parsed.days });
      case 'monthly':
        return `每月 ${parsed.day ?? 1} 日`;
      case 'yearly':
        return `每年 ${parsed.month ?? 1} 月 ${parsed.day ?? 1} 日`;
      default:
        return '重复';
    }
  } catch {
    return '自定义重复';
  }
}

export function getGreeting(now = new Date()): string {
  const hour = now.getHours();
  if (hour < 6) return '夜深了';
  if (hour < 11) return '早安';
  if (hour < 14) return '午安';
  if (hour < 18) return '下午好';
  return '晚上好';
}

const SHICHEN_NAMES = ['子', '丑', '寅', '卯', '辰', '巳', '午', '未', '申', '酉', '戌', '亥'];
const KE_NAMES = ['初刻', '一刻', '二刻', '三刻'];

export function getShichenLabel(now = new Date()): string {
  const hour = now.getHours();
  const minute = now.getMinutes();
  const idx = Math.floor(((hour + 1) % 24) / 2);
  const isZheng = hour % 2 === 0;
  const ke = KE_NAMES[Math.min(3, Math.floor(minute / 15))];
  return `${SHICHEN_NAMES[idx]}${isZheng ? '正' : '初'}${ke}`;
}

export function timeToMinutes(hhmm: string): number {
  const parts = hhmm.split(':');
  const h = Number(parts[0] ?? 0);
  const m = Number(parts[1] ?? 0);
  if (!Number.isFinite(h) || !Number.isFinite(m)) return 0;
  return h * 60 + m;
}

export function formatPercent(completed: number, total: number): number {
  if (total <= 0) return 0;
  return Math.round((completed / total) * 100);
}
