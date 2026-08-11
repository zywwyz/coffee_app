# 项目状态

> 本文件保存未来开发需要的高密度上下文。不要把临时日志或完整会话历史写入此处。

## 项目简介

### 产品目标

个人使用、免费、离线优先的 Android 咖啡日记：记录连锁咖啡和个人咖啡豆，按月历展示每日饮用图片，提供月度/年度统计、手动公开目录更新、截图 OCR/裁剪，以及完整本地备份恢复。

### 当前开发阶段

当前个人 Debug 侧载范围已取得发布 GO。`72dcb28` 补齐饮用日期时间、持久草稿恢复、记录编辑/删除和 Room v2 迁移；`8e939f0` 进一步原子清理悬空编辑草稿并提供“放弃草稿并新建”。高风险复审与最终发布审计均 PASS，功能分支已快进合并到 `main` 并推送 `origin/main`。待真机验收。

---

# 技术栈

## 客户端

- Kotlin、Jetpack Compose、Material 3
- ViewModel + StateFlow
- Room / SQLite
- Coil 图片加载
- ML Kit 本地文字识别；图片像素和识别结果本地处理，但 SDK 可能发送设备、应用、性能及 API 使用诊断指标
- OkHttp，仅用于用户手动触发的公开官网目录更新

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

目录更新：

用户手动触发指定品牌
→ 仅公开官网适配器
→ 候选 diff
→ 用户确认
→ 更新当前目录；不得修改历史记录快照

图片回退：产品图 → 历史品牌 Logo → 通用占位图。官网图失败时提示上传完整截图并本地 OCR/裁剪，允许跳过。

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

### 公开目录只允许手动更新

不调用微信小程序私有接口，不后台抓取。首批支持瑞幸、Manner、M Stand、Peet's、% Arabica；只有存在稳定公开产品页的品牌使用官网适配器，其余走截图/手工录入。任何候选必须用户确认后入库。

### 历史快照不可被目录更新改写

记录保存产品名称、属性、产品图及品牌 Logo 快照。普通目录更新不改变历史；显式编辑记录且更换产品时才生成新快照。

### 图片与恢复共享变更协调锁

普通图片导入/删除和备份恢复使用同一个 `ImageMutationCoordinator`，避免数据库引用与文件状态竞态。

### 备份安全边界

ZIP 解压限额和压缩比按实际流式读取字节计算，不能信任中心目录大小。恢复写入前重新验证 DB 和每张图片，事务提交边界与取消语义明确。

---

# 当前 Milestone

## 当前目标

完成最终审计修复并重新取得发布 GO：

- 可选择/补记饮用日期时间
- App 重启后恢复未完成草稿
- 修改与删除错误记录并立即刷新日历/统计
- Room v1→v2 无损迁移
- 备份 v1/v2 兼容

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

功能 worktree：`/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal`

分支：`codex/coffee-journal`

提交 `72dcb28` 与 `8e939f0` 已实现 Room v2、日期时间、草稿恢复、记录编辑/删除、草稿恢复入口和备份兼容，并生成 `app/schemas/.../2.json`。

2026-08-11 fresh 验证：307 个 JVM/Robolectric 测试通过（0 failure/error/skip），Lint 0 error，Debug、androidTest 和 unsigned Release APK 构建成功；高风险复审最终 PASS，无 Critical/Important。

Debug APK：58,468,925 bytes，SHA-256 `ae55b02666f60f37d1c004434eea2ca020e3a7ef81a6d6e82513463354f725c1`。

## 下一步

1. 有设备时补 connected test、安装和真机 OCR/SAF/恢复验收。
2. 如不再需要，删除已合并的本地功能 worktree 和 `codex/coffee-journal` 分支；该删除需要用户单独授权。

---

# 已知问题 / 残余风险

- 无连接 Android 设备；安装、系统图片/文档选择器、真机中文 OCR、裁剪手势和完整恢复尚未真机验证。
- 个人侧载的是 debuggable Debug APK；Release APK 尚未配置个人签名。
- Manner 之外部分品牌没有稳定公开产品目录，需截图或手工录入。
- 项目多 Agent 配置使用当前运行时支持的 Terra（explorer / implementer / reviewer）与 Sol（critical reviewer）。

---

# 重要文件

- `AGENTS.md` — 项目多 Agent 工作流和验收规范
- `.codex/config.toml` / `.codex/agents/*.toml` — 项目 Agent 配置
- `docs/superpowers/specs/2026-08-01-coffee-journal-android-design.md` — 产品与技术设计
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
