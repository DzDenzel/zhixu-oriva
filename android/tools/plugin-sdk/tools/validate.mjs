import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { validatePackage } from './toolkit.mjs';
try {
  const path = process.argv[2] ?? fileURLToPath(new URL('../dist/plugin-focus-advice-0.1.0.unsigned.pljs', import.meta.url));
  console.log(JSON.stringify({ path, ...await validatePackage(await readFile(path)) }, null, 2));
  console.log('Structure validated. Signature trust must be checked with JarFile/native host.');
} catch (failure) { console.error(`Local validation failed: ${failure.message}`); process.exitCode = 1; }
