import { useEffect, useState } from 'preact/hooks';
import type { ApiClient } from '../api/client';
import { ApiError } from '../api/client';
import type { PluginCatalogDocument, PluginCatalogEntry } from '../types';
import {
  SAMPLE_FORM_B_CATALOG,
  validateFormBCatalog,
  type CatalogValidationResult,
} from '../utils/catalog';
import { safeHttpUrl } from '../utils/security';

export interface PluginsPageProps {
  api: ApiClient;
  onNotify: (text: string, tone?: 'ok' | 'warn' | 'error') => void;
}

export function PluginsPage({ api, onNotify }: PluginsPageProps) {
  const [sourceTab, setSourceTab] = useState<'official' | 'third-party'>('official');
  const [catalog, setCatalog] = useState<PluginCatalogDocument | null>(null);
  const [loading, setLoading] = useState(true);
  const [errorText, setErrorText] = useState<string | null>(null);

  const [formBInput, setFormBInput] = useState('');
  const [formBResult, setFormBResult] = useState<CatalogValidationResult | null>(null);

  async function loadCatalog(source: 'official' | 'third-party') {
    setLoading(true);
    setErrorText(null);
    try {
      const doc = await api.getPluginCatalog(source);
      setCatalog(doc);
    } catch (err) {
      setCatalog(null);
      if (err instanceof ApiError) {
        setErrorText(err.message);
      } else {
        setErrorText('加载插件目录失败');
      }
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadCatalog(sourceTab);
  }, [sourceTab]);

  async function copyText(value: string, label: string) {
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(value);
        onNotify(`已复制${label}`);
        return;
      }
    } catch {
      // fallback below
    }
    onNotify(`无法自动写入剪贴板，请手动选中复制${label}`, 'warn');
  }

  function handleValidateFormB(raw: string) {
    setFormBInput(raw);
    if (!raw.trim()) {
      setFormBResult(null);
      return;
    }
    setFormBResult(validateFormBCatalog(raw));
  }

  function handleLocalFileChange(evt: Event) {
    const input = evt.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    if (file.size > 1024 * 1024) {
      onNotify('catalog.json 文件超过 1 MiB 上限，已拒绝解析', 'error');
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const text = typeof reader.result === 'string' ? reader.result : '';
      handleValidateFormB(text);
    };
    reader.onerror = () => {
      onNotify('读取本地 catalog.json 失败', 'error');
    };
    reader.readAsText(file, 'utf-8');
  }

  function renderPluginCard(plugin: PluginCatalogEntry) {
    const safeHome = safeHttpUrl(plugin.homepage);
    const safeRepo = safeHttpUrl(plugin.repository);

    return (
      <article key={plugin.id} class="paper-card stack-sm">
        <div class="row-between">
          <div class="row-wrap">
            <h3 class="ledger-title">{plugin.name}</h3>
            <span class="tag tag-indigo mono">{plugin.id}</span>
            <span class="tag">v{plugin.version}</span>
            <span class="tag">契约 v{plugin.contractVersion}</span>
            {plugin.type && <span class="tag tag-brass">{plugin.type}</span>}
          </div>
          {plugin.license && <span class="muted mono">{plugin.license}</span>}
        </div>

        <p>{plugin.description}</p>

        <div class="row-wrap">
          {plugin.requiredCapabilities.map((cap) => (
            <span key={cap} class="tag tag-jade mono">
              {cap}
            </span>
          ))}
        </div>

        <div class="ledger-meta">
          入口：<span class="mono">{plugin.entryPoint}</span>
          {plugin.author ? ` · 作者：${plugin.author}` : ''}
        </div>

        {plugin.sha256 && (
          <div class="row-between paper-card-sunk">
            <span class="mono">SHA-256: {plugin.sha256}</span>
            <button
              type="button"
              class="btn btn-sm"
              onClick={() => void copyText(plugin.sha256 || '', '产物 SHA-256 摘要')}
            >
              复制摘要
            </button>
          </div>
        )}

        {(safeHome || safeRepo) && (
          <div class="row-wrap">
            {safeHome && (
              <a href={safeHome} target="_blank" rel="noopener noreferrer" class="btn btn-sm">
                项目主页
              </a>
            )}
            {safeRepo && (
              <a href={safeRepo} target="_blank" rel="noopener noreferrer" class="btn btn-sm">
                源码仓库
              </a>
            )}
          </div>
        )}
      </article>
    );
  }

  return (
    <div class="stack-lg">
      <header class="page-header">
        <div>
          <p class="eyebrow">W5 · REQ-037 开源插件站只读视图</p>
          <h1 class="page-title">插件站</h1>
          <p class="page-subtitle">
            同源浏览本实例插件目录，并在浏览器本地校验预览形态 B 开源插件站 catalog.json 与信任锚指纹（零外部网络请求）。
          </p>
        </div>
      </header>

      <div class="notice notice-brass" role="note">
        <span>
          Web 数字站仅提供插件目录只读审查与签名指纹核对指引；.plp / .pljs 插件运行与签名校验均在 Android 端「设置 › 插件工坊」沙箱内完成。
        </span>
      </div>

      <section class="stack-md">
        <div class="row-between">
          <div class="segmented" role="tablist" aria-label="本实例插件目录源">
            <button
              type="button"
              role="tab"
              aria-selected={sourceTab === 'official'}
              class={`segmented-btn${sourceTab === 'official' ? ' is-active' : ''}`}
              onClick={() => setSourceTab('official')}
            >
              本实例官方源（official）
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={sourceTab === 'third-party'}
              class={`segmented-btn${sourceTab === 'third-party' ? ' is-active' : ''}`}
              onClick={() => setSourceTab('third-party')}
            >
              本实例第三方源（third-party）
            </button>
          </div>
          {catalog && (
            <span class="mono muted">
              sourceId: {catalog.sourceId} · format v{catalog.catalogFormatVersion}
            </span>
          )}
        </div>

        {errorText && (
          <div class="notice notice-crimson" role="alert">
            <span>{errorText}</span>
          </div>
        )}

        {loading ? (
          <div class="paper-card muted">正在拉取同源插件目录…</div>
        ) : !catalog || catalog.plugins.length === 0 ? (
          <div class="paper-card empty-state">
            当前目录（{sourceTab}）暂无已收录插件产物。
          </div>
        ) : (
          <div class="stack-md">{catalog.plugins.map((plugin) => renderPluginCard(plugin))}</div>
        )}
      </section>

      <hr class="divider" />

      <section class="paper-card stack-md">
        <div class="row-between">
          <div>
            <h2 class="section-title">形态 B 开源插件站 catalog.json 校验预览与指纹核对</h2>
            <p class="muted">
              粘贴或选择本地下载的开源插件站 <span class="mono">catalog.json</span>，浏览器纯本地校验结构、危险链接与 SHA-256 证书信任锚指纹（绝不发起外部跨域请求）。
            </p>
          </div>
          <div class="row-wrap">
            <button
              type="button"
              class="btn btn-sm"
              onClick={() => handleValidateFormB(SAMPLE_FORM_B_CATALOG)}
            >
              载入示例 catalog.json
            </button>
            <label class="btn btn-sm">
              选择本地 JSON
              <input
                type="file"
                accept=".json,application/json"
                hidden
                onChange={handleLocalFileChange}
              />
            </label>
          </div>
        </div>

        <textarea
          class="textarea mono"
          aria-label="形态 B catalog.json 输入"
          placeholder='粘贴 REQ-037 形态 B catalog.json 原文（含 trustedFingerprints 与 plugins 数组）…'
          value={formBInput}
          onInput={(evt) => handleValidateFormB((evt.currentTarget as HTMLTextAreaElement).value)}
        />

        {formBResult && (
          <div class="stack-md">
            <div class={`notice ${formBResult.valid ? 'notice-jade' : 'notice-crimson'}`}>
              <div>
                <strong>
                  {formBResult.valid
                    ? '校验通过：结构、安全链接与指纹格式均符合 REQ-037 形态 B 契约'
                    : `校验未通过：发现 ${formBResult.errors.length} 项问题`}
                </strong>
                {formBResult.errors.length > 0 && (
                  <ul>
                    {formBResult.errors.map((err) => (
                      <li key={err}>{err}</li>
                    ))}
                  </ul>
                )}
                {formBResult.warnings.length > 0 && (
                  <ul>
                    {formBResult.warnings.map((w) => (
                      <li key={w}>{w}</li>
                    ))}
                  </ul>
                )}
              </div>
            </div>

            {formBResult.catalog && (
              <div class="stack-md">
                <div class="paper-card-sunk stack-sm">
                  <div class="row-between">
                    <strong>
                      {formBResult.catalog.sourceName || formBResult.catalog.sourceId}
                    </strong>
                    <span class="tag tag-brass">{formBResult.catalog.sourceType}</span>
                  </div>
                  {formBResult.catalog.sourceDescription && (
                    <p class="muted">{formBResult.catalog.sourceDescription}</p>
                  )}
                  <div class="eyebrow">
                    Android 端接入步骤：设置 › 插件工坊 › 添加外部插件源 › 填写目录地址并核对下方 SHA-256 证书指纹
                  </div>
                  {(formBResult.catalog.trustedFingerprints ?? []).length > 0 && (
                    <div class="stack-sm">
                      {(formBResult.catalog.trustedFingerprints ?? []).map((fp) => (
                        <div key={fp} class="row-between code-box">
                          <span>{fp}</span>
                          <button
                            type="button"
                            class="btn btn-sm"
                            onClick={() => void copyText(fp, '信任锚指纹')}
                          >
                            复制指纹
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                {formBResult.catalog.plugins.length > 0 && (
                  <div class="stack-md">
                    {formBResult.catalog.plugins.map((p) => renderPluginCard(p))}
                  </div>
                )}
              </div>
            )}
          </div>
        )}
      </section>
    </div>
  );
}
