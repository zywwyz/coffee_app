# 项目状态

> 本文件只保存后续开发需要的高密度状态，不记录临时日志或完整会话历史。

## 项目简介

个人使用、完全离线的 Android“咖啡日历”。记录连锁咖啡与个人咖啡豆，在月历按品牌或咖啡显示每日饮用图片，提供月度/年度总结、手动目录和本地备份恢复。无服务端、账号、登录或云同步；业务数据与图片都在应用私有目录。

## 当前开发阶段

A「奶油贴纸簿·实拍版」及试用反馈二轮调整已实现：纸面背景、橄榄绿操作、白色照片贴纸与最近一杯便签统一至各客户端页面。模块单测、原生渲染预览、lint 和 Debug APK 构建通过。未做真机安装或 connected instrumentation 验证；本轮未重新构建 Release。

## 技术栈与架构

- Kotlin、Jetpack Compose、Material 3；ViewModel + StateFlow；Room / SQLite；本地 Bitmap 缩略图加载。
- Room 数据库为 v4，包含 v1→v2、v2→v3、v3→v4 迁移；schema 已导出。
- `journal`：记录、草稿、日历显示模式、详情与不可变快照。
- `catalog`：12 个内置 Logo、连锁品牌/产品的手动 CRUD、个人豆库。
- `core/image`：本地原始图片字节、引用、产品图→Logo→占位图回退及变更协调。
- `insights`：月/年比较、习惯统计、占比、排行与历史图片回退。
- `backup` / `settings`：版本化 ZIP、校验、原子恢复与 SAF 设置页。
- 已删除 `importer` 和网络相关架构：无官网更新、OCR、截图裁剪、ML Kit、OkHttp 或 `INTERNET` 权限。

## 已实现范围

- App 与首 Tab 为“咖啡日历”，无全局顶栏；底部根 Tab 为咖啡日历、豆库、总结。
- 月历“品牌／咖啡”显示模式持久化；记录以本地中午的 date-only 语义保存，图片导入缩略图上限为 512px，并按三级回退。
- 12 个预置连锁品牌 Logo，三列品牌网格与双列产品网格；用户可以本地手动维护连锁品牌、产品和产品实拍图。
- 手动新增/编辑/删除自定义连锁品牌与 Logo；手动新增/编辑/删除产品名称、黑咖/果咖/奶咖分类和可选实拍图。个人豆在统计中固定归类为“手冲”。
- `DrinkSnapshot` 固化 `CoffeeType`：历史记录不受目录后续编辑影响；新建个人豆记录写入 `HAND_BREW`。
- 备份格式为 v4：v1–v3 恢复时派生历史 `CoffeeType`，v4 恢复严格校验快照类型与领域规则；恢复仍采用 staging 校验与原子替换。
- 总结支持月度/年度切换：习惯摘要含上期杯数差（月度同日比较、年度同期间比较，年份 1 无基线）；月度与年度均刻意不显示趋势卡；咖啡类型与品牌双 Donut（黑咖/果咖/奶咖/手冲；Top 4 品牌+其他）、Top 3，以及最好/最差记录卡与历史图片回退。Donut 图例与圆环保留 16dp 安全间距；每个图例固定为色点、名称、杯数、分隔符、百分比五列的单行对齐布局，仅名称列可换行，杯数和百分比列跨行对齐。
- MANNER 使用用户确认来源图生成透明 512px 打包 Logo，并保留来源与输出哈希审计；所有内置品牌 Logo 随包提供。
- minSdk 23；统计日期相关实现兼容 API 23。

- A 视觉：共享细点纸底与胶带便签；日历按实际月份显示必要周数，空日透明，照片完整显示，摘要为三列数字。最近一杯限定当前月份，按时间与 id 稳定选择真实快照，点击进入原有当天详情。全宽记录按钮位于 Scaffold bottomBar，为小屏滚动内容预留空间。豆库、记录、总结、设置与底栏共享色板；持久层与备份逻辑未变。

- 二轮布局：日历紧凑顶栏同排模式，照片格比例0.78、图片内距1dp；豆库收藏卡、横滑分类、两行产品名与流式豆子操作；总结紧凑模式导航、胶带摘要和分层指标。

## 关键数据流与决策

记录：`JournalViewModel` 持久化草稿 → `JournalRepository` Room 事务写入记录和目录/图片/咖啡类型快照 → 日历及总结 Flow 自动刷新。目录更新不得改写历史快照；只有显式更换产品才产生新快照。

目录：用户在本地选择 Logo/整张实拍图并手动维护。连锁产品仅有黑咖、果咖、奶咖三类；无图回退到品牌 Logo。个人豆记录快照归类为手冲。图片只通过系统 picker 获得单文件授权，原字节保存。

恢复：备份先在 staging 进行格式、数据库和图片校验，再通过图片协调锁和单一 Room 事务替换数据；失败或取消保持当前数据。ZIP 不加密，卸载或清除数据前必须导出。

## 已知风险 / 后续事项

1. Debug APK 使用 debug 签名且可调试；Release APK 当前为 unsigned。签名不同的升级需卸载，未导出数据会丢失。
2. 本轮没有连接设备；仍需在目标手机验证侧载、系统 picker、真机图片显示、完整备份恢复与 Android 6+ 体验。

## 重要文件

- `app/src/main/java/com/niumi/coffeejournal/core/database/CoffeeDatabase.kt` — Room v4 与迁移。
- `app/src/main/java/com/niumi/coffeejournal/catalog/BundledBrandCatalog.kt` — 12 个内置品牌 Logo。
- `app/src/main/java/com/niumi/coffeejournal/catalog` — 手动连锁目录与个人豆库。
- `app/src/main/java/com/niumi/coffeejournal/journal` — 记录、草稿、日历与显示偏好。
- `app/src/main/java/com/niumi/coffeejournal/insights` — 总结计算、状态与 Compose 界面。
- `app/src/main/java/com/niumi/coffeejournal/backup` — 备份校验、版本兼容与恢复。
- `README.md` — 安装、使用、离线/隐私说明。
- `docs/superpowers/specs/2026-09-01-insights-redesign-and-manner-logo-design.md` — 已实施的总结与 MANNER Logo 规格。

- `docs/superpowers/specs/2026-09-11-cream-scrapbook-design.md` — 已实施的 A 视觉规格。

## 构建与验证

本机 `.local-tools` 链接至旧工作树工具目录，离线依赖复用该工作树缓存。

```bash
export JAVA_HOME="$PWD/.local-tools/jdk/Contents/Home"
export ANDROID_HOME="$PWD/.local-tools/android-sdk"
export GRADLE_USER_HOME="$PWD/.worktrees/codex-coffee-journal/.gradle"

./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest lintDebug assembleDebug --offline --no-daemon
```

最终验证（2026-09-12）：

- `testDebugUnitTest lintDebug assembleDebug --offline --no-daemon`：PASS；395 tests，0 failures / errors / skips；lint 0 errors、8 warnings。
- `testDebugUnitTest -PcalendarPreview --tests '*CalendarPreviewRenderTest' --offline --no-daemon`：PASS，10 tests；覆盖实拍/品牌、空月/满月、小屏、豆库与记录页。小屏断言便签不与记录按钮重叠。
- `testDebugUnitTest -PinsightsPreview --tests '*InsightsPreviewRenderTest' --offline --no-daemon`：PASS，1 test，生成月度/年度共 8 张总结图。
- 20 张真实 Compose 预览与安装包统一保存于 `build/deliverables/scrapbook-a-v2/`。测试照片不会打入 APK。
- Debug APK：`build/deliverables/scrapbook-a-v2/coffee-journal-scrapbook-a-v2-debug.apk`，14,508,249 bytes，SHA-256 `7c83e5a24f439ee7a13753ee61b4831f6e9d6f05ac17e8b5a272838348d0e6d8`；版本 1.0，Android Debug 签名，`apksigner verify --print-certs` PASS。
- 二轮 reviewer 发现的产品筛选溢出与长标题挤占返回区域已修复；复审无交付阻塞问题。日历320dp/1.3字体、设置标签完整显示及照片尺寸均有渲染断言。
- `git diff --check`：PASS。尚未进行真机验证。

最后更新：2026-09-12
