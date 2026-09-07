import * as XLSX from 'xlsx';
import { zipSync, unzipSync, strToU8, strFromU8 } from 'fflate';

export const markdown = '# 회의 기록\n\n**중요한 내용**과 *강조*, ~~취소~~, `코드`를 확인합니다.\n\n> 검토할 문서입니다.\n\n- [x] 완료한 작업\n- [ ] 다음 작업\n\n| 항목 | 값 |\n| --- | --- |\n| 진행률 | 75% |\n\n```kotlin\nval answer = 42\n```\n\n<script>window.compromised = true</script>\n<style>body{display:none}</style>\n[외부 링크](https://example.net)\n![외부 이미지](https://example.net/image.png)';
export const plain = '  들여쓰기\n\n\n둘째 문단\t탭 유지\n<태그도 그대로>\n마지막 공백  ';

export function workbookFixture(type = 'xlsx') {
  const book = XLSX.utils.book_new();
  const sheet = XLSX.utils.aoa_to_sheet([
    ['월간 보고서'], ['항목', '금액', '비율', '합계'], ['수입', 1234.5, .25], ['날짜', 45000],
  ]);
  sheet.B3.z = '#,##0.00'; sheet.C3.z = '0%'; sheet.B4.z = 'yyyy-mm-dd';
  sheet.D3 = { t: 'n', f: 'B3*2', v: 2469, z: '#,##0.00' };
  sheet['!merges'] = [XLSX.utils.decode_range('A1:D1')];
  sheet['!cols'] = [{ wch: 24 }, { wch: 18 }, { wch: 14 }, { wch: 18 }];
  sheet['!rows'] = [{ hpt: 42 }];
  XLSX.utils.sheet_add_aoa(sheet, [['다음 구간']], { origin: 'A201' });
  XLSX.utils.sheet_add_aoa(sheet, [['먼 열의 값']], { origin: 'CM1' });
  XLSX.utils.book_append_sheet(book, sheet, '수입');
  XLSX.utils.book_append_sheet(book, XLSX.utils.aoa_to_sheet([['둘째 시트'], ['한글 이름을 유지합니다.']]), '메모');
  const bytes = XLSX.write(book, { type: 'buffer', bookType: type });
  if (type !== 'xlsx') return bytes;
  const zip = unzipSync(bytes);
  let styles = strFromU8(zip['xl/styles.xml']);
  // Keep the writer's number-format XFs, append one visual XF, and point A1 at it.
  const count = Number(styles.match(/<cellXfs count="(\d+)"/)[1]);
  const fonts = Number(styles.match(/<fonts count="(\d+)"/)[1]);
  const fills = Number(styles.match(/<fills count="(\d+)"/)[1]);
  styles = styles.replace(/<fonts count="\d+"/, `<fonts count="${fonts + 1}"`)
    .replace('</fonts>', '<font><b/><sz val="20"/><color rgb="FFFFFFFF"/><name val="Arial"/></font></fonts>')
    .replace(/<fills count="\d+"/, `<fills count="${fills + 1}"`)
    .replace('</fills>', '<fill><patternFill patternType="solid"><fgColor rgb="FF217346"/></patternFill></fill></fills>')
    .replace(/<cellXfs count="\d+"/, `<cellXfs count="${count + 1}"`)
    .replace('</cellXfs>', `<xf numFmtId="0" fontId="${fonts}" fillId="${fills}" borderId="0" xfId="0" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf></cellXfs>`);
  zip['xl/styles.xml'] = strToU8(styles);
  zip['xl/worksheets/sheet1.xml'] = strToU8(strFromU8(zip['xl/worksheets/sheet1.xml'])
    .replace(/<c r="A1"[^>]*>/, match => match.replace(/ s="\d+"/, '').replace('>', ` s="${count}">`)));
  return zipSync(zip);
}
