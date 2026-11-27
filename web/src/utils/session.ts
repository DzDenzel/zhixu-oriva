/**
 * 会话与设备标识管理（对齐 ADR-022 D2 & REQ-041 W2 & Astra B3 / R1 v1.3 终轮收口）：
 * - JWT 原文仅存放在单标签作用域的 sessionStorage，绝不写入 localStorage、window.name 或 cookie；
 * - 彻底废弃跨标签共享读改写数组（revokedSet）与固定容量 FIFO 淘汰，改为：
 *   1) 每令牌独立正向租约键（oriva_web_lease_v1:<digest> = {"exp":<sec>}）；
 *   2) 每令牌独立撤销键（oriva_web_revoked_v1:<digest> = {"exp":<sec>}），GC 仅允许清理已明确过期项
 *      （nowSec > exp + EXPIRY_SKEW_SEC），绝不因数量上限淘汰任何未过期凭据的撤销记录；
 *   3) 同标签页持久化 window.name fail-closed 撤销标记（__ORIVA_REVOKED_V1__:[...]），
 *      即使 sessionStorage 与 localStorage 同时写入失败，同标签刷新仍可拒收旧令牌；
 * - clearSessionToken(fallbackToken?) 即使在 sessionStorage.getItem 抛异常时仍使用调用方内存令牌补齐撤销证据；
 * - 冷启动 readSessionToken() 严格执行正向 fail-closed 校验：仅在 sessionStorage/localStorage/window.name 读取与解析无异常、
 *   JWT 未过期、未被 revoked 键或 window.name 撤销且存在对应有效 lease 租约时才恢复会话，彻底杜绝空证据或孤儿旧会话恢复。
 */

export const TOKEN_SESSION_KEY = 'oriva.web.session.token';
export const REVOKED_KEY_PREFIX = 'oriva_web_revoked_v1:';
export const LEASE_KEY_PREFIX = 'oriva_web_lease_v1:';
export const WINDOW_NAME_REVOKED_PREFIX = '__ORIVA_REVOKED_V1__:';
export const REVOKED_TOKEN_SENTINEL = '__ORIVA_REVOKED__';
export const DEVICE_UID_KEY = 'oriva.web.deviceUid';
export const THEME_KEY = 'oriva.web.theme';

const EXPIRY_SKEW_SEC = 60;
const MAX_EXP_SEC = Number.MAX_SAFE_INTEGER;
const WEB_UID_PATTERN = /^web-[A-Za-z0-9-]{8,64}$/;
const DIGEST_HEX_PATTERN = /^[0-9a-f]{16}$/;

let sessionInvalidated = false;
const rejectedStaleTokens = new Set<string>();

interface TokenMetadata {
  hasJwtExp: boolean;
  expSec: number;
}

interface StoredExpRecord {
  exp: number;
}

interface WindowNameRevokedEntry {
  d: string;
  exp: number;
}

function nowEpochSeconds(): number {
  return Math.floor(Date.now() / 1000);
}

/** 非秘密 64-bit FNV-1a 摘要（仅作本地存储键后缀，绝不存原始 JWT） */
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

function decodeBase64Url(segment: string): string | null {
  try {
    const normalized = segment.replace(/-/g, '+').replace(/_/g, '/');
    const padLen = (4 - (normalized.length % 4)) % 4;
    const padded = normalized + '='.repeat(padLen);
    return atob(padded);
  } catch {
    return null;
  }
}

/**
 * 解析令牌结构与有效期（秒级时间戳）：
 * - 三段式 JWT：校验 payload JSON；若含 exp 字段则必须为正有限数，否则判畸形（返回 null）；
 * - 非三段式测试/不透明凭据：保守赋值为 MAX_EXP_SEC（永不自动 GC 淘汰）。
 */
function parseTokenMetadata(token: string): TokenMetadata | null {
  const parts = token.split('.');
  if (parts.length === 3) {
    if (!parts[0] || !parts[1] || !parts[2]) return null;
    const decoded = decodeBase64Url(parts[1]);
    if (decoded === null) return null;
    try {
      const payload: unknown = JSON.parse(decoded);
      if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
        return null;
      }
      const expRaw = (payload as Record<string, unknown>).exp;
      if (expRaw === undefined) {
        return { hasJwtExp: false, expSec: MAX_EXP_SEC };
      }
      if (typeof expRaw !== 'number' || !Number.isFinite(expRaw) || expRaw <= 0) {
        return null;
      }
      return { hasJwtExp: true, expSec: Math.floor(expRaw) };
    } catch {
      return null;
    }
  }
  return { hasJwtExp: false, expSec: MAX_EXP_SEC };
}

function isExpiredForGc(expSec: number, nowSec: number): boolean {
  return expSec < MAX_EXP_SEC && nowSec > expSec + EXPIRY_SKEW_SEC;
}

function parseExpRecord(raw: string): StoredExpRecord | null {
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    const exp = (parsed as Record<string, unknown>).exp;
    if (typeof exp !== 'number' || !Number.isFinite(exp) || exp <= 0) return null;
    return { exp: Math.floor(exp) };
  } catch {
    return null;
  }
}

/**
 * 仅清理已明确过期的独立 revoked / lease 键；严禁因数量上限做任何 FIFO 驱逐！
 */
export function gcExpiredSessionKeys(nowSec = nowEpochSeconds()): number {
  let removed = 0;
  try {
    const expiredKeys: string[] = [];
    for (let i = 0; i < window.localStorage.length; i += 1) {
      const key = window.localStorage.key(i);
      if (!key) continue;
      if (!key.startsWith(REVOKED_KEY_PREFIX) && !key.startsWith(LEASE_KEY_PREFIX)) {
        continue;
      }
      const raw = window.localStorage.getItem(key);
      if (raw === null) continue;
      const rec = parseExpRecord(raw);
      if (rec && isExpiredForGc(rec.exp, nowSec)) {
        expiredKeys.push(key);
      }
    }
    for (const key of expiredKeys) {
      try {
        window.localStorage.removeItem(key);
        removed += 1;
      } catch {
        // ignore single removal error
      }
    }
  } catch {
    // ignore storage enumeration errors during lazy GC
  }
  return removed;
}

function readWindowNameRevocations(): { ok: boolean; entries: WindowNameRevokedEntry[] } {
  try {
    if (typeof window === 'undefined') return { ok: true, entries: [] };
    const raw = window.name;
    if (!raw || !raw.startsWith(WINDOW_NAME_REVOKED_PREFIX)) {
      return { ok: true, entries: [] };
    }
    const payload: unknown = JSON.parse(raw.slice(WINDOW_NAME_REVOKED_PREFIX.length));
    if (!Array.isArray(payload)) {
      return { ok: false, entries: [] };
    }
    const entries: WindowNameRevokedEntry[] = [];
    for (const item of payload) {
      if (!item || typeof item !== 'object' || Array.isArray(item)) {
        return { ok: false, entries: [] };
      }
      const rec = item as Record<string, unknown>;
      if (
        typeof rec.d !== 'string' ||
        !DIGEST_HEX_PATTERN.test(rec.d) ||
        typeof rec.exp !== 'number' ||
        !Number.isFinite(rec.exp) ||
        rec.exp <= 0
      ) {
        return { ok: false, entries: [] };
      }
      entries.push({ d: rec.d, exp: Math.floor(rec.exp) });
    }
    return { ok: true, entries };
  } catch {
    return { ok: false, entries: [] };
  }
}

function recordWindowNameRevocation(digest: string, expSec: number): boolean {
  try {
    if (typeof window === 'undefined') return false;
    const nowSec = nowEpochSeconds();
    const current = readWindowNameRevocations();
    const activeEntries = current.ok
      ? current.entries.filter((entry) => !isExpiredForGc(entry.exp, nowSec))
      : [];
    const existing = activeEntries.find((entry) => entry.d === digest);
    if (existing) {
      existing.exp = Math.max(existing.exp, expSec);
    } else {
      activeEntries.push({ d: digest, exp: expSec });
    }
    const serialized = `${WINDOW_NAME_REVOKED_PREFIX}${JSON.stringify(activeEntries)}`;
    window.name = serialized;
    return window.name === serialized;
  } catch {
    return false;
  }
}

function isDigestRevokedInWindowName(digest: string): boolean {
  const state = readWindowNameRevocations();
  if (!state.ok) {
    // window.name 撤销数据损坏 => 严格 fail-closed
    return true;
  }
  return state.entries.some((entry) => entry.d === digest);
}

function clearAllSessionPersistenceForTest(): void {
  try {
    const toRemove: string[] = [];
    for (let i = 0; i < window.localStorage.length; i += 1) {
      const key = window.localStorage.key(i);
      if (key && (key.startsWith(REVOKED_KEY_PREFIX) || key.startsWith(LEASE_KEY_PREFIX))) {
        toRemove.push(key);
      }
    }
    for (const key of toRemove) {
      window.localStorage.removeItem(key);
    }
  } catch {
    // ignore
  }
  try {
    if (typeof window !== 'undefined' && window.name.startsWith(WINDOW_NAME_REVOKED_PREFIX)) {
      window.name = '';
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

/**
 * 冷启动严格正向 fail-closed 恢复（Astra R1 v1.3）：
 * 仅在同时满足全部条件时返回 sessionStorage 中的 JWT，任意异常/缺失租约/过期/撤销均拒收返回 null。
 */
export function readSessionToken(): string | null {
  if (sessionInvalidated) {
    return null;
  }
  try {
    const raw = window.sessionStorage.getItem(TOKEN_SESSION_KEY);
    const token = raw && raw.trim().length > 0 ? raw.trim() : null;
    if (!token || token === REVOKED_TOKEN_SENTINEL) return null;
    if (rejectedStaleTokens.has(token)) {
      return null;
    }

    const meta = parseTokenMetadata(token);
    if (!meta) return null;
    const nowSec = nowEpochSeconds();
    if (meta.hasJwtExp && nowSec >= meta.expSec) {
      return null;
    }

    const digest = digestTokenNonSecret(token);

    // 1) 同标签页 window.name 撤销校验（损坏则 fail-closed）
    if (isDigestRevokedInWindowName(digest)) {
      return null;
    }

    // 2) 独立持久化撤销键校验（读取抛异常或非 null 均拒收）
    const revokedMarker = window.localStorage.getItem(`${REVOKED_KEY_PREFIX}${digest}`);
    if (revokedMarker !== null) {
      return null;
    }

    // 3) 必须存在有效的正向会话租约凭证（彻底杜绝孤儿 token 或持久化写失败后的空证据恢复）
    const leaseRaw = window.localStorage.getItem(`${LEASE_KEY_PREFIX}${digest}`);
    if (leaseRaw === null) {
      return null;
    }
    const lease = parseExpRecord(leaseRaw);
    if (!lease) {
      return null;
    }
    if (lease.exp < MAX_EXP_SEC && nowSec >= lease.exp) {
      return null;
    }

    return token;
  } catch {
    return null;
  }
}

/**
 * 持久化新会话（Astra R1 v1.3）：
 * 仅在 JWT 合法未过期、未被撤销，且 sessionStorage 写入与 localStorage 独立正向租约写入均明确成功回读后，
 * 才解除当前标签页内存失效栅栏；绝不修改或删除其他令牌的撤销键与租约键。
 */
export function writeSessionToken(token: string): boolean {
  const normalized = token.trim();
  if (!normalized || normalized === REVOKED_TOKEN_SENTINEL) return false;
  if (rejectedStaleTokens.has(normalized)) return false;

  const meta = parseTokenMetadata(normalized);
  if (!meta) return false;
  const nowSec = nowEpochSeconds();
  if (meta.hasJwtExp && nowSec >= meta.expSec) return false;

  const digest = digestTokenNonSecret(normalized);
  if (isDigestRevokedInWindowName(digest)) return false;

  try {
    if (window.localStorage.getItem(`${REVOKED_KEY_PREFIX}${digest}`) !== null) {
      return false;
    }
    window.sessionStorage.setItem(TOKEN_SESSION_KEY, normalized);
    const confirmedToken = window.sessionStorage.getItem(TOKEN_SESSION_KEY);
    if (confirmedToken !== normalized) {
      return false;
    }
    const leaseKey = `${LEASE_KEY_PREFIX}${digest}`;
    const leaseValue = JSON.stringify({ exp: meta.expSec });
    window.localStorage.setItem(leaseKey, leaseValue);
    if (window.localStorage.getItem(leaseKey) !== leaseValue) {
      return false;
    }

    gcExpiredSessionKeys(nowSec);
    sessionInvalidated = false;
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
 * 原子化清除会话（Astra R1 v1.3）：
 * 1) 即使 sessionStorage.getItem 抛异常，亦通过 fallbackToken（调用方内存 JWT）补齐撤销目标；
 * 2) 立即激活内存失效栅栏并记录旧令牌集合；
 * 3) 尝试清除 sessionStorage（removeItem 失败则覆写空串/哨兵）；
 * 4) 对每个待撤销令牌删除其独立 lease 租约、写入独立 revoked 键（含 exp，绝不 FIFO 驱逐），
 *    并追加至同标签持久化的 window.name 撤销列表；绝不删除其他令牌的租约或撤销键。
 */
export function clearSessionToken(fallbackToken?: string): ClearSessionResult {
  const tokensToRevoke = new Set<string>();
  if (fallbackToken) {
    const trimmedFallback = fallbackToken.trim();
    if (trimmedFallback && trimmedFallback !== REVOKED_TOKEN_SENTINEL) {
      tokensToRevoke.add(trimmedFallback);
    }
  }
  try {
    const existing = window.sessionStorage.getItem(TOKEN_SESSION_KEY);
    if (existing && existing.trim().length > 0 && existing.trim() !== REVOKED_TOKEN_SENTINEL) {
      tokensToRevoke.add(existing.trim());
    }
  } catch {
    // 即使读取 sessionStorage 抛异常，仍有 fallbackToken 参与撤销
  }

  for (const tok of tokensToRevoke) {
    rejectedStaleTokens.add(tok);
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
      window.sessionStorage.setItem(TOKEN_SESSION_KEY, '');
      const remaining = window.sessionStorage.getItem(TOKEN_SESSION_KEY);
      cleared = !remaining || remaining === REVOKED_TOKEN_SENTINEL;
    } catch {
      cleared = false;
    }
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
  for (const tok of tokensToRevoke) {
    const digest = digestTokenNonSecret(tok);
    const meta = parseTokenMetadata(tok);
    const expSec = meta ? meta.expSec : MAX_EXP_SEC;

    let leaseRemoved = false;
    try {
      const leaseKey = `${LEASE_KEY_PREFIX}${digest}`;
      window.localStorage.removeItem(leaseKey);
      leaseRemoved = window.localStorage.getItem(leaseKey) === null;
    } catch {
      leaseRemoved = false;
    }

    let revokedWritten = false;
    try {
      const revokedKey = `${REVOKED_KEY_PREFIX}${digest}`;
      const payload = JSON.stringify({ exp: expSec });
      window.localStorage.setItem(revokedKey, payload);
      revokedWritten = window.localStorage.getItem(revokedKey) !== null;
    } catch {
      revokedWritten = false;
    }

    const windowNameRecorded = recordWindowNameRevocation(digest, expSec);
    if (leaseRemoved || revokedWritten || windowNameRecorded) {
      revocationPersisted = true;
    }
  }

  return { storageCleared: cleared, revocationPersisted };
}

/** 仅供单元测试在用例间重置模块级内存栅栏；preservePersistedState=true 用于模拟同标签页面刷新 */
export function resetSessionGuardForTest(preservePersistedState = false): void {
  sessionInvalidated = false;
  rejectedStaleTokens.clear();
  if (!preservePersistedState) {
    clearAllSessionPersistenceForTest();
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
