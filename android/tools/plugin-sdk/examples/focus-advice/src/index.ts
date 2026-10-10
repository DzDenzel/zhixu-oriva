import { createHost, HostError } from '@oriva/plugin-sdk';
import type { Host, PluginEvent, PluginResult, TodaySnapshot } from '@oriva/plugin-sdk';

function advice(snapshot: TodaySnapshot | null, configuration: Record<string, string> = {}): string {
  if (configuration.enabled === 'false') return '专注建议已关闭，可在计划编辑页开启。';
  const prefix = configuration.style === 'gentle' ? '慢慢来，先完成一件小事。' : '';
  const note = configuration.note?.trim() ? `提示：${configuration.note.trim()}。` : '';
  if (!snapshot) return '暂无今日缓存，请先在今日页查看安排。';
  const active = snapshot.plans.find(plan => plan.enabled && plan.inWindow);
  if (active) return `${prefix}${note}先专注「${active.name}」，再处理下一项安排。`;
  if (snapshot.arrangements.length > 0) return `${prefix}${note}今日有 ${snapshot.arrangements.length} 项安排，先为第一项留出一个不被打断的时段。`;
  return `${prefix}${note}今天还没有安排，先为一件小事留出专注时间。`;
}

/** The host persists submitted values; this plugin computes advice and visible acknowledgements. */
export function entryPoint(event: PluginEvent, rawHost: Host): PluginResult {
  const host = createHost(rawHost);
  let snapshot: TodaySnapshot | null;
  try { snapshot = host.query.today(); }
  catch (error) {
    return { message: `今日数据暂不可用：${error instanceof Error ? error.message : String(error)}`, snapshot: null };
  }
  let message = advice(snapshot, event.configuration);
  if (event.kind === 'action' && typeof event.fieldId === 'string') {
    if (event.fieldId === 'enabled' && event.actionId === 'advice.enabled.changed' && ['true', 'false'].includes(event.value ?? '')) {
      message = event.value === 'true' ? `已保存：显示专注建议。${advice(snapshot)}` : '已保存：关闭专注建议。';
    } else if (event.fieldId === 'style' && event.actionId === 'advice.style.changed' && ['brief', 'gentle'].includes(event.value ?? '')) {
      message = event.value === 'gentle' ? '已保存：温和风格。慢慢来，先完成一件小事。' : `已保存：简短风格。${advice(snapshot)}`;
    } else if (event.fieldId === 'note' && event.actionId === 'advice.note.changed' && typeof event.value === 'string') {
      message = event.value.trim() ? `已保存提示语：${event.value}` : '已保存：清空专注提示语。';
    } else return { message: '配置未保存：字段或选项不匹配。', snapshot };
  }
  // Loading must stay passive. UI actions return message for Snackbar even if FEEDBACK has no native delegate.
  if (event.kind !== 'load') {
    try { host.feedback(message); }
    catch (error) { if (!(error instanceof HostError)) throw error; }
  }
  return { message, snapshot };
}
