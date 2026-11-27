import type { RhythmDayCell } from '../types';

export interface CompletionRingProps {
  percent: number;
  label: string;
}

/**
 * 纯 SVG 7 日完成率环（零内联 style=""，以 stroke-dasharray/dashoffset 几何属性呈现）
 */
export function CompletionRing({ percent, label }: CompletionRingProps) {
  const clamped = Math.max(0, Math.min(100, Math.round(percent)));
  const r = 46;
  const circumference = 2 * Math.PI * r;
  const dashOffset = circumference * (1 - clamped / 100);

  return (
    <svg class="ring-svg" viewBox="0 0 116 116" role="img" aria-label={`${label} ${clamped}%`}>
      <circle cx="58" cy="58" r={r} class="ring-track" />
      <circle
        cx="58"
        cy="58"
        r={r}
        class="ring-value"
        stroke-dasharray={circumference.toFixed(2)}
        stroke-dashoffset={dashOffset.toFixed(2)}
        transform="rotate(-90 58 58)"
      />
      <text x="58" y="58" class="ring-text">
        {clamped}%
      </text>
    </svg>
  );
}

export interface RhythmBarChartProps {
  cells: RhythmDayCell[];
  scale: 'week' | 'month';
  selectedDate: string | null;
  onSelectDate: (date: string) => void;
}

/**
 * 纯 SVG 周/月堆叠柱状图（完成草绿 / 提前结束琥珀 / 未执行淡墨）
 * 全部几何属性直接绑定 SVG 属性，零 style="" 内联样式。
 */
export function RhythmBarChart({
  cells,
  scale,
  selectedDate,
  onSelectDate,
}: RhythmBarChartProps) {
  const width = 700;
  const height = 210;
  const padLeft = 16;
  const padRight = 16;
  const padTop = 18;
  const padBottom = 34;
  const plotW = width - padLeft - padRight;
  const plotH = height - padTop - padBottom;

  const maxStack = Math.max(
    1,
    ...cells.map((c) => Math.max(c.planned, c.completed + c.earlyEnded + c.missed)),
  );
  const count = Math.max(1, cells.length);
  const step = plotW / count;
  const barW = scale === 'week' ? Math.min(34, step * 0.45) : Math.max(7, step * 0.62);

  return (
    <svg
      class="rhythm-chart-svg"
      viewBox={`0 0 ${width} ${height}`}
      role="img"
      aria-label={scale === 'week' ? '周执行节奏柱状图' : '月执行节奏柱状图'}
    >
      <line x1={padLeft} y1={padTop} x2={width - padRight} y2={padTop} class="chart-grid-line" />
      <line
        x1={padLeft}
        y1={padTop + plotH / 2}
        x2={width - padRight}
        y2={padTop + plotH / 2}
        class="chart-grid-line"
      />
      <line
        x1={padLeft}
        y1={padTop + plotH}
        x2={width - padRight}
        y2={padTop + plotH}
        class="chart-grid-line"
      />

      {cells.map((cell, index) => {
        const cx = padLeft + index * step + step / 2;
        const x = cx - barW / 2;
        const baseY = padTop + plotH;

        const completedH = (cell.completed / maxStack) * plotH;
        const earlyH = (cell.earlyEnded / maxStack) * plotH;
        const missedH = (cell.missed / maxStack) * plotH;

        const yCompleted = baseY - completedH;
        const yEarly = yCompleted - earlyH;
        const yMissed = yEarly - missedH;

        const dayNum = Number(cell.date.slice(8, 10));
        const showLabel =
          scale === 'week' ||
          dayNum === 1 ||
          dayNum === 8 ||
          dayNum === 15 ||
          dayNum === 22 ||
          dayNum === 29 ||
          cell.isToday;
        const labelText = scale === 'week' ? cell.date.slice(5) : String(dayNum);
        const isSelected = selectedDate === cell.date;

        return (
          <g key={cell.date} onClick={() => onSelectDate(cell.date)}>
            {isSelected && (
              <rect
                x={(cx - step / 2 + 1).toFixed(1)}
                y={padTop - 4}
                width={Math.max(4, step - 2).toFixed(1)}
                height={plotH + 8}
                rx="6"
                class="chart-bar-selected"
              />
            )}

            {completedH > 0 && (
              <rect
                x={x.toFixed(1)}
                y={yCompleted.toFixed(1)}
                width={barW.toFixed(1)}
                height={Math.max(2, completedH).toFixed(1)}
                rx="3"
                class="chart-bar-completed"
              />
            )}

            {earlyH > 0 && (
              <rect
                x={x.toFixed(1)}
                y={yEarly.toFixed(1)}
                width={barW.toFixed(1)}
                height={Math.max(2, earlyH).toFixed(1)}
                rx="2"
                class="chart-bar-early"
              />
            )}

            {missedH > 0 && (
              <rect
                x={x.toFixed(1)}
                y={yMissed.toFixed(1)}
                width={barW.toFixed(1)}
                height={Math.max(2, missedH).toFixed(1)}
                rx="2"
                class="chart-bar-missed"
              />
            )}

            <rect
              x={(cx - step / 2).toFixed(1)}
              y={padTop}
              width={step.toFixed(1)}
              height={plotH + padBottom}
              class="chart-bar-hit"
            >
              <title>
                {`${cell.date}：完成 ${cell.completed} / 提前结束 ${cell.earlyEnded} / 未执行 ${cell.missed} / 专注 ${cell.focusMinutes} 分钟`}
              </title>
            </rect>

            {showLabel && (
              <text
                x={cx.toFixed(1)}
                y={height - 10}
                class={cell.isToday ? 'chart-label chart-label-today' : 'chart-label'}
              >
                {cell.isToday ? '今' : labelText}
              </text>
            )}
          </g>
        );
      })}
    </svg>
  );
}
