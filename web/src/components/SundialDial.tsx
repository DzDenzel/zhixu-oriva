import type { PlanItem, ScheduleItem } from '../types';
import {
  formatTimeShort,
  getShichenLabel,
  resolvePlanTypeMeta,
  timeToMinutes,
  type PlanTypeOption,
} from '../utils/format';

export interface SundialDialProps {
  plans: PlanItem[];
  todaySchedules: ScheduleItem[];
  planTypes: PlanTypeOption[];
  now?: Date;
}

function polarToCartesian(cx: number, cy: number, radius: number, angleDeg: number) {
  const rad = ((angleDeg - 90) * Math.PI) / 180;
  return {
    x: cx + radius * Math.cos(rad),
    y: cy + radius * Math.sin(rad),
  };
}

function describeArc(cx: number, cy: number, radius: number, startMin: number, endMin: number): string {
  const startAngle = (startMin / 1440) * 360;
  let deltaMin = endMin - startMin;
  if (deltaMin <= 0) deltaMin += 1440;
  deltaMin = Math.max(15, Math.min(deltaMin, 1430));
  const endAngle = startAngle + (deltaMin / 1440) * 360;

  const start = polarToCartesian(cx, cy, radius, startAngle);
  const end = polarToCartesian(cx, cy, radius, endAngle);
  const largeArcFlag = deltaMin > 720 ? 1 : 0;
  return `M ${start.x.toFixed(2)} ${start.y.toFixed(2)} A ${radius} ${radius} 0 ${largeArcFlag} 1 ${end.x.toFixed(2)} ${end.y.toFixed(2)}`;
}

/**
 * 墨序 24 小时日晷表盘（纯 SVG 几何属性，零内联 style=""，严格通过 style-src 'self'）
 */
export function SundialDial({ plans, todaySchedules, planTypes, now = new Date() }: SundialDialProps) {
  const cx = 130;
  const cy = 130;
  const ringR = 98;
  const currentMinutes = now.getHours() * 60 + now.getMinutes();
  const pointerAngle = (currentMinutes / 1440) * 360;
  const pointerInner = polarToCartesian(cx, cy, 56, pointerAngle);
  const pointerOuter = polarToCartesian(cx, cy, 110, pointerAngle);

  const activePlans = plans.filter((p) => p.status === 1);
  const timedSchedules = todaySchedules.filter((s) => !s.allDay && s.startTime && s.endTime);
  const hasAllDay = todaySchedules.some((s) => s.allDay);
  const totalCount = activePlans.length + todaySchedules.length;

  const hh = String(now.getHours()).padStart(2, '0');
  const mm = String(now.getMinutes()).padStart(2, '0');

  const ticks = Array.from({ length: 24 }, (_, hour) => {
    const angle = (hour / 24) * 360;
    const isMajor = hour % 6 === 0;
    const p1 = polarToCartesian(cx, cy, isMajor ? 112 : 114, angle);
    const p2 = polarToCartesian(cx, cy, 121, angle);
    return { hour, isMajor, p1, p2 };
  });

  const numerals = [0, 3, 6, 9, 12, 15, 18, 21].map((hour) => {
    const pos = polarToCartesian(cx, cy, 78, (hour / 24) * 360);
    return { hour, pos };
  });

  return (
    <div class="sundial-wrap">
      <svg
        class="sundial-svg"
        viewBox="0 0 260 260"
        role="img"
        aria-label={`今日 24 小时日晷，当前时间 ${hh}:${mm}，共 ${totalCount} 项安排`}
      >
        <circle cx={cx} cy={cy} r={124} class="sundial-disc" />
        <circle cx={cx} cy={cy} r={ringR} class="sundial-ring-bg" />

        {hasAllDay && <circle cx={cx} cy={cy} r={66} class="sundial-allday-ring" />}

        {activePlans.map((plan) => {
          const meta = resolvePlanTypeMeta(plan.type, planTypes);
          const d = describeArc(
            cx,
            cy,
            ringR,
            timeToMinutes(formatTimeShort(plan.startTime)),
            timeToMinutes(formatTimeShort(plan.endTime)),
          );
          return <path key={`p-${plan.id}`} d={d} class={`sundial-arc ${meta.arcClass}`} />;
        })}

        {timedSchedules.map((sched) => {
          const d = describeArc(
            cx,
            cy,
            ringR,
            timeToMinutes(formatTimeShort(sched.startTime)),
            timeToMinutes(formatTimeShort(sched.endTime)),
          );
          return <path key={`s-${sched.id}`} d={d} class="sundial-arc arc-amber" />;
        })}

        {ticks.map((tick) => (
          <line
            key={tick.hour}
            x1={tick.p1.x.toFixed(1)}
            y1={tick.p1.y.toFixed(1)}
            x2={tick.p2.x.toFixed(1)}
            y2={tick.p2.y.toFixed(1)}
            class={tick.isMajor ? 'sundial-tick-major' : 'sundial-tick'}
          />
        ))}

        {numerals.map((item) => (
          <text
            key={item.hour}
            x={item.pos.x.toFixed(1)}
            y={item.pos.y.toFixed(1)}
            class="sundial-num"
          >
            {item.hour}
          </text>
        ))}

        <line
          x1={pointerInner.x.toFixed(1)}
          y1={pointerInner.y.toFixed(1)}
          x2={pointerOuter.x.toFixed(1)}
          y2={pointerOuter.y.toFixed(1)}
          class="sundial-pointer"
        />
        <circle
          cx={pointerOuter.x.toFixed(1)}
          cy={pointerOuter.y.toFixed(1)}
          r="4"
          class="sundial-pointer-dot"
        />

        <text x={cx} y={108} class="sundial-shichen">
          {getShichenLabel(now)}
        </text>
        <text x={cx} y={141} class="sundial-clock">
          {hh}:{mm}
        </text>
        <text x={cx} y={162} class="sundial-count">
          今日 {totalCount} 项安排
        </text>
      </svg>
    </div>
  );
}
