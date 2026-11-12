// Notesis: a Markdown note read on screen.
//
// The same rendering as the PDF layout (md-print.js) - marked (GFM),
// KaTeX for $math$, highlight.js for code, Obsidian's [[links]], ![[embeds]],
// ==highlights==, #tags, %%comments%%, callouts - plus footnotes, a note's
// properties, pictures from the note's own files, and taps sent back to the
// app: a link followed, a task ticked. The app calls notesisRead.show(text,
// options) and hears back through the NotesisBridge object it adds.
(() => {
  'use strict';
  const bridge = window.NotesisBridge || { follow() {}, open() {}, toggleTask() {}, ready() {}, size() {} };
  const note = document.getElementById('note');
  const escapeHtml = (text) => String(text).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  const at = (text, needle) => { const i = text.indexOf(needle); return i < 0 ? undefined : i; };
  // A block mark counts only at the start of a line; found mid-line it would cut the paragraph there.
  const lineStart = (text, mark) => { const m = new RegExp('(^|\\n)[ \\t]*' + mark.source).exec(text); return m ? m.index + m[1].length : undefined; };
  const IMAGE = /\.(png|jpe?g|gif|webp|bmp|svg|avif|heic|heif)$/i;
  const CALLOUTS = {
    note: '#0969da', info: '#0969da', todo: '#0969da', abstract: '#0a7f86', summary: '#0a7f86', tldr: '#0a7f86',
    tip: '#1a7f37', hint: '#1a7f37', important: '#1a7f37', success: '#1a7f37', check: '#1a7f37', done: '#1a7f37',
    question: '#9a6700', help: '#9a6700', faq: '#9a6700', warning: '#bc4c00', caution: '#bc4c00', attention: '#bc4c00',
    failure: '#cf222e', fail: '#cf222e', missing: '#cf222e', danger: '#cf222e', error: '#cf222e', bug: '#cf222e',
    example: '#8250df', quote: '#6e7781', cite: '#6e7781',
  };

  /** A file of the note's own (a picture), as the app serves it. */
  const fileUrl = (name) => 'file/' + encodeURIComponent(name.replace(/^\.?\//, ''));
  const isAddress = (url) => /^[a-z][a-z0-9+.-]*:/i.test(url) || url.startsWith('//');

  /** The few words the page shows itself, in the app's language (see show). */
  let words = { copy: 'Copy', copied: 'Copied', notFound: '{0} (not found)', noText: '(no text)', picture: 'Picture', empty: 'Nothing written yet.' };
  let footnotes = new Map();
  let footnoteOrder = [];

  function math(tex, display) {
    try { return katex.renderToString(tex, { displayMode: display, throwOnError: false, output: 'html' }); }
    catch (e) { return `<code>${escapeHtml(tex)}</code>`; }
  }

  marked.use({
    gfm: true,
    // Obsidian, unless told to be strict, keeps a single line break as a break.
    breaks: true,
    renderer: {
      image({ href, title, text }) {
        const src = isAddress(href) ? href : fileUrl(decodeURIComponent(href));
        return `<img src="${escapeHtml(src)}" alt="${escapeHtml(text)}"${title ? ` title="${escapeHtml(title)}"` : ''} loading="lazy">`;
      },
      link({ href, title, tokens }) {
        const inner = this.parser.parseInline(tokens);
        return `<a data-href="${escapeHtml(href)}"${title ? ` title="${escapeHtml(title)}"` : ''}>${inner}</a>`;
      },
    },
    extensions: [
      {
        name: 'mathBlock', level: 'block', start: (src) => lineStart(src, /\$\$/),
        tokenizer(src) {
          const m = /^\$\$([\s\S]+?)\$\$[^\S\n]*(?:\n|$)/.exec(src);
          if (m) return { type: 'mathBlock', raw: m[0], text: m[1].trim() };
        },
        renderer: (token) => `<div class="math-block">${math(token.text, true)}</div>`,
      },
      {
        name: 'commentBlock', level: 'block', start: (src) => lineStart(src, /%%/),
        tokenizer(src) {
          const m = /^%%[\s\S]*?%%[^\S\n]*(?:\n|$)/.exec(src);
          if (m) return { type: 'commentBlock', raw: m[0] };
        },
        renderer: () => '',
      },
      {
        name: 'footnoteDef', level: 'block', start: (src) => { const m = /^\[\^[^\]\s]+\]:/m.exec(src); return m ? m.index : undefined; },
        tokenizer(src) {
          const m = /^\[\^([^\]\s]+)\]:[ \t]*([^\n]*(?:\n(?:[ ]{2,}|\t)[^\n]*)*)(?:\n|$)/.exec(src);
          if (!m) return;
          const token = { type: 'footnoteDef', raw: m[0], id: m[1], tokens: [] };
          this.lexer.inline(m[2].replace(/\n[ \t]+/g, '\n'), token.tokens);
          return token;
        },
        renderer(token) { footnotes.set(token.id, this.parser.parseInline(token.tokens)); return ''; },
      },
      {
        name: 'mathInline', level: 'inline', start: (src) => at(src, '$'),
        tokenizer(src) {
          const d = /^\$\$([^$]+?)\$\$/.exec(src);
          if (d) return { type: 'mathInline', raw: d[0], text: d[1], display: true };
          const m = /^\$(?![\s$])([^$\n]+?)(?<![\s\\])\$(?!\d)/.exec(src);
          if (m) return { type: 'mathInline', raw: m[0], text: m[1], display: false };
        },
        renderer: (token) => math(token.text, token.display),
      },
      {
        name: 'footnoteRef', level: 'inline', start: (src) => at(src, '[^'),
        tokenizer(src) {
          const m = /^\[\^([^\]\s]+)\]/.exec(src);
          if (m) return { type: 'footnoteRef', raw: m[0], id: m[1] };
        },
        renderer(token) {
          if (!footnoteOrder.includes(token.id)) footnoteOrder.push(token.id);
          const n = footnoteOrder.indexOf(token.id) + 1;
          return `<sup class="fn" id="fnref-${escapeHtml(token.id)}"><a data-footnote="${escapeHtml(token.id)}">${n}</a></sup>`;
        },
      },
      {
        name: 'wikilink', level: 'inline',
        start: (src) => { const i = src.search(/!?\[\[/); return i < 0 ? undefined : i; },
        tokenizer(src) {
          const m = /^(!?)\[\[([^\[\]\n]+?)\]\]/.exec(src);
          if (!m) return;
          const parts = m[2].split('|');
          return { type: 'wikilink', raw: m[0], embed: m[1] === '!', target: parts[0].trim(), alias: parts.slice(1).join('|').trim() };
        },
        renderer(token) {
          if (token.embed && IMAGE.test(token.target)) {
            const width = parseInt(token.alias, 10);
            return `<img src="${escapeHtml(fileUrl(token.target))}" alt="${escapeHtml(token.target)}"${width > 0 ? ` width="${width}"` : ''} loading="lazy">`;
          }
          const shown = (!token.embed && token.alias) || token.target.replace(/#\^?/, ' › ');
          if (token.embed) return `<span class="embed-note" data-target="${escapeHtml(token.target)}">↳ ${escapeHtml(shown)}</span>`;
          return `<a class="wikilink" data-target="${escapeHtml(token.target)}">${escapeHtml(shown)}</a>`;
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

  /** Front matter is the note's properties, shown as Obsidian does, above the text. */
  function splitFrontMatter(text) {
    const m = /^---\r?\n([\s\S]*?)\r?\n(?:---|\.\.\.)[^\S\n]*(?:\r?\n|$)/.exec(text);
    if (!m) return [null, text];
    const rows = [];
    for (const line of m[1].split(/\r?\n/)) {
      const kv = /^([^\s:#][^:]*):[ \t]*(.*)$/.exec(line);
      if (kv) rows.push([kv[1].trim(), kv[2].trim()]);
      else if (/^\s*-\s+/.test(line) && rows.length) {
        const last = rows[rows.length - 1];
        last[1] = (last[1] ? last[1] + ', ' : '') + line.replace(/^\s*-\s+/, '');
      }
    }
    return [rows, text.slice(m[0].length)];
  }

  function highlightCode(code) {
    const language = (/language-([\w+#-]+)/.exec(code.className) || [])[1];
    const pre = code.parentElement;
    const copy = document.createElement('button');
    copy.className = 'copy';
    copy.textContent = words.copy;
    copy.dataset.code = code.textContent;
    pre.appendChild(copy);
    if (!language) return;
    const label = document.createElement('span');
    label.className = 'lang';
    label.textContent = language;
    pre.appendChild(label);
    pre.classList.add('has-lang');
    try {
      if (hljs.getLanguage(language)) {
        code.innerHTML = hljs.highlight(code.textContent, { language, ignoreIllegals: true }).value;
        code.classList.add('hljs');
      }
    } catch (e) { /* left plain */ }
  }

  function enhance(root) {
    root.querySelectorAll('pre > code').forEach(highlightCode);
    // Tasks are ticked here; the app finds the same box by its place among them all.
    root.querySelectorAll('li > input[type="checkbox"], li > p > input[type="checkbox"]').forEach((box, index) => {
      const item = box.closest('li');
      item.classList.add('task-list-item');
      if (box.checked) item.classList.add('done');
      box.disabled = false;
      box.dataset.task = String(index);
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
      if (m[2]) box.classList.add('foldable');
      if (m[2] === '-') box.classList.add('folded');
      box.style.setProperty('--callout', CALLOUTS[type] || '#0969da');
      const title = document.createElement('div');
      title.className = 'callout-title';
      title.textContent = m[3].trim() || type.charAt(0).toUpperCase() + type.slice(1);
      box.append(title, ...Array.from(quote.childNodes));
      quote.replaceWith(box);
    });
    root.querySelectorAll('img').forEach((img) => {
      img.addEventListener('error', () => {
        const missing = document.createElement('span');
        missing.className = 'missing';
        missing.textContent = words.notFound.replace('{0}', img.alt || words.picture);
        img.replaceWith(missing);
      }, { once: true });
    });
  }

  function render(text) {
    footnotes = new Map();
    footnoteOrder = [];
    const [properties, body] = splitFrontMatter(text);
    let html = '';
    if (properties && properties.length) {
      html += '<div class="properties">' + properties.map(([k, v]) => `<div><b>${escapeHtml(k)}</b><span>${escapeHtml(v)}</span></div>`).join('') + '</div>';
    }
    html += marked.parse(body);
    const ids = footnoteOrder.concat(Array.from(footnotes.keys()).filter((id) => !footnoteOrder.includes(id)));
    if (ids.length) {
      html += '<section class="footnotes"><ol>' + ids.map((id) =>
        `<li id="fn-${escapeHtml(id)}">${footnotes.has(id) ? footnotes.get(id) : `<span class="missing">${escapeHtml(words.noText)}</span>`} <a data-footnote-back="${escapeHtml(id)}">↩</a></li>`).join('') + '</ol></section>';
    }
    if (!text.trim()) html = `<p class="empty">${escapeHtml(words.empty)}</p>`;
    note.innerHTML = html;
    enhance(note);
  }

  document.addEventListener('click', (event) => {
    const target = event.target;
    const box = target.closest('input[data-task]');
    if (box) { event.preventDefault(); bridge.toggleTask(parseInt(box.dataset.task, 10)); return; }
    const copy = target.closest('button.copy');
    if (copy) {
      navigator.clipboard && navigator.clipboard.writeText(copy.dataset.code).catch(() => {});
      copy.textContent = words.copied;
      setTimeout(() => { copy.textContent = words.copy; }, 1200);
      return;
    }
    const wiki = target.closest('[data-target]');
    if (wiki) { bridge.follow(wiki.dataset.target); return; }
    const foot = target.closest('[data-footnote]');
    if (foot) { const el = document.getElementById('fn-' + foot.dataset.footnote); if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' }); return; }
    const back = target.closest('[data-footnote-back]');
    if (back) { const el = document.getElementById('fnref-' + back.dataset.footnoteBack); if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' }); return; }
    const link = target.closest('a[data-href]');
    if (link) {
      const href = link.dataset.href;
      if (href.startsWith('#')) {
        // A heading in this note, by the id GitHub would give it.
        const id = decodeURIComponent(href.slice(1)).toLowerCase().replace(/[^\p{L}\p{N}\s-]/gu, '').trim().replace(/\s+/g, '-');
        const heading = Array.from(note.querySelectorAll('h1,h2,h3,h4,h5,h6')).find((h) =>
          h.textContent.toLowerCase().replace(/[^\p{L}\p{N}\s-]/gu, '').trim().replace(/\s+/g, '-') === id);
        if (heading) heading.scrollIntoView({ behavior: 'smooth' });
        return;
      }
      if (isAddress(href)) bridge.open(href); else bridge.follow(decodeURIComponent(href).replace(/\.md$/i, ''));
      return;
    }
    const fold = target.closest('.callout.foldable > .callout-title');
    if (fold) fold.parentElement.classList.toggle('folded');
  });

  window.notesisRead = {
    /** Shows [text]; [options] carries the app's colours ({bg, fg, accent, muted, line, code, mark, dark}) and its [words]. */
    show(text, options) {
      const o = options || {};
      const root = document.documentElement.style;
      for (const key of ['bg', 'fg', 'accent', 'muted', 'line', 'code', 'mark']) if (o[key]) root.setProperty('--' + key, o[key]);
      document.documentElement.classList.toggle('dark', !!o.dark);
      // One formula or block alone, inside the editor: no page round it, and its height told to the app.
      document.documentElement.classList.toggle('compact', !!o.compact);
      if (o.words) words = Object.assign(words, o.words);
      const y = window.scrollY;
      render(text);
      window.scrollTo(0, y);
      if (o.compact && bridge.size) requestAnimationFrame(() => bridge.size(Math.ceil(note.getBoundingClientRect().height)));
    },
  };
  bridge.ready();
})();
