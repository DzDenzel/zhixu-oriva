import { writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { buildExample } from './toolkit.mjs';
try {
  const metadata = await buildExample({ exampleDir: new URL('../examples/focus-advice/', import.meta.url),
    outDir: fileURLToPath(new URL('../dist/', import.meta.url)) });
  await writeFile(new URL('../dist/artifact.json', import.meta.url), JSON.stringify(metadata, null, 2) + '\n', 'utf8');
  console.log(JSON.stringify(metadata, null, 2));
  console.log('Unsigned authoring package built; sign with tools/sign-package.ps1 before installation.');
} catch (failure) { console.error(`Build failed: ${failure.message}`); process.exitCode = 1; }
