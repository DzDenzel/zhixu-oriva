import { useEffect, useState } from 'preact/hooks';
import type { ApiClient } from '../api/client';
import { ApiError } from '../api/client';
import { CompletionRing, RhythmBarChart } from '../components/RhythmCharts';
import type { RhythmSummary } from '../types';
import { triggerTextDownload } from '../utils/export';
import { formatMinutes, formatPercent, todayIsoDate } from '../utils/format';

export interface RhythmPageProps {
  api: ApiClient;
  onNotify: (text: string, tone?: 'ok' | 'warn' | 'error') => void;
}

export function RhythmPage({ api, onNotify }: RhythmPageProps) {
  const [data, setData] = useState<RhythmSummary | null>(null);
  const [scale, setScale] = useState<'week' | 'month'>('week');
  const [pageOffset, setPageOffset] = useState(0); // 0 = 最新窗口，1 = 上一窗口
  const [selectedDate, setSelectedDate] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [errorText, setErrorText] = useState<string | null>(null);

  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

  async function loadRhythm() {
    setLoading(true);
    setErrorText(null);
    try {
      const summary = await api.getRhythm(62, tz);
      setData(summary);
      const todayCell = summary.days.find((d) => d.isToday) ?? summary.days[summary.days.length - 1];
      setSelectedDate(todayCell?.date ?? null);
    } catch (err) {
      if (err instanceof ApiError && !err.isUnauthorized) {
        setErrorText(err.message);
      }
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadRhythm();
  }, []);

  async function handleExport(format: 'json' | 'csv') {
    try {
      const body = await api.exportRecords(format);
      const mime = format === 'csv' ? 'text/csv;charset=utf-8' : 'application/json;charset=utf-8';
      triggerTextDownload(`oriva-records-${todayIsoDate()}.${format}`, body, mime);
      onNotify(`已导出执行记录（.${format}）`);
    } catch (err) {
      if (err instanceof ApiError && !err.isUnauthorized) {
        onNotify(err.message, 'error');
      }
    }
  }

  const allDays = data?.days ?? [];
  const windowSize = scale === 'week' ? 7 : 31;
  const maxPage = Math.max(0, Math.ceil(allDays.length / windowSize) - 1);
  const clampedPage = Math.min(pageOffset, maxPage);
  const endIdx = Math.max(0, allDays.length - clampedPage * windowSize);
  const startIdx = Math.max(0, endIdx - windowSize);
  const visibleCells = allDays.slice(startIdx, endIdx);

  const activeCell =
    visibleCells.find((c) => c.date === selectedDate) ??
    visibleCells[visibleCells.length - 1] ??
    null;

  const rate7d = data ? formatPercent(data.completionRate7d.completed, data.completionRate7d.total) : 0;
  const ratePrev7d = data
    ? formatPercent(data.completionRatePrev7d.completed, data.completionRatePrev7d.total)
    : 0;
  const rateDelta = rate7d - ratePrev7d;
  const focusDelta = data ? data.focusMinutes30d - data.focusMinutesPrev30d : 0;
  const earlyDelta = data ? data.earlyEnds30d - data.earlyEndsPrev30d : 0;

  return (
    <div class="stack-lg">
      <header class="page-header">
        <div>
          <p class="eyebrow">W4 · 我的节奏（时区 {tz} · 近 62 天）</p>
          <h1 class="page-title">我的节奏</h1>
          <p class="page-subtitle">
            {data?.headline || '按你的浏览器本地时区汇总近 7 天完成率、30 天专注与周/月趋势。'}
          </p>
        </div>
        <div class="header-actions">
          <button type="button" class="btn" onClick={() => void handleExport('csv')}>
            导出 CSV
          </button>
          <button type="button" class="btn" onClick={() => void handleExport('json')}>
            导出 JSON
          </button>
          <button type="button" class="btn btn-primary" onClick={() => void loadRhythm()} disabled={loading}>
            刷新
          </button>
        </div>
      </header>

      {errorText && (
        <div class="notice notice-crimson" role="alert">
          <span>{errorText}</span>
        </div>
      )}

      {loading || !data ? (
        <div class="paper-card muted">正在汇总节奏统计…</div>
      ) : (
        <div class="stack-lg">
          <section class="paper-card stack-md">
            <div class="row-wrap">
              <CompletionRing percent={rate7d} label="近 7 天完成率" />
              <div class="stack-sm">
                <div class="eyebrow">近 7 天完成</div>
                <div class="numeral numeral-lg">
                  {data.completionRate7d.completed}/{data.completionRate7d.total} 次
                </div>
                <div>
                  <span
                    class={`tag ${rateDelta > 0 ? 'tag-jade' : rateDelta < 0 ? 'tag-brass' : 'tag-stone'}`}
                  >
                    {rateDelta > 0
                      ? `比前 7 天高 ${rateDelta} 个百分点`
                      : rateDelta < 0
                        ? `比前 7 天低 ${Math.abs(rateDelta)} 个百分点`
                        : '与前 7 天持平'}
                  </span>
                </div>
              </div>
            </div>

            <hr class="divider" />

            <div class="grid-3">
              <div>
                <div class="eyebrow">连续达成</div>
                <div class="numeral numeral-md">{data.streakDays} 天</div>
              </div>
              <div>
                <div class="eyebrow">近 30 天专注</div>
                <div class="numeral numeral-md">{formatMinutes(data.focusMinutes30d)}</div>
                <div class="muted">
                  较前 30 天 {focusDelta >= 0 ? `+${focusDelta}` : focusDelta} 分钟
                </div>
              </div>
              <div>
                <div class="eyebrow">近 30 天提前结束</div>
                <div class="numeral numeral-md">{data.earlyEnds30d} 次</div>
                <div class="muted">
                  较前 30 天 {earlyDelta > 0 ? `+${earlyDelta}` : earlyDelta} 次
                </div>
              </div>
            </div>
          </section>

          <section class="paper-card stack-md">
            <div class="row-between">
              <div class="segmented" role="tablist" aria-label="统计尺度">
                <button
                  type="button"
                  role="tab"
                  aria-selected={scale === 'week'}
                  class={`segmented-btn${scale === 'week' ? ' is-active' : ''}`}
                  onClick={() => {
                    setScale('week');
                    setPageOffset(0);
                  }}
                >
                  周视图
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={scale === 'month'}
                  class={`segmented-btn${scale === 'month' ? ' is-active' : ''}`}
                  onClick={() => {
                    setScale('month');
                    setPageOffset(0);
                  }}
                >
                  月视图
                </button>
              </div>

              <div class="row-wrap">
                <button
                  type="button"
                  class="btn btn-sm"
                  disabled={clampedPage >= maxPage}
                  onClick={() => setPageOffset((p) => Math.min(maxPage, p + 1))}
                >
                  上一{scale === 'week' ? '周' : '月'}
                </button>
                <span class="mono muted">
                  {visibleCells.length > 0
                    ? `${visibleCells[0].date} ~ ${visibleCells[visibleCells.length - 1].date}`
                    : '暂无区间'}
                </span>
                <button
                  type="button"
                  class="btn btn-sm"
                  disabled={clampedPage <= 0}
                  onClick={() => setPageOffset((p) => Math.max(0, p - 1))}
                >
                  下一{scale === 'week' ? '周' : '月'}
                </button>
              </div>
            </div>

            <RhythmBarChart
              cells={visibleCells}
              scale={scale}
              selectedDate={activeCell?.date ?? null}
              onSelectDate={setSelectedDate}
            />

            <div class="row-between">
              <div class="row-wrap">
                <span class="tag tag-jade">完成</span>
                <span class="tag tag-brass">提前结束</span>
                <span class="tag tag-stone">未执行</span>
              </div>
              {activeCell && (
                <div class="muted">
                  <strong>{activeCell.date}</strong>：计划 {activeCell.planned} · 完成{' '}
                  {activeCell.completed} · 提前结束 {activeCell.earlyEnded} · 专注{' '}
                  {activeCell.focusMinutes} 分钟
                </div>
              )}
            </div>
          </section>

          <section class="grid-2">
            <div class="paper-card stack-md">
              <h2 class="section-title">各计划近 30 天达成</h2>
              {data.plans.length === 0 ? (
                <p class="muted">近 30 天暂无计划执行样本。</p>
              ) : (
                <div class="stack-md">
                  {data.plans.map((p) => {
                    const pct = formatPercent(p.completed, p.total);
                    return (
                      <div key={p.planId} class="stack-sm">
                        <div class="row-between">
                          <span>
                            <strong>{p.name}</strong> <span class="muted">· {p.type}</span>
                          </span>
                          <span class="numeral">
                            {p.completed}/{p.total}（{pct}%）
                          </span>
                        </div>
                        <progress class="ink-progress" value={pct} max={100} />
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            <div class="ink-block stack-md">
              <div>
                <div class="eyebrow ink-block-accent">近 7 天分心时长（唯一墨块 · 移动端观测汇总）</div>
                <div class="numeral numeral-lg">
                  {formatMinutes(data.distraction?.minutes7d ?? 0)}
                </div>
              </div>
              {data.distraction && data.distraction.topSources.length > 0 ? (
                <div class="stack-sm">
                  {data.distraction.topSources.map((src) => (
                    <div key={src.appName} class="row-between">
                      <span>{src.appName}</span>
                      <span class="mono">{src.minutes} 分钟</span>
                    </div>
                  ))}
                </div>
              ) : (
                <p class="muted">近 7 天暂无分心来源记录（应用观测数据来自移动端上报）。</p>
              )}
            </div>
          </section>
        </div>
      )}
    </div>
  );
}
