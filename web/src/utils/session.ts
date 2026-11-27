/**
 * 会话与设备标识管理（对齐 ADR-022 D2 & REQ-041 W2 & Astra B3 / R1 v1.4 终轮收口）：
 * - JWT 原文仅存放在单标签作用域的 sessionStorage，绝不写入 localStorage、window.name 或 cookie；
 * - 严格 JWT 结构与有限有效期门禁：持久化写入与冷启动恢复均强制校验三段合法 Base64URL（header.payload.signature）、
 *   header 与 payload 均为合法 JSON 对象且 payload.exp 为有限正数且未过期；任何非 JWT、缺段或缺少/非有限/已过期 exp
 *   均严禁写入正向租约且严禁被冷启动恢复；
 * - 独立每令牌持久化租约（oriva_web_lease_v1:<digest>）与仅按 exp 过期 GC 的独立撤销键（oriva_web_revoked_v1:<digest>），
 *   零共享数组、零 FIFO 淘汰；writeSessionToken 若写 lease 失败会立即回滚清理 sessionStorage 防止留下孤儿令牌；
 * - clearSessionToken(fallbackToken?) 对所有待撤销令牌采用全通过（&&）聚合 storageCleared / revocationPersisted /
 *   durableRevocationPersisted，绝不因部分成功误报全通过。
 */

export const TOKEN_SESSION_KEY = 'oriva.web.session.token';
export const REVOKED_KEY_PREFIX = 'oriva_web_revoked_v1:';
export const LEASE_KEY_PREFIX = 'oriva_web_lease_v1:';
export const WINDOW_NAME_REVOKED_PREFIX = '__ORIVA_REVOKED_V1__:';
export const REVOKED_TOKEN_SENTINEL = '__ORIVA_REVOKED__';
export const DEVICE_UID_KEY = 'oriva.web.deviceUid';
export const THEME_KEY = 'oriva.web.theme';

const EXPIRY_SKEW_SEC = 60;
const FALLBACK_OPAQUE_REVOCATION_TTL_SEC = 365 * 24 * 3600;
const WEB_UID_PATTERN = /^web-[A-Za-z0-9-]{8,64}$/;
const DIGEST_HEX_PATTERN = /^[0-9a-f]{16}$/;
const BASE64URL_SEGMENT_PATTERN = /^[A-Za-z0-9_-]+$/;

let sessionInvalidated = false;
const rejectedStaleTokens = new Set<string>();

interface StrictJwtMetadata {
  expSec: number;
}

interface StoredExpRecord {
  exp: number;
}

interface WindowNameRevokedEntry {
  d: string;
  exp: number;
}

export type WriteSessionReason =
  | 'persisted'
  | 'invalid_token'
  | 'expired_token'
  | 'revoked_token'
  | 'storage_failed';

export interface WriteSessionResult {
  persisted: boolean;
  reason: WriteSessionReason;
}

export interface ClearSessionResult {
  storageCleared: boolean;
  revocationPersisted: boolean;
  durableRevocationPersisted: boolean;
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
  if (!BASE64URL_SEGMENT_PATTERN.test(segment)) {
    return null;
  }
  try {
    const normalized = segment.replace(/-/g, '+').replace(/_/g, '/');
    const rem = normalized.length % 4;
    if (rem === 1) return null;
    const padded = rem === 0 ? normalized : normalized + '='.repeat(4 - rem);
    return atob(padded);
  } catch {
    return null;
  }
}

/**
 * 严格校验三段式 Base64URL JWT 结构与有限正数 exp（Astra R1 v1.4）：
 * - 必须精确 3 段且每段均匹配 ^[A-Za-z0-9_-]+$；
 * - header 与 payload 必须均可解码并解析为合法 JSON 对象；
 * - payload.exp 必须为有限正数（< Number.MAX_SAFE_INTEGER）；无 exp 或非 JWT 一律返回 null。
 */
export function parseStrictJwtMetadata(token: string): StrictJwtMetadata | null {
  const parts = token.split('.');
  if (parts.length !== 3) {
    return null;
  }
  const [headerSeg, payloadSeg, sigSeg] = parts;
  if (
    !BASE64URL_SEGMENT_PATTERN.test(headerSeg) ||
    !BASE64URL_SEGMENT_PATTERN.test(payloadSeg) ||
    !BASE64URL_SEGMENT_PATTERN.test(sigSeg)
  ) {
    return null;
  }
  const headerDecoded = decodeBase64Url(headerSeg);
  const payloadDecoded = decodeBase64Url(payloadSeg);
  if (headerDecoded === null || payloadDecoded === null) {
    return null;
  }
  try {
    const headerObj: unknown = JSON.parse(headerDecoded);
    const payloadObj: unknown = JSON.parse(payloadDecoded);
    if (
      !headerObj ||
      typeof headerObj !== 'object' ||
      Array.isArray(headerObj) ||
      !payloadObj ||
      typeof payloadObj !== 'object' ||
      Array.isArray(payloadObj)
    ) {
      return null;
    }
    const expRaw = (payloadObj as Record<string, unknown>).exp;
    if (
      typeof expRaw !== 'number' ||
      !Number.isFinite(expRaw) ||
      expRaw <= 0 ||
      expRaw >= Number.MAX_SAFE_INTEGER
    ) {
      return null;
    }
    return { expSec: Math.floor(expRaw) };
  } catch {
    return null;
  }
}

function isExpiredForGc(expSec: number, nowSec: number): boolean {
  return expSec < Number.MAX_SAFE_INTEGER && nowSec > expSec + EXPIRY_SKEW_SEC;
}

function parseExpRecord(raw: string): StoredExpRecord | null {
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    const exp = (parsed as Record<string, unknown>).exp;
    if (
      typeof exp !== 'number' ||
      !Number.isFinite(exp) ||
      exp <= 0 ||
      exp >= Number.MAX_SAFE_INTEGER
    ) {
      return null;
    }
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
    if (typeof raw !== 'string') {
      return { ok: false, entries: [] };
    }
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
        rec.exp <= 0 ||
        rec.exp >= Number.MAX_SAFE_INTEGER
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
    // window.name 读取异常或数据损坏 => 严格 fail-closed
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
    if (typeof window !== 'undefined' && typeof window.name === 'string' && window.name.startsWith(WINDOW_NAME_REVOKED_PREFIX)) {
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
 * 冷启动严格正向 fail-closed 恢复（Astra R1 v1.4）：
 * 仅在同时满足全部条件时返回 sessionStorage 中的 JWT，任意非 JWT/无 exp/过期/撤销/缺少有效正向租约/读写异常均拒收返回 null。
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

    const meta = parseStrictJwtMetadata(token);
    if (!meta) return null;
    const nowSec = nowEpochSeconds();
    if (nowSec >= meta.expSec) {
      return null;
    }

    const digest = digestTokenNonSecret(token);

    // 1) 同标签页 window.name 撤销校验（读异常或损坏均 fail-closed）
    if (isDigestRevokedInWindowName(digest)) {
      return null;
    }

    // 2) 独立持久化撤销键校验（读取抛异常或非 null 均拒收）
    const revokedMarker = window.localStorage.getItem(`${REVOKED_KEY_PREFIX}${digest}`);
    if (revokedMarker !== null) {
      return null;
    }

    // 3) 必须存在有效的有限期正向会话租约凭证（与 JWT exp 一致且未过期）
    const leaseRaw = window.localStorage.getItem(`${LEASE_KEY_PREFIX}${digest}`);
    if (leaseRaw === null) {
      return null;
    }
    const lease = parseExpRecord(leaseRaw);
    if (!lease || lease.exp !== meta.expSec || nowSec >= lease.exp) {
      return null;
    }

    return token;
  } catch {
    return null;
  }
}

/**
 * 持久化新会话并返回明确原因（Astra R1 v1.4）：
 * - 区分 invalid_token / expired_token / revoked_token / storage_failed；
 * - 若 localStorage 正向 lease 写入失败，立即回滚清理 sessionStorage，绝不留下无租约孤儿令牌。
 */
export function writeSessionTokenDetailed(token: string): WriteSessionResult {
  const normalized = token.trim();
  if (!normalized || normalized === REVOKED_TOKEN_SENTINEL) {
    return { persisted: false, reason: 'invalid_token' };
  }

  const meta = parseStrictJwtMetadata(normalized);
  if (!meta) {
    return { persisted: false, reason: 'invalid_token' };
  }
  const nowSec = nowEpochSeconds();
  if (nowSec >= meta.expSec) {
    return { persisted: false, reason: 'expired_token' };
  }

  if (rejectedStaleTokens.has(normalized)) {
    return { persisted: false, reason: 'revoked_token' };
  }
  const digest = digestTokenNonSecret(normalized);
  if (isDigestRevokedInWindowName(digest)) {
    return { persisted: false, reason: 'revoked_token' };
  }

  try {
    if (window.localStorage.getItem(`${REVOKED_KEY_PREFIX}${digest}`) !== null) {
      return { persisted: false, reason: 'revoked_token' };
    }
  } catch {
    return { persisted: false, reason: 'storage_failed' };
  }

  let wroteSessionStorage = false;
  try {
    window.sessionStorage.setItem(TOKEN_SESSION_KEY, normalized);
    const confirmedToken = window.sessionStorage.getItem(TOKEN_SESSION_KEY);
    if (confirmedToken !== normalized) {
      return { persisted: false, reason: 'storage_failed' };
    }
    wroteSessionStorage = true;

    const leaseKey = `${LEASE_KEY_PREFIX}${digest}`;
    const leaseValue = JSON.stringify({ exp: meta.expSec });
    window.localStorage.setItem(leaseKey, leaseValue);
    if (window.localStorage.getItem(leaseKey) !== leaseValue) {
      // 回滚 sessionStorage 防止残留无租约孤儿
      try {
        window.sessionStorage.removeItem(TOKEN_SESSION_KEY);
      } catch {
        // ignore rollback failure
      }
      return { persisted: false, reason: 'storage_failed' };
    }

    gcExpiredSessionKeys(nowSec);
    sessionInvalidated = false;
    return { persisted: true, reason: 'persisted' };
  } catch {
    if (wroteSessionStorage) {
      try {
        window.sessionStorage.removeItem(TOKEN_SESSION_KEY);
      } catch {
        // ignore rollback failure
      }
    }
    return { persisted: false, reason: 'storage_failed' };
  }
}

export function writeSessionToken(token: string): boolean {
  return writeSessionTokenDetailed(token).persisted;
}

/**
 * 原子化清除会话（Astra R1 v1.4）：
 * - 对所有待撤销令牌（fallbackToken 与 sessionStorage 现存值）统一按全通过（&&）聚合
 *   revocationPersisted 与 durableRevocationPersisted，任一令牌未持久化撤销均诚实上报 false。
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

  if (tokensToRevoke.size === 0) {
    return {
      storageCleared: cleared,
      revocationPersisted: false,
      durableRevocationPersisted: false,
    };
  }

  const nowSec = nowEpochSeconds();
  let allDurable = true;
  let allAnyPersisted = true;

  for (const tok of tokensToRevoke) {
    const digest = digestTokenNonSecret(tok);
    const meta = parseStrictJwtMetadata(tok);
    const expSec = meta ? meta.expSec : nowSec + FALLBACK_OPAQUE_REVOCATION_TTL_SEC;

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
    const tokDurable = leaseRemoved || revokedWritten;
    const tokAny = tokDurable || windowNameRecorded;
    allDurable = allDurable && tokDurable;
    allAnyPersisted = allAnyPersisted && tokAny;
  }

  return {
    storageCleared: cleared,
    revocationPersisted: allAnyPersisted,
    durableRevocationPersisted: allDurable,
  };
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
