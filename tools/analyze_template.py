#!/usr/bin/env python3
"""解析 template.docx 的表格网格结构，产出 DocxGenerator 必须复现的定位基准。

用法:
    python3 tools/analyze_template.py template.docx

输出:
  1. 每个 w:tbl 的 w:tblGrid 列数
  2. 每行的 tc 概要: 文本 / gridSpan / vMerge
  3. 展开后的逻辑网格 (row, col) -> tc 索引
  4. run.py 语义下的目标单元格: row in (5,6), col = day+1

这份输出就是 Kotlin DocxGenerator 里单元格定位逻辑的黄金基准。
"""
import sys
import zipfile
import xml.etree.ElementTree as ET

W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
NS = {'w': W[1:-1]}


def text_of(el):
    return ''.join(t.text or '' for t in el.iter(W + 't'))


def tc_props(tc):
    pr = tc.find(W + 'tcPr')
    span, vmerge = 1, None
    if pr is not None:
        gs = pr.find(W + 'gridSpan')
        if gs is not None:
            span = int(gs.get(W + 'val'))
        vm = pr.find(W + 'vMerge')
        if vm is not None:
            vmerge = vm.get(W + 'val') or 'continue'
    return span, vmerge


def grid_of(tbl):
    """把 w:tbl 展开成逻辑网格。

    返回 rows[r] = list of (logical_col_start, span, tc_element, vmerge)
    以及 ncols。
    """
    grid_el = tbl.find(W + 'tblGrid')
    declared = len(grid_el.findall(W + 'gridCol')) if grid_el is not None else None
    rows = []
    for tr in tbl.findall(W + 'tr'):
        row, col = [], 0
        for tc in tr.findall(W + 'tc'):
            span, vm = tc_props(tc)
            row.append((col, span, tc, vm))
            col += span
        rows.append((row, col))
    return declared, rows


def main(path):
    with zipfile.ZipFile(path) as z:
        xml = z.read('word/document.xml')
    root = ET.fromstring(xml)
    body = root.find(W + 'body')
    tbls = body.findall(W + 'tbl')
    print(f'# tables = {len(tbls)}')

    for ti, tbl in enumerate(tbls):
        declared, rows = grid_of(tbl)
        print(f'\n=== table {ti}: tblGrid declares {declared} cols, {len(rows)} rows ===')
        for ri, (row, width) in enumerate(rows):
            desc = []
            for (c0, span, tc, vm) in row:
                tag = text_of(tc).strip()
                marks = []
                if span != 1:
                    marks.append(f'span{span}')
                if vm:
                    marks.append(f'vmerge:{vm}')
                desc.append(f'[{c0}]{tag!r}{"(" + ",".join(marks) + ")" if marks else ""}')
            print(f'  r{ri:<3} width={width:<3} {" ".join(desc)}')

        # 展开逻辑网格: (row, col) -> tc 元素 (跟随 vMerge 向上找 restart)
        print(f'\n--- logical grid lookup for table {ti} ---')
        grid = {}
        for ri, (row, width) in enumerate(rows):
            for (c0, span, tc, vm) in row:
                for dd in range(span):
                    grid[(ri, c0 + dd)] = (tc, vm, ri)

        def resolve(ri, ci):
            """模拟 python-docx: vMerge continue 返回上方起始 tc。"""
            hit = grid.get((ri, ci))
            if hit is None:
                return None
            tc, vm, r0 = hit
            if vm == 'continue':
                rr = r0 - 1
                while rr >= 0:
                    up = grid.get((rr, ci))
                    if up is None:
                        break
                    utc, uvm, ur0 = up
                    if uvm in (None, 'restart') and utc is not tc:
                        return ('INHERITED', utc, ur0)
                    if utc is tc or uvm == 'restart':
                        break
                    rr = ur0 - 1
            return ('OWN', tc, r0)

        for target_row in (5, 6):
            cells = []
            for day in range(1, 32):
                res = resolve(target_row, day + 1)
                if res is None:
                    cells.append(f'{day}:MISSING')
                else:
                    kind, tc, r0 = res
                    cells.append(f'{day}:r{r0}{"/" + kind if kind == "INHERITED" else ""}')
            print(f'  row {target_row}: ' + ' '.join(cells))

        # 打印要写入的目标格的 XML 片段（第 5 行第 2 列 = 1 日）
        print(f'\n--- sample target tc XML (row5, 1日) ---')
        res = resolve(5, 2)
        if res:
            print(ET.tostring(res[1], encoding='unicode')[:2000])


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'template.docx')
