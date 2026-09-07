import * as XLSX from 'xlsx';

const children = (node, tag) => Array.from(node?.children || []).filter(n => n.localName === tag);
const child = (node, tag) => children(node, tag)[0];
const all = (node, tag) => Array.from(node?.getElementsByTagNameNS('*', tag) || []);
const val = (node, tag) => child(node, tag)?.getAttribute('val');
const flag = (node, tag) => !!child(node, tag) && val(node, tag) !== '0';
const clamp = (n, min, max) => Math.max(min, Math.min(max, n));
const indexedColors = ['000000', 'FFFFFF', 'FF0000', '00FF00', '0000FF', 'FFFF00', 'FF00FF', '00FFFF',
  '000000', 'FFFFFF', 'FF0000', '00FF00', '0000FF', 'FFFF00', 'FF00FF', '00FFFF',
  '800000', '008000', '000080', '808000', '800080', '008080', 'C0C0C0', '808080',
  '9999FF', '993366', 'FFFFCC', 'CCFFFF', '660066', 'FF8080', '0066CC', 'CCCCFF',
  '000080', 'FF00FF', 'FFFF00', '00FFFF', '800080', '800000', '008080', '0000FF',
  '00CCFF', 'CCFFFF', 'CCFFCC', 'FFFF99', '99CCFF', 'FF99CC', 'CC99FF', 'FFCC99',
  '3366FF', '33CCCC', '99CC00', 'FFCC00', 'FF9900', 'FF6600', '666699', '969696',
  '003366', '339966', '003300', '333300', '993300', '993366', '333399', '333333'];

function xmlPart(entries, path) {
  const bytes = entries[path] || entries[Object.keys(entries).find(key => key.toLowerCase() === path.toLowerCase())];
  if (!bytes) return null;
  const text = new TextDecoder().decode(bytes);
  if (/<!DOCTYPE|<!ENTITY/i.test(text)) throw new Error('지원하지 않는 XML 문서입니다.');
  const xml = new DOMParser().parseFromString(text, 'application/xml');
  if (all(xml, 'parsererror').length) throw new Error('Excel 서식이 손상되었습니다.');
  return xml;
}

function color(node, theme) {
  if (!node) return null;
  let rgb = node.getAttribute('rgb')?.slice(-6);
  if (!rgb && node.hasAttribute('theme')) rgb = theme[Number(node.getAttribute('theme'))];
  if (!rgb && node.hasAttribute('indexed')) rgb = indexedColors[Number(node.getAttribute('indexed'))];
  if (!rgb || !/^[a-f\d]{6}$/i.test(rgb)) return null;
  const tint = clamp(Number(node.getAttribute('tint') || 0), -1, 1);
  if (tint) rgb = rgb.match(/../g).map(pair => {
    const channel = parseInt(pair, 16);
    return Math.round(tint < 0 ? channel * (1 + tint) : channel + (255 - channel) * tint).toString(16).padStart(2, '0');
  }).join('');
  return `#${rgb}`;
}

function readStyles(entries) {
  const scheme = all(xmlPart(entries, 'xl/theme/theme1.xml'), 'clrScheme')[0];
  const theme = ['lt1', 'dk1', 'lt2', 'dk2', 'accent1', 'accent2', 'accent3', 'accent4', 'accent5', 'accent6', 'hlink', 'folHlink']
    .map(name => { const n = child(scheme, name)?.firstElementChild; return n?.getAttribute('lastClr') || n?.getAttribute('val'); });
  const root = xmlPart(entries, 'xl/styles.xml')?.documentElement;
  const fonts = children(child(root, 'fonts'), 'font').map(font => ({
    fontFamily: val(font, 'name') ? `${JSON.stringify(val(font, 'name'))}, sans-serif` : undefined,
    fontSize: `${clamp(Number(val(font, 'sz') || 11), 1, 409) * 4 / 3}px`,
    fontWeight: flag(font, 'b') ? 'bold' : 'normal',
    fontStyle: flag(font, 'i') ? 'italic' : 'normal',
    textDecoration: [flag(font, 'u') ? 'underline' : '', flag(font, 'strike') ? 'line-through' : ''].filter(Boolean).join(' '),
    color: color(child(font, 'color'), theme),
  }));
  const fills = children(child(root, 'fills'), 'fill').map(fill => {
    const pattern = child(fill, 'patternFill');
    return pattern?.getAttribute('patternType') === 'solid' ? color(child(pattern, 'fgColor'), theme) : null;
  });
  const borders = children(child(root, 'borders'), 'border').map(border => {
    const css = {};
    for (const side of ['left', 'right', 'top', 'bottom']) {
      const node = child(border, side);
      const type = node?.getAttribute('style');
      if (!type || type === 'none') continue;
      const width = type === 'double' || type === 'thick' ? 3 : /medium/i.test(type) ? 2 : 1;
      const line = type === 'double' ? 'double' : /dash/i.test(type) ? 'dashed' : /dotted|hair/i.test(type) ? 'dotted' : 'solid';
      css[`border${side[0].toUpperCase()}${side.slice(1)}`] = `${width}px ${line} ${color(child(node, 'color'), theme) || '#222'}`;
    }
    return css;
  });
  return children(child(root, 'cellXfs'), 'xf').map(xf => {
    const align = child(xf, 'alignment');
    const horizontal = align?.getAttribute('horizontal');
    const vertical = align?.getAttribute('vertical');
    return {
      ...fonts[Number(xf.getAttribute('fontId') || 0)],
      ...borders[Number(xf.getAttribute('borderId') || 0)],
      backgroundColor: fills[Number(xf.getAttribute('fillId') || 0)],
      textAlign: ['left', 'right', 'center', 'justify'].includes(horizontal) ? horizontal : undefined,
      verticalAlign: vertical === 'center' ? 'middle' : ['top', 'bottom'].includes(vertical) ? vertical : undefined,
      whiteSpace: align?.getAttribute('wrapText') === '1' ? 'pre-wrap' : 'pre',
      paddingLeft: align?.hasAttribute('indent') ? `${7 + clamp(Number(align.getAttribute('indent')), 0, 250) * 10}px` : undefined,
    };
  });
}

function sheetStyleMaps(entries, names) {
  const workbook = xmlPart(entries, 'xl/workbook.xml');
  const rels = all(xmlPart(entries, 'xl/_rels/workbook.xml.rels'), 'Relationship');
  const refs = all(workbook, 'sheet');
  const paths = new Map(rels.filter(r => r.getAttribute('TargetMode') !== 'External').map(r => {
    const path = new URL(r.getAttribute('Target'), 'https://local.invalid/xl/workbook.xml').pathname.slice(1);
    return [r.getAttribute('Id'), path];
  }));
  return names.map((name, index) => {
    const ref = refs.find(s => s.getAttribute('name') === name);
    const id = ref && Array.from(ref.attributes).find(a => a.localName === 'id')?.value;
    const xml = xmlPart(entries, paths.get(id) || `xl/worksheets/sheet${index + 1}.xml`);
    return new Map(all(xml, 'c').filter(c => c.hasAttribute('s')).map(c => [c.getAttribute('r'), Number(c.getAttribute('s'))]));
  });
}

export function openWorkbook(bytes, entries) {
  const book = XLSX.read(bytes, { type: 'array', cellStyles: true, cellNF: true, cellText: true });
  if (!book.SheetNames.length) throw new Error('Excel 시트를 찾을 수 없습니다.');
  const styles = readStyles(entries);
  const styleMaps = sheetStyleMaps(entries, book.SheetNames);
  const names = book.SheetNames.filter((_, i) => !book.Workbook?.Sheets?.[i]?.Hidden);
  const shownNames = names.length ? names : book.SheetNames;
  return {
    names: shownNames,
    sheet(index, columnPage = 0) {
      const name = shownNames[index];
      const sheet = book.Sheets[name];
      const range = XLSX.utils.decode_range(sheet['!ref'] || 'A1');
      const lastRow = clamp(range.e.r, 0, 1048575);
      const lastColumn = clamp(range.e.c, 0, 16383);
      const columnStart = columnPage * 64;
      const columnEnd = Math.min(Math.max(lastColumn, 9), columnStart + 63);
      const rowCount = Math.max(lastRow + 1, 30);
      const rowsPerPage = 200;
      const cellStyles = styleMaps[book.SheetNames.indexOf(name)];
      return {
        count: Math.ceil(rowCount / rowsPerPage),
        columnCount: Math.ceil((lastColumn + 1) / 64),
        columnLabel(page) { return `${XLSX.utils.encode_col(page * 64)}–${XLSX.utils.encode_col(Math.min(lastColumn, page * 64 + 63))}`; },
        render(page, onCell) {
          const rowStart = page * rowsPerPage;
          const rowEnd = Math.min(rowCount - 1, rowStart + rowsPerPage - 1);
          const table = document.createElement('table');
          table.className = 'worksheet';
          table.setAttribute('aria-label', `${name} · ${rowStart + 1}–${rowEnd + 1}행`);
          const cols = document.createElement('colgroup');
          const rowHeader = document.createElement('col'); rowHeader.style.width = '52px'; cols.append(rowHeader);
          let width = 52;
          for (let c = columnStart; c <= columnEnd; c++) {
            const spec = sheet['!cols']?.[c];
            const w = spec?.hidden ? 0 : clamp(spec?.wpx || (spec?.wch ? spec.wch * 7 + 5 : 100), 20, 2000);
            const col = document.createElement('col'); col.style.width = `${w}px`; cols.append(col); width += w;
          }
          table.append(cols); table.style.width = `${width}px`;
          const head = table.createTHead().insertRow(); head.append(document.createElement('th'));
          for (let c = columnStart; c <= columnEnd; c++) {
            const th = document.createElement('th'); th.textContent = XLSX.utils.encode_col(c); th.scope = 'col';
            if (sheet['!cols']?.[c]?.hidden) th.style.display = 'none';
            head.append(th);
          }
          const merges = (sheet['!merges'] || []).filter(m => m.e.r >= rowStart && m.s.r <= rowEnd && m.e.c >= columnStart && m.s.c <= columnEnd);
          const covered = new Set();
          const anchors = new Map();
          for (const m of merges) {
            const startR = Math.max(m.s.r, rowStart), startC = Math.max(m.s.c, columnStart);
            const endR = Math.min(m.e.r, rowEnd), endC = Math.min(m.e.c, columnEnd);
            anchors.set(`${startR},${startC}`, { r: m.s.r, c: m.s.c, rows: endR - startR + 1, cols: endC - startC + 1 });
            for (let r = startR; r <= endR; r++) for (let c = startC; c <= endC; c++) {
              if (r !== startR || c !== startC) covered.add(`${r},${c}`);
            }
          }
          const body = table.createTBody();
          for (let r = rowStart; r <= rowEnd; r++) {
            if (sheet['!rows']?.[r]?.hidden) continue;
            const tr = body.insertRow();
            const height = sheet['!rows']?.[r]?.hpx || (sheet['!rows']?.[r]?.hpt || 21) * 4 / 3;
            tr.style.height = `${clamp(height, 1, 2000)}px`;
            const th = document.createElement('th'); th.scope = 'row'; th.textContent = String(r + 1); tr.append(th);
            for (let c = columnStart; c <= columnEnd; c++) {
              if (covered.has(`${r},${c}`)) continue;
              const merge = anchors.get(`${r},${c}`);
              const address = XLSX.utils.encode_cell(merge || { r, c });
              const cell = sheet[address];
              const td = tr.insertCell(); td.tabIndex = 0; td.dataset.address = address;
              if (sheet['!cols']?.[c]?.hidden) td.style.display = 'none';
              if (merge) { td.rowSpan = merge.rows; td.colSpan = merge.cols; }
              const value = cell ? XLSX.utils.format_cell(cell) : '';
              td.textContent = value;
              td.style.textAlign = cell?.t === 'n' ? 'right' : 'left';
              const css = styles[cellStyles?.get(address) ?? 0] || {};
              for (const [key, value] of Object.entries(css)) if (value != null) td.style[key] = value;
              // BIFF readers expose fills directly; number formats are resolved by SheetJS.
              if (!Object.keys(entries).length && cell?.s?.fgColor?.rgb) td.style.backgroundColor = `#${cell.s.fgColor.rgb.slice(-6)}`;
              td.addEventListener('focus', () => onCell(address, cell?.f ? `=${cell.f}\n저장된 값: ${value || '(없음)'}` : value));
            }
          }
          return { element: table, width, rangeLabel: `${rowStart + 1}–${rowEnd + 1}행 · ${lastRow + 1}행까지 데이터` };
        },
      };
    },
  };
}
