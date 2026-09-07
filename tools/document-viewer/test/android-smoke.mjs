// Runs against an installed, debuggable Notesis APK on an emulator/test device.
// Native WebView/CDP validation catches issues (e.g. zero viewport height) that
// desktop Chromium cannot reproduce. Only our own app's test cache is written.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { markdown, plain, workbookFixture } from './fixtures.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const adb = process.env.ADB_PATH || 'adb';
const run = args => execFileSync(adb, args, { encoding: 'utf8', windowsHide: true });
const delay = ms => new Promise(r => setTimeout(r, ms));
const output = resolve(root, 'test-output');
await mkdir(output, { recursive: true });
run(['shell', 'am', 'force-stop', 'com.notesis']);
run(['shell', 'run-as', 'com.notesis', 'mkdir', '-p', 'cache/shared']);
let forwarded = false;

async function evaluate(expression, format) {
  const targets = await (await fetch('http://127.0.0.1:9223/json/list')).json();
  const target = targets.find(t => t.url === `https://document.notesis.invalid/viewer/index.html?format=${format}`);
  if (!target) return null;
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true });
    ws.addEventListener('error', reject, { once: true });
  });
  try {
    const response = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('CDP timed out')), 10000);
      ws.addEventListener('message', event => { const msg = JSON.parse(event.data); if (msg.id === 1) { clearTimeout(timer); resolve(msg); } });
    });
    ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, returnByValue: true } }));
    const result = await response;
    if (result.result?.exceptionDetails || result.error) throw new Error(JSON.stringify(result));
    return result.result.result.value;
  } finally { ws.close(); }
}

try {
  const cases = [
    ['hwp', await readFile(resolve(root, 'test/fixtures/table.hwp'))],
    ['hwpx', await readFile(resolve(root, 'test/fixtures/table.hwpx'))],
    ['ppt', await readFile(resolve(root, 'test/fixtures/sample.ppt'))],
    ['pptx', await readFile(resolve(root, 'test/fixtures/showcase.pptx'))],
    ['md', Buffer.from(markdown)], ['txt', Buffer.from(plain)],
    ['xls', workbookFixture('biff8')], ['xlsx', workbookFixture()],
  ];
  for (const [format, bytes] of cases) {
    const name = `notesis-smoke.${format}`;
    // Windows adb's stdin can interpret 0x1A as EOF; push keeps CFB bytes intact.
    const local = resolve(output, name), remote = `/data/local/tmp/${name}`;
    await writeFile(local, bytes);
    run(['push', local, remote]);
    run(['shell', `cat ${remote} | run-as com.notesis tee cache/shared/${name} > /dev/null`]);
    assert.equal(run(['shell', 'run-as', 'com.notesis', 'sha256sum', `cache/shared/${name}`]).split(' ')[0], createHash('sha256').update(bytes).digest('hex'));
    run(['shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-d', `content://com.notesis.files/shared/${name}`, '-n', 'com.notesis/.MainActivity']);
    let snapshot;
    for (let attempt = 0; attempt < 100; attempt++) {
      await delay(300);
      if (!forwarded) {
        const pid = run(['shell', 'pidof', 'com.notesis']).trim();
        if (!run(['shell', 'cat', '/proc/net/unix']).includes(`webview_devtools_remote_${pid}`)) continue;
        run(['forward', 'tcp:9223', `localabstract:webview_devtools_remote_${pid}`]);
        forwarded = true;
      }
      snapshot = await evaluate(`JSON.stringify({ state:document.body.dataset.state, error:document.getElementById('status')?.textContent, height:document.getElementById('viewport')?.clientHeight, text:document.getElementById('surface')?.textContent, svg:document.querySelectorAll('.paper > svg').length, cells:document.querySelectorAll('.worksheet td').length })`, format);
      if (snapshot && ['ready', 'error'].includes(JSON.parse(snapshot).state)) break;
    }
    const state = JSON.parse(snapshot || '{}');
    assert.equal(state.state, 'ready', `${format}: ${state.error || snapshot}`);
    assert.ok(state.height > 300, `${format}: document viewport collapsed (${state.height}px)`);
    if (['hwp', 'hwpx', 'ppt', 'pptx'].includes(format)) assert.equal(state.svg, 1);
    else if (['xls', 'xlsx'].includes(format)) assert.ok(state.cells > 10);
    else assert.ok(state.text?.length > 20);
    const png = execFileSync(adb, ['exec-out', 'screencap', '-p'], { windowsHide: true, maxBuffer: 20 * 1024 * 1024 });
    await writeFile(resolve(output, `android-${format}.png`), png);
    console.log(`PASS Android ${format}: ${state.height}px viewport`);
  }
} finally {
  if (forwarded) run(['forward', '--remove', 'tcp:9223']);
}
