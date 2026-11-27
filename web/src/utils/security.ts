/**
 * 安全边界工具（REQ-041 W6 & REQ-037）：
 * - 外链仅允许 http:// / https://，严禁凭据、控制字符、反斜杠与 javascript:/data:/file:
 * - 纯文本控制字符清洗（严禁使用 innerHTML / dangerouslySetInnerHTML）
 * - SHA-256 信任锚指纹、64 位十六进制摘要、安全插件产物名与 Int32 边界校验
 */

const MAX_URL_LENGTH = 2048;
export const MAX_INT32 = 2_147_483_647;
const SHA256_COLON_PATTERN = /^[0-9A-F]{2}(?::[0-9A-F]{2}){31}$/i;
const SHA256_HEX_PATTERN = /^[0-9A-F]{64}$/i;
const SAFE_ARTIFACT_NAME_PATTERN = /^[A-Za-z0-9._-]+$/;
const FORM_B_DOWNLOAD_REL_PATTERN = /^(?:\.\/)?artifacts\/([A-Za-z0-9._-]+)$/;
const SAME_ORIGIN_DOWNLOAD_PATTERN = /^\/api\/v1\/plugins\/artifacts\/(official|third-party)\/([A-Za-z0-9._-]+)$/;

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

export function isValidSha256Hex(raw: unknown): boolean {
  if (typeof raw !== 'string') return false;
  return SHA256_HEX_PATTERN.test(raw.trim());
}

export function isSafePositiveInt32(raw: unknown): raw is number {
  return typeof raw === 'number' && Number.isInteger(raw) && raw >= 1 && raw <= MAX_INT32;
}

export function isSafeArtifactFilename(name: unknown): boolean {
  if (typeof name !== 'string') return false;
  if (!name || name === '.' || name === '..' || name.length > 180) return false;
  if (!SAFE_ARTIFACT_NAME_PATTERN.test(name)) return false;
  return name.endsWith('.plp') || name.endsWith('.pljs');
}

/**
 * 校验形态 B 插件下载路径：仅允许规范化相对路径 `./artifacts/<安全文件名>` 或 `artifacts/<安全文件名>`。
 */
export function safeFormBDownloadUrl(raw: unknown): string | null {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  if (!trimmed || trimmed.length > MAX_URL_LENGTH) return null;
  if (hasControlOrWhitespace(trimmed) || trimmed.includes('\\') || trimmed.includes('..')) {
    return null;
  }
  const match = FORM_B_DOWNLOAD_REL_PATTERN.exec(trimmed);
  if (!match) return null;
  const filename = match[1];
  if (!isSafeArtifactFilename(filename)) return null;
  return `./artifacts/${filename}`;
}

/**
 * 校验本实例同源插件下载路径：仅允许 `/api/v1/plugins/artifacts/(official|third-party)/<安全文件名>`。
 */
export function safeSameOriginDownloadUrl(raw: unknown, expectedSource?: string): string | null {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  if (!trimmed || trimmed.length > MAX_URL_LENGTH) return null;
  if (hasControlOrWhitespace(trimmed) || trimmed.includes('\\') || trimmed.includes('..')) {
    return null;
  }
  const match = SAME_ORIGIN_DOWNLOAD_PATTERN.exec(trimmed);
  if (!match) return null;
  const source = match[1];
  const filename = match[2];
  if (expectedSource && source !== expectedSource) return null;
  if (!isSafeArtifactFilename(filename)) return null;
  return `/api/v1/plugins/artifacts/${source}/${filename}`;
}

/**
 * 安全生成诊断文本，避免对无原型对象（Object.create(null)）或异常 toString 对象调用 String() 抛 TypeError。
 */
export function safeDiagnosticValue(value: unknown, maxLength = 40): string {
  if (typeof value === 'string') {
    const clean = stripDisallowedControlChars(value, false);
    return clean.length > maxLength ? `${clean.slice(0, maxLength)}…` : clean;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  if (value === null) return 'null';
  if (value === undefined) return 'undefined';
  if (Array.isArray(value)) return `[Array(${value.length})]`;
  return `[${typeof value}]`;
}

export function utf8ByteLength(text: string): number {
  if (typeof TextEncoder !== 'undefined') {
    return new TextEncoder().encode(text).length;
  }
  return text.length * 2;
}
