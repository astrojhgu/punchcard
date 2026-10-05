#!/usr/bin/env bash
# 一条命令跑完全部验证：单测 -> 生成产物 -> 与 run.py 的基准逐单元格对比。
#
# 这个脚本是「改动了 docx 生成逻辑之后是否还把表填对了」的唯一入口。
# 不要手动拼命令，跑这个。
set -euo pipefail

cd "$(dirname "$0")/.."

PY="$HOME/venvs/attendance/bin/python"
if [ ! -x "$PY" ]; then
  echo "缺少 python 环境，先建：python3 -m venv ~/venvs/attendance && ~/venvs/attendance/bin/pip install python-docx"
  exit 1
fi

MONTH="${1:-202607}"

echo "=== 1/4 生成 fixture ==="
"$PY" tools/make_fixture.py "$MONTH"

echo "=== 2/4 用原 run.py 生成基准 docx ==="
rm -rf build/ref && mkdir -p build/ref
cp template.docx run.py build/ref/
cp build/fixture/*.md build/ref/
( cd build/ref && "$PY" run.py "$MONTH" )

echo "=== 3/4 Kotlin 单测（同时产出 build/dut/ 里的待对比文件）==="
tools/gradle.sh :app:testDebugUnitTest --console=plain -q

DUT="build/dut/考勤表_${MONTH}_张三.docx"
REF="build/ref/考勤表_${MONTH}_张三.docx"
if [ ! -f "$DUT" ]; then
  echo "单测没有产出 $DUT"
  exit 1
fi

echo "=== 4/4 逐单元格基准对比 ==="
"$PY" tools/compare_docx.py "$REF" "$DUT"
