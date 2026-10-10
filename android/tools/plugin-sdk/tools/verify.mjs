import { spawnSync } from 'node:child_process';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
const sdk = fileURLToPath(new URL('../', import.meta.url));
const evidence = resolve(process.argv[2] ?? resolve(sdk, '../../../work/req028-029-20261008/sdk-evidence'));
await mkdir(evidence, { recursive: true });
const stages = [
  ['typecheck', ['node_modules/typescript/bin/tsc', '--noEmit']],
  ['tests', ['--test', '--test-reporter=tap', 'test/bridge.test.mjs', 'test/toolkit.test.mjs']],
  ['build', ['tools/build.mjs']], ['validate', ['tools/validate.mjs']], ['author-preview', ['tools/preview.mjs']],
];
const results = [];
for (const [name, args] of stages) {
  const run = spawnSync(process.execPath, args, { cwd: sdk, encoding: 'utf8', timeout: 30000 });
  const output = `${run.stdout ?? ''}${run.stderr ?? ''}${run.error ? run.error.message + '\n' : ''}`;
  await writeFile(resolve(evidence, `${name}.log`), output, 'utf8');
  const item = { name, command: [process.execPath, ...args], exitCode: run.status ?? 1, signal: run.signal, log: `${name}.log` };
  if (name === 'tests') {
    item.tests = Number(output.match(/^# tests (\d+)$/m)?.[1] ?? 0);
    item.passed = Number(output.match(/^# pass (\d+)$/m)?.[1] ?? 0);
    item.failed = Number(output.match(/^# fail (\d+)$/m)?.[1] ?? 0);
  }
  results.push(item); console.log(`${name}: exit ${item.exitCode}`);
  if (item.exitCode !== 0) { console.log(output); break; }
}
const versions = {};
for (const name of ['esbuild', 'typescript', 'jszip']) versions[name] = JSON.parse(await readFile(resolve(sdk, `node_modules/${name}/package.json`), 'utf8')).version;
const passed = results.length === stages.length && results.every(item => item.exitCode === 0);
await writeFile(resolve(evidence, 'verification.json'), JSON.stringify({ node: process.version, versions, passed,
  scope: 'Official SDK local typing, messages, real bundle and author preview; device/QuickJS acceptance is separate', results }, null, 2) + '\n', 'utf8');
if (!passed) process.exitCode = 1;
