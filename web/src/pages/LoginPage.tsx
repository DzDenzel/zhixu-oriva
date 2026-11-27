import { useState } from 'preact/hooks';
import type { ApiClient } from '../api/client';
import { ApiError } from '../api/client';
import type { InkThemeMode } from '../utils/session';
import { getOrCreateDeviceUid } from '../utils/session';

export interface LoginPageProps {
  api: ApiClient;
  theme: InkThemeMode;
  noticeMessage: string | null;
  onToggleTheme: () => void;
  onLoginSuccess: () => Promise<void>;
}

export function LoginPage({
  api,
  theme,
  noticeMessage,
  onToggleTheme,
  onLoginSuccess,
}: LoginPageProps) {
  const [account, setAccount] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [errorText, setErrorText] = useState<string | null>(null);
  const deviceUid = getOrCreateDeviceUid();

  async function handleSubmit(evt: Event) {
    evt.preventDefault();
    const trimmedAccount = account.trim();
    if (!trimmedAccount) {
      setErrorText('请输入手机号或邮箱账号');
      return;
    }
    if (!password) {
      setErrorText('请输入登录密码');
      return;
    }
    setSubmitting(true);
    setErrorText(null);
    try {
      await api.login(trimmedAccount, password);
      setPassword('');
      await onLoginSuccess();
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.message.includes('15 分钟') || err.message.includes('尝试过多')) {
          setErrorText('登录尝试次数已达部署实例限制（15 分钟内最多 5 次），请 15 分钟后再试。');
        } else {
          setErrorText(err.message);
        }
      } else {
        setErrorText('登录失败，请稍后重试');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div class="login-shell">
      <section class="login-card stack-lg" aria-labelledby="login-heading">
        <div class="row-between">
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
              <h1 id="login-heading" class="brand-title">
                知序 ORIVA
              </h1>
              <p class="brand-sub">个人 Web 数字站 · 同源直连</p>
            </div>
          </div>
          <button type="button" class="btn btn-sm" onClick={onToggleTheme}>
            {theme === 'dark' ? '宣纸' : '夜墨'}
          </button>
        </div>

        {noticeMessage && (
          <div class="notice notice-brass" role="alert">
            <span>{noticeMessage}</span>
          </div>
        )}

        {errorText && (
          <div class="notice notice-crimson" role="alert">
            <span>{errorText}</span>
          </div>
        )}

        <form class="stack-md" onSubmit={handleSubmit}>
          <label class="field">
            <span class="field-label">实例账号（手机号或邮箱）</span>
            <input
              type="text"
              class="input"
              name="account"
              autoComplete="username"
              placeholder="例如 owner@example.com 或手机号"
              value={account}
              disabled={submitting}
              onInput={(evt) => setAccount((evt.currentTarget as HTMLInputElement).value)}
            />
          </label>

          <label class="field">
            <span class="field-label">密码</span>
            <input
              type="password"
              class="input"
              name="password"
              autoComplete="current-password"
              placeholder="请输入账号密码"
              value={password}
              disabled={submitting}
              onInput={(evt) => setPassword((evt.currentTarget as HTMLInputElement).value)}
            />
          </label>

          <button type="submit" class="btn btn-primary" disabled={submitting}>
            {submitting ? '正在验证并连接实例…' : '登录数字站'}
          </button>
        </form>

        <div class="paper-card-sunk stack-sm">
          <div class="eyebrow">会话与设备安全边界（ADR-022）</div>
          <div class="muted">
            会话令牌仅存于当前浏览器标签页的 <span class="mono">sessionStorage</span>，关闭标签页或退出登录即自动清除；不写入持久 Cookie 或跨站存储。
          </div>
          <div class="muted">
            当前浏览器设备标识：<span class="mono">{deviceUid}</span>
          </div>
        </div>
      </section>
    </div>
  );
}
