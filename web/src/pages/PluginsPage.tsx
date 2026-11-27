import { useEffect, useRef, useState } from 'preact/hooks';
import type { ApiClient } from '../api/client';
import { ApiError, StaleSessionError } from '../api/client';
import type { PluginCatalogDocument, PluginCatalogEntry } from '../types';
import {
  MAX_CATALOG_BYTES,
  SAMPLE_FORM_B_CATALOG,
  validateFormBCatalog,
  type CatalogValidationResult,
} from '../utils/catalog';
import { safeHttpUrl, utf8ByteLength } from '../utils/security';

export interface PluginsPageProps {
  api: ApiClient;
  onNotify: (text: string, tone?: 'ok' | 'warn' | 'error') => void;
}

export function PluginsPage({ api, onNotify }: PluginsPageProps) {
  const [sourceTab, setSourceTab] = useState<'official' | 'third-party'>('official');
  const [catalogState, setCatalogState] = useState<{
    source: 'official' | 'third-party';
    doc: PluginCatalogDocument;
  } | null>(null);
  const [loading, setLoading] = useState(true);
  const [errorText, setErrorText] = useState<string | null>(null);

  const [formBInput, setFormBInput] = useState('');
  const [formBSizeWarning, setFormBSizeWarning] = useState<string | null>(null);
  const [formBResult, setFormBResult] = useState<CatalogValidationResult | null>(null);

  // R2: 切源点击时同步递增 catalogReqSeq 并绑定 activeSourceRef，立即废止先前在途请求且杜绝首帧串闪
  const catalogReqSeq = useRef(0);
  const activeSourceRef = useRef<'official' | 'third-party'>('official');

  async function loadCatalog(source: 'official' | 'third-party', reqId: number) {
    const gen = api.getSessionGeneration();
    try {
      const doc = await api.getPluginCatalog(source);
      if (
        !api.isGenerationCurrent(gen) ||
        reqId !== catalogReqSeq.current ||
        activeSourceRef.current !== source
      ) {
        return;
      }
      setCatalogState({ source, doc });
    } catch (err) {
      if (err instanceof StaleSessionError) return;
      if (
        !api.isGenerationCurrent(gen) ||
        reqId !== catalogReqSeq.current ||
        activeSourceRef.current !== source
      ) {
        return;
      }
      setCatalogState(null);
      if (err instanceof ApiError) {
        setErrorText(err.message);
      } else {
        setErrorText('加载插件目录失败');
      }
    } finally {
      if (
        api.isGenerationCurrent(gen) &&
        reqId === catalogReqSeq.current &&
        activeSourceRef.current === source
      ) {
        setLoading(false);
      }
    }
  }

  function switchSourceTab(nextSource: 'official' | 'third-party') {
    if (nextSource === activeSourceRef.current) return;
    const reqId = ++catalogReqSeq.current;
    activeSourceRef.current = nextSource;
    setSourceTab(nextSource);
    setCatalogState(null);
    setErrorText(null);
    setLoading(true);
    void loadCatalog(nextSource, reqId);
  }

  useEffect(() => {
    const reqId = ++catalogReqSeq.current;
    activeSourceRef.current = 'official';
    setCatalogState(null);
    setErrorText(null);
    setLoading(true);
    void loadCatalog('official', reqId);
    return () => {
      catalogReqSeq.current += 1;
    };
  }, []);

  const catalog = catalogState && catalogState.source === sourceTab ? catalogState.doc : null;

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

  function handleDraftChange(raw: string) {
    const bytes = utf8ByteLength(raw);
    if (bytes > MAX_CATALOG_BYTES) {
      setFormBSizeWarning(
        `输入文本超过 ${MAX_CATALOG_BYTES / (1024 * 1024)} MiB 安全上限，已阻止自动解析，请精简后再校验。`,
      );
      setFormBInput(raw.slice(0, 65536));
      setFormBResult(null);
      return;
    }
    setFormBSizeWarning(null);
    setFormBInput(raw);
  }

  function runFormBValidation(rawOverride?: string) {
    const text = rawOverride ?? formBInput;
    if (rawOverride !== undefined) {
      setFormBInput(rawOverride);
      setFormBSizeWarning(null);
    }
    if (!text.trim()) {
      setFormBResult(null);
      return;
    }
    setFormBResult(validateFormBCatalog(text));
  }

  function handleLocalFileChange(evt: Event) {
    const input = evt.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    if (file.size > MAX_CATALOG_BYTES) {
      onNotify('catalog.json 文件超过 2 MiB 上限，已拒绝解析', 'error');
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const text = typeof reader.result === 'string' ? reader.result : '';
      runFormBValidation(text);
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
            {plugin.versionCode !== undefined && (
              <span class="tag mono">code {plugin.versionCode}</span>
            )}
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
          {plugin.downloadUrl ? (
            <>
              {' · '}产物路径：<span class="mono">{plugin.downloadUrl}</span>
            </>
          ) : null}
          {plugin.author ? ` · 作者：${plugin.author}` : ''}
        </div>

        {plugin.sha256 && (
          <div class="row-between paper-card-sunk">
            <span class="mono">SHA-256: {plugin.sha256}</span>
            <button
              type="button"
              class="btn btn-sm"
              onClick={() =>
                void copyText(plugin.sha256 || '', '产物 SHA-256 摘要（须经独立渠道核对）')
              }
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
            同源浏览本实例公开插件目录（不携带会话令牌），并在浏览器本地对形态 B catalog.json 执行有界格式预检（零外部网络请求）。
          </p>
        </div>
      </header>

      <div class="notice notice-brass" role="note">
        <span>
          Web 数字站仅提供目录只读审查与格式预检，未下载也未对插件包字节验签；.plp / .pljs 签名校验与运行均在 Android 端「设置 › 插件工坊」沙箱内完成。
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
              onClick={() => switchSourceTab('official')}
            >
              本实例官方源（official）
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={sourceTab === 'third-party'}
              class={`segmented-btn${sourceTab === 'third-party' ? ' is-active' : ''}`}
              onClick={() => switchSourceTab('third-party')}
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
          <div class="paper-card muted">正在拉取同源公开插件目录…</div>
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
            <h2 class="section-title">形态 B 开源插件站 catalog.json 本地格式预检与指纹查看</h2>
            <p class="muted">
              粘贴或选择本地下载的开源插件站 <span class="mono">catalog.json</span>（上限 2 MiB、最多 256 项插件），点击「校验并预览」在浏览器本地检查结构与安全边界。
            </p>
          </div>
          <div class="row-wrap">
            <button
              type="button"
              class="btn btn-sm"
              onClick={() => runFormBValidation(SAMPLE_FORM_B_CATALOG)}
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
            <button
              type="button"
              class="btn btn-sm btn-primary"
              disabled={!formBInput.trim() || Boolean(formBSizeWarning)}
              onClick={() => runFormBValidation()}
            >
              校验并预览
            </button>
          </div>
        </div>

        <textarea
          class="textarea mono"
          aria-label="形态 B catalog.json 输入"
          placeholder='粘贴 REQ-037 形态 B catalog.json 原文后点击「校验并预览」…'
          value={formBInput}
          onInput={(evt) => handleDraftChange((evt.currentTarget as HTMLTextAreaElement).value)}
        />

        {formBSizeWarning && (
          <div class="notice notice-crimson" role="alert">
            <span>{formBSizeWarning}</span>
          </div>
        )}

        {formBResult && (
          <div class="stack-md">
            <div
              class={`notice ${
                formBResult.trustState === 'invalid' ? 'notice-crimson' : 'notice-brass'
              }`}
            >
              <div class="stack-sm">
                <strong>
                  {formBResult.trustState === 'invalid'
                    ? `格式预检未通过：发现 ${formBResult.errors.length + formBResult.omittedErrorCount} 项问题`
                    : formBResult.trustState === 'unanchored'
                      ? '格式预检完成（缺少信任锚指纹）：不可作为可信源背书'
                      : '本地格式预检通过（仅校验 JSON 结构与边界，未验签插件包字节）'}
                </strong>
                {formBResult.trustState !== 'invalid' && (
                  <span class="muted">
                    浏览器未下载插件包也未验证 JAR/包体签名；下方展示的指纹来自所粘贴的目录文本自身，导入 Android 端前请务必通过独立可信渠道核对。
                  </span>
                )}
                {formBResult.errors.length > 0 && (
                  <ul>
                    {formBResult.errors.map((err) => (
                      <li key={err}>{err}</li>
                    ))}
                    {formBResult.omittedErrorCount > 0 && (
                      <li>……其余 {formBResult.omittedErrorCount} 项错误已省略</li>
                    )}
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

            {formBResult.valid && formBResult.catalog && (
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
                    Android 端接入指引：设置 › 插件工坊 › 添加外部插件源 › 填入目录地址并通过独立可信渠道核对下方证书指纹
                  </div>
                  {(formBResult.catalog.trustedFingerprints ?? []).length > 0 ? (
                    <div class="stack-sm">
                      {(formBResult.catalog.trustedFingerprints ?? []).map((fp) => (
                        <div key={fp} class="row-between code-box">
                          <span>{fp}</span>
                          <button
                            type="button"
                            class="btn btn-sm"
                            onClick={() =>
                              void copyText(
                                fp,
                                '目录声明的指纹文本（请务必通过独立可信渠道核对）',
                              )
                            }
                          >
                            复制指纹（须独立渠道核对）
                          </button>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <p class="muted">
                      当前目录未声明 trustedFingerprints，无法提供证书指纹复制。
                    </p>
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
