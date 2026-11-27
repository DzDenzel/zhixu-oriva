import { useEffect, useState } from 'preact/hooks';
import type { ApiClient } from '../api/client';
import { ApiError, StaleSessionError } from '../api/client';
import { ConflictModal, type ConflictFieldRow } from '../components/ConflictModal';
import type { JournalItem, JournalUpsertPayload } from '../types';
import { buildSafeJournalMarkdown, triggerTextDownload } from '../utils/export';
import { todayIsoDate } from '../utils/format';

export interface JournalPageProps {
  api: ApiClient;
  onNotify: (text: string, tone?: 'ok' | 'warn' | 'error') => void;
}

const PRESET_MOODS = ['平静', '专注', '愉悦', '充实', '疲惫', '焦虑'];

interface JournalFormState {
  id: number | null;
  version: number | null;
  clientId: string | null;
  entryDate: string;
  mood: string;
  tagsInput: string;
  content: string;
}

function emptyJournalForm(): JournalFormState {
  return {
    id: null,
    version: null,
    clientId: null,
    entryDate: todayIsoDate(),
    mood: '平静',
    tagsInput: '',
    content: '',
  };
}

function fromJournalItem(item: JournalItem): JournalFormState {
  return {
    id: item.id,
    version: item.version,
    clientId: item.clientId ?? null,
    entryDate: item.entryDate,
    mood: item.mood ?? '',
    tagsInput: (item.tags ?? []).join(', '),
    content: item.content,
  };
}

function parseTagsInput(raw: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const part of raw.split(/[,，\s]+/)) {
    const trimmed = part.trim();
    if (!trimmed || seen.has(trimmed)) continue;
    seen.add(trimmed);
    out.push(trimmed.slice(0, 20));
    if (out.length >= 8) break;
  }
  return out;
}

function toJournalPayload(form: JournalFormState): JournalUpsertPayload {
  return {
    clientId: form.clientId || undefined,
    entryDate: form.entryDate,
    mood: form.mood.trim() ? form.mood.trim().slice(0, 24) : null,
    tags: parseTagsInput(form.tagsInput),
    content: form.content,
  };
}

export function JournalPage({ api, onNotify }: JournalPageProps) {
  const [entries, setEntries] = useState<JournalItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [errorText, setErrorText] = useState<string | null>(null);

  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [moodFilter, setMoodFilter] = useState('');
  const [keyword, setKeyword] = useState('');

  const [form, setForm] = useState<JournalFormState>(emptyJournalForm());
  const [saving, setSaving] = useState(false);
  const [conflictServer, setConflictServer] = useState<JournalItem | null>(null);

  async function loadEntries(params?: { from?: string; to?: string; mood?: string }) {
    setLoading(true);
    setErrorText(null);
    try {
      const list = await api.listJournals(
        params ?? {
          from: fromDate || undefined,
          to: toDate || undefined,
          mood: moodFilter || undefined,
        },
      );
      setEntries(list);
    } catch (err) {
      if (err instanceof ApiError && !err.isUnauthorized) {
        setErrorText(err.message);
      }
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadEntries();
  }, []);

  async function handleSubmit(evt?: Event, overrideVersion?: number) {
    evt?.preventDefault();
    if (!form.content.trim()) {
      onNotify('请填写日常记录正文', 'warn');
      return;
    }
    setSaving(true);
    try {
      const payload = toJournalPayload(form);
      if (form.id === null) {
        await api.createJournal(payload);
        onNotify('已保存日常记录');
      } else {
        await api.updateJournal(form.id, overrideVersion ?? form.version ?? 0, payload);
        onNotify('已更新日常记录');
      }
      setForm(emptyJournalForm());
      setConflictServer(null);
      await loadEntries();
    } catch (err) {
      if (err instanceof ApiError && err.isConflict && form.id !== null) {
        try {
          const latestList = await api.listJournals();
          setEntries(latestList);
          const latest = latestList.find((j) => j.id === form.id) ?? null;
          setConflictServer(latest);
          if (!latest) {
            onNotify('该记录已在其他设备删除', 'warn');
            setForm(emptyJournalForm());
          }
        } catch {
          onNotify('检测到 1409 版本冲突，请刷新后重试', 'warn');
        }
      } else if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete(item: JournalItem) {
    try {
      await api.deleteJournal(item.id, item.version);
      setEntries((prev) => prev.filter((e) => e.id !== item.id));
      if (form.id === item.id) setForm(emptyJournalForm());
      onNotify('已删除该条日常记录');
    } catch (err) {
      if (err instanceof ApiError && err.isConflict) {
        onNotify('该记录版本已变化（1409），已重新加载', 'warn');
        await loadEntries();
      } else if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    }
  }

  async function handleServerExportMarkdown() {
    const gen = api.getSessionGeneration();
    try {
      const md = await api.exportJournalsMarkdown();
      if (!api.isGenerationCurrent(gen)) return;
      triggerTextDownload(
        `oriva-journals-${todayIsoDate()}.md`,
        md,
        'text/markdown;charset=utf-8',
      );
      onNotify('已导出全量日常记录（防注入 Markdown）');
    } catch (err) {
      if (err instanceof StaleSessionError) return;
      if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    }
  }

  function handleFilteredExportMarkdown() {
    const md = buildSafeJournalMarkdown(filteredEntries);
    triggerTextDownload(
      `oriva-journals-filtered-${todayIsoDate()}.md`,
      md,
      'text/markdown;charset=utf-8',
    );
    onNotify(`已导出当前筛选结果（${filteredEntries.length} 条防注入 Markdown）`);
  }

  const filteredEntries = entries.filter((item) => {
    if (!keyword.trim()) return true;
    const q = keyword.trim().toLowerCase();
    return (
      item.content.toLowerCase().includes(q) ||
      (item.mood ?? '').toLowerCase().includes(q) ||
      (item.tags ?? []).some((t) => t.toLowerCase().includes(q))
    );
  });

  const conflictRows: ConflictFieldRow[] = conflictServer
    ? [
        { label: '日期', draftValue: form.entryDate, serverValue: conflictServer.entryDate },
        { label: '心情', draftValue: form.mood || '（空）', serverValue: conflictServer.mood || '（空）' },
        {
          label: '正文摘要',
          draftValue: form.content.slice(0, 80),
          serverValue: conflictServer.content.slice(0, 80),
        },
        { label: '版本', draftValue: `v${form.version ?? 0}`, serverValue: `v${conflictServer.version}` },
      ]
    : [];

  return (
    <div class="stack-lg">
      <header class="page-header">
        <div>
          <p class="eyebrow">W4 · REQ-039 日常记录</p>
          <h1 class="page-title">日常记录</h1>
          <p class="page-subtitle">
            全站纯文本渲染零 HTML 注入，支持按日期/心情/关键词筛选与防 Markdown 注入 .md 导出。
          </p>
        </div>
        <div class="header-actions">
          <button type="button" class="btn" onClick={handleFilteredExportMarkdown}>
            导出当前筛选 .md
          </button>
          <button
            type="button"
            class="btn btn-primary"
            onClick={() => void handleServerExportMarkdown()}
          >
            导出全量 .md
          </button>
        </div>
      </header>

      {errorText && (
        <div class="notice notice-crimson" role="alert">
          <span>{errorText}</span>
        </div>
      )}

      <section class="paper-card stack-md">
        <div class="row-between">
          <h2 class="section-title">
            {form.id === null ? '写一段日常记录' : `正在编辑记录（v${form.version ?? 0}）`}
          </h2>
          {form.id !== null && (
            <button
              type="button"
              class="btn btn-sm btn-ghost"
              onClick={() => setForm(emptyJournalForm())}
            >
              取消编辑
            </button>
          )}
        </div>

        <form class="stack-md" onSubmit={(evt) => void handleSubmit(evt)}>
          <div class="grid-3">
            <label class="field">
              <span class="field-label">记录日期</span>
              <input
                type="date"
                class="input"
                required
                value={form.entryDate}
                onInput={(evt) =>
                  setForm({ ...form, entryDate: (evt.currentTarget as HTMLInputElement).value })
                }
              />
            </label>
            <label class="field">
              <span class="field-label">心情（≤ 24 字）</span>
              <input
                type="text"
                class="input"
                maxLength={24}
                placeholder="平静 / 专注 / 愉悦…"
                value={form.mood}
                onInput={(evt) =>
                  setForm({ ...form, mood: (evt.currentTarget as HTMLInputElement).value })
                }
              />
            </label>
            <label class="field">
              <span class="field-label">标签（逗号分隔，最多 8 个）</span>
              <input
                type="text"
                class="input"
                placeholder="复盘, 阅读, 散步"
                value={form.tagsInput}
                onInput={(evt) =>
                  setForm({ ...form, tagsInput: (evt.currentTarget as HTMLInputElement).value })
                }
              />
            </label>
          </div>

          <div class="row-wrap">
            {PRESET_MOODS.map((m) => (
              <button
                key={m}
                type="button"
                class={`btn btn-sm${form.mood === m ? ' btn-primary' : ''}`}
                onClick={() => setForm({ ...form, mood: m })}
              >
                {m}
              </button>
            ))}
          </div>

          <label class="field">
            <span class="field-label">正文（纯文本存储，支持多行）</span>
            <textarea
              class="textarea"
              name="journalContent"
              required
              maxLength={10000}
              placeholder="记录今天的想法、进展或微小的收获…"
              value={form.content}
              onInput={(evt) =>
                setForm({ ...form, content: (evt.currentTarget as HTMLTextAreaElement).value })
              }
            />
          </label>

          <div class="row-wrap">
            <button type="submit" class="btn btn-vermilion" disabled={saving}>
              {saving ? '保存中…' : form.id === null ? '发布记录' : '保存修改'}
            </button>
          </div>
        </form>
      </section>

      <section class="paper-card-sunk stack-sm">
        <div class="grid-4">
          <label class="field">
            <span class="field-label">起始日期</span>
            <input
              type="date"
              class="input"
              value={fromDate}
              onInput={(evt) => setFromDate((evt.currentTarget as HTMLInputElement).value)}
            />
          </label>
          <label class="field">
            <span class="field-label">截止日期</span>
            <input
              type="date"
              class="input"
              value={toDate}
              onInput={(evt) => setToDate((evt.currentTarget as HTMLInputElement).value)}
            />
          </label>
          <label class="field">
            <span class="field-label">心情筛选</span>
            <input
              type="text"
              class="input"
              placeholder="精确心情，如 平静"
              value={moodFilter}
              onInput={(evt) => setMoodFilter((evt.currentTarget as HTMLInputElement).value)}
            />
          </label>
          <label class="field">
            <span class="field-label">关键词</span>
            <input
              type="search"
              class="input"
              placeholder="正文/标签关键词"
              value={keyword}
              onInput={(evt) => setKeyword((evt.currentTarget as HTMLInputElement).value)}
            />
          </label>
        </div>
        <div class="row-wrap">
          <button type="button" class="btn btn-sm btn-primary" onClick={() => void loadEntries()}>
            应用服务端筛选
          </button>
          <button
            type="button"
            class="btn btn-sm btn-ghost"
            onClick={() => {
              setFromDate('');
              setToDate('');
              setMoodFilter('');
              setKeyword('');
              void loadEntries({});
            }}
          >
            重置筛选
          </button>
        </div>
      </section>

      {loading ? (
        <div class="paper-card muted">正在加载日常记录…</div>
      ) : filteredEntries.length === 0 ? (
        <div class="paper-card empty-state">暂无符合条件的日常记录。</div>
      ) : (
        <div class="ledger-list">
          {filteredEntries.map((item) => (
            <article key={item.id} class="paper-card stack-sm">
              <div class="row-between">
                <div class="row-wrap">
                  <strong class="numeral">{item.entryDate}</strong>
                  {item.mood && <span class="tag tag-jade">{item.mood}</span>}
                  {(item.tags ?? []).map((t) => (
                    <span key={t} class="tag">
                      #{t}
                    </span>
                  ))}
                  <span class="faint mono">v{item.version}</span>
                </div>
                <div class="row-wrap">
                  <button
                    type="button"
                    class="btn btn-sm"
                    onClick={() => setForm(fromJournalItem(item))}
                  >
                    编辑
                  </button>
                  <button
                    type="button"
                    class="btn btn-sm btn-danger"
                    onClick={() => void handleDelete(item)}
                  >
                    删除
                  </button>
                </div>
              </div>
              <p class="journal-content">{item.content}</p>
            </article>
          ))}
        </div>
      )}

      {conflictServer && (
        <ConflictModal
          title="日常记录存在并发修改（1409）"
          description="其他设备已更新此条记录，请选择加载服务端最新正文或基于最新版本号覆盖保存。"
          rows={conflictRows}
          busy={saving}
          onReloadServer={() => {
            setForm(fromJournalItem(conflictServer));
            setConflictServer(null);
            onNotify('已加载服务端最新记录');
          }}
          onOverwriteWithLatest={() => {
            void handleSubmit(undefined, conflictServer.version);
          }}
          onCancel={() => setConflictServer(null)}
        />
      )}
    </div>
  );
}
