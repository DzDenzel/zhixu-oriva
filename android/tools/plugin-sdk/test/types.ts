import { createHost, HostError } from '@oriva/plugin-sdk';
import type { Host, PluginEntry, MessageRequest, FormField, PluginManifest } from '@oriva/plugin-sdk';
declare const nativeHost: Host;
const client = createHost(nativeHost);
client.query.today(); client.query.today('2026-10-08'); client.query.plan(17); client.query.report('week');
client.home(); client.haptic(); client.feedback('focus-ready'); client.overlay('专注中', 1500);
nativeHost.postMessage(JSON.stringify({ op: 'query.today', args: {} }));
const entry: PluginEntry = (event, host) => ({ message: event.value ?? String(host), snapshot: null });
const fields: FormField[] = [
  { id: 'enabled', kind: 'switch', label: '开关', value: false, onEvent: 'enabled.changed' },
  { id: 'note', kind: 'text', label: '备注', value: '', onEvent: 'note.changed' },
  { id: 'style', kind: 'choice', label: '风格', value: 'brief', onEvent: 'style.changed', options: [{ value: 'brief', label: '简短' }] },
];
const manifest: PluginManifest = { id: 'demo', name: '示例', version: '0.1.0', type: 'js', contractVersion: 2,
  entryPoint: 'entryPoint', sourceId: 'official', sourceType: 'OFFICIAL', requiredCapabilities: ['QUERY_TODAY'],
  contributions: [{ kind: 'FormSection', id: 'config', slot: 'plan.edit.configuration', title: '配置', fields }] };
void entry; void manifest; void new HostError('error');
// @ts-expect-error JNI host accepts JSON strings, never request objects.
nativeHost.postMessage({ op: 'query.today', args: {} });
// @ts-expect-error Network API is not present.
nativeHost.fetch('https://example.invalid');
// @ts-expect-error Filesystem API is not present.
nativeHost.fs.readFile('secret');
// @ts-expect-error Native Context is not present.
nativeHost.context;
// @ts-expect-error Unsupported RPC operation.
const invalid: MessageRequest = { op: 'network', args: {} };
// @ts-expect-error Static switch values must be booleans.
const wrongSwitch: FormField = { id: 'a', kind: 'switch', label: '开关', value: 'true', onEvent: 'a.changed' };
// @ts-expect-error Report period must be known.
client.query.report('year');
void invalid; void wrongSwitch;
