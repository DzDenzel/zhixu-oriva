import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import JSZip from 'jszip';

const sdk = fileURLToPath(new URL('../', import.meta.url));
const evidence = process.argv[2];
if (!evidence) throw new Error('Usage: node tools/signature-test.mjs <evidence-dir> [java-executable]');
const java = process.argv[3] ?? 'C:/Program Files/Java/jdk-21/bin/java.exe';
const destination = resolve(evidence);
await mkdir(destination, { recursive: true });
const packagePath = resolve(sdk, 'dist/plugin-focus-advice-0.1.0.pljs');
const bytes = await readFile(packagePath);
const anchor = resolve(destination, 'official-signer.cer');
const verifier = resolve(sdk, 'tools/VerifySignedPackage.java');
const checks = [];
function verify(name, path, expectedPass, certificate = anchor) {
  const run = spawnSync(java, [verifier, path, certificate], { encoding: 'utf8', timeout: 30000 });
  const log = `${run.stdout ?? ''}${run.stderr ?? ''}${run.error?.message ?? ''}`;
  assert.equal(run.status === 0, expectedPass, `${name}\n${log}`);
  checks.push({ name, path, exitCode: run.status, expectedPass, passed: true, log });
  console.log(`${name}: expected ${expectedPass ? 'accept' : 'reject'}, exit ${run.status}`);
}
verify('official signed payloads', packagePath, true);
const tampered = await JSZip.loadAsync(bytes);
tampered.file('index.js', (await tampered.file('index.js').async('string')) + '\n// unauthorized modification\n');
const tamperedPath = resolve(destination, 'tampered-script.pljs');
await writeFile(tamperedPath, await tampered.generateAsync({ type: 'nodebuffer' }));
verify('script digest tamper', tamperedPath, false);
const appended = await JSZip.loadAsync(bytes);
appended.file('assets/untrusted.txt', 'payload added after signing');
const unsignedExtraPath = resolve(destination, 'unsigned-extra-payload.pljs');
await writeFile(unsignedExtraPath, await appended.generateAsync({ type: 'nodebuffer' }));
verify('unsigned extra payload', unsignedExtraPath, false);
verify('unsigned authoring package', resolve(sdk, 'dist/plugin-focus-advice-0.1.0.unsigned.pljs'), false);
verify('different source anchor', packagePath, false, resolve(destination, 'third-party-signer.cer'));
for (const item of checks) await writeFile(resolve(destination, `signature-${item.name.replaceAll(' ', '-')}.log`), item.log, 'utf8');
const metadata = {
  path: packagePath, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex').toUpperCase(),
  signerSha256: checks[0].log.match(/SIGNER_SHA256=(.+)/)?.[1],
  keystore: resolve(sdk, '../../.plugin-signing/planlock-plugin-dev.p12'),
  source: 'official', alias: 'planlock-plugin-dev', verification: 'java.util.jar.JarFile with X.509 source anchor; all payload entries verified',
  checks: checks.map(({ log, ...item }) => item),
};
await writeFile(resolve(destination, 'signed-artifact.json'), JSON.stringify(metadata, null, 2) + '\n', 'utf8');
await writeFile(resolve(destination, 'plugin-focus-advice-0.1.0.pljs'), bytes);
console.log(JSON.stringify(metadata, null, 2));
