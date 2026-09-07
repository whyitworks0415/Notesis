import DOMPurify from 'dompurify';
import { unzipSync } from 'fflate';

export const MAX_SOURCE = 128 * 1024 * 1024;
const MAX_EXPANDED = 96 * 1024 * 1024;
const MAX_ENTRY = 24 * 1024 * 1024;

// Check declared expansion before handing the same archive to a format engine.
// fflate verifies the entries it actually decodes. Bounded input also covers CFB.
export function inspectZip(bytes, keepXml = false) {
  let total = 0;
  let count = 0;
  return unzipSync(bytes, { filter(entry) {
    total += entry.originalSize;
    if (++count > 20000 || entry.originalSize > MAX_ENTRY || total > MAX_EXPANDED) {
      throw new Error('문서 내부 데이터가 너무 큽니다.');
    }
    return keepXml && /^(xl\/.*\.xml|xl\/.*\.rels)$/i.test(entry.name);
  } });
}

export function decodeText(bytes) {
  if (bytes[0] === 0xff && bytes[1] === 0xfe) return new TextDecoder('utf-16le').decode(bytes);
  if (bytes[0] === 0xfe && bytes[1] === 0xff) return new TextDecoder('utf-16be').decode(bytes);
  try { return new TextDecoder('utf-8', { fatal: true }).decode(bytes); }
  catch { return new TextDecoder('euc-kr').decode(bytes); }
}

export function sanitize(markup, svg = false) {
  // Native SVG text keeps slide geometry while avoiding executable foreignObject HTML.
  return DOMPurify.sanitize(markup, {
    USE_PROFILES: svg ? { svg: true, svgFilters: true } : { html: true },
    FORBID_TAGS: ['script', 'foreignObject', 'iframe', 'object', 'embed', 'form', 'style', 'link', 'base', 'video', 'audio'],
    FORBID_ATTR: ['srcset', 'action', 'formaction'],
    ADD_DATA_URI_TAGS: ['image'],
  });
}

export function lockLinks(root) {
  root.querySelectorAll('a').forEach(link => {
    link.removeAttribute('href');
    link.removeAttribute('xlink:href');
    link.removeAttribute('target');
  });
  root.querySelectorAll('img, image').forEach(img => {
    const uri = img.getAttribute('src') || img.getAttribute('href') || img.getAttribute('xlink:href') || '';
    if (!/^(data:image\/(?:png|jpeg|gif|webp|bmp|svg\+xml);|blob:)/i.test(uri)) {
      const label = document.createElement('span');
      label.textContent = img.getAttribute('alt') || '[외부 이미지]';
      img.replaceWith(label);
    }
  });
}
