#!/usr/bin/env python3
"""替换 template.docx 里表格「姓名」格的文字。

模板只有一个真源：项目根目录的 template.docx。
app/src/main/assets/template.docx 由 Gradle 在构建时自动同步
（见 app/build.gradle.kts 的 syncTemplate 任务），**不要手工编辑那一份**。

实现上刻意不做 DOM 序列化：python-docx / ElementTree 写回会重排命名空间前缀和
属性顺序，整个 document.xml 都会变样，之后就没法判断"这次改动到底改了哪儿"。
这里只用字符串替换那一处文本，其余字节原样透传。

用法:
    python3 tools/set_template_name.py                 # 只显示当前姓名
    python3 tools/set_template_name.py 张三            # 改成 张三
"""
import os
import sys
import zipfile
import xml.etree.ElementTree as ET

W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
DOCUMENT = 'word/document.xml'
NAME_ROW = 5          # 表格里「姓名」所在行（0 基），与原 run.py 的 irow 同一行组
DOCX = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'template.docx')


def detect_name(path):
    """从表格第 NAME_ROW 行第 0 列读出当前姓名。只读，不改文件。"""
    with zipfile.ZipFile(path) as z:
        xml = z.read(DOCUMENT)
    root = ET.fromstring(xml)
    table = root.find(W + 'body').find(W + 'tbl')
    if table is None:
        raise SystemExit('template.docx 里找不到表格')
    rows = table.findall(W + 'tr')
    if len(rows) <= NAME_ROW:
        raise SystemExit(f'template.docx 只有 {len(rows)} 行，读不到第 {NAME_ROW} 行')
    cells = rows[NAME_ROW].findall(W + 'tc')
    return ''.join(t.text or '' for t in cells[0].iter(W + 't')).strip()


def replace_text(path, old, new):
    with zipfile.ZipFile(path) as z:
        entries = [(i, z.read(i.filename)) for i in z.infolist()]
    xml = next(d for i, d in entries if i.filename == DOCUMENT).decode('utf-8')

    needle = f'<w:t>{old}</w:t>'
    count = xml.count(needle)
    if count == 0:
        raise SystemExit(f'在 document.xml 里找不到独立的 <w:t>{old}</w:t>，模板结构可能变了')
    if count > 1:
        raise SystemExit(
            f'「{old}」在 document.xml 里出现了 {count} 次，不能盲目全局替换；'
            '请人工确认要改哪一处'
        )
    new_xml = xml.replace(needle, f'<w:t>{new}</w:t>').encode('utf-8')

    tmp = path + '.tmp'
    with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
        for info, data in entries:
            zout.writestr(info, new_xml if info.filename == DOCUMENT else data)
    os.replace(tmp, path)


def main():
    old = detect_name(DOCX)
    if len(sys.argv) < 2:
        print(f'{DOCX} 里的姓名是：{old}')
        return
    new = sys.argv[1].strip()
    if not new:
        raise SystemExit('新姓名不能为空')
    if new == old:
        print(f'姓名已经是 {new}，无需改动')
        return
    replace_text(DOCX, old, new)
    print(f'{old} -> {new}')
    print(f'已写回 {DOCX}（当前读到：{detect_name(DOCX)}）')


if __name__ == '__main__':
    main()
