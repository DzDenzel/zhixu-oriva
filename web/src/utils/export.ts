import type { JournalItem, ScheduleItem } from '../types';
import { stripDisallowedControlChars } from './security';

/**
 * 转义 Markdown 行内特殊字符及表格/公式注入前缀（对齐后端 JournalRules.escapeMarkdownInline）
 */
export function escapeMarkdownInline(value: string): string {
  const sanitized = stripDisallowedControlChars(value, false).trim();
  if (!sanitized) return '';
  let out = '';
  for (let i = 0; i < sanitized.length; i += 1) {
    const ch = sanitized.charAt(i);
    if (
      i === 0 &&
      (ch === '=' ||
        ch === '+' ||
        ch === '-' ||
        ch === '@' ||
        ch === '＝' ||
        ch === '＋' ||
        ch === '－' ||
        ch === '＠')
    ) {
      out += '\\';
    } else if ('\\`*_#>|[]<()!'.includes(ch)) {
      out += '\\';
    }
    out += ch;
  }
  return out;
}

/**
 * 动态长度反引号代码围栏隔离多行正文，防止 Setext/ATX 标题、围栏闭合或内联 HTML 注入。
 */
export function formatFencedBody(raw: string): string {
  const normalized = stripDisallowedControlChars(
    raw.replace(/\r\n/g, '\n').replace(/\r/g, '\n'),
    true,
  );
  let maxRun = 0;
  let currentRun = 0;
  for (let i = 0; i < normalized.length; i += 1) {
    if (normalized.charAt(i) === '`') {
      currentRun += 1;
      if (currentRun > maxRun) maxRun = currentRun;
    } else {
      currentRun = 0;
    }
  }
  const fence = '`'.repeat(Math.max(3, maxRun + 1));
  const trailingNewline = normalized.length > 0 && !normalized.endsWith('\n') ? '\n' : '';
  return `${fence}text\n${normalized}${trailingNewline}${fence}`;
}

export function buildSafeJournalMarkdown(entries: JournalItem[], exportedAt = new Date()): string {
  const sorted = [...entries].sort((a, b) => {
    const byDate = b.entryDate.localeCompare(a.entryDate);
    return byDate !== 0 ? byDate : b.id - a.id;
  });

  const lines: string[] = [
    '# 知序 · 日常记录导出',
    '',
    `- 导出时间: ${exportedAt.toISOString().replace('T', ' ').slice(0, 19)}`,
    `- 记录数: ${sorted.length}`,
    '',
  ];

  for (const entry of sorted) {
    const dateLabel = stripDisallowedControlChars(entry.entryDate || '0000-00-00', false);
    const safeMood = entry.mood ? escapeMarkdownInline(entry.mood) : '';
    lines.push(safeMood ? `## ${dateLabel} · ${safeMood}` : `## ${dateLabel}`);
    lines.push('');
    if (safeMood) {
      lines.push(`- 心情: ${safeMood}`);
    }
    const safeTags = (entry.tags ?? [])
      .map((tag) => escapeMarkdownInline(tag))
      .filter((tag) => tag.length > 0);
    if (safeTags.length > 0) {
      lines.push(`- 标签: ${safeTags.join(', ')}`);
    }
    if (safeMood || safeTags.length > 0) {
      lines.push('');
    }
    lines.push(formatFencedBody(entry.content || ''));
    lines.push('');
  }
  return lines.join('\n');
}

function escapeIcsText(value: string): string {
  const normalizedLineBreaks = value.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  return stripDisallowedControlChars(normalizedLineBreaks, true)
    .replace(/\\/g, '\\\\')
    .replace(/;/g, '\\;')
    .replace(/,/g, '\\,')
    .replace(/\n/g, '\\n');
}

function formatIcsDate(date: string): string {
  return date.replace(/-/g, '');
}

/** RFC 5545 all-day DATE DTEND is exclusive (next calendar day). */
function formatIcsExclusiveEndDate(date: string): string {
  const parts = date.split('-').map((part) => Number(part));
  if (parts.length !== 3 || parts.some((n) => !Number.isInteger(n))) {
    return formatIcsDate(date);
  }
  const nextDay = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2] + 1));
  const y = String(nextDay.getUTCFullYear()).padStart(4, '0');
  const m = String(nextDay.getUTCMonth() + 1).padStart(2, '0');
  const d = String(nextDay.getUTCDate()).padStart(2, '0');
  return `${y}${m}${d}`;
}

function formatIcsDateTime(date: string, time: string): string {
  const cleanTime = time.replace(/:/g, '').padEnd(6, '0').slice(0, 6);
  return `${formatIcsDate(date)}T${cleanTime}`;
}

const UTF8_ENCODER = new TextEncoder();

/** Fold an ICS content line at <= 75 UTF-8 octets without splitting multibyte characters. */
function foldIcsLine(line: string): string {
  const out: string[] = [];
  let current = '';
  let bytes = 0;
  let maxBytes = 75;

  for (const ch of line) {
    const chBytes = UTF8_ENCODER.encode(ch).length;
    if (bytes + chBytes > maxBytes && current.length > 0) {
      out.push(current);
      current = ` ${ch}`;
      bytes = 1 + chBytes;
      maxBytes = 75;
    } else {
      current += ch;
      bytes += chBytes;
    }
  }
  if (current.length > 0 || out.length === 0) {
    out.push(current);
  }
  return out.join('\r\n');
}

export function buildSchedulesIcs(schedules: ScheduleItem[]): string {
  const nowStamp = new Date().toISOString().replace(/[-:]/g, '').slice(0, 15) + 'Z';
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//ORIVA PlanLock Web//V1.0//CN',
  ];

  for (const s of schedules) {
    lines.push('BEGIN:VEVENT');
    lines.push(`UID:schedule-${s.id}@planlock`);
    lines.push(`DTSTAMP:${nowStamp}`);
    if (s.allDay) {
      lines.push(`DTSTART;VALUE=DATE:${formatIcsDate(s.startDate)}`);
      lines.push(`DTEND;VALUE=DATE:${formatIcsExclusiveEndDate(s.endDate || s.startDate)}`);
    } else {
      lines.push(`DTSTART:${formatIcsDateTime(s.startDate, s.startTime || '09:00:00')}`);
      lines.push(`DTEND:${formatIcsDateTime(s.endDate, s.endTime || '10:00:00')}`);
    }
    lines.push(`SUMMARY:${escapeIcsText(s.title)}`);
    if (s.location) {
      lines.push(`LOCATION:${escapeIcsText(s.location)}`);
    }
    if (s.note) {
      lines.push(`DESCRIPTION:${escapeIcsText(s.note)}`);
    }
    lines.push('END:VEVENT');
  }
  lines.push('END:VCALENDAR');
  return `${lines.map(foldIcsLine).join('\r\n')}\r\n`;
}

export function triggerTextDownload(filename: string, content: string, mimeType: string): void {
  const blob = new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.rel = 'noopener';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
