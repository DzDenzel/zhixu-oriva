import { readFile } from 'node:fs/promises';
import { runInNewContext } from 'node:vm';
import JSZip from 'jszip';
// Only the known author-owned example is executed; CLI validation never executes unknown packages.
const zip = await JSZip.loadAsync(await readFile(new URL('../dist/plugin-focus-advice-0.1.0.unsigned.pljs', import.meta.url)));
const context = {}; runInNewContext(await zip.file('index.js').async('string'), context, { timeout: 500 });
const snapshot = { today: '2026-10-08', arrangements: ['09:00 阅读一章'], plans: [
  { id: 7, name: '读一章', enabled: true, inWindow: true, remainingMinutes: 20 },
] };
const requests = [];
const host = { postMessage: raw => {
  const request = JSON.parse(raw); requests.push(request);
  return JSON.stringify(request.op === 'query.today' ? { ok: true, data: snapshot } : { ok: false, error: 'Feedback unavailable in UI action; message is shown by Snackbar' });
} };
console.log('SDK 作者预览：真实打包脚本 / 本地 AssistantSnapshot 夹具 / 字符串桥；不是设备 UI 与真实 QuickJS 验收。');
for (const [step, event] of [
  ['被动加载', { kind: 'load' }],
  ['详情页运行按钮', { kind: 'action', planId: 7, actionId: 'advice.run' }],
  ['关闭建议', { kind: 'action', planId: 7, actionId: 'advice.enabled.changed', fieldId: 'enabled', value: 'false' }],
  ['切换建议风格', { kind: 'action', planId: 7, actionId: 'advice.style.changed', fieldId: 'style', value: 'gentle' }],
  ['修改提示语', { kind: 'action', planId: 7, actionId: 'advice.note.changed', fieldId: 'note', value: '先读20分钟' }],
]) console.log(JSON.stringify({ step, result: context.entryPoint(event, host) }, null, 2));
console.log(JSON.stringify({ requests, contributions: JSON.parse(await zip.file('plugin.json').async('string')).contributions }, null, 2));
