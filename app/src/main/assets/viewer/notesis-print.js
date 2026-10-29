// Notesis: the layout a document is printed with when it becomes a PDF note.
//
// The viewer shows one page at a time; printing needs them all. prepare()
// walks the viewer through its pages and keeps a copy of each in
// #notesis-print, which is all the print style shows. With ?arrange=1 a tap
// on a paragraph of a flowing document (Markdown, text) starts it on a new
// page, and a second tap takes that back. No bridge to the app: the app reads
// and sets this through evaluateJavascript.
(() => {
  'use strict';
  const params = new URLSearchParams(location.search);
  const arrange = params.get('arrange') === '1';
  const PAPERS = { A4: [210, 297], Letter: [215.9, 279.4], A5: [148, 210] };
  const PX_PER_MM = 96 / 25.4;
  const options = { paper: 'A4', landscape: false, scale: 1, margin: 15 };
  let version = 0;
  let state = 'idle';
  // A fixed-page document's own page size in mm, read off its first page.
  let pagesMm = null;
  let sheetFit = 1;

  const style = document.createElement('style');
  document.head.appendChild(style);
  const byId = (id) => document.getElementById(id);
  const surface = () => byId('surface');
  const shown = () => surface() && surface().firstElementChild;
  const ready = () => document.body.dataset.state === 'ready' && !!shown();
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  function kind() {
    if (document.body.classList.contains('workbook')) return 'sheet';
    const page = shown();
    if (!page) return 'none';
    return page.classList.contains('flow-page') ? 'flow' : 'pages';
  }

  function pageCount() {
    const n = Number(byId('page-number') && byId('page-number').max);
    return n > 0 ? n : 1;
  }

  /** The page the PDF is laid on, in mm: a fixed-page document keeps its own. */
  function paperMm() {
    if (kind() === 'pages' && pagesMm) return pagesMm;
    let [w, h] = PAPERS[options.paper] || PAPERS.A4;
    if (options.landscape) [w, h] = [h, w];
    return [w, h];
  }

  function marginMm() {
    return kind() === 'pages' ? 0 : options.margin;
  }

  function render() {
    const [w, h] = paperMm();
    const margin = marginMm();
    const fixed = kind() === 'pages';
    style.textContent = `
#notesis-print { display: none; }
.flow-page { font-size: ${16 * options.scale}px; }
${arrange ? `
.flow-page > * { cursor: pointer; }
.flow-page > .notesis-break { position: relative; margin-top: 34px; }
.flow-page > .notesis-break::before {
  content: 'New page'; position: absolute; left: -12px; right: -12px; top: -26px;
  border-top: 2px dashed #2764ce; color: #2764ce; font: 600 12px Arial, sans-serif; padding-top: 3px;
}` : ''}
@page { size: ${w}mm ${h}mm; margin: ${margin}mm; }
@media print {
  html, body { height: auto !important; overflow: visible !important; background: #fff !important; display: block !important; }
  #toolbar, #status, #formula-bar, #sheets, #viewport { display: none !important; }
  #notesis-print { display: block !important; --notesis-fit: ${sheetFit}; }
  #notesis-print > * { box-shadow: none !important; margin: 0 !important; break-after: page; }
  #notesis-print > *:last-child { break-after: auto; }
  ${fixed ? `
  #notesis-print > .paper { width: ${w}mm !important; height: ${h}mm !important; overflow: hidden; break-inside: avoid; }
  #notesis-print > .paper > svg { width: 100% !important; height: 100% !important; }` : ''}
  .flow-page { width: auto !important; min-height: 0 !important; padding: 0 !important; }
  .flow-page > .notesis-break { margin-top: 0 !important; break-before: page; }
  .flow-page > .notesis-break::before { content: none !important; display: none !important; }
  .flow-page pre, .flow-page blockquote, .flow-page img, .flow-page tr, .flow-page li, .flow-page figure { break-inside: avoid; }
  .flow-page h1, .flow-page h2, .flow-page h3, .flow-page h4, .flow-page h5, .flow-page h6 { break-after: avoid; }
  .flow-page p { orphans: 3; widows: 3; }
  .worksheet { zoom: var(--notesis-fit, 1); }
  .worksheet thead th, .worksheet tbody th { position: static !important; }
}`;
  }

  // Ids inside each copied page are made its own: SVG pages that share ids
  // (clip paths, gradients) would otherwise all point at the first page's.
  function ownIds(node, prefix) {
    const renamed = new Map();
    node.querySelectorAll('[id]').forEach((element) => {
      const next = `${prefix}${element.id}`;
      renamed.set(element.id, next);
      element.id = next;
    });
    if (renamed.size === 0) return;
    const swap = (text) => text
      .replace(/url\(\s*['"]?#([^'")\s]+)['"]?\s*\)/g, (whole, id) => (renamed.has(id) ? `url(#${renamed.get(id)})` : whole))
      .replace(/^#(.+)$/, (whole, id) => (renamed.has(id) ? `#${renamed.get(id)}` : whole));
    node.querySelectorAll('*').forEach((element) => {
      for (const attribute of Array.from(element.attributes)) {
        const value = attribute.value;
        if (value.includes('#')) {
          const next = swap(value);
          if (next !== value) element.setAttribute(attribute.name, next);
        }
      }
      if (element.tagName.toLowerCase() === 'style' && element.textContent.includes('#')) {
        element.textContent = element.textContent.replace(/#([A-Za-z_][\w-]*)/g,
          (whole, id) => (renamed.has(id) ? `#${renamed.get(id)}` : whole));
      }
    });
  }

  async function settled(previous, tries = 1200) {
    for (let i = 0; i < tries; i++) {
      if (ready() && shown() !== previous) return true;
      await sleep(50);
    }
    return false;
  }

  async function toFirstPage() {
    const back = byId('previous');
    if (!back || back.disabled) return true;
    const field = byId('page-number');
    if (field) {
      const before = shown();
      field.value = '1';
      field.dispatchEvent(new Event('change', { bubbles: true }));
      field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      // The field may not move the viewer; then the button walks it back.
      await settled(before, 40);
    }
    for (let guard = 0; !back.disabled && guard < 5000; guard++) {
      const before = shown();
      back.click();
      if (!(await settled(before))) return false;
    }
    return true;
  }

  function measure(first) {
    if (kind() === 'pages') {
      const svg = first && first.querySelector('svg');
      const box = svg && svg.viewBox && svg.viewBox.baseVal;
      const width = (box && box.width) || (first && first.offsetWidth) || 794;
      const height = (box && box.height) || (first && first.offsetHeight) || 1123;
      pagesMm = [width / PX_PER_MM, height / PX_PER_MM];
    } else if (kind() === 'sheet') {
      const [w] = paperMm();
      const room = (w - 2 * marginMm()) * PX_PER_MM;
      const wide = first ? first.scrollWidth : room;
      sheetFit = wide > room ? Math.max(0.35, room / wide) : 1;
    }
  }

  async function prepare() {
    state = 'collecting';
    try {
      let out = byId('notesis-print');
      if (!out) {
        out = document.createElement('div');
        out.id = 'notesis-print';
        document.body.appendChild(out);
      }
      out.replaceChildren();
      const count = pageCount();
      if (count > 1 && !(await toFirstPage())) throw new Error('pages');
      const next = byId('next');
      for (let i = 0; i < count; i++) {
        const page = shown();
        if (!page) throw new Error('page');
        if (i === 0) measure(page);
        const copy = page.cloneNode(true);
        ownIds(copy, `np${i}-`);
        out.appendChild(copy);
        if (i < count - 1) {
          next.click();
          if (!(await settled(page))) throw new Error('next');
        }
      }
      render();
      state = 'printable';
    } catch (error) {
      state = 'failed';
    }
  }

  if (arrange) {
    document.addEventListener('click', (event) => {
      const page = event.target.closest && event.target.closest('#surface .flow-page');
      if (!page) return;
      let block = event.target;
      while (block && block.parentElement !== page) block = block.parentElement;
      if (!block) return;
      event.preventDefault();
      event.stopPropagation();
      // A new page before the first paragraph would only leave the first page empty.
      if (block === page.firstElementChild && !block.classList.contains('notesis-break')) return;
      block.classList.toggle('notesis-break');
      version++;
    }, true);
  }

  window.notesisLayout = {
    kind,
    state: () => state,
    version: () => version,
    breaks: () => document.querySelectorAll('#surface .notesis-break').length,
    paperMm,
    /** Changes the paper, its turn, the text size or the margins; the page breaks stay. */
    set(next) {
      Object.assign(options, next || {});
      version++;
      state = 'idle';
      render();
      return true;
    },
    prepare() {
      prepare();
      return true;
    },
  };
  render();
})();
