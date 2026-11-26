/**
 * 会话与设备标识管理（对齐 ADR-022 D2 & REQ-041 W2 & Astra B3）：
 * - JWT 仅存放在单标签作用域的 sessionStorage，绝不写入 localStorage 或 cookie；
 * - 引入会话清除栅栏（sessionInvalidated / rejectedStaleToken）：即使存储层 removeItem 抛异常，
 *   运行态也绝不回读残留在 sessionStorage 的旧 JWT；
 * - 稳定的实例作用域 deviceUid（web-<UUID>）保存在 localStorage 供同一浏览器 profile 复用；
 * - 主题偏好（light / dark）保存在 localStorage。
 */

export const TOKEN_SESSION_KEY = 'oriva.web.session.token';
export const DEVICE_UID_KEY = 'oriva.web.deviceUid';
export const THEME_KEY = 'oriva.web.theme';

const WEB_UID_PATTERN = /^web-[A-Za-z0-9-]{8,64}$/;

let sessionInvalidated = false;
let rejectedStaleToken: string | null = null;

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
  if (sessionInvalidated) {
    return null;
  }
  try {
    const raw = window.sessionStorage.getItem(TOKEN_SESSION_KEY);
    const token = raw && raw.trim().length > 0 ? raw.trim() : null;
    if (!token) return null;
    if (rejectedStaleToken !== null && token === rejectedStaleToken) {
      return null;
    }
    return token;
  } catch {
    return null;
  }
}

export function writeSessionToken(token: string): boolean {
  const normalized = token.trim();
  if (!normalized) return false;
  sessionInvalidated = false;
  rejectedStaleToken = null;
  try {
    window.sessionStorage.setItem(TOKEN_SESSION_KEY, normalized);
    return true;
  } catch {
    // 隐私模式禁用 sessionStorage 时退化为内存会话（由调用方持有）
    return false;
  }
}

export interface ClearSessionResult {
  storageCleared: boolean;
}

/**
 * 原子化清除会话：无论 sessionStorage.removeItem 是否抛出异常，
 * 内存栅栏均立即将当前标签页标记为已清除，杜绝再次回读残留旧 JWT。
 */
export function clearSessionToken(): ClearSessionResult {
  try {
    const existing = window.sessionStorage.getItem(TOKEN_SESSION_KEY);
    if (existing && existing.trim().length > 0) {
      rejectedStaleToken = existing.trim();
    }
  } catch {
    // ignore read failure
  }
  sessionInvalidated = true;
  try {
    window.sessionStorage.removeItem(TOKEN_SESSION_KEY);
    const remaining = window.sessionStorage.getItem(TOKEN_SESSION_KEY);
    return { storageCleared: !remaining };
  } catch {
    return { storageCleared: false };
  }
}

/** 仅供单元测试在用例间重置模块级内存栅栏 */
export function resetSessionGuardForTest(): void {
  sessionInvalidated = false;
  rejectedStaleToken = null;
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
