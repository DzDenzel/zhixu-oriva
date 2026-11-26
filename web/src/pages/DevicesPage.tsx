import { useEffect, useState } from 'preact/hooks';
import type { ApiClient } from '../api/client';
import { ApiError } from '../api/client';
import type { DeviceItem, MeResponse } from '../types';

export interface DevicesPageProps {
  api: ApiClient;
  me: MeResponse | null;
  onNotify: (text: string, tone?: 'ok' | 'warn' | 'error') => void;
  onLogout: () => void;
}

export function DevicesPage({ api, me, onNotify, onLogout }: DevicesPageProps) {
  const [devices, setDevices] = useState<DeviceItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [errorText, setErrorText] = useState<string | null>(null);
  const [confirmUnbind, setConfirmUnbind] = useState<DeviceItem | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  async function loadDevices() {
    setLoading(true);
    setErrorText(null);
    try {
      const list = await api.listDevices();
      setDevices(list);
    } catch (err) {
      if (err instanceof ApiError && !err.isUnauthorized) {
        setErrorText(err.message);
      }
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadDevices();
  }, []);

  async function handleSetPrimary(device: DeviceItem) {
    setBusyId(device.id);
    try {
      await api.setPrimaryDevice(device.id);
      onNotify(`已将「${device.deviceName || device.platform}」设为主设备`);
      await loadDevices();
    } catch (err) {
      if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    } finally {
      setBusyId(null);
    }
  }

  async function handleConfirmUnbind() {
    if (!confirmUnbind) return;
    const target = confirmUnbind;
    setBusyId(target.id);
    try {
      await api.unbindDevice(target.id);
      setConfirmUnbind(null);
      if (me?.deviceId && target.id === me.deviceId) {
        onNotify('已解绑当前 Web 会话设备，正在退出登录…', 'warn');
        onLogout();
        return;
      }
      onNotify(`已解绑设备「${target.deviceName || target.deviceUid}」`);
      await loadDevices();
    } catch (err) {
      if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div class="stack-lg">
      <header class="page-header">
        <div>
          <p class="eyebrow">W5 · 账号与已绑定设备</p>
          <h1 class="page-title">设备管理</h1>
          <p class="page-subtitle">
            查看连接到当前个人实例的手机与 Web 浏览器会话，支持切换主设备或解绑闲置设备。
          </p>
        </div>
        <div class="header-actions">
          <button type="button" class="btn" onClick={() => void loadDevices()} disabled={loading}>
            刷新设备
          </button>
        </div>
      </header>

      {errorText && (
        <div class="notice notice-crimson" role="alert">
          <span>{errorText}</span>
        </div>
      )}

      {me && (
        <section class="paper-card-sunk grid-3">
          <div>
            <div class="eyebrow">当前账号</div>
            <strong>{me.accountMasked}</strong>
          </div>
          <div>
            <div class="eyebrow">实例标签与版本</div>
            <strong>
              {me.instanceLabel || '未命名实例'} · v{me.version}
            </strong>
          </div>
          <div>
            <div class="eyebrow">当前设备 ID</div>
            <strong class="mono">{me.deviceId ?? '—'}</strong>
          </div>
        </section>
      )}

      {loading ? (
        <div class="paper-card muted">正在加载已绑定设备…</div>
      ) : devices.length === 0 ? (
        <div class="paper-card empty-state">暂无已绑定设备记录。</div>
      ) : (
        <div class="ledger-list">
          {devices.map((dev) => {
            const isCurrent = me?.deviceId === dev.id;
            const isWeb = dev.deviceUid.startsWith('web-') || dev.platform === 'web';
            return (
              <article key={dev.id} class="ledger-item">
                <div class="ledger-body">
                  <div class="row-wrap">
                    <span class={`tag ${dev.isPrimary ? 'tag-vermilion' : 'tag-brass'}`}>
                      {dev.isPrimary ? '主设备' : isWeb ? 'Web 端' : dev.platform}
                    </span>
                    <h2 class="ledger-title">{dev.deviceName || dev.platform}</h2>
                    {isCurrent && <span class="tag tag-indigo">当前会话</span>}
                    <span class={`tag ${dev.online ? 'tag-jade' : 'tag-stone'}`}>
                      {dev.online ? '在线' : '离线'}
                    </span>
                  </div>
                  <div class="ledger-meta mono">
                    UID: {dev.deviceUid} · 最近在线: {dev.lastOnlineAt || '未知'}
                  </div>
                </div>
                <div class="row-wrap">
                  {!dev.isPrimary && (
                    <button
                      type="button"
                      class="btn btn-sm"
                      disabled={busyId === dev.id}
                      onClick={() => void handleSetPrimary(dev)}
                    >
                      设为主设备
                    </button>
                  )}
                  <button
                    type="button"
                    class="btn btn-sm btn-danger"
                    disabled={busyId === dev.id}
                    onClick={() => setConfirmUnbind(dev)}
                  >
                    解绑
                  </button>
                </div>
              </article>
            );
          })}
        </div>
      )}

      {confirmUnbind && (
        <div class="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="unbind-title">
          <div class="modal-panel stack-md">
            <h2 id="unbind-title" class="section-title">
              确认解绑设备「{confirmUnbind.deviceName || confirmUnbind.deviceUid}」？
            </h2>
            <p class="muted">
              {me?.deviceId === confirmUnbind.id
                ? '这是您当前正在使用的 Web 会话设备，解绑后将立即退出当前浏览器登录状态。'
                : '解绑后该设备需重新登录方可同步此个人后端实例的数据。'}
            </p>
            <div class="row-wrap">
              <button
                type="button"
                class="btn btn-danger"
                disabled={busyId === confirmUnbind.id}
                onClick={() => void handleConfirmUnbind()}
              >
                确认解绑
              </button>
              <button
                type="button"
                class="btn btn-ghost"
                onClick={() => setConfirmUnbind(null)}
              >
                取消
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
