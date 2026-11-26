/**
 * SPA 显式 9 条同源路由契约（严格对齐 WebPortalController）：
 * '/', '/login', '/today', '/plans', '/schedules', '/journal', '/rhythm', '/devices', '/plugins'
 */

export const PORTAL_ROUTES = [
  '/',
  '/login',
  '/today',
  '/plans',
  '/schedules',
  '/journal',
  '/rhythm',
  '/devices',
  '/plugins',
] as const;

export type PortalRoute = (typeof PORTAL_ROUTES)[number];

export type ResolvedRoute =
  | { kind: 'known'; path: PortalRoute }
  | { kind: 'unknown'; rawPath: string };

const KNOWN_SET = new Set<string>(PORTAL_ROUTES);

export function normalizePathname(raw: string): string {
  const withoutQuery = raw.split('?')[0]?.split('#')[0] ?? '/';
  if (withoutQuery.length > 1 && withoutQuery.endsWith('/')) {
    return withoutQuery.slice(0, -1);
  }
  return withoutQuery || '/';
}

export function resolveRoute(pathname: string): ResolvedRoute {
  const normalized = normalizePathname(pathname);
  if (KNOWN_SET.has(normalized)) {
    return { kind: 'known', path: normalized as PortalRoute };
  }
  return { kind: 'unknown', rawPath: normalized };
}

export function navigateTo(path: PortalRoute, replace = false): void {
  if (typeof window === 'undefined') return;
  const current = normalizePathname(window.location.pathname);
  if (current === path && !replace) return;
  if (replace) {
    window.history.replaceState(null, '', path);
  } else {
    window.history.pushState(null, '', path);
  }
  window.dispatchEvent(new PopStateEvent('popstate'));
}
