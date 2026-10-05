#!/usr/bin/env python3
"""生成基准对比用的考勤工作数据。

产出两样东西，内容严格一致，只是序列化不同：
  build/fixture/YYYYMMDD.md   —— 喂给原 run.py 的输入（与原方案同格式，供生成基准 docx）
  build/fixture.json          —— 同一份数据的结构化版本（供 Kotlin DocxGenerator 单元测试复用）

设计目标：把符号表里每一种状态都覆盖到，这样基准对比不是"抽样验证"而是全量验证。

用法:
    python3 tools/make_fixture.py [YYYYMM]
"""
import datetime
import json
import os
import sys

# 顺序即填充顺序，逐一铺满 (天数 x 2) 个槽位。
# 前 16 个是脚本 symbols 表里所有非空符号，后 2 个是空符号（周末/放假）。
STATES = [
    '出勤', '出差', '补休', '年休假', '事假', '病假', '婚假', '丧假',
    '探亲', '产假', '育儿假', '护理假', '工伤', '迟到', '早退', '旷工',
    '周末', '放假',
]

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUTDIR = os.path.join(ROOT, 'build', 'fixture')
# 结构化版本直接落进测试资源目录：Kotlin 单测和 run.py 基准用的是同一份数据，
# 不存在"两份 fixture 各自漂移"的可能。
FIXTURE_JSON = os.path.join(ROOT, 'app', 'src', 'test', 'resources', 'fixture.json')


def build_records(yyyymm):
    yyyy, mm = int(yyyymm[:4]), int(yyyymm[4:6])
    ndays = (datetime.date(yyyy + (mm == 12), mm % 12 + 1, 1) - datetime.timedelta(days=1)).day
    records = {}
    slot = 0
    for day in range(1, ndays + 1):
        pair = []
        for _ in range(2):
            pair.append(STATES[slot % len(STATES)])
            slot += 1
        records[f'{yyyy:04d}-{mm:02d}-{day:02d}'] = pair
    return records


def main():
    yyyymm = sys.argv[1] if len(sys.argv) > 1 else '202607'
    records = build_records(yyyymm)
    os.makedirs(OUTDIR, exist_ok=True)

    # 清掉旧的 md，避免 run.py 的 glob 读到其他月份
    for f in os.listdir(OUTDIR):
        if f.endswith('.md'):
            os.remove(os.path.join(OUTDIR, f))

    for date, pair in records.items():
        md = os.path.join(OUTDIR, date.replace('-', '') + '.md')
        with open(md, 'w', encoding='utf-8') as fh:
            fh.write(' '.join(pair) + '\n')

    fixture_json = FIXTURE_JSON
    os.makedirs(os.path.dirname(fixture_json), exist_ok=True)
    with open(fixture_json, 'w', encoding='utf-8') as fh:
        json.dump({'month': yyyymm, 'records': records}, fh, ensure_ascii=False, indent=2)

    print(f'wrote {len(records)} md files -> {OUTDIR}')
    print(f'wrote {fixture_json}')


if __name__ == '__main__':
    main()
