import initHangul, { HwpDocument } from '@rhwp/core';
import { parse as parsePpt, renderSlideToSvg } from '@web-ppt/core';
import { marked } from 'marked';
import { openWorkbook } from './workbook.js';
import { decodeText, inspectZip, lockLinks, MAX_SOURCE, sanitize } from './safety.js';

const $ = id => document.getElementById(id);
const viewport = $('viewport');
const surface = $('surface');
let model;
let index = 0;
let zoom = 1;
let fit = true;
let contentWidth = 794;
let renderSerial = 0;
let activeSheet = 0;
let columnPage = 0;
let workbook;
let sheet;
const columnSelect = document.createElement('select');
columnSelect.id = 'column-window';
columnSelect.setAttribute('aria-label', '표시할 열 범위');
columnSelect.hidden = true;
$('toolbar').append(columnSelect);

function status(message = '', error = false) {
  $('status').textContent = message;
  $('status').hidden = !message;
  $('status').classList.toggle('error', error);
}

function applyZoom() {
  const padding = parseFloat(getComputedStyle(viewport).paddingLeft) * 2;
  if (fit) zoom = Math.min(3, Math.max(.1, (viewport.clientWidth - padding) / contentWidth));
  surface.style.zoom = String(zoom);
  $('zoom-label').textContent = `${Math.round(zoom * 100)}%`;
  $('zoom-out').disabled = zoom <= .1;
  $('zoom-in').disabled = zoom >= 3;
}

function showError(error) {
  console.error('Document renderer:', error);
  status(`문서 배치를 표시하지 못했습니다.\n${String(error?.message || error).slice(0, 240)}\n상단의 텍스트 보기로 내용을 확인할 수 있습니다.`, true);
  document.body.dataset.state = 'error';
}

async function render(page) {
  if (!model) return;
  const serial = ++renderSerial;
  index = Math.max(0, Math.min(model.count - 1, Number.isFinite(page) ? page : 0));
  $('previous').disabled = index === 0;
  $('next').disabled = index === model.count - 1;
  $('page-number').value = index + 1;
  $('page-number').max = model.count;
  $('page-count').textContent = `/ ${model.count}${workbook ? ' 구간' : model.kind === 'slide' ? ' 슬라이드' : ' 페이지'}`;
  document.body.dataset.state = 'rendering';
  try {
    const result = await model.render(index);
    if (serial !== renderSerial) return;
    surface.replaceChildren(result.element);
    contentWidth = result.width;
    applyZoom();
    viewport.scrollTo(0, 0);
    status(workbook ? `${result.rangeLabel} · 수식은 저장된 계산값으로 표시합니다.` : '');
    document.body.dataset.state = 'ready';
  } catch (error) {
    if (serial === renderSerial) showError(error);
  }
}

function svgPage(markup, fallbackWidth) {
  const paper = document.createElement('article');
  paper.className = 'paper';
  paper.innerHTML = sanitize(markup, true);
  lockLinks(paper);
  const svg = paper.querySelector('svg');
  if (!svg) throw new Error('페이지를 그리지 못했습니다.');
  const width = svg.viewBox?.baseVal?.width || svg.width?.baseVal?.value || fallbackWidth;
  if (!Number.isFinite(width) || width <= 0 || width > 100000) throw new Error('올바르지 않은 페이지 크기입니다.');
  paper.style.width = `${width}px`;
  return { element: paper, width };
}

async function hangul(bytes) {
  const ctx = document.createElement('canvas').getContext('2d');
  globalThis.measureTextWidth = (font, text) => { ctx.font = font; return ctx.measureText(text).width; };
  await initHangul({ module_or_path: new URL('rhwp_bg.wasm', location.href) });
  const doc = new HwpDocument(bytes);
  model = { count: doc.pageCount(), kind: 'page', render: page => svgPage(doc.renderPageSvg(page), 794), dispose: () => doc.free() };
}

async function presentation(bytes) {
  const pres = await parsePpt(bytes);
  for (const font of pres.embeddedFonts || []) {
    try {
      const face = new FontFace(font.family, font.src, { weight: font.bold ? '700' : '400', style: font.italic ? 'italic' : 'normal' });
      await face.load(); document.fonts.add(face);
    } catch { /* The platform's fallback font remains available. */ }
  }
  model = {
    count: pres.slides.length, kind: 'slide', dispose: () => pres.dispose?.(),
    render: page => svgPage(renderSlideToSvg(pres, pres.slides[page], { textMode: 'svg' }), pres.width),
  };
}

function textDocument(bytes, markdown) {
  const paper = document.createElement('article');
  paper.className = 'paper flow-page';
  if (markdown) {
    paper.innerHTML = sanitize(marked.parse(decodeText(bytes), { gfm: true, breaks: false }));
    lockLinks(paper);
  } else {
    paper.classList.add('text-document');
    paper.textContent = decodeText(bytes);
  }
  model = { count: 1, kind: 'page', render: () => ({ element: paper, width: 794 }) };
}

function selectSheet(number, columns = 0) {
  activeSheet = number;
  columnPage = columns;
  sheet = workbook.sheet(activeSheet, columnPage);
  columnSelect.replaceChildren();
  for (let page = 0; page < sheet.columnCount; page++) {
    const option = document.createElement('option');
    option.value = page; option.textContent = sheet.columnLabel(page); columnSelect.append(option);
  }
  columnSelect.value = columnPage;
  columnSelect.hidden = sheet.columnCount <= 1;
  $('sheets').querySelectorAll('button').forEach((button, i) => button.setAttribute('aria-selected', String(i === activeSheet)));
  $('cell-address').textContent = '';
  $('cell-value').textContent = '셀을 선택하면 전체 내용을 볼 수 있습니다.';
  model = {
    count: sheet.count,
    render: page => sheet.render(page, (address, value) => {
      $('cell-address').textContent = address; $('cell-value').textContent = value;
    }),
  };
  return render(0);
}

async function spreadsheet(bytes, entries) {
  workbook = openWorkbook(bytes, entries);
  document.body.classList.add('workbook');
  $('sheets').hidden = false;
  $('formula-bar').hidden = false;
  $('sheets').setAttribute('role', 'tablist');
  for (const [number, name] of workbook.names.entries()) {
    const button = document.createElement('button');
    button.textContent = name; button.setAttribute('role', 'tab');
    button.addEventListener('click', () => { selectSheet(number).catch(showError); });
    $('sheets').append(button);
  }
  fit = false;
  zoom = 1;
  await selectSheet(0);
}

$('previous').addEventListener('click', () => render(index - 1));
$('next').addEventListener('click', () => render(index + 1));
$('page-number').addEventListener('change', event => render(Math.floor(Number(event.target.value)) - 1));
$('zoom-in').addEventListener('click', () => { fit = false; zoom = Math.min(3, zoom + .15); applyZoom(); });
$('zoom-out').addEventListener('click', () => { fit = false; zoom = Math.max(.1, zoom - .15); applyZoom(); });
$('fit').addEventListener('click', () => { fit = true; applyZoom(); });
columnSelect.addEventListener('change', () => { selectSheet(activeSheet, Number(columnSelect.value)).catch(showError); });
new ResizeObserver(applyZoom).observe(viewport);
document.addEventListener('keydown', event => {
  if (event.target.matches('input, select, button, td')) return;
  if (event.key === 'ArrowRight' || event.key === 'PageDown') { event.preventDefault(); render(index + 1); }
  if (event.key === 'ArrowLeft' || event.key === 'PageUp') { event.preventDefault(); render(index - 1); }
});
window.addEventListener('pagehide', () => model?.dispose?.());
window.addEventListener('error', event => showError(event.error || event.message));
window.addEventListener('unhandledrejection', event => showError(event.reason));

async function open() {
  const format = new URLSearchParams(location.search).get('format')?.toLowerCase();
  if (!['hwp', 'hwpx', 'ppt', 'pptx', 'xls', 'xlsx', 'md', 'markdown', 'txt'].includes(format)) throw new Error('지원하지 않는 문서 형식입니다.');
  const response = await fetch('/document');
  if (!response.ok) throw new Error('파일을 읽을 수 없습니다. 문서를 다시 열어 주세요.');
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (bytes.length > MAX_SOURCE) throw new Error('128MB보다 큰 문서는 열 수 없습니다.');
  const entries = bytes[0] === 0x50 && bytes[1] === 0x4b ? inspectZip(bytes, format === 'xlsx') : {};
  if (format === 'hwp' || format === 'hwpx') await hangul(bytes);
  else if (format === 'ppt' || format === 'pptx') await presentation(bytes);
  else if (format === 'xls' || format === 'xlsx') await spreadsheet(bytes, entries);
  else textDocument(bytes, format !== 'txt');
  if (!model?.count) throw new Error('표시할 페이지가 없습니다.');
  $('toolbar').hidden = false;
  await render(0);
}
open().catch(showError);
