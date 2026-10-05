# 考勤 app

把原来「每天在电脑上跑 run.py 填 md 文件」的流程搬到手机上：每天定点弹一条通知问
上午/下午的出勤情况，记录存在手机本地，月底直接生成与原模板完全一致的考勤表 docx。

原方案（`run.py` + `template.docx`）保留在仓库里，作为新实现的**验证基准**，不是死代码。

## 它做什么

- **每天定点一条通知**，带【出勤】【出差】【请假…】三个快捷按钮。点前两个 = 上下午都记成该状态，一键收工；点【请假…】或通知正文进填报界面，上午/下午分别选。
- **请假细类**：补休 □ / 年休假 △ / 事假 S / 病假 B / 婚假 H / 丧假 SJ / 探亲 T / 产假 CJ / 育儿假 Y / 护理假 HL / 工伤 G / 迟到 C / 早退 Z / 旷工 K。
- **周末自动留空**，法定节假日/调休在日历上手动标（调休安排每年变，不写死在代码里）。
- **当天没填会重复提醒**（默认间隔 2 小时、最多 3 次），首页常驻「本月还有 N 天没填」，点日历任意一天可补填/修改。
- **月底生成 docx**：每月最后一天自动生成，也随时可以手动点「生成考勤表」，可分享或存到系统「下载」目录。
- **不申请任何网络权限**，数据全在本机；提供 JSON 备份的导出/导入。

## 目录结构

```
run.py                     原方案，作为验证基准保留
template.docx              原模板，也是 app 里打包的资源
app/src/main/assets/       template.docx 的副本（app 运行时读它）
app/src/main/java/.../core/    状态表(DateUtils/AttendanceStatus) —— 符号的唯一真源
app/src/main/java/.../docx/    DocxGenerator(改 XML) + SheetExporter(出文件)
app/src/main/java/.../data/    JSON 存储与设置
app/src/main/java/.../notify/  闹钟与通知
app/src/main/java/.../ui/      三个界面
tools/                     构建、验证、基准对比脚本
build/ref/                 run.py 生成的基准 docx（每次 verify 重建）
build/dut/                 app 逻辑生成的 docx（每次 verify 重建）
```

## 环境（本机是 NixOS）

没有 Android Studio，全部命令行。

```bash
# 1. JDK（nix 提供，避免官方预编译 JDK 在 NixOS 上的 glibc 问题）
nix profile add nixpkgs#jdk21

# 2. Android SDK（必须装在可写目录，nix store 只读，sdkmanager 没法往里装组件）
#    见 tools/setup_android_sdk.sh：下载 cmdline-tools 并安装
#    platform-tools / platforms;android-35 / build-tools;35.0.0 / emulator / system-image
bash tools/setup_android_sdk.sh

# 3. Gradle（下载发行版而不是用系统包，版本与 AGP 对齐可控）
#    见 tools/gradle.sh，它负责导出 JAVA_HOME / ANDROID_HOME
```

`local.properties` 里写 `sdk.dir=$HOME/android-sdk`。

## 构建

```bash
tools/gradle.sh :app:assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
tools/gradle.sh :app:assembleRelease    # -> app/build/outputs/apk/release/app-release.apk
```

也可以直接用仓库里的 `./gradlew`（wrapper 已提交），它会自己下 Gradle 发行版。

### 关于签名密钥

`tools/attendance.keystore` **不在版本库里**（见 `.gitignore`）。它是这个 app 的身份：

- **丢了** → 再也无法覆盖升级，只能卸载重装，记录全丢。换机器请手工拷贝。
- **泄露** → 别人能签出你手机愿意接受的"更新包"。

clone 下来的副本没有它，构建时会打印一条警告并回退到 AGP 默认 debug 签名 ——
**依然能构建成功**，只是签出来的包跟已安装版本证书不同，装上去需要先卸载。

`debug` 和 `release` 共用同一套密钥，所以调试包和发布包可以互相覆盖安装、数据保留。

### 模板只有一个真源

项目根目录的 `template.docx` 是唯一真源（原方案的 `run.py` 也用它）。
`app/src/main/assets/template.docx` 由 `syncTemplate` 任务在构建时自动同步，**已加入 .gitignore，不要手工编辑**。

改表里的姓名用脚本，不要直接编辑 docx：

```bash
python3 tools/set_template_name.py 张三     # 只改「姓名」那一格，其余字节原样透传
```

## 验证：怎么证明生成的表没跑偏

这是整个项目最要紧的一条纪律：**docx 生成逻辑的任何改动，都必须跑 `tools/verify.sh`。**

```bash
tools/verify.sh 202607
```

它做四件事：

1. `tools/make_fixture.py` 造一份覆盖**全部 18 种状态**的测试数据（`build/fixture/`），
   同时把结构化版本写到 `app/src/test/resources/fixture.json`，
   保证 run.py 用的和 Kotlin 单测用的是**同一份数据**。
2. 用**原来的 run.py** 生成基准 docx → `build/ref/`。
3. 跑 Kotlin 单测，用同一份数据经新实现生成 docx → `build/dut/`。
4. `tools/compare_docx.py` 逐层对比两份文件：
   - body 全部段落文本
   - **510 个逻辑单元格**（15 行 × 34 列）的文本
   - **62 个日期格**（行 5/6 × 31 天）的单元格 XML 结构

第 4 步全绿，才说明新实现与原方案在内容**和格式**上都一致。

## 手机上要手动开的三项（小米 HyperOS）

国产 ROM 上「某天没收到提醒」几乎都不是 app 的 bug，而是系统拦截。设置页里有对应入口：

1. **允许精确闹钟** — Android 12+ 把精确闹钟列为敏感权限，默认拒绝。
2. **自启动管理** — 不开的话 app 被杀后不再自己起来排闹钟。
3. **电池优化设为「无限制」** — 否则深度休眠时闹钟会被推迟甚至吞掉。

另外首次启动要允许通知（Android 13+ 运行时权限）。

## 已知取舍

- **没有内置法定节假日表**。调休安排每年由国务院公布，写死在代码里迟早出错，所以只自动判周六周日，节假日你在日历上点一下标掉（会从「未填」统计里消失，表里留空）。
- **只维持一个待触发闹钟**。每次触发时决定下一个是「今天的重复提醒」还是「明天的第一次」，用同一个 PendingIntent 覆盖。改设置、填记录、重启都会重排，不存在多个闹钟互相踩。
- **release 用 debug 签名**。个人侧载，不进应用商店，可读的崩溃栈比签名规范重要。
- **debug 包里会有 INTERNET 权限**，那是 AGP 为调试器自动注入的；release 包没有，主 manifest 里也没申请。

## 踩过的坑（都已在代码/脚本里固化）

| 坑 | 表现 | 处理 |
|---|---|---|
| `python3 -m zipfile -e` 解压 cmdline-tools | `sdkmanager: Permission denied`（exit 126） | `tools/setup_android_sdk.sh` 里补 `chmod +x` |
| emulator 二进制在 NixOS 上 | `libX11.so.6: cannot open shared object file` | `tools/emulator-fhs.nix` 提供 FHS 沙箱 |
| Android 的 `org.json` 在 JVM 单测里是 stub | JSON 单测假失败 | `testImplementation("org.json:json:...")` |
| `JSONObject.keys()` 返回 `Iterator` | Kotlin 里没有 `.sorted()` | `.asSequence().sorted()` |
| `python-docx` 写空值时 | 生成 `<w:r>` 但**不生成** `<w:t>` | Kotlin 侧同样不生成 `<w:t>`，保证 XML 逐字一致 |
| `cell.text = s` 会丢弃原格 `pPr` | 字体/行距与模板不同 | 复刻该行为，不"顺手保留" |
| 从 `xml.indexOf('<')` 解析 document.xml | 撞上 `<?xml?>` 声明返回 null | 从 `<w:document` 开始解析 |
| `XmlScan.attribute` 对自闭合元素失效 | 所有 `gridSpan` 被当成 1，合并列的行号全错 | 重新用 `findTagEnd` 求开标签结束位置 |
| 真机 adb 报 `no permissions` | 非 seat 会话拿不到 logind 的 uaccess ACL | `tools/adb-usb-permit.sh` 显式 chmod |
| `dl.google.com` 只解析出 IPv6 | release 构建卡在 `lintVitalRelease` 拉依赖上，24 分钟后超时 | `lint { checkReleaseBuilds = false }` |
| 用 AGP 默认的 `~/.android/debug.keystore` | 该目录被清理后 AGP 自动新建密钥，新旧 APK 证书不同，`adb install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，只能卸载重装丢数据 | 密钥固定为项目内 `tools/attendance.keystore`，debug/release 共用 |
| `Notifications.notify` 里用 `runCatching {}` 吞异常 | 通知不响但**没有任何报错**，无从排查 | 改为捕获后 `Log.e`，并返回成功与否 |
| 提醒被静默跳过 | 「立刻试一次提醒」在当天已填时毫无反应，无法验证链路 | 加 `EXTRA_FORCE`，手动测试无视「已填」强制弹出 |
