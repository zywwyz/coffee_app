# 奶油贴纸簿 A 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement the coordinated UI task. 主 Agent 负责环境恢复、最终视觉验收与文档；一个 implementer 负责连续 UI 修改。

**Goal:** 将用户确认的 A 实拍版落地为可安装、可验证的 Compose 客户端。

**Architecture:** 共享 CoffeeVisuals/主题和小型纸面组件承载视觉。现有 JournalUiState、图片解析和回调承载最近记录便签，不改持久层。其他页面复用视觉 token，保留数据与业务交互。

**Tech Stack:** Kotlin、Jetpack Compose Material 3、Robolectric Native Graphics、Gradle 8.13、JDK 17、Android SDK 36。

---

### 1. 准备与基线（主 Agent）

- [x] 用户确认 A，记录规格及边界。
- [x] 创建当前目录开发分支 `codex/cream-scrapbook-a`，保留原有 AGENTS.md 与 .codex 删除。
- [x] 恢复 `.local-tools` 链接，复用 `.worktrees/codex-coffee-journal/.local-tools`；完整依赖缓存位于该 worktree 的 `.gradle`。
- [x] 改动前主题、日历、小屏、完整图片展示合约共 31 项定向测试 PASS。

本机执行环境：

```sh
export JAVA_HOME="$PWD/.local-tools/jdk/Contents/Home"
export ANDROID_HOME="$PWD/.local-tools/android-sdk"
export GRADLE_USER_HOME="$PWD/.worktrees/codex-coffee-journal/.gradle"
```

下文 `gradle` 指 `./.local-tools/gradle-8.13/bin/gradle`。该工具需要本地 socket，沙箱内启动被阻止时使用获批的沙箱外执行。

### 2. 连续 UI 实现（单 implementer）

文件：`ui/CoffeeVisuals.kt`、`ui/theme/CoffeeTheme.kt`、必要的新共享纸面组件；`journal/JournalScreen.kt`、`journal/RecordDrinkScreen.kt`；`catalog/CatalogScreen.kt`、`catalog/BrandProductsScreen.kt`；`insights/InsightsScreen.kt`；`navigation/AppNavigation.kt`；必要的 `settings/SettingsScreen.kt`。

- [x] 更新已有视觉 token 合约预期与新增最近记录展示的行为测试，先观察相关失败，再实现。
- [x] 设置规格色板、统一标题和正文层次、细点纸底与胶带便签组件；保留现有 token 名以减少无关改动。
- [x] 日历改成白色照片贴纸、透明空日、三列无卡片摘要、全宽记录按钮和最近一杯便签。选择最近记录须限定本月，按 occurredAtEpochMillis / id 确定性排序；图片使用该记录自己的快照解析，不能错用另一条同日记录图片。
- [x] 将页头、图片区、分区和底栏样式统一至 A；统计信息与所有已有回调保留。
- [x] 运行下列相关测试（工具路径由主 Agent 提供）：

```sh
gradle testDebugUnitTest --tests '*CoffeeVisualsTest' --tests '*JournalScreenRobolectricTest' --tests '*JournalSmallScreenTest' --tests '*ImagePresentationContractTest' --tests '*CatalogScreenRobolectricTest' --tests '*BrandProductsScreenTest' --tests '*InsightsScreenRobolectricTest' --tests '*AppNavigationTest' --tests '*SettingsScreenRobolectricTest' --offline --no-daemon
```

- [x] 调整已有预览 fixture：杯数/消费必须与记录一致。正常月份使用稀疏真实图片，保留满月覆盖；新增 UI 预览使用现有 Native Graphics 路径。不要把两种 graphics 模式混在全套测试进程。

### 3. 验收与交付（主 Agent）

- [x] 单独执行日历与总结 Native Graphics 预览，检查完整原图、长文字、空月/满月和小屏。
- [x] 按需修正视觉差异，再执行受影响测试。
- [x] reviewer 审查 UI 状态/回调/图片一致性和规格覆盖，处理有意义的问题。
- [x] `lintDebug assembleDebug --offline --no-daemon` 通过；必要时扩大到模块单测。
- [x] 更新 `docs/PROJECT_STATE.md`，保存可打开的真实截图和 APK，汇报命令 PASS/FAIL 与真机验证限制。

## 最终执行结果（2026-09-11）

- 实现完成；主 Agent 补充视觉验收、预览 fixture 与小屏按钮遮挡修复，使用失败断言确认问题后验证修复。
- reviewer 初审未发现必须修复项；等待图片测试改为精确等待已加载状态。最终复审因额度中断，由主 Agent 完成 diff 与截图验收。
- `testDebugUnitTest lintDebug assembleDebug --offline --no-daemon`：PASS，393 tests，lint 0 errors / 8 warnings。
- `testDebugUnitTest -PcalendarPreview --tests '*CalendarPreviewRenderTest' --offline --no-daemon`：PASS，7 tests。
- `testDebugUnitTest -PinsightsPreview --tests '*InsightsPreviewRenderTest' --offline --no-daemon`：PASS，1 test。
- Debug APK 与 17 张真实预览位于 `build/deliverables/scrapbook-a/`；签名验证通过，尚未真机验证。
