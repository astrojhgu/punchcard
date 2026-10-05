#!/usr/bin/env python3
"""逐单元格对比两份考勤表 docx。

用法:
    python3 tools/compare_docx.py build/ref/考勤表_202607_张三.docx build/dut/考勤表_202607_张三.docx

对比三个层次，任一层有差异都算失败（退出码 1）：
  1. 表格逻辑网格的全部单元格文本（含表头、图例，不只是我们填的那些格子）
  2. body 里所有段落的文本（标题、填报人行）
  3. 目标日期格的单元格 XML（元素结构 / 属性 / 文本），归一化后比较

第 1、2 层抓「内容写错」，第 3 层抓「格式走样」（字体、字号、居中丢了）。
"""
import sys
import zipfile
import xml.etree.ElementTree as ET

W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'


def document_root(path):
    with zipfile.ZipFile(path) as z:
        return ET.fromstring(z.read('word/document.xml'))


def body_of(root):
    return root.find(W + 'body')


def para_texts(body):
    return [''.join(t.text or '' for t in p.iter(W + 't'))
            for p in body.findall(W + 'p')]


def tc_span(tc):
    pr = tc.find(W + 'tcPr')
    if pr is None:
        return 1
    gs = pr.find(W + 'gridSpan')
    return int(gs.get(W + 'val')) if gs is not None else 1


def text_of(el):
    return ''.join(t.text or '' for t in el.iter(W + 't'))


def grid(body):
    """{(row, col): text}，按 gridSpan 展开成逻辑网格。"""
    tbl = body.find(W + 'tbl')
    if tbl is None:
        raise SystemExit('文档里没有表格')
    out = {}
    for ri, tr in enumerate(tbl.findall(W + 'tr')):
        col = 0
        for tc in tr.findall(W + 'tc'):
            span = tc_span(tc)
            for k in range(span):
                out[(ri, col + k)] = text_of(tc)
            col += span
    return out


def normalize(el):
    """把元素序列化成可比较的规范化字符串：属性排序、去掉纯空白文本节点。"""
    def walk(e):
        attrs = tuple(sorted(e.attrib.items()))
        children = [c for c in e if not (c.tag == W + 't' and not (c.text or ''))]
        kids = tuple(walk(c) for c in children)
        text = (e.text or '') if e.tag == W + 't' else ''
        return (e.tag, attrs, text, kids)
    return repr(walk(el))


def target_cells(body, targets):
    """取若干 (row, day) 的 tc 元素，用于格式层比较。"""
    tbl = body.find(W + 'tbl')
    rows = tbl.findall(W + 'tr')
    out = {}
    for ri, day in targets:
        if ri >= len(rows):
            continue
        col = 0
        for tc in rows[ri].findall(W + 'tc'):
            span = tc_span(tc)
            if col == day + 1:
                out[(ri, day)] = tc
                break
            col += span
    return out


def main():
    ref_path, dut_path = sys.argv[1], sys.argv[2]
    ref_body, dut_body = body_of(document_root(ref_path)), body_of(document_root(dut_path))

    failures = []

    ref_paras, dut_paras = para_texts(ref_body), para_texts(dut_body)
    if ref_paras != dut_paras:
        failures.append('段落文本不一致')
        for i, (a, b) in enumerate(zip(ref_paras, dut_paras)):
            if a != b:
                print(f'  [para {i}] ref={a!r}\n             dut={b!r}')
        if len(ref_paras) != len(dut_paras):
            print(f'  段落数不同: ref={len(ref_paras)} dut={len(dut_paras)}')
    else:
        print(f'段落文本: {len(ref_paras)} 段全部一致')

    ref_grid, dut_grid = grid(ref_body), grid(dut_body)
    keys = sorted(set(ref_grid) | set(dut_grid))
    diffs = [(k, ref_grid.get(k), dut_grid.get(k)) for k in keys if ref_grid.get(k) != dut_grid.get(k)]
    if diffs:
        failures.append(f'表格网格有 {len(diffs)} 处不一致')
        for (r, c), a, b in diffs[:40]:
            print(f'  [r{r} c{c}] ref={a!r} dut={b!r}')
        if len(diffs) > 40:
            print(f'  ...还有 {len(diffs) - 40} 处')
    else:
        print(f'表格网格: {len(keys)} 个逻辑单元格全部一致')

    # 格式层：只比我们真正写过的 62 个格子（行 5/6 x 日 1..31）
    rc = target_cells(ref_body, [(5, d) for d in range(1, 32)] + [(6, d) for d in range(1, 32)])
    dc = target_cells(dut_body, [(5, d) for d in range(1, 32)] + [(6, d) for d in range(1, 32)])
    fmt_diffs = []
    for key in sorted(set(rc) | set(dc)):
        a, b = rc.get(key), dc.get(key)
        if a is None or b is None:
            fmt_diffs.append((key, '缺失', '缺失'))
            continue
        if normalize(a) != normalize(b):
            fmt_diffs.append((key, ET.tostring(a, encoding='unicode'), ET.tostring(b, encoding='unicode')))
    if fmt_diffs:
        failures.append(f'目标格格式有 {len(fmt_diffs)} 处不一致')
        for (r, d), a, b in fmt_diffs[:10]:
            print(f'  [r{r} {d}日] 单元格结构不同')
            print(f'    ref={a[:400]}')
            print(f'    dut={b[:400]}')
    else:
        print(f'目标格格式: {len(rc)} 个格子的 XML 结构一致')

    if failures:
        print('\nFAIL: ' + '; '.join(failures))
        return 1
    print('\nPASS: 两份 docx 在内容与格式上完全一致')
    return 0


if __name__ == '__main__':
    sys.exit(main())
