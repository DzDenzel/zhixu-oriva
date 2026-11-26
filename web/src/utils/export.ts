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
    if (i === 0 && (ch === '=' || ch === '+' || ch === '-' || ch === '@')) {
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
  return stripDisallowedControlChars(value, true)
    .replace(/\\/g, '\\\\')
    .replace(/;/g, '\\;')
    .replace(/,/g, '\\,')
    .replace(/\r?\n/g, '\\n');
}

function formatIcsDate(date: string): string {
  return date.replace(/-/g, '');
}

function formatIcsDateTime(date: string, time: string): string {
  const cleanTime = time.replace(/:/g, '').padEnd(6, '0').slice(0, 6);
  return `${formatIcsDate(date)}T${cleanTime}`;
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
      lines.push(`DTEND;VALUE=DATE:${formatIcsDate(s.endDate)}`);
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
  return `${lines.join('\r\n')}\r\n`;
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
