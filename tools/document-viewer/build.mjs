import { build } from 'esbuild';
import { mkdir, copyFile, readFile, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));
const output = resolve(root, '../../app/src/main/assets/viewer');
await mkdir(output, { recursive: true });
await build({
  absWorkingDir: root, entryPoints: ['src/viewer.js'], bundle: true,
  outfile: resolve(output, 'viewer.js'), format: 'esm', platform: 'browser',
  target: ['chrome90'], minify: true, legalComments: 'linked',
});
for (const file of ['index.html', 'viewer.css']) {
  await copyFile(resolve(root, 'src', file), resolve(output, file));
}
await copyFile(resolve(root, 'node_modules/@rhwp/core/rhwp_bg.wasm'), resolve(output, 'rhwp_bg.wasm'));
const lock = JSON.parse(await readFile(resolve(root, 'package-lock.json'), 'utf8'));
const notices = ['Notesis document viewer — third-party licenses',
  'This product uses the HWP file format specification published by Hancom.\n'];
for (const [path, info] of Object.entries(lock.packages)) {
  if (!path || info.dev) continue;
  notices.push(`\n${path.replace('node_modules/', '')} ${info.version} (${info.license || 'see below'})\n`);
  let found = false;
  for (const file of ['LICENSE', 'LICENSE.md', 'LICENSE.txt', 'LICENSE-MIT', 'LICENSE-APACHE', 'NOTICE']) {
    try { notices.push(await readFile(resolve(root, path, file), 'utf8')); found = true; } catch {}
  }
  if (!found) throw new Error(`Missing license for ${path}`);
}
await writeFile(resolve(output, 'THIRD_PARTY_NOTICES.txt'), notices.join('\n'));
console.log(`Offline viewer built in ${output}`);
