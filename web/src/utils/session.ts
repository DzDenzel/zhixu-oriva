/**
 * 会话与设备标识管理（对齐 ADR-022 D2 & REQ-041 W2 & Astra B3 / R1）：
 * - JWT 仅存放在单标签作用域的 sessionStorage，绝不写入 localStorage 或 cookie；
 * - 写入新令牌时，只有在 sessionStorage.setItem 明确未抛异常成功落盘后才解除内存失效栅栏；
 * - 清除令牌时采用三层防御：
 *   1) 内存失效栅栏（sessionInvalidated / rejectedStaleToken）；
 *   2) 尝试 removeItem，失败则回退覆写哨兵值 "__ORIVA_REVOKED__"；
 *   3) 若 sessionStorage 双操作均抛异常导致旧值仍残留，则在 localStorage 记录该旧令牌的不可逆
 *      非秘密摘要（绝不存原始 JWT），使页面刷新或新建 ApiClient 实例同样拒收该残留旧凭据。
 */

export const TOKEN_SESSION_KEY = 'oriva.web.session.token';
export const REVOKED_DIGEST_KEY = 'oriva.web.session.revokedDigest';
export const REVOKED_TOKEN_SENTINEL = '__ORIVA_REVOKED__';
export const DEVICE_UID_KEY = 'oriva.web.deviceUid';
export const THEME_KEY = 'oriva.web.theme';

const WEB_UID_PATTERN = /^web-[A-Za-z0-9-]{8,64}$/;

let sessionInvalidated = false;
let rejectedStaleToken: string | null = null;

/** 非秘密 64-bit FNV-1a 摘要（仅用于在极端存储删除失败时跨刷新拒收残留旧凭据，绝不还原 JWT） */
function digestTokenNonSecret(token: string): string {
  let h1 = 0x811c9dc5;
  let h2 = 0x9e3779b9;
  for (let i = 0; i < token.length; i += 1) {
    const ch = token.charCodeAt(i);
    h1 ^= ch;
    h1 = Math.imul(h1, 0x01000193);
    h2 ^= ch + (i & 0xff);
    h2 = Math.imul(h2, 0x85ebca6b);
  }
  return `${(h1 >>> 0).toString(16).padStart(8, '0')}${(h2 >>> 0).toString(16).padStart(8, '0')}`;
}

function recordPersistedRevokedDigest(token: string): void {
  try {
    window.localStorage.setItem(REVOKED_DIGEST_KEY, digestTokenNonSecret(token));
  } catch {
    // ignore localStorage failure
  }
}

function clearPersistedRevokedDigest(): void {
  try {
    window.localStorage.removeItem(REVOKED_DIGEST_KEY);
  } catch {
    // ignore
  }
}

function matchesPersistedRevokedDigest(token: string): boolean {
  try {
    const saved = window.localStorage.getItem(REVOKED_DIGEST_KEY);
    return Boolean(saved && saved === digestTokenNonSecret(token));
  } catch {
    return false;
  }
}

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
    if (!token || token === REVOKED_TOKEN_SENTINEL) return null;
    if (rejectedStaleToken !== null && token === rejectedStaleToken) {
      return null;
    }
    if (matchesPersistedRevokedDigest(token)) {
      return null;
    }
    return token;
  } catch {
    return null;
  }
}

/**
 * 仅在 sessionStorage.setItem 明确成功落盘且回读确认与新令牌一致时，才解除内存失效栅栏（Astra R1）。
 * 若写入失败，保持原有失效拒绝状态不变，绝不解除对残留旧凭据的阻断。
 */
export function writeSessionToken(token: string): boolean {
  const normalized = token.trim();
  if (!normalized || normalized === REVOKED_TOKEN_SENTINEL) return false;
  try {
    window.sessionStorage.setItem(TOKEN_SESSION_KEY, normalized);
    const confirmed = window.sessionStorage.getItem(TOKEN_SESSION_KEY);
    if (confirmed !== normalized) {
      return false;
    }
    sessionInvalidated = false;
    rejectedStaleToken = null;
    clearPersistedRevokedDigest();
    return true;
  } catch {
    return false;
  }
}

export interface ClearSessionResult {
  storageCleared: boolean;
}

/**
 * 原子化清除会话：
 * 1) 立即激活内存失效栅栏并记录旧令牌；
 * 2) 尝试 removeItem；若失败或旧值仍在，则尝试覆写为哨兵值 REVOKED_TOKEN_SENTINEL；
 * 3) 若双写入均失败导致旧值仍残留于 sessionStorage，则记录不可逆撤销摘要供新实例/刷新后拒收。
 */
export function clearSessionToken(): ClearSessionResult {
  let stale: string | null = null;
  try {
    const existing = window.sessionStorage.getItem(TOKEN_SESSION_KEY);
    if (existing && existing.trim().length > 0 && existing.trim() !== REVOKED_TOKEN_SENTINEL) {
      stale = existing.trim();
      rejectedStaleToken = stale;
    }
  } catch {
    // ignore read failure
  }
  sessionInvalidated = true;

  let cleared = false;
  try {
    window.sessionStorage.removeItem(TOKEN_SESSION_KEY);
    const remaining = window.sessionStorage.getItem(TOKEN_SESSION_KEY);
    cleared = !remaining || remaining === REVOKED_TOKEN_SENTINEL;
  } catch {
    cleared = false;
  }

  if (!cleared) {
    try {
      window.sessionStorage.setItem(TOKEN_SESSION_KEY, REVOKED_TOKEN_SENTINEL);
      const remaining = window.sessionStorage.getItem(TOKEN_SESSION_KEY);
      cleared = !remaining || remaining === REVOKED_TOKEN_SENTINEL;
    } catch {
      cleared = false;
    }
  }

  if (!cleared && stale) {
    recordPersistedRevokedDigest(stale);
  } else if (cleared) {
    clearPersistedRevokedDigest();
  }

  return { storageCleared: cleared };
}

/** 仅供单元测试在用例间重置模块级内存栅栏；preservePersistedDigest=true 用于模拟页面刷新 */
export function resetSessionGuardForTest(preservePersistedDigest = false): void {
  sessionInvalidated = false;
  rejectedStaleToken = null;
  if (!preservePersistedDigest) {
    clearPersistedRevokedDigest();
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
