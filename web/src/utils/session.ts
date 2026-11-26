/**
 * 会话与设备标识管理（对齐 ADR-022 D2 & REQ-041 W2）：
 * - JWT 仅存放在单标签作用域的 sessionStorage，绝不写入 localStorage 或 cookie
 * - 稳定的实例作用域 deviceUid（web-<UUID>）保存在 localStorage 供同一浏览器 profile 复用
 * - 主题偏好（light / dark）保存在 localStorage
 */

export const TOKEN_SESSION_KEY = 'oriva.web.session.token';
export const DEVICE_UID_KEY = 'oriva.web.deviceUid';
export const THEME_KEY = 'oriva.web.theme';

const WEB_UID_PATTERN = /^web-[A-Za-z0-9-]{8,64}$/;

function generateUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i += 1) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function getOrCreateDeviceUid(): string {
  try {
    const existing = window.localStorage.getItem(DEVICE_UID_KEY);
    if (existing && WEB_UID_PATTERN.test(existing)) {
      return existing;
    }
    const created = `web-${generateUuid()}`;
    window.localStorage.setItem(DEVICE_UID_KEY, created);
    return created;
  } catch {
    return `web-${generateUuid()}`;
  }
}

export function buildWebDeviceName(): string {
  if (typeof navigator === 'undefined') return '知序 Web 数字站';
  const ua = navigator.userAgent || '';
  if (ua.includes('Edg/')) return '知序 Web（Edge）';
  if (ua.includes('Firefox/')) return '知序 Web（Firefox）';
  if (ua.includes('Chrome/')) return '知序 Web（Chrome）';
  if (ua.includes('Safari/')) return '知序 Web（Safari）';
  return '知序 Web 数字站';
}

export function readSessionToken(): string | null {
  try {
    const token = window.sessionStorage.getItem(TOKEN_SESSION_KEY);
    return token && token.trim().length > 0 ? token.trim() : null;
  } catch {
    return null;
  }
}

export function writeSessionToken(token: string): void {
  try {
    window.sessionStorage.setItem(TOKEN_SESSION_KEY, token);
  } catch {
    // 隐私模式禁用 sessionStorage 时退化为内存会话（由调用方持有）
  }
}

export function clearSessionToken(): void {
  try {
    window.sessionStorage.removeItem(TOKEN_SESSION_KEY);
  } catch {
    // ignore
  }
}

export type InkThemeMode = 'light' | 'dark';

export function readInitialTheme(): InkThemeMode {
  try {
    const saved = window.localStorage.getItem(THEME_KEY);
    if (saved === 'light' || saved === 'dark') return saved;
  } catch {
    // ignore
  }
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  return 'light';
}

export function saveTheme(theme: InkThemeMode): void {
  try {
    window.localStorage.setItem(THEME_KEY, theme);
  } catch {
    // ignore
  }
}

export function applyThemeToDocument(theme: InkThemeMode): void {
  if (typeof document !== 'undefined') {
    document.documentElement.setAttribute('data-theme', theme);
  }
}
