import type { ComponentChildren } from 'preact';
import type { MeResponse, ToastMessage } from '../types';
import type { InkThemeMode } from '../utils/session';
import { navigateTo, type PortalRoute } from '../utils/router';

interface NavEntry {
  path: PortalRoute;
  seal: string;
  label: string;
}

const NAV_ENTRIES: NavEntry[] = [
  { path: '/today', seal: '今', label: '今日总览' },
  { path: '/plans', seal: '行', label: '计划' },
  { path: '/schedules', seal: '记', label: '日程' },
  { path: '/rhythm', seal: '律', label: '我的节奏' },
  { path: '/journal', seal: '录', label: '日常记录' },
  { path: '/devices', seal: '端', label: '设备管理' },
  { path: '/plugins', seal: '工', label: '插件站' },
];

export interface ShellProps {
  currentPath: PortalRoute;
  me: MeResponse | null;
  theme: InkThemeMode;
  toast: ToastMessage | null;
  onDismissToast: () => void;
  onToggleTheme: () => void;
  onLogout: () => void;
  children: ComponentChildren;
}

export function Shell({
  currentPath,
  me,
  theme,
  toast,
  onDismissToast,
  onToggleTheme,
  onLogout,
  children,
}: ShellProps) {
  return (
    <div class="app-shell">
      <aside class="sidebar" aria-label="主导航">
        <div class="brand-lockup">
          <div class="brand-seal" aria-hidden="true">
            <svg viewBox="0 0 64 64" focusable="false">
              <path
                d="M14 45C24 41 37 33 50 18"
                fill="none"
                stroke="currentColor"
                stroke-width="6"
                stroke-linecap="round"
              />
              <circle cx="33" cy="23" r="5.5" fill="currentColor" />
            </svg>
          </div>
          <div>
            <p class="brand-title">知序 ORIVA</p>
            <p class="brand-sub">{me?.instanceLabel || 'Web 数字站'}</p>
          </div>
        </div>

        <nav class="nav-list">
          {NAV_ENTRIES.map((item) => {
            const active = currentPath === item.path;
            return (
              <a
                key={item.path}
                href={item.path}
                class={`nav-link${active ? ' is-active' : ''}`}
                aria-current={active ? 'page' : undefined}
                onClick={(evt) => {
                  evt.preventDefault();
                  navigateTo(item.path);
                }}
              >
                <span class="nav-seal" aria-hidden="true">
                  {item.seal}
                </span>
                <span>{item.label}</span>
              </a>
            );
          })}
        </nav>

        <div class="sidebar-footer">
          {me && (
            <div class="profile-card">
              <div class="profile-name">{me.accountMasked}</div>
              <div class="profile-meta">
                {me.deviceName || 'Web 浏览器'} · v{me.version}
              </div>
              <div class="profile-meta">
                {me.deploymentEnabled ? '自建部署实例' : '本地/标准实例'}
              </div>
            </div>
          )}

          <div class="sidebar-actions">
            <button
              type="button"
              class="btn btn-sm"
              onClick={onToggleTheme}
              aria-pressed={theme === 'dark'}
            >
              {theme === 'dark' ? '切换宣纸' : '切换夜墨'}
            </button>
            <button type="button" class="btn btn-sm btn-ghost" onClick={onLogout}>
              退出登录
            </button>
          </div>
        </div>
      </aside>

      <main class="main-stage">{children}</main>

      {toast && (
        <div class="toast-host" role="status" aria-live="polite">
          <div
            class={`toast-card${toast.tone === 'warn' ? ' is-warn' : toast.tone === 'error' ? ' is-error' : ''}`}
          >
            <span>{toast.text}</span>
            <button
              type="button"
              class="btn btn-sm btn-ghost"
              aria-label="关闭提示"
              onClick={onDismissToast}
            >
              ×
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
