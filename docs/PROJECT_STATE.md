# 项目状态

> 本文件保存未来开发需要的高密度上下文。不要把临时日志或完整会话历史写入此处。

## 项目简介

### 产品目标

个人使用、免费、完全离线优先的 Android 咖啡日记：记录连锁咖啡和个人咖啡豆，按月历展示每日饮用图片，提供月度/年度统计、手动维护品牌与产品，以及完整本地备份恢复。

### 当前开发阶段

当前个人 Debug 侧载范围已取得发布 GO。真机试用后已确认下一里程碑：重做连锁豆库，改为三列品牌 Logo → 双列实拍产品的纯手动流程，并删除不可用的官网更新、截图 OCR 与裁剪。整体规格已确认；另确认当前 Manifest 缺少 NoActionBar 主题，导致系统 `Coffee Journal` ActionBar 遮挡三个根 Tab 的顶部内容。本次会把 App 和首个 Tab 统一改名为“咖啡日历”，不设全局顶栏，并为月历增加可记忆的“品牌／咖啡”显示切换。尚未修改生产代码。

---

# 技术栈

## 客户端

- Kotlin、Jetpack Compose、Material 3
- ViewModel + StateFlow
- Room / SQLite
- Coil 图片加载
- 当前基线仍包含 ML Kit OCR 和 OkHttp 官网更新；下一里程碑将删除两者及相应联网权限

## 服务端 / Authentication

无服务端、无账号、无登录、无云同步。业务数据保存在应用私有目录。

## 部署方式

个人侧载 Debug APK。Release APK 当前未配置用户签名。系统云备份和设备迁移均显式排除应用私有数据；迁移设备前必须手动导出备份。

---

# 当前架构

- `app/src/main/java/com/niumi/coffeejournal/core/model` — 领域模型和不变量
- `core/database` — Room entities、DAO、数据库与迁移
- `journal` — 记录、草稿、月历、记录详情及其 Repository/ViewModel/UI
- `catalog` — 连锁品牌、产品和个人豆库
- `importer` — 官网候选更新、截图 OCR/裁剪与人工确认
- `core/image` — 本地图片内容寻址、引用与三级回退
- `insights` — 月度/年度汇总和记录详情
- `backup` / `settings` — 版本化流式 ZIP、校验、原子恢复和 SAF 设置页

---

# 关键数据流

记录咖啡：

用户选择目录条目并填写表单
→ `JournalViewModel`
→ 持久化 `draft_records`
→ `JournalRepository`
→ Room 事务写入记录与不可变目录/图片快照
→ 日历和 Insights 的 Flow 自动刷新

目录维护（下一里程碑）：

用户选择或新建品牌
→ 手动填写产品名和黑咖／果咖／奶咖类型
→ 可选导入实拍原图
→ 写入当前目录；不得修改历史记录快照

图片回退：产品实拍图 → 品牌 Logo → 通用占位图。图片只从本机选择并保存原图，显示时居中裁切。

备份恢复：

六张业务表一致 Room 快照 + 被引用图片
→ 版本化流式 ZIP 和 SHA/大小/magic 校验
→ 私有 staging 二次校验
→ 图片协调锁 + 单一 Room 事务原子替换
→ 失败/取消回滚并清理临时文件。

---

# 重要技术决策

### 本地优先、零账号

不建设后端或云同步；Android 系统备份与设备迁移也被禁用。跨设备或卸载前必须手动导出备份。

### 连锁目录只允许手动维护（已确认的新方向）

删除官网抓取、微信小程序接口、截图 OCR 和裁剪。品牌与产品均由用户手动维护；产品只允许黑咖、果咖、奶咖三类，实拍图可选且缺图时回退到品牌 Logo。

### 预置品牌与布局（已确认）

按 2025 年底中国大陆在营门店数量预置 Top 10（包含窗口店口径），另保留 Peet's 和 %Arabica，共 12 个本地 Logo。豆库不显示门店数或排名；连锁品牌为三列 Logo 卡片，点击进入双列产品实拍图子页面。

### 历史快照不可被目录更新改写

记录保存产品名称、属性、产品图及品牌 Logo 快照。普通目录更新不改变历史；显式编辑记录且更换产品时才生成新快照。

### 图片与恢复共享变更协调锁

普通图片导入/删除和备份恢复使用同一个 `ImageMutationCoordinator`，避免数据库引用与文件状态竞态。

### 备份安全边界

ZIP 解压限额和压缩比按实际流式读取字节计算，不能信任中心目录大小。恢复写入前重新验证 DB 和每张图片，事务提交边界与取消语义明确。

---

# 当前 Milestone

## 当前目标

实施并验收纯手动连锁豆库改版：

- 三列品牌 Logo 首页与双列产品子页面
- Top 10 + Peet's / %Arabica 共 12 个预置品牌
- 手动新增／编辑品牌 Logo 和产品名称／实拍图／三分类
- 记录页快速新增产品并自动选中
- App／首个 Tab 改名“咖啡日历”，三个 Tab 使用各自页面标题且不设全局顶栏
- 月历支持可记忆的“品牌／咖啡”显示模式
- Room v3 与 v1/v2/v3 备份兼容
- 删除官网更新、截图 OCR、裁剪、相关依赖及联网权限

## 已完成并验证

- 目录、个人豆库、五个初始品牌和自定义品牌
- 日历图片、同日 `×N`、月度/年度总结
- 真实产品图、Logo 和通用占位三级回退
- 截图 OCR/裁剪和缺图提示
- 用户确认的官网更新和失败重试/截图/手工回退
- 不可变历史快照
- 安全的本地备份与原子恢复
- Android 12+ 数据提取规则、隐私披露和正式 App 图标
- 真实 `MainActivity → Navigation → Room → Repository/ViewModel → UI` 验收旅程

最近一次已验证基线 `77b4bcb`：278 个 JVM/Robolectric 测试通过，Lint 0 error，Debug/androidTest/Release APK 构建成功；Debug APK v1/v2 签名有效。

## 正在进行

2026-08-16 已完成连锁豆库改版的分段需求确认，正式规格见 `docs/superpowers/specs/2026-08-16-manual-chain-catalog-redesign-design.md`。当前仍处于规格确认阶段，未开始 Room v3 或 UI 实施。

## 下一步

1. 用户整体确认新版规格。
2. 生成可执行实施计划，按 TDD 交由 implementer 实施。
3. Room v3、旧备份恢复和图片一致性完成 critical review。
4. 生成新版 Debug APK并进行真机验收。

---

# 已知问题 / 残余风险

- 当前设备侧已完成一次 Debug APK 试用，但新版系统图片选择、品牌／产品编辑和完整恢复仍待真机验证。
- 个人侧载的是 debuggable Debug APK；Release APK 尚未配置个人签名。
- 12 个真实品牌 Logo 的官方资产来源、尺寸归一和商标说明需在实施时记录。
- `AndroidManifest.xml` 未指定应用主题，系统原生 ActionBar 与 Compose 页面重叠；应在 Activity 窗口主题层修复，不能给三个页面分别堆叠 padding。
- 项目多 Agent 配置使用当前运行时支持的 Terra（explorer / implementer / reviewer）与 Sol（critical reviewer）。

---

# 重要文件

- `AGENTS.md` — 项目多 Agent 工作流和验收规范
- `.codex/config.toml` / `.codex/agents/*.toml` — 项目 Agent 配置
- `docs/superpowers/specs/2026-08-01-coffee-journal-android-design.md` — 产品与技术设计
- `docs/superpowers/specs/2026-08-16-manual-chain-catalog-redesign-design.md` — 纯手动连锁豆库改版规格
- `docs/superpowers/plans/2026-08-01-coffee-journal-android-implementation.md` — 分阶段实现计划
- `app/src/main/java/com/niumi/coffeejournal/CoffeeJournalApp.kt` — 应用依赖入口
- `app/src/main/java/com/niumi/coffeejournal/core/database/CoffeeDatabase.kt` — Room 版本与迁移
- `app/src/main/java/com/niumi/coffeejournal/journal/JournalRepository.kt` — 草稿、记录和快照事务
- `app/src/main/java/com/niumi/coffeejournal/backup/BackupManager.kt` — 导出、验证和恢复
- `README.md` — 构建、使用、备份与隐私说明

---

# 项目约定

- 金额统一以分存储；评分统一为 1–10 个半星单位，可为空。
- 时间以 epoch millis 存储，月历和统计按用户本地时区投影。
- 可选图片、评分、价格、冲煮和备注不得阻止保存；未选择具体目录条目必须阻止保存。
- 所有候选目录更新和 OCR 结果都必须用户确认。
- 删除、迁移、恢复、并发和数据一致性修改使用 `critical_reviewer`。
- 保留用户未提交改动；文件编辑使用 `apply_patch`；发布成功前必须有新鲜验证证据。

---

# Build / Test / Lint

在功能 worktree 中：

```bash
export JAVA_HOME="$PWD/.local-tools/jdk/Contents/Home"
export ANDROID_HOME="$PWD/.local-tools/android-sdk"
export GRADLE_USER_HOME="$PWD/.gradle"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Targeted / 首次恢复验证：

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest --no-daemon
```

完整发布矩阵：

```bash
./.local-tools/gradle-8.13/bin/gradle clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --no-daemon
```

Debug APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

真机验收（有设备时）：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
./.local-tools/gradle-8.13/bin/gradle connectedDebugAndroidTest --no-daemon
```

---

# 最近的重要变更

## 2026-08-11

- 完成饮用日期时间、草稿恢复、记录编辑/删除、revision CAS 和 Room v1→v2 迁移。
- 增加合法 v1 备份真实恢复到 v2、恶意 schema 结构拒绝和提交后取消竞态测试。
- 307 个本地测试和完整发布矩阵通过，高风险复审 PASS；审计修复已提交为 `72dcb28` 和 `8e939f0`。
- 最终发布审计 GO：0 Critical、0 Important。

## 2026-08-10

- 引入项目级多 Agent 配置和 `AGENTS.md`。
- 最终发布审计发现日期时间、草稿恢复、记录编辑/删除三个跨模块缺口；审计修复已静态实现但尚未验证。

## 2026-08-03

- `77b4bcb` 形成最近一次完整验证的发布候选基线。
- Task 11 备份恢复和 Task 12 跨层验收均通过独立规格/质量复审。

---

最后更新：2026-08-11
