/**
 * 会话与设备标识管理（对齐 ADR-022 D2 & REQ-041 W2 & Astra B3 / R1 v1.2）：
 * - JWT 仅存放在单标签作用域的 sessionStorage，绝不写入 localStorage 或 cookie；
 * - 写入新令牌时，只有在 sessionStorage.setItem 明确未抛异常成功落盘且回读确认一致后才解除内存失效栅栏；
 * - 清除令牌时采用三层防御：
 *   1) 内存失效栅栏（sessionInvalidated / rejectedStaleToken）；
 *   2) 尝试 removeItem，失败则回退覆写哨兵值 "__ORIVA_REVOKED__"；
 *   3) 若 sessionStorage 双操作均抛异常导致旧值仍残留，则在 localStorage 按独立摘要键
 *      （oriva.web.session.revoked.<digest>）与只追加有界集合（oriva.web.session.revokedSet，上限 64 条）
 *      持久化该旧令牌的 64-bit FNV-1a 不可逆非秘密摘要（绝不存原始 JWT）；
 *      任意其他同源标签页的登录或清理动作绝不删除或覆盖其他标签尚存在的撤销记录；
 * - 冷启动读取持久化撤销清单若发生存储异常或格式损坏而无法确认撤销状态，严格按 fail-closed 拒收疑似残留旧令牌。
 */

export const TOKEN_SESSION_KEY = 'oriva.web.session.token';
export const REVOKED_DIGEST_PREFIX = 'oriva.web.session.revoked.';
export const REVOKED_DIGEST_SET_KEY = 'oriva.web.session.revokedSet';
export const REVOKED_TOKEN_SENTINEL = '__ORIVA_REVOKED__';
export const DEVICE_UID_KEY = 'oriva.web.deviceUid';
export const THEME_KEY = 'oriva.web.theme';

const MAX_PERSISTED_REVOKED_DIGESTS = 64;
const WEB_UID_PATTERN = /^web-[A-Za-z0-9-]{8,64}$/;
const DIGEST_HEX_PATTERN = /^[0-9a-f]{16}$/;

let sessionInvalidated = false;
let rejectedStaleToken: string | null = null;

/** 非秘密 64-bit FNV-1a 摘要（仅用于在极端存储删除失败时跨刷新拒收残留旧凭据，绝不还原 JWT） */
export function digestTokenNonSecret(token: string): string {
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

/**
 * 仅追加（不覆盖其他标签记录）地将残留旧令牌的非秘密摘要写入独立键与有界索引集合。
 */
function recordPersistedRevokedDigest(token: string): boolean {
  const digest = digestTokenNonSecret(token);
  let keyWritten = false;
  try {
    window.localStorage.setItem(`${REVOKED_DIGEST_PREFIX}${digest}`, '1');
    keyWritten = window.localStorage.getItem(`${REVOKED_DIGEST_PREFIX}${digest}`) !== null;
  } catch {
    keyWritten = false;
  }

  let setWritten = false;
  try {
    const existingRaw = window.localStorage.getItem(REVOKED_DIGEST_SET_KEY);
    let list: string[] = [];
    if (existingRaw !== null) {
      const parsed: unknown = JSON.parse(existingRaw);
      if (Array.isArray(parsed)) {
        list = parsed.filter((item): item is string => typeof item === 'string' && DIGEST_HEX_PATTERN.test(item));
      }
    }
    if (!list.includes(digest)) {
      list.push(digest);
    }
    while (list.length > MAX_PERSISTED_REVOKED_DIGESTS) {
      const evicted = list.shift();
      if (evicted && evicted !== digest) {
        try {
          window.localStorage.removeItem(`${REVOKED_DIGEST_PREFIX}${evicted}`);
        } catch {
          // ignore eviction failure
        }
      }
    }
    window.localStorage.setItem(REVOKED_DIGEST_SET_KEY, JSON.stringify(list));
    setWritten = true;
  } catch {
    setWritten = false;
  }

  return keyWritten || setWritten;
}

/**
 * 校验令牌摘要是否已被持久化撤销：
 * - 命中独立摘要键或撤销集合中该摘要 => 拒收（true）；
 * - 读取 localStorage 抛异常或撤销集合被破坏无法解析 => fail-closed 拒收（true），绝不放行疑似残留旧凭据。
 */
function isDigestRevokedOrUnverifiable(token: string): boolean {
  const digest = digestTokenNonSecret(token);
  try {
    const perDigestMarker = window.localStorage.getItem(`${REVOKED_DIGEST_PREFIX}${digest}`);
    if (perDigestMarker !== null) {
      return true;
    }
    const rawSet = window.localStorage.getItem(REVOKED_DIGEST_SET_KEY);
    if (rawSet !== null) {
      const parsed: unknown = JSON.parse(rawSet);
      if (!Array.isArray(parsed)) {
        return true;
      }
      for (const entry of parsed) {
        if (typeof entry !== 'string' || !DIGEST_HEX_PATTERN.test(entry)) {
          return true;
        }
        if (entry === digest) {
          return true;
        }
      }
    }
    return false;
  } catch {
    // 无法确认持久化撤销状态（存储权限受限或数据损坏）=> 严格 fail-closed
    return true;
  }
}

function clearAllPersistedRevokedDigestsForTest(): void {
  try {
    const toRemove: string[] = [];
    for (let i = 0; i < window.localStorage.length; i += 1) {
      const key = window.localStorage.key(i);
      if (key && (key === REVOKED_DIGEST_SET_KEY || key.startsWith(REVOKED_DIGEST_PREFIX))) {
        toRemove.push(key);
      }
    }
    for (const key of toRemove) {
      window.localStorage.removeItem(key);
    }
  } catch {
    // ignore
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
    if (isDigestRevokedOrUnverifiable(token)) {
      return null;
    }
    return token;
  } catch {
    return null;
  }
}

/**
 * 仅在 sessionStorage.setItem 明确成功落盘且回读确认与新令牌一致时，才解除本标签页内存失效栅栏（Astra R1）。
 * 绝不删除或修改 localStorage 中其他同源标签页留存的持久化撤销摘要。
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
    return true;
  } catch {
    return false;
  }
}

export interface ClearSessionResult {
  storageCleared: boolean;
  revocationPersisted: boolean;
}

/**
 * 原子化清除会话：
 * 1) 立即激活内存失效栅栏并记录旧令牌；
 * 2) 尝试 removeItem；若失败或旧值仍在，则尝试覆写为哨兵值 REVOKED_TOKEN_SENTINEL；
 * 3) 若双写入均失败导致旧值仍残留于 sessionStorage，则向独立摘要键与只追加有界撤销集合写入该旧令牌的不可逆摘要；
 *    无论本标签清除成功与否，绝不删除其他同源标签页的撤销记录。
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

  let revocationPersisted = false;
  if (!cleared && stale) {
    revocationPersisted = recordPersistedRevokedDigest(stale);
  }

  return { storageCleared: cleared, revocationPersisted };
}

/** 仅供单元测试在用例间重置模块级内存栅栏；preservePersistedDigest=true 用于模拟同源页面刷新 */
export function resetSessionGuardForTest(preservePersistedDigest = false): void {
  sessionInvalidated = false;
  rejectedStaleToken = null;
  if (!preservePersistedDigest) {
    clearAllPersistedRevokedDigestsForTest();
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
