// Notesis: a Markdown note laid on pages for a PDF.
//
// The note is rendered the way GitHub and Obsidian show Markdown - marked
// (GFM), github-markdown-css, KaTeX for $math$, highlight.js for code, plus
// Obsidian's [[links]], ==highlights==, #tags, %%comments%% and callouts -
// then laid block by block onto pages of the paper chosen, each page a real
// box of that size, so what is shown is what is printed. With ?arrange=1 a
// tap on a block starts a new page there; a tap on the first block of a
// page pulls it back onto the page before, which is shrunk to hold it; a
// second tap takes either back. The app drives this through evaluateJavascript.
(() => {
  'use strict';
  const params = new URLSearchParams(location.search);
  const arrange = params.get('arrange') === '1';
  if (arrange) document.documentElement.classList.add('arrange');
  const PAPERS = { A4: [210, 297], Letter: [215.9, 279.4], A5: [148, 210] };
  const PX = 96 / 25.4;
  const options = { paper: 'A4', landscape: false, scale: 1, margin: 15, numbers: true };
  const forced = new Set();
  const pulled = new Set();
  let version = 0;
  let state = 'loading';
  let pieces = [];
  const pageStyle = document.createElement('style');
  document.head.appendChild(pageStyle);
  const $ = (id) => document.getElementById(id);
  const escapeHtml = (text) => String(text).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

  const CALLOUTS = {
    note: '#0969da', info: '#0969da', todo: '#0969da', abstract: '#0a7f86', summary: '#0a7f86', tldr: '#0a7f86',
    tip: '#1a7f37', hint: '#1a7f37', important: '#1a7f37', success: '#1a7f37', check: '#1a7f37', done: '#1a7f37',
    question: '#9a6700', help: '#9a6700', faq: '#9a6700', warning: '#9a6700', caution: '#9a6700', attention: '#9a6700',
    failure: '#cf222e', fail: '#cf222e', missing: '#cf222e', danger: '#cf222e', error: '#cf222e', bug: '#cf222e',
    example: '#8250df', quote: '#57606a', cite: '#57606a',
  };

  // ---- Markdown, as Obsidian writes it ---------------------------------------------------------

  function setupMarked() {
    const math = (tex, display) => {
      try { return katex.renderToString(tex, { displayMode: display, throwOnError: false, output: 'html' }); }
      catch (e) { return `<code>${escapeHtml(tex)}</code>`; }
    };
    const at = (text, needle) => { const i = text.indexOf(needle); return i < 0 ? undefined : i; };
    marked.use({
      gfm: true,
      // Obsidian, unless told to be strict, keeps a single line break as a break.
      breaks: true,
      extensions: [
        {
          name: 'mathBlock', level: 'block', start: (src) => at(src, '$$'),
          tokenizer(src) {
            const m = /^\$\$([\s\S]+?)\$\$[^\S\n]*(?:\n|$)/.exec(src);
            if (m) return { type: 'mathBlock', raw: m[0], text: m[1].trim() };
          },
          renderer: (token) => `<div class="math-block">${math(token.text, true)}</div>`,
        },
        {
          name: 'commentBlock', level: 'block', start: (src) => at(src, '%%'),
          tokenizer(src) {
            const m = /^%%[\s\S]*?%%[^\S\n]*(?:\n|$)/.exec(src);
            if (m) return { type: 'commentBlock', raw: m[0] };
          },
          renderer: () => '',
        },
        {
          name: 'mathInline', level: 'inline', start: (src) => at(src, '$'),
          tokenizer(src) {
            const m = /^\$(?![\s$])([^$\n]+?)(?<![\s\\])\$(?!\d)/.exec(src);
            if (m) return { type: 'mathInline', raw: m[0], text: m[1] };
          },
          renderer: (token) => math(token.text, false),
        },
        {
          name: 'wikilink', level: 'inline',
          start: (src) => { const i = src.search(/!?\[\[/); return i < 0 ? undefined : i; },
          tokenizer(src) {
            const m = /^(!?)\[\[([^\[\]\n]+?)\]\]/.exec(src);
            if (!m) return;
            const [target, alias] = m[2].split('|');
            return { type: 'wikilink', raw: m[0], embed: m[1] === '!', target: target.trim(), alias: alias && alias.trim() };
          },
          renderer: (token) => {
            const shown = token.alias || token.target.replace(/#\^?/, ' › ');
            return `<span class="${token.embed ? 'embed' : 'wikilink'}">${escapeHtml(shown)}</span>`;
          },
        },
        {
          name: 'highlight', level: 'inline', start: (src) => at(src, '=='),
          tokenizer(src) {
            const m = /^==(?=\S)([^\n]+?)(?<=\S)==/.exec(src);
            if (m) return { type: 'highlight', raw: m[0], tokens: this.lexer.inlineTokens(m[1]) };
          },
          renderer(token) { return `<mark>${this.parser.parseInline(token.tokens)}</mark>`; },
        },
        {
          name: 'commentInline', level: 'inline', start: (src) => at(src, '%%'),
          tokenizer(src) {
            const m = /^%%[^\n]*?%%/.exec(src);
            if (m) return { type: 'commentInline', raw: m[0] };
          },
          renderer: () => '',
        },
        {
          name: 'tag', level: 'inline',
          start: (src) => { const m = /(^|\s)#[\p{L}_]/u.exec(src); return m ? m.index + m[1].length : undefined; },
          tokenizer(src) {
            const m = /^#([\p{L}\p{N}_/-]*[\p{L}_/-][\p{L}\p{N}_/-]*)/u.exec(src);
            if (m) return { type: 'tag', raw: m[0], text: m[0] };
          },
          renderer: (token) => `<span class="tag">${escapeHtml(token.text)}</span>`,
        },
      ],
    });
  }

  /** Front matter is the note's properties, not its text: left out of the pages. */
  function withoutFrontMatter(text) {
    const m = /^---\r?\n[\s\S]*?\r?\n---[^\S\n]*(?:\r?\n|$)/.exec(text);
    return m ? text.slice(m[0].length) : text;
  }

  function highlightCode(code) {
    const language = (/language-([\w+#-]+)/.exec(code.className) || [])[1];
    code.dataset.raw = code.textContent;
    if (!language) return;
    code.dataset.language = language;
    try {
      if (hljs.getLanguage(language)) {
        code.innerHTML = hljs.highlight(code.dataset.raw, { language, ignoreIllegals: true }).value;
        code.classList.add('hljs');
      }
    } catch (e) { /* left plain */ }
  }

  function enhance(root) {
    root.querySelectorAll('pre code').forEach(highlightCode);
    root.querySelectorAll('li > input[type="checkbox"]').forEach((box) => {
      box.parentElement.classList.add('task-list-item');
      box.classList.add('task-list-item-checkbox');
      box.parentElement.parentElement.classList.add('contains-task-list');
    });
    root.querySelectorAll('blockquote').forEach((quote) => {
      const first = quote.firstElementChild;
      if (!first || first.tagName !== 'P') return;
      const m = /^\[!([\w-]+)\]([+-]?)[ \t]*([^\n<]*)/.exec(first.innerHTML);
      if (!m) return;
      const type = m[1].toLowerCase();
      first.innerHTML = first.innerHTML.slice(m[0].length).replace(/^\s*<br>\s*/, '');
      if (!first.textContent.trim() && !first.querySelector('img,.katex')) first.remove();
      const box = document.createElement('div');
      box.className = 'callout';
      box.style.setProperty('--callout', CALLOUTS[type] || '#0969da');
      const title = document.createElement('div');
      title.className = 'callout-title';
      title.textContent = m[3].trim() || type.charAt(0).toUpperCase() + type.slice(1);
      box.append(title, ...Array.from(quote.childNodes));
      quote.replaceWith(box);
    });
  }

  /** The note as blocks to lay on pages; a list's items each their own, so a list can turn a page. */
  function piecesOf(html) {
    const holder = document.createElement('div');
    holder.innerHTML = html;
    enhance(holder);
    const out = [];
    let n = 0;
    for (const node of Array.from(holder.childNodes)) {
      if (node.nodeType !== 1) continue;
      const tag = node.tagName;
      if ((tag === 'UL' || tag === 'OL') && node.children.length > 1) {
        const items = Array.from(node.children);
        const start = tag === 'OL' ? parseInt(node.getAttribute('start') || '1', 10) : 1;
        items.forEach((item, k) => {
          const list = node.cloneNode(false);
          if (tag === 'OL') list.setAttribute('start', String(start + k));
          list.appendChild(item);
          if (k > 0) list.classList.add('cont-before');
          if (k < items.length - 1) list.classList.add('cont-after');
          out.push({ id: `${n}.${k}`, el: list });
        });
      } else {
        out.push({ id: `${n}`, el: node });
      }
      n++;
    }
    return out;
  }

  // ---- pages -----------------------------------------------------------------------------------

  function paperMm() {
    let [w, h] = PAPERS[options.paper] || PAPERS.A4;
    if (options.landscape) [w, h] = [h, w];
    return [w, h];
  }

  /** Cuts [el], alone on a page and too tall for it, where it fits; answers the rest, or null if it cannot be cut. */
  function split(piece, body, room) {
    const el = piece.el;
    const fits = () => body.scrollHeight <= room + 0.5;
    const restOf = (node, k) => ({ id: `${piece.id}~${k}`, el: node });
    const tag = el.tagName;
    // Code: by lines, each part coloured again.
    const code = tag === 'PRE' && el.querySelector('code');
    if (code && code.dataset.raw !== undefined) {
      const lines = code.dataset.raw.replace(/\n$/, '').split('\n');
      if (lines.length < 2) return null;
      const make = (from, to) => {
        const pre = el.cloneNode(false);
        const part = code.cloneNode(false);
        part.dataset.raw = lines.slice(from, to).join('\n');
        part.textContent = part.dataset.raw;
        if (part.dataset.language && hljs.getLanguage(part.dataset.language)) {
          part.innerHTML = hljs.highlight(part.dataset.raw, { language: part.dataset.language, ignoreIllegals: true }).value;
        }
        pre.appendChild(part);
        return pre;
      };
      let low = 1, high = lines.length - 1, best = 0;
      while (low <= high) {
        const mid = (low + high) >> 1;
        const head = make(0, mid);
        body.replaceChild(head, body.lastElementChild);
        if (fits()) { best = mid; low = mid + 1; } else high = mid - 1;
      }
      if (best === 0) { body.replaceChild(el, body.lastElementChild); return null; }
      const head = make(0, best);
      head.dataset.piece = piece.id;
      head.classList.add('cont-after');
      body.replaceChild(head, body.lastElementChild);
      const rest = make(best, lines.length);
      rest.classList.add('cont-before');
      return restOf(rest, 1);
    }
    // A table: by rows, its header repeated.
    if (tag === 'TABLE') {
      const rows = Array.from(el.tBodies[0] ? el.tBodies[0].rows : []);
      if (rows.length < 2) return null;
      let kept = rows.length;
      while (kept > 1 && !fits()) { rows[--kept].remove(); }
      if (kept === rows.length || !fits()) { return null; }
      const rest = el.cloneNode(false);
      if (el.tHead) rest.appendChild(el.tHead.cloneNode(true));
      const tbody = document.createElement('tbody');
      rows.slice(kept).forEach((row) => tbody.appendChild(row));
      rest.appendChild(tbody);
      return restOf(rest, 1);
    }
    // A box of blocks (a quote, a callout, a list item): by its children.
    if ((tag === 'BLOCKQUOTE' || el.classList.contains('callout') || tag === 'UL' || tag === 'OL' || tag === 'DIV') && el.children.length > 1) {
      const children = Array.from(el.children);
      const moved = [];
      while (el.children.length > 1 && !fits()) moved.unshift(el.lastElementChild), el.lastElementChild.remove();
      if (moved.length === 0 || !fits()) { moved.forEach((c) => el.appendChild(c)); return null; }
      const rest = el.cloneNode(false);
      rest.classList.add('cont-before');
      if (rest.classList.contains('callout')) {
        const title = children[0].classList.contains('callout-title') ? children[0].cloneNode(true) : null;
        if (title) rest.appendChild(title);
      }
      moved.forEach((c) => rest.appendChild(c));
      el.classList.add('cont-after');
      return restOf(rest, 1);
    }
    // Text: at a word, as far down as the page goes.
    if (/^(P|H[1-6]|LI)$/.test(tag) || el.classList.contains('cont')) {
      const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
      const nodes = [];
      let total = 0;
      while (walker.nextNode()) { nodes.push([walker.currentNode, total]); total += walker.currentNode.length; }
      if (total < 2) return null;
      const point = (k) => {
        for (let i = nodes.length - 1; i >= 0; i--) if (nodes[i][1] <= k) return [nodes[i][0], Math.min(k - nodes[i][1], nodes[i][0].length)];
        return [nodes[0][0], 0];
      };
      const part = (from, to) => {
        const range = document.createRange();
        if (from === null) range.setStart(el, 0); else range.setStart(...point(from));
        if (to === null) range.setEnd(el, el.childNodes.length); else range.setEnd(...point(to));
        const copy = el.cloneNode(false);
        copy.appendChild(range.cloneContents());
        return copy;
      };
      let low = 1, high = total - 1, best = 0;
      while (low <= high) {
        const mid = (low + high) >> 1;
        body.replaceChild(part(null, mid), body.lastElementChild);
        if (fits()) { best = mid; low = mid + 1; } else high = mid - 1;
      }
      if (best === 0) { body.replaceChild(el, body.lastElementChild); return null; }
      // Back to the space before, so no word is cut in two.
      const text = el.textContent;
      const space = text.lastIndexOf(' ', best);
      if (space > best * 0.6) best = space + 1;
      const head = part(null, best);
      head.dataset.piece = piece.id;
      head.classList.add('cont-after');
      body.replaceChild(head, body.lastElementChild);
      const rest = part(best, null);
      rest.classList.add('cont-before');
      return restOf(rest, best);
    }
    return null;
  }

  function paginate() {
    const pagesEl = $('pages');
    pagesEl.style.zoom = '1';
    pagesEl.replaceChildren();
    const [w, h] = paperMm();
    const margin = options.margin;
    const room = (h - 2 * margin) * PX;
    const wide = (w - 2 * margin) * PX;
    pageStyle.textContent = `@page { size: ${w}mm ${h}mm; margin: 0; }`;
    const pages = [];
    let body = null;
    const newPage = () => {
      const page = document.createElement('section');
      page.className = 'page';
      page.style.width = `${w}mm`;
      page.style.height = `${h}mm`;
      page.style.padding = `${margin}mm`;
      body = document.createElement('div');
      body.className = 'markdown-body page-body';
      body.style.width = `${wide}px`;
      body.style.fontSize = `${16 * options.scale}px`;
      page.appendChild(body);
      pagesEl.appendChild(page);
      pages.push({ page, body });
    };
    newPage();
    const overflowing = () => body.scrollHeight > room + 1;
    const queue = pieces.map((p) => ({ id: p.id, el: p.el.cloneNode(true) }));
    let guard = 0;
    while (queue.length && guard++ < 100000) {
      const piece = queue.shift();
      piece.el.dataset.piece = piece.id;
      if (body.childElementCount > 0 && forced.has(piece.id)) newPage();
      const first = body.childElementCount === 0;
      body.appendChild(piece.el);
      if (first && forced.has(piece.id) && pages.length > 1) piece.el.classList.add('forced');
      if (!overflowing()) continue;
      if (!first && pulled.has(piece.id)) { piece.el.classList.add('pulled'); continue; }
      if (!first) {
        piece.el.remove();
        // A heading is not left at the foot of a page: it goes over with what it heads.
        const last = body.lastElementChild;
        const carried = last && body.childElementCount > 1 && /^H[1-6]$/.test(last.tagName) && !forced.has(piece.id)
          ? last : null;
        if (carried) carried.remove();
        newPage();
        if (carried) body.appendChild(carried);
        body.appendChild(piece.el);
        if (!overflowing()) continue;
        if (carried) {
          // Even together they do not fit: the heading stays with the start of what it heads anyway.
          const rest = split(piece, body, room);
          if (rest) queue.unshift(rest);
          continue;
        }
      }
      const rest = split(piece, body, room);
      if (rest) queue.unshift(rest);
    }
    pages.forEach(({ page, body: b }, i) => {
      const full = b.scrollHeight;
      // A pixel or two over is rounding, not content.
      if (full > room + 2) {
        const s = Math.max(0.45, room / full);
        b.style.transform = `scale(${s})`;
        b.style.width = `${wide / s}px`;
        if (arrange) {
          const note = document.createElement('div');
          note.className = 'page-note';
          note.textContent = `Shrunk to ${Math.round(s * 100)}% to fit`;
          page.appendChild(note);
        }
      }
      if (options.numbers && pages.length > 1) {
        const number = document.createElement('div');
        number.className = 'page-number';
        number.style.bottom = `${Math.max(4, margin / 2 - 2)}mm`;
        number.textContent = `${i + 1} / ${pages.length}`;
        page.appendChild(number);
      }
    });
    fitToScreen();
    $('status').textContent = arrange ? `${pages.length} page${pages.length === 1 ? '' : 's'} · tap a block to start a new page there; tap a page's first block to pull it back` : '';
    return pages.length;
  }

  function fitToScreen() {
    const pagesEl = $('pages');
    const [w] = paperMm();
    const zoom = Math.min(1, (window.innerWidth - 24) / (w * PX));
    pagesEl.style.zoom = String(zoom);
  }

  // ---- editing the pages ---------------------------------------------------------------------

  if (arrange) {
    document.addEventListener('click', (event) => {
      const block = event.target.closest && event.target.closest('.page-body > [data-piece]');
      if (!block) return;
      event.preventDefault();
      const id = block.dataset.piece;
      const body = block.parentElement;
      const firstOnPage = body.firstElementChild === block;
      const page = body.parentElement;
      const index = Array.from(page.parentElement.children).indexOf(page);
      if (pulled.has(id)) pulled.delete(id);
      else if (firstOnPage && index > 0) {
        if (forced.has(id)) forced.delete(id); else pulled.add(id);
      } else if (!firstOnPage) {
        forced.add(id);
      } else return;
      version++;
      const top = document.scrollingElement.scrollTop;
      paginate();
      document.scrollingElement.scrollTop = top;
    }, true);
    window.addEventListener('resize', fitToScreen);
  }

  async function load() {
    try {
      setupMarked();
      const response = await fetch('../document', { cache: 'no-store' });
      const bytes = new Uint8Array(await response.arrayBuffer());
      const text = new TextDecoder('utf-8').decode(bytes).replace(/^﻿/, '');
      pieces = piecesOf(marked.parse(withoutFrontMatter(text)));
      if (document.fonts && document.fonts.ready) await document.fonts.ready;
      paginate();
      state = 'ready';
    } catch (error) {
      state = 'failed';
      $('status').textContent = `Couldn't lay out the note: ${error && error.message ? error.message : error}`;
    }
  }

  window.notesisLayout = {
    kind: () => (state === 'loading' ? 'none' : 'flow'),
    state: () => (state === 'ready' ? 'printable' : state),
    version: () => version,
    breaks: () => forced.size + pulled.size,
    pages: () => $('pages').children.length,
    paperMm,
    set(next) {
      Object.assign(options, next || {});
      version++;
      if (state === 'ready') paginate();
      return true;
    },
    reset() {
      forced.clear();
      pulled.clear();
      version++;
      if (state === 'ready') paginate();
      return true;
    },
    prepare() { return true; },
  };
  load();
})();
