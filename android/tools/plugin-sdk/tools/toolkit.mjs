import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { build, transform } from 'esbuild';
import JSZip from 'jszip';
import { assertArchivePaths } from './archive-names.mjs';
import { assertGlobalEntry, assertCallableExport } from './entry.mjs';

const runtime = ['OVERLAY', 'HOME', 'HAPTIC', 'FEEDBACK', 'QUERY_TODAY', 'QUERY_PLAN', 'QUERY_REPORT'];
const system = ['USAGE_STATS', 'ACCESSIBILITY', 'DND', 'NOTIFICATIONS', 'LOCATION'];
const capabilities = new Set([...runtime, ...system]);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const requireValue = (condition, message) => { if (!condition) throw new Error(message); };
const nonempty = (value, name) => requireValue(typeof value === 'string' && value.trim().length > 0, `${name} must be a nonempty string`);

/** JS contract-v2 authoring validation; native enforcement remains authoritative. */
export function validateManifest(m) {
  requireValue(object(m), 'manifest must be an object');
  for (const key of ['id', 'name', 'version', 'entryPoint', 'sourceId']) nonempty(m[key], `manifest ${key}`);
  requireValue(/^[a-z][a-z0-9-]*$/.test(m.id), 'manifest id must be a safe lowercase identifier');
  requireValue(/^\d+\.\d+\.\d+$/.test(m.version), 'manifest version must be x.y.z');
  requireValue(m.type === 'js', 'manifest type must be js (JS SDK only)');
  requireValue(m.contractVersion === 2, 'contractVersion must be 2');
  requireValue(/^[A-Za-z_$][\w$]*$/.test(m.entryPoint), 'entryPoint must be a plain JS function identifier');
  requireValue(['OFFICIAL', 'THIRD_PARTY'].includes(m.sourceType), 'sourceType must be OFFICIAL or THIRD_PARTY');
  requireValue(Array.isArray(m.requiredCapabilities), 'requiredCapabilities must be an array');
  const declared = new Set();
  for (const capability of m.requiredCapabilities) {
    requireValue(capabilities.has(capability), `Unknown capability: ${capability}`);
    requireValue(!declared.has(capability), `duplicate capability: ${capability}`); declared.add(capability);
  }
  requireValue(m.contributions === undefined || Array.isArray(m.contributions), 'contributions must be an array');
  const ids = new Set();
  for (const item of m.contributions ?? []) {
    requireValue(object(item), 'contribution must be an object');
    nonempty(item.id, 'contribution id');
    requireValue(!ids.has(item.id), `duplicate contribution id: ${item.id}`); ids.add(item.id);
    requireValue(item.shapeVersion === undefined || item.shapeVersion === 1, 'Unsupported contribution shapeVersion');
    if (item.kind === 'FormSection') {
      requireValue(item.slot === 'plan.edit.configuration', 'FormSection slot must be plan.edit.configuration');
      nonempty(item.title, 'section title');
      requireValue(Array.isArray(item.fields) && item.fields.length > 0, 'section fields must be a nonempty array');
      const fields = new Set();
      for (const field of item.fields) {
        requireValue(object(field), 'field must be an object');
        for (const key of ['id', 'label', 'onEvent']) nonempty(field[key], `field ${key}`);
        requireValue(!fields.has(field.id), `duplicate field id: ${field.id}`); fields.add(field.id);
        if (field.kind === 'switch') requireValue(typeof field.value === 'boolean', 'switch value must be boolean');
        else if (field.kind === 'text') requireValue(typeof field.value === 'string', 'text value must be a string');
        else if (field.kind === 'choice') {
          requireValue(Array.isArray(field.options) && field.options.length > 0, 'choice options are required');
          const values = new Set();
          for (const option of field.options) {
            requireValue(object(option), 'choice option must be an object');
            nonempty(option.value, 'choice option value'); nonempty(option.label, 'choice option label');
            requireValue(!values.has(option.value), 'duplicate choice option value'); values.add(option.value);
          }
          requireValue(typeof field.value === 'string' && values.has(field.value), 'choice value must match an option');
        } else throw new Error(`Unsupported field kind: ${field.kind}`);
      }
    } else {
      requireValue(item.slot === 'plan.detail.protection', 'Unsupported contribution slot');
      if (item.kind === 'ActionRow') {
        nonempty(item.label, 'action label'); nonempty(item.actionId, 'actionId');
        requireValue(item.input === undefined || ['NONE', 'APPLICATION_SINGLE'].includes(item.input), 'Unsupported action input');
      } else if (item.kind === 'StatusLine') {
        nonempty(item.text, 'status text');
        requireValue(item.tone === undefined || ['neutral', 'active', 'warn'].includes(item.tone), 'Unknown status tone');
        if (item.requiresCapability !== undefined) {
          requireValue(system.includes(item.requiresCapability) && declared.has(item.requiresCapability), 'Undeclared status capability');
          nonempty(item.unavailableText, 'unavailableText');
        }
      } else if (item.kind === 'CapabilityRequest') {
        nonempty(item.reason, 'capability reason'); nonempty(item.actionId, 'actionId');
        requireValue(system.includes(item.capabilityId) && declared.has(item.capabilityId), 'Undeclared system capability');
      } else throw new Error(`Unsupported contribution kind: ${item.kind}`);
    }
  }
  return m;
}

/** Structure and JS syntax only. Presence of META-INF is never claimed as proof of signing. */
export async function validatePackage(bytes) {
  assertArchivePaths(bytes);
  requireValue(bytes.length <= 8 * 1024 * 1024, 'Package exceeds SDK 8MiB limit');
  const zip = await JSZip.loadAsync(bytes, { checkCRC32: true });
  requireValue(zip.file('plugin.json') !== null, 'Package is missing plugin.json');
  requireValue(zip.file('index.js') !== null, 'Package is missing index.js');
  for (const entry of Object.values(zip.files)) {
    requireValue(entry.name === 'plugin.json' || entry.name === 'index.js' || entry.name.startsWith('assets/') || entry.name.startsWith('META-INF/'),
      `Unsupported archive entry: ${entry.name}`);
  }
  const manifest = validateManifest(JSON.parse(await zip.file('plugin.json').async('string')));
  const source = await zip.file('index.js').async('string');
  requireValue(source.trim().length > 0, 'index.js must be nonempty');
  try { await transform(source, { loader: 'js', target: 'es2020', logLevel: 'silent' }); }
  catch (failure) { throw new Error(`Invalid JavaScript: ${failure.message}`); }
  assertGlobalEntry(source, manifest.entryPoint);
  return { manifest, signature: Object.keys(zip.files).some(name => /^META-INF\/.*\.(SF|RSA|EC|DSA)$/i.test(name)) ? 'UNVERIFIED' : 'ABSENT',
    entries: Object.values(zip.files).filter(entry => !entry.dir).map(entry => entry.name).sort() };
}

export async function buildExample({ exampleDir, outDir }) {
  const base = exampleDir instanceof URL ? fileURLToPath(exampleDir) : resolve(exampleDir);
  const manifest = validateManifest(JSON.parse(await readFile(resolve(base, 'plugin.json'), 'utf8')));
  assertCallableExport(resolve(base, 'src/index.ts'), manifest.entryPoint);
  const result = await build({
    stdin: { contents: `import { ${manifest.entryPoint} as entry } from './src/index.ts'; export { entry as ${manifest.entryPoint} };`,
      resolveDir: base, loader: 'ts' }, bundle: true, write: false,
    alias: { '@oriva/plugin-sdk': fileURLToPath(new URL('../index.js', import.meta.url)) },
    platform: 'neutral', format: 'iife', globalName: 'orivaPlugin', target: 'es2020',
    legalComments: 'none', charset: 'utf8', logLevel: 'silent',
    footer: { js: `function ${manifest.entryPoint}(event, host) { return orivaPlugin.${manifest.entryPoint}(event, host); }` },
  });
  const zip = new JSZip();
  const options = { date: new Date('2000-01-01T00:00:00.000Z'), createFolders: false };
  zip.file('plugin.json', JSON.stringify(manifest, null, 2) + '\n', options);
  zip.file('index.js', result.outputFiles[0].contents, options);
  const bytes = await zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE', compressionOptions: { level: 9 }, platform: 'DOS' });
  await validatePackage(bytes);
  await mkdir(outDir, { recursive: true });
  const path = resolve(outDir, `plugin-${manifest.id}-${manifest.version}.unsigned.pljs`);
  await writeFile(path, bytes);
  return { path, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), signature: 'ABSENT', target: 'es2020' };
}
