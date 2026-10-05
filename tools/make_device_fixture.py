#!/usr/bin/env python3
"""把测试 fixture 转成 app 的存储格式，用于在模拟器/真机上做端到端验证。

关键点：状态标签 -> code 的映射**从 Kotlin 源码里解析**（AttendanceStatus.kt），
不在这里再抄一份。符号表只能有一个真源，抄第二份迟早漂移。

用法:
    python3 tools/make_device_fixture.py [YYYYMM]
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATUS_KT = os.path.join(
    ROOT, 'app', 'src', 'main', 'java', 'com', 'gujunhua', 'attendance', 'core', 'AttendanceStatus.kt'
)


def parse_status_table():
    """从 AttendanceStatus.kt 解析 enum 条目 -> {label: (code, symbol)}。"""
    src = open(STATUS_KT, encoding='utf-8').read()
    pattern = re.compile(
        r'^\s*([A-Z_]+)\("([^"]+)",\s*"([^"]+)",\s*"([^"]*)",\s*Group\.(\w+)\)',
        re.M,
    )
    table = {}
    for name, code, label, symbol, group in pattern.findall(src):
        table[label] = (code, symbol)
    if not table:
        raise SystemExit(f'没能从 {STATUS_KT} 解析出任何状态，正则或格式变了？')
    return table


def main():
    yyyymm = sys.argv[1] if len(sys.argv) > 1 else '202607'
    table = parse_status_table()

    fixture = json.load(open(os.path.join(ROOT, 'app', 'src', 'test', 'resources', 'fixture.json'),
                             encoding='utf-8'))
    if fixture['month'] != yyyymm:
        raise SystemExit(f"fixture 是 {fixture['month']}，与请求的 {yyyymm} 不一致；先跑 tools/make_fixture.py {yyyymm}")

    records = {}
    for date, pair in fixture['records'].items():
        entry = {}
        for slot, label in zip(('am', 'pm'), pair):
            code = table[label][0]
            # symbol 为空的（周末/放假）在 app 里也是有效记录，一样要写
            entry[slot] = code
        records[date] = entry

    out = os.path.join(ROOT, 'build', 'device_records.json')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, 'w', encoding='utf-8') as fh:
        json.dump({'version': 1, 'records': records}, fh, ensure_ascii=False, indent=2)
    print(f'wrote {out} ({len(records)} days)')
    print('状态表来源:', STATUS_KT)
    print('样例:', dict(list(records.items())[:3]))


if __name__ == '__main__':
    main()
