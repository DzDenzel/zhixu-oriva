export interface ConflictFieldRow {
  label: string;
  draftValue: string;
  serverValue: string;
}

export interface ConflictModalProps {
  title: string;
  description: string;
  rows: ConflictFieldRow[];
  busy?: boolean;
  onReloadServer: () => void;
  onOverwriteWithLatest?: () => void;
  onCancel: () => void;
}

/**
 * 通用 1409 乐观锁冲突弹窗（REQ-041 W3 / W4）：
 * 当其他设备已更新同一条目时展示本地草稿与服务端最新快照差异，支持一键重载或基于最新 version 再次保存。
 */
export function ConflictModal({
  title,
  description,
  rows,
  busy = false,
  onReloadServer,
  onOverwriteWithLatest,
  onCancel,
}: ConflictModalProps) {
  return (
    <div class="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="conflict-modal-title">
      <div class="modal-panel stack-md">
        <div>
          <span class="tag tag-brass">版本冲突 · 1409</span>
          <h2 id="conflict-modal-title" class="section-title">
            {title}
          </h2>
          <p class="muted">{description}</p>
        </div>

        {rows.length > 0 && (
          <table class="diff-table">
            <thead>
              <tr>
                <th>字段</th>
                <th>您的草稿</th>
                <th>服务端最新</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.label}>
                  <th>{row.label}</th>
                  <td>{row.draftValue}</td>
                  <td>{row.serverValue}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <div class="row-wrap">
          <button
            type="button"
            class="btn btn-primary"
            disabled={busy}
            onClick={onReloadServer}
          >
            加载服务端最新数据
          </button>
          {onOverwriteWithLatest && (
            <button
              type="button"
              class="btn btn-vermilion"
              disabled={busy}
              onClick={onOverwriteWithLatest}
            >
              以我的草稿覆盖最新版本
            </button>
          )}
          <button type="button" class="btn btn-ghost" disabled={busy} onClick={onCancel}>
            返回编辑
          </button>
        </div>
      </div>
    </div>
  );
}
