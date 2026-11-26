/**
 * 安全边界工具（REQ-041 W6 & REQ-037）：
 * - 外链仅允许 http:// / https://，严禁凭据、控制字符、反斜杠与 javascript:/data:/file:
 * - 纯文本控制字符清洗（严禁使用 innerHTML / dangerouslySetInnerHTML）
 * - SHA-256 信任锚指纹格式校验
 */

const MAX_URL_LENGTH = 2048;
const SHA256_COLON_PATTERN = /^[0-9A-F]{2}(?::[0-9A-F]{2}){31}$/i;
const SAFE_ARTIFACT_NAME_PATTERN = /^[A-Za-z0-9._-]+$/;

export function hasControlOrWhitespace(value: string): boolean {
  for (let i = 0; i < value.length; i += 1) {
    const code = value.charCodeAt(i);
    if (code <= 0x20 || (code >= 0x7f && code <= 0x9f) || /\s/.test(value.charAt(i))) {
      return true;
    }
  }
  return false;
}

export function stripDisallowedControlChars(value: string, allowNewlineAndTab = false): string {
  let out = '';
  for (let i = 0; i < value.length; i += 1) {
    const code = value.charCodeAt(i);
    if (allowNewlineAndTab && (code === 0x0a || code === 0x09)) {
      out += value.charAt(i);
    } else if (code >= 0x20 && code !== 0x7f && !(code >= 0x80 && code <= 0x9f)) {
      out += value.charAt(i);
    }
  }
  return out;
}

/**
 * 校验外部链接，仅允许不含凭据与控制字符的 http:// 或 https:// URL。
 * 任何 javascript: / data: / file: / vbscript: / //relative / user:pass@host 均返回 null。
 */
export function safeHttpUrl(raw: unknown): string | null {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  if (!trimmed || trimmed.length > MAX_URL_LENGTH) return null;
  if (hasControlOrWhitespace(trimmed) || trimmed.includes('\\')) return null;

  const lower = trimmed.toLowerCase();
  if (!lower.startsWith('http://') && !lower.startsWith('https://')) {
    return null;
  }

  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null;
    if (!parsed.hostname || parsed.username || parsed.password) return null;
    // 防御 authority 中残留 @ 欺骗
    const afterScheme = trimmed.slice(trimmed.indexOf('://') + 3);
    const authority = afterScheme.split(/[/?#]/)[0] ?? '';
    if (authority.includes('@')) return null;
    return parsed.href;
  } catch {
    return null;
  }
}

export function isValidFingerprint(raw: unknown): boolean {
  if (typeof raw !== 'string') return false;
  return SHA256_COLON_PATTERN.test(raw.trim());
}

export function isSafeArtifactFilename(name: unknown): boolean {
  if (typeof name !== 'string') return false;
  if (!name || name === '.' || name === '..') return false;
  if (!SAFE_ARTIFACT_NAME_PATTERN.test(name)) return false;
  return name.endsWith('.plp') || name.endsWith('.pljs');
}
