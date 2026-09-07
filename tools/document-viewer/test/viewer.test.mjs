import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { markdown, plain, workbookFixture } from './fixtures.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const assets = resolve(root, '../../app/src/main/assets/viewer');
let source, browser, server, base;
before(async () => {
  await mkdir(resolve(root, 'test-output'), { recursive: true });
  server = createServer(async (req, res) => {
    const path = new URL(req.url, 'http://localhost').pathname;
    if (path === '/document') { res.setHeader('Content-Type', 'application/octet-stream'); res.end(source); return; }
    const file = path.replace('/viewer/', '');
    if (!/^[a-zA-Z0-9_.-]+$/.test(file)) { res.writeHead(404); res.end(); return; }
    try {
      const bytes = await readFile(resolve(assets, file));
      res.setHeader('Content-Type', ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.wasm': 'application/wasm' })[extname(file)] || 'text/plain');
      res.end(bytes);
    } catch { res.writeHead(404); res.end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
  browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL || 'chrome', headless: true });
});
after(async () => { await browser?.close(); server?.close(); });

async function open(format, bytes) {
  source = bytes;
  const page = await browser.newPage({ viewport: { width: 1100, height: 1000 } });
  await page.goto(`${base}/viewer/index.html?format=${format}`);
  await page.waitForFunction(() => ['ready', 'error'].includes(document.body.dataset.state), { timeout: 60000 });
  assert.equal(await page.locator('body').getAttribute('data-state'), 'ready', await page.locator('#status').textContent());
  return page;
}
async function screenshot(page, name) { await page.screenshot({ path: resolve(root, `test-output/${name}.png`), fullPage: true }); }

test('HWP and HWPX render paper geometry, table rules and selectable text', async () => {
  for (const format of ['hwp', 'hwpx']) {
    const page = await open(format, await readFile(resolve(root, `test/fixtures/table.${format}`)));
    await screenshot(page, format);
    await writeFile(resolve(root, `test-output/${format}.svg`), await page.locator('.paper').innerHTML());
    assert.equal(await page.locator('.paper > svg').count(), 1);
    assert.ok(await page.locator('svg text').count() > 5);
    assert.ok(await page.locator('svg path, svg line, svg rect, svg polyline, svg polygon').count() > 5);
    const before = await page.locator('#zoom-label').textContent();
    await page.locator('#zoom-in').click();
    assert.notEqual(await page.locator('#zoom-label').textContent(), before);
    await page.close();
  }
});

test('HWPX embedded images survive sanitization', async () => {
  const page = await open('hwpx', await readFile(resolve(root, 'test/fixtures/image.hwpx')));
  assert.ok(await page.locator('svg image').count() > 0);
  await screenshot(page, 'hwpx-image');
  await page.close();
});

test('legacy PPT and PPTX retain slide geometry and can navigate', async () => {
  for (const [format, file] of [['ppt', 'sample.ppt'], ['pptx', 'showcase.pptx']]) {
    const page = await open(format, await readFile(resolve(root, `test/fixtures/${file}`)));
    const box = await page.locator('.paper > svg').boundingBox();
    assert.ok(box.width > box.height, 'Slide aspect ratio must remain landscape');
    assert.ok(await page.locator('svg text, svg path, svg rect, svg image').count() > 1);
    await screenshot(page, format);
    if (await page.locator('#next').isEnabled()) {
      await page.locator('#next').click();
      await page.waitForFunction(() => document.body.dataset.state === 'ready');
      assert.equal(await page.locator('#page-number').inputValue(), '2');
      await page.locator('#previous').click();
      assert.equal(await page.locator('#page-number').inputValue(), '1');
    }
    await page.close();
  }
});

test('Markdown renders semantics, code and tables without executing document HTML', async () => {
  const page = await open('md', Buffer.from(markdown));
  assert.equal(await page.locator('h1').textContent(), '회의 기록');
  assert.equal(await page.locator('article strong').textContent(), '중요한 내용');
  assert.equal(await page.locator('blockquote').count(), 1);
  assert.equal(await page.locator('article table').count(), 1);
  assert.equal(await page.locator('pre code').count(), 1);
  assert.equal(await page.locator('article script, article style, article [href]').count(), 0);
  assert.equal(await page.evaluate(() => window.compromised), undefined);
  await screenshot(page, 'markdown');
  await page.close();
});

test('TXT preserves indentation, blank lines, tabs and literal markup in UTF-8/UTF-16/Korean', async () => {
  for (const bytes of [Buffer.from(plain), Buffer.concat([Buffer.from([255, 254]), Buffer.from(plain, 'utf16le')])]) {
    const page = await open('txt', bytes);
    assert.equal(await page.locator('.text-document').textContent(), plain);
    await screenshot(page, 'text');
    await page.close();
  }
  const page = await open('txt', Buffer.from([0xc7, 0xd1, 0xb1, 0xdb]));
  assert.equal(await page.locator('.text-document').textContent(), '한글');
  await page.close();
});

test('XLS/XLSX show merged cells, formatted values, row/column ranges and sheet tabs', async () => {
  for (const format of ['xls', 'xlsx']) {
    const bytes = workbookFixture(format === 'xls' ? 'biff8' : 'xlsx');
    await writeFile(resolve(root, `test-output/workbook.${format}`), bytes);
    const page = await open(format, bytes);
    assert.equal(await page.locator('[data-address="A1"]').getAttribute('colspan'), '4');
    assert.equal(await page.locator('[data-address="B3"]').textContent(), '1,234.50');
    assert.equal(await page.locator('[data-address="C3"]').textContent(), '25%');
    assert.equal(await page.locator('[data-address="B4"]').textContent(), '2023-03-15');
    if (format === 'xlsx') {
      assert.equal(await page.locator('[data-address="A1"]').evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(33, 115, 70)');
      assert.equal(await page.locator('[data-address="A1"]').evaluate(el => getComputedStyle(el).fontWeight), '700');
      await page.locator('[data-address="D3"]').focus();
      assert.match(await page.locator('#cell-value').textContent(), /B3\*2/);
    }
    await screenshot(page, format);
    await page.locator('#next').click();
    assert.equal(await page.locator('[data-address="A201"]').textContent(), '다음 구간');
    await page.locator('#column-window').selectOption('1');
    assert.equal(await page.locator('[data-address="CM1"]').textContent(), '먼 열의 값');
    await page.getByRole('tab', { name: '메모', exact: true }).click();
    assert.equal(await page.locator('[data-address="A1"]').textContent(), '둘째 시트');
    await page.close();
  }
});

test('damaged documents produce an explicit error rather than a fake text preview', async () => {
  source = Buffer.from('not a document');
  const page = await browser.newPage();
  await page.goto(`${base}/viewer/index.html?format=hwp`);
  await page.waitForFunction(() => document.body.dataset.state === 'error');
  assert.match(await page.locator('#status').textContent(), /텍스트 보기/);
  assert.equal(await page.locator('.paper').count(), 0);
  await page.close();
});
