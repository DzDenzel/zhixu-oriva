import test from 'node:test';
import assert from 'node:assert/strict';
import JSZip from 'jszip';
import { mkdtemp, readFile, rm, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { runInNewContext } from 'node:vm';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { validateManifest, validatePackage, buildExample } from '../tools/toolkit.mjs';

const fixture = async () => JSON.parse(await readFile(new URL('../examples/focus-advice/plugin.json', import.meta.url), 'utf8'));
const form = m => m.contributions.find(item => item.kind === 'FormSection');
async function zip(entries) {
  const z = new JSZip();
  for (const [name, content] of Object.entries(entries)) z.file(name, content, { createFolders: false });
  return z.generateAsync({ type: 'nodebuffer' });
}
const payload = async (source = 'function entryPoint(event,host) { return {}; }') => ({ 'plugin.json': JSON.stringify(await fixture()), 'index.js': source });

test('accepts host contract v2, legacy contribution ActionRow and three editable field types', async () => {
  const m = validateManifest(await fixture());
  assert.equal(m.entryPoint, 'entryPoint'); assert.equal(m.contributions[0].slot, 'plan.detail.protection');
  assert.deepEqual(form(m).fields.map(field => field.kind), ['switch', 'choice', 'text']);
});
for (const [name, mutate, expected] of [
  ['DEX/old contract is rejected by JS authoring tooling', m => m.type = 'dex', /type.*js/],
  ['unknown capability', m => m.requiredCapabilities = ['NETWORK'], /capability/],
  ['duplicate capability', m => m.requiredCapabilities = ['FEEDBACK', 'FEEDBACK'], /duplicate.*capability/],
  ['missing contract version', m => delete m.contractVersion, /contractVersion/],
  ['entry identifier injection', m => m.entryPoint = 'entryPoint();evil()', /entryPoint/],
  ['output path escape', m => m.id = '../escape', /manifest id/],
  ['duplicate contribution id', m => m.contributions[1].id = m.contributions[0].id, /duplicate.*contribution/],
  ['duplicate form field id', m => form(m).fields.push({ ...form(m).fields[0] }), /duplicate.*field/],
  ['switch string default', m => form(m).fields[0].value = 'true', /switch.*boolean/],
  ['choice outside options', m => form(m).fields[1].value = 'unknown', /choice.*option/],
  ['unsupported form slot', m => form(m).slot = 'plan_edit', /FormSection slot/],
  ['arbitrary HTML contribution', m => m.contributions = [{ id: 'evil', kind: 'Html', slot: 'plan.detail.protection' }], /contribution kind/],
  ['undeclared system capability', m => m.contributions.push({ id: 'grant', kind: 'CapabilityRequest', slot: 'plan.detail.protection', reason: 'why', capabilityId: 'ACCESSIBILITY', actionId: 'grant' }), /Undeclared/],
]) test(`rejects ${name}`, async () => { const m = await fixture(); mutate(m); assert.throws(() => validateManifest(m), expected); });

test('unsigned ZIP structure is validated with no claim of trust', async () => {
  const result = await validatePackage(await zip(await payload()));
  assert.equal(result.signature, 'ABSENT'); assert.deepEqual(result.entries, ['index.js', 'plugin.json']);
});
test('fake signature files never produce a verified claim', async () => {
  const result = await validatePackage(await zip({ ...await payload(), 'META-INF/FAKE.SF': 'not a signature' }));
  assert.equal(result.signature, 'UNVERIFIED');
});
test('plugin.json is the only manifest entry', async () => {
  await assert.rejects(validatePackage(await zip({ 'manifest.json': JSON.stringify(await fixture()), 'index.js': 'function entryPoint() {}' })), /plugin.json/);
});
test('missing payload is rejected', async () => {
  await assert.rejects(validatePackage(await zip({ 'plugin.json': JSON.stringify(await fixture()) })), /index.js/);
});
for (const [name, source, expected] of [
  ['invalid syntax', 'function {', /JavaScript/],
  ['missing entry', 'function other() {}', /entryPoint/],
  ['non-callable entry', 'const entryPoint = 42;', /callable/],
]) test(`rejects ${name}`, async () => await assert.rejects(validatePackage(await zip(await payload(source))), expected));

for (const badName of ['../evil.txt', './index.js', 'assets/../evil.txt', 'C:/evil.txt', 'assets\\evil.txt']) {
  test(`rejects raw unsafe ZIP name ${badName}`, async () => {
    await assert.rejects(validatePackage(await zip({ ...await payload(), [badName]: 'evil' })), /unsafe.*path/);
  });
}
test('rejects duplicate raw entries before ZIP normalization', async () => {
  const bytes = await zip({ ...await payload(), 'other.js': 'function other() {}' });
  const needle = Buffer.from('other.js');
  for (let p = bytes.indexOf(needle); p !== -1; p = bytes.indexOf(needle, p + needle.length)) bytes.set(Buffer.from('index.js'), p);
  await assert.rejects(validatePackage(bytes), /duplicate.*archive/);
});
test('build rejects TS exports which cannot be called', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'oriva-noncallable-'));
  try {
    await mkdir(join(dir, 'src'));
    await writeFile(join(dir, 'plugin.json'), JSON.stringify(await fixture()), 'utf8');
    await writeFile(join(dir, 'src/index.ts'), 'export const entryPoint = 42;', 'utf8');
    await assert.rejects(buildExample({ exampleDir: dir, outDir: join(dir, 'dist') }), /entryPoint.*callable/s);
  } finally { await rm(dir, { recursive: true, force: true }); }
});
test('CLI missing package exits unsuccessfully with a useful message', () => {
  const result = spawnSync(process.execPath, [fileURLToPath(new URL('../tools/validate.mjs', import.meta.url)), 'missing.pljs'], { encoding: 'utf8' });
  assert.equal(result.status, 1); assert.match(result.stderr, /Local validation failed:.*ENOENT/s);
});

test('real reproducible ES2020 bundle executes event-first over string JNI bridge', async () => {
  const outDir = await mkdtemp(join(tmpdir(), 'oriva-sdk-bundle-'));
  try {
    const options = { exampleDir: new URL('../examples/focus-advice/', import.meta.url), outDir };
    const first = await buildExample(options); const bytes = await readFile(first.path);
    const second = await buildExample(options); assert.deepEqual(await readFile(second.path), bytes);
    const archive = await JSZip.loadAsync(bytes);
    assert.deepEqual(Object.keys(archive.files).sort(), ['index.js', 'plugin.json']);
    const context = {}; runInNewContext(await archive.file('index.js').async('string'), context, { timeout: 500 });
    assert.equal(typeof context.entryPoint, 'function');
    const messages = [];
    const snapshot = { today: '2026-10-08', arrangements: ['阅读'], plans: [{ id: 7, name: '读一章', enabled: true, inWindow: true, remainingMinutes: 20 }] };
    const host = { postMessage(raw) {
      assert.equal(typeof raw, 'string'); const request = JSON.parse(raw); messages.push(request);
      return JSON.stringify(request.op === 'query.today' ? { ok: true, data: snapshot } : { ok: false, error: 'Capability unavailable', code: 'UNAVAILABLE' });
    } };
    const load = context.entryPoint({ kind: 'load' }, host);
    assert.equal(load.message, '先专注「读一章」，再处理下一项安排。');
    assert.deepEqual(messages, [{ op: 'query.today', args: {} }]);
    const run = context.entryPoint({ kind: 'action', planId: 7, actionId: 'advice.run' }, host);
    assert.equal(run.message, load.message); assert.deepEqual(JSON.parse(JSON.stringify(run.snapshot)), snapshot);
    assert.equal(messages.at(-1).op, 'feedback');
    const nativeRun = context.entryPoint({ kind: 'action', planId: 7, actionId: 'advice.run', fieldId: null, value: null }, host);
    assert.equal(nativeRun.message, load.message, 'Native null field must remain a normal action');
    const disabled = context.entryPoint({ kind: 'action', planId: 7, actionId: 'advice.run', fieldId: null, configuration: { enabled: 'false' } }, host);
    assert.equal(disabled.message, '专注建议已关闭，可在计划编辑页开启。');
    const configured = context.entryPoint({ kind: 'action', planId: 7, actionId: 'advice.run', configuration: { style: 'gentle', note: '先读20分钟' } }, host);
    assert.match(configured.message, /慢慢来/); assert.match(configured.message, /先读20分钟/);
    for (const [fieldId, actionId, value, expected] of [
      ['enabled', 'advice.enabled.changed', 'false', '已保存：关闭专注建议。'],
      ['style', 'advice.style.changed', 'gentle', '已保存：温和风格。慢慢来，先完成一件小事。'],
      ['note', 'advice.note.changed', '阅读20分钟', '已保存提示语：阅读20分钟'],
      ['note', 'advice.note.changed', '', '已保存：清空专注提示语。'],
    ]) {
      const changed = context.entryPoint({ kind: 'action', planId: 7, fieldId, actionId, value }, host);
      assert.equal(changed.message, expected); assert.equal(changed.snapshot.today, '2026-10-08');
      assert.equal(messages.at(-1).args.code, expected);
    }
    const before = messages.length;
    const invalid = context.entryPoint({ kind: 'action', fieldId: 'style', actionId: 'advice.style.changed', value: 'evil' }, host);
    assert.equal(invalid.message, '配置未保存：字段或选项不匹配。');
    assert.equal(messages.length, before + 1); // Only read-only query; no invalid feedback emitted.
    const absent = context.entryPoint({ kind: 'load' }, { postMessage: () => JSON.stringify({ ok: true, data: null }) });
    assert.equal(absent.message, '暂无今日缓存，请先在今日页查看安排。');
    const denied = context.entryPoint({ kind: 'load' }, { postMessage: () => JSON.stringify({ ok: false, error: 'Undeclared QUERY_TODAY' }) });
    assert.match(denied.message, /Undeclared QUERY_TODAY/); assert.equal(denied.snapshot, null);
  } finally { await rm(outDir, { recursive: true, force: true }); }
});
