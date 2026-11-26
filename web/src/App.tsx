import { useCallback, useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { createApiClient, type ApiClient } from './api/client';
import { Shell } from './components/Shell';
import { DevicesPage } from './pages/DevicesPage';
import { JournalPage } from './pages/JournalPage';
import { LoginPage } from './pages/LoginPage';
import { PlansPage } from './pages/PlansPage';
import { PluginsPage } from './pages/PluginsPage';
import { RhythmPage } from './pages/RhythmPage';
import { SchedulesPage } from './pages/SchedulesPage';
import { TodayPage } from './pages/TodayPage';
import type { MeResponse, ToastMessage } from './types';
import { navigateTo, resolveRoute, type PortalRoute } from './utils/router';
import {
  applyThemeToDocument,
  readInitialTheme,
  saveTheme,
  type InkThemeMode,
} from './utils/session';

export interface AppProps {
  apiClient?: ApiClient;
  initialPath?: string;
}

export function App({ apiClient, initialPath }: AppProps) {
  const [pathname, setPathname] = useState<string>(
    () => initialPath ?? (typeof window !== 'undefined' ? window.location.pathname : '/'),
  );
  const [theme, setTheme] = useState<InkThemeMode>(() => readInitialTheme());
  const [me, setMe] = useState<MeResponse | null>(null);
  const [bootstrapping, setBootstrapping] = useState<boolean>(true);
  const [loginNotice, setLoginNotice] = useState<string | null>(null);
  const [toast, setToast] = useState<ToastMessage | null>(null);
  const toastCounter = useRef(0);
  const toastTimer = useRef<number | null>(null);

  const notify = useCallback((text: string, tone: 'ok' | 'warn' | 'error' = 'ok') => {
    toastCounter.current += 1;
    setToast({ id: toastCounter.current, text, tone });
    if (toastTimer.current !== null) {
      window.clearTimeout(toastTimer.current);
    }
    toastTimer.current = window.setTimeout(() => {
      setToast(null);
    }, 3200);
  }, []);

  const handleUnauthorized = useCallback((reason: string) => {
    setMe(null);
    setLoginNotice(reason || '登录会话已过期，请重新登录');
    navigateTo('/login', true);
  }, []);

  const api = useMemo(
    () => apiClient ?? createApiClient({ onUnauthorized: handleUnauthorized }),
    [apiClient, handleUnauthorized],
  );

  useEffect(() => {
    applyThemeToDocument(theme);
    saveTheme(theme);
  }, [theme]);

  useEffect(() => {
    const onPopState = () => {
      setPathname(window.location.pathname);
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  const hydrateSession = useCallback(async () => {
    const gen = api.getSessionGeneration();
    if (!api.getToken()) {
      setMe(null);
      setBootstrapping(false);
      return;
    }
    try {
      const profile = await api.getMe();
      if (!api.isGenerationCurrent(gen)) return;
      setMe(profile);
      setLoginNotice(null);
    } catch {
      if (!api.isGenerationCurrent(gen)) return;
      setMe(null);
    } finally {
      if (api.isGenerationCurrent(gen)) {
        setBootstrapping(false);
      }
    }
  }, [api]);

  useEffect(() => {
    void hydrateSession();
  }, [hydrateSession]);

  const resolved = resolveRoute(pathname);
  const authenticated = Boolean(api.getToken() && me);

  useEffect(() => {
    if (bootstrapping) return;
    if (resolved.kind === 'known') {
      if (resolved.path === '/') {
        navigateTo(authenticated ? '/today' : '/login', true);
      } else if (resolved.path === '/login' && authenticated) {
        navigateTo('/today', true);
      } else if (resolved.path !== '/login' && !authenticated) {
        navigateTo('/login', true);
      }
    }
  }, [bootstrapping, resolved, authenticated]);

  function handleToggleTheme() {
    setTheme((prev) => (prev === 'dark' ? 'light' : 'dark'));
  }

  function handleLogout() {
    const result = api.clearSession();
    setMe(null);
    setLoginNotice(
      result.storageCleared
        ? '您已安全退出当前浏览器会话。'
        : '已清除当前页面会话并拒绝复用旧凭据；浏览器存储清理受限，建议随后关闭本标签页。',
    );
    navigateTo('/login', true);
  }

  if (bootstrapping) {
    return (
      <div class="login-shell">
        <div class="paper-card">正在连接个人数字站…</div>
      </div>
    );
  }

  if (resolved.kind === 'unknown') {
    return (
      <div class="login-shell">
        <section class="login-card stack-md">
          <span class="tag tag-brass">未登记路由</span>
          <h1 class="page-title">页面不存在</h1>
          <p class="muted">
            知序 Web 数字站仅开放 9 条显式页面路径，未识别路径 <span class="mono">{resolved.rawPath}</span>。
          </p>
          <button
            type="button"
            class="btn btn-primary"
            onClick={() => navigateTo(authenticated ? '/today' : '/login', true)}
          >
            {authenticated ? '返回今日总览' : '前往登录'}
          </button>
        </section>
      </div>
    );
  }

  if (resolved.path === '/login' || !authenticated) {
    return (
      <LoginPage
        api={api}
        theme={theme}
        noticeMessage={loginNotice}
        onToggleTheme={handleToggleTheme}
        onLoginSuccess={async () => {
          await hydrateSession();
          navigateTo('/today', true);
        }}
      />
    );
  }

  const activeRoute: PortalRoute = resolved.path === '/' ? '/today' : resolved.path;

  return (
    <Shell
      currentPath={activeRoute}
      me={me}
      theme={theme}
      toast={toast}
      onDismissToast={() => setToast(null)}
      onToggleTheme={handleToggleTheme}
      onLogout={handleLogout}
    >
      {activeRoute === '/today' && <TodayPage api={api} me={me} />}
      {activeRoute === '/plans' && <PlansPage api={api} onNotify={notify} />}
      {activeRoute === '/schedules' && <SchedulesPage api={api} onNotify={notify} />}
      {activeRoute === '/rhythm' && <RhythmPage api={api} onNotify={notify} />}
      {activeRoute === '/journal' && <JournalPage api={api} onNotify={notify} />}
      {activeRoute === '/devices' && (
        <DevicesPage api={api} me={me} onNotify={notify} onLogout={handleLogout} />
      )}
      {activeRoute === '/plugins' && <PluginsPage api={api} onNotify={notify} />}
    </Shell>
  );
}
