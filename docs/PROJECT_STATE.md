# 项目状态

> 本文件只保存后续开发需要的高密度状态，不记录临时日志或完整会话历史。

## 项目简介

个人使用、完全离线的 Android“咖啡日历”。记录连锁咖啡与个人咖啡豆，在月历按品牌或咖啡显示每日饮用图片，提供月度/年度总结、手动目录和本地备份恢复。无服务端、账号、登录或云同步；业务数据与图片都在应用私有目录。

## 当前开发阶段

个人豆管理改进已完成：处理法/烘焙度下拉、日期日历选择、养豆中与天数、已喝完平均评分排序；用户已确认旧归档并入已喝完。豆库图片是否自动同步到历史记录仍待用户决定，保留快照机制；1.1 新增当天详情逐条显示实拍图与手动换图，保留评分/价格。

上一轮轻量画布豆库与总结已在 main 发布（5e4539a），日历保留 A 风格。人工上传照片与离线原则不变。

## 技术栈与架构

- Kotlin、Jetpack Compose、Material 3；ViewModel + StateFlow；Room / SQLite；本地 Bitmap 缩略图加载。
- Room 数据库为 v5，包含 v1→v2→v3→v4→v5 迁移；schema 已导出。
- `journal`：记录、草稿、日历显示模式、详情与不可变快照。
- `catalog`：12 个内置 Logo、连锁品牌/产品的手动 CRUD、个人豆库。
- `core/image`：本地原始图片字节、引用、产品图→Logo→占位图回退及变更协调。
- `insights`：月/年比较、习惯统计、占比、排行与历史图片回退。
- `backup` / `settings`：版本化 ZIP、校验、原子恢复与 SAF 设置页。
- 已删除 `importer` 和网络相关架构：无官网更新、OCR、截图裁剪、ML Kit、OkHttp 或 `INTERNET` 权限。

## 已实现范围

- App 与首 Tab 为“咖啡日历”，无全局顶栏；底部根 Tab 为咖啡日历、豆库、总结。
- 月历“品牌／咖啡”显示模式持久化；记录以本地中午的 date-only 语义保存，图片导入缩略图上限为 512px，并按三级回退。
- 12 个预置连锁品牌 Logo，横向品牌筛选与 48dp 小缩略图产品列表；用户可以本地手动维护连锁品牌、产品和产品实拍图。
- 手动新增/编辑/删除自定义连锁品牌与 Logo；手动新增/编辑/删除产品名称、黑咖/果咖/奶咖分类和可选实拍图。个人豆在统计中固定归类为“手冲”。
- `DrinkSnapshot` 固化 `CoffeeType`：历史记录不受目录后续编辑影响；新建个人豆记录写入 `HAND_BREW`。
- 备份数据库版本为 v5（ZIP manifest formatVersion 仍为1）：接受旧v1–v4；v1–v3派生历史CoffeeType，v4–v5严格校验快照类型。v4→v5升级及旧备份恢复仅将个人豆ARCHIVED改为DISCONTINUED，链产品状态、图片与记录不变；恢复保持staging校验与原子替换。RESTING仅v5个人豆可用，日期必须严格有效；恢复不按当前时间拒绝未来日期，避免设备时区或时钟变化破坏合法旧备份。
- 总结支持月度/年度切换：习惯摘要含上期杯数差（月度同日比较、年度同期间比较，年份 1 无基线）；月度与年度均刻意不显示趋势卡；咖啡类型与品牌占比横条（黑咖/果咖/奶咖/手冲；Top 4 品牌+其他）、Top 3，以及最好/最差记录行与 40dp 历史图片回退。横条同时展示杯数和百分比，并提供完整的无障碍描述。
- MANNER 使用用户确认来源图生成透明 512px 打包 Logo，并保留来源与输出哈希审计；所有内置品牌 Logo 随包提供。
- minSdk 23；统计日期相关实现兼容 API 23。

- A 视觉：共享细点纸底与胶带便签；日历按实际月份显示必要周数，空日透明，照片完整显示，摘要为三列数字。最近一杯限定当前月份，按时间与 id 稳定选择真实快照，点击进入原有当天详情。全宽记录按钮位于 Scaffold bottomBar，为小屏滚动内容预留空间。豆库、记录、总结、设置与底栏共享色板；持久层与备份逻辑未变。

- 二轮布局：日历紧凑顶栏同排模式，照片格比例0.78、图片内距1dp；豆库、总结的收藏卡与胶带摘要已由本次轻量画布替代；品牌管理、豆子开始喝/喝完和月年导航保持可达。

- 个人豆：处理法/烘焙度可选常见值并保留自定义，日期用系统日历且可清除；不再输入默认冲煮方式，新个人豆饮用草稿不继承旧目录默认值，已有历史冲煮数据保留。
- 状态：正在喝（含旧NEEDS_IMAGE）、已喝完、养豆中。保存/状态写入口校验RESTING的烘焙日期有效且不晚于本地今日，禁止个人ARCHIVED/链RESTING。正在喝和养豆中均显示烘焙距今天数，按UTC归一化日历日差计算避免DST影响，页面ON_RESUME及每分钟刷新日期；异常旧日期显示待补充。已喝完按PERSONAL_BEAN的sourceItemId全部非空评分均值降序、无评分最后、同分名称/id稳定排序，Room Flow实时更新。

## 关键数据流与决策

记录：`JournalViewModel` 持久化草稿 → `JournalRepository` Room 事务写入记录和目录/图片/咖啡类型快照 → 日历及总结 Flow 自动刷新。目录更新不得改写历史快照；显式更换产品产生新快照，当天详情换图仅更新该条记录的图片、更新时间和 revision，不更改其他字段或清理编辑草稿。

目录：当前类型的各品牌产品通过只读 Flow 聚合，支持按产品名/品牌名搜索和品牌筛选，切换连锁/个人豆时重置筛选；用户在本地选择 Logo/整张实拍图并手动维护。连锁产品仅有黑咖、果咖、奶咖三类；无图回退到品牌 Logo。个人豆记录快照归类为手冲。图片只通过系统 picker 获得单文件授权，原字节保存。

恢复：备份先在 staging 进行格式、数据库和图片校验，再通过图片协调锁和单一 Room 事务替换数据；失败或取消保持当前数据。ZIP 不加密，卸载或清除数据前必须导出。

## 已知风险 / 后续事项

1. Debug APK 使用 debug 签名且可调试；Release APK 当前为 unsigned。签名不同的升级需卸载，未导出数据会丢失。
2. 本轮没有连接设备；仍需在目标手机验证侧载、系统 picker、真机图片显示、完整备份恢复与 Android 6+ 体验。

## 重要文件

- `app/src/main/java/com/niumi/coffeejournal/core/database/CoffeeDatabase.kt` — Room v5 与迁移。
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

最终验证（2026-09-20）：

- `testDebugUnitTest lintDebug assembleDebug --offline --no-daemon`：PASS；405 tests，0 failures/errors/skips；lint 0 errors、8 warnings。
- 覆盖下拉选择与自定义输入、日历选择/重开/清除、养豆日期/跨DST日差、评分Flow新增/修改/删除及无评分/连锁记录排除；v4归档恢复保留图片与记录，v5养豆备份往返、非法状态/日期拒绝。
- 养豆与已喝完列表：320dp/1.3倍字体原生渲染通过，检查9天独立可见及评分排序；截图/安装包位于 `build/deliverables/bean-management/`。
- 弹窗原生截图存在Robolectric AppNotIdle限制；以未改动的品牌弹窗复现确认。未保留无法运行的截图探针；真实表单交互由普通Compose测试覆盖，弹窗外观仍待真机确认。
- critical reviewer：PASS，无阻塞问题。`git diff --check`：PASS。未做真机安装，本轮未提交或推送。

补充验证（正在喝显示烘焙天数）：原生定向渲染与 assembleDebug PASS，320dp/1.3倍字体显示9天；新版包与截图位于 `build/deliverables/bean-management-v2/`。

1.1 验证（2026-09-23）：

- 当天详情提供逐条92dp完整图片、添加/更换图片、评分/价格与编辑删除；操作栏独立，详情可滚动，320dp/1.5倍字体及多记录末项已验证。
- 照片更新捕获记录revision及恢复世代；在图片协调锁内核验世代并执行条件更新。成功恢复在同锁内推进世代，使恢复前的换图请求失效；冲突与失败由现有导入器清理未引用新图，共享旧图保留。
- `testDebugUnitTest lintDebug assembleDebug --offline --no-daemon`：PASS，410 tests，0 failures/errors/skips；lint 0 errors、8 warnings。
- critical复审：PASS，无剩余Critical/High/Medium；`git diff --check`：PASS。
- APK：`build/deliverables/v1.1/coffee-journal-1.1-debug.apk`；versionName=1.1，versionCode=2，包名不变；签名校验通过且SHA-256证书与此前bean-management-v2包相同，符合覆盖升级条件。未做真机安装；未提交/推送。

1.1.1 历史编辑退出修复（2026-09-25）：

- 页面返回与系统返回：历史编辑草稿按 revision 删除成功后再清内存并退出，未保存修改不写历史；普通新增草稿仍保留。新增入口先等初始化恢复完成，再清理残留历史编辑草稿，避免误把新增保存回历史。
- busy期间拦截系统返回；退出失败留页可重试，日历中新建失败显示错误；历史编辑仅成功加载后才开页，加载失败不会暴露此前残留记录。
- 真实Room+JournalFeature回归覆盖无修改页面返回和修改后系统返回，再新增保存成第二条，原历史整体相等。旧返回路径已确认测试失败，修复后通过。VM覆盖延迟恢复、新增草稿保留、失败重试和加载失败不打开编辑。
- `testDebugUnitTest lintDebug assembleDebug --offline --no-daemon`：PASS，418 tests，0 failures/errors/skips；lint 0 errors、8 warnings；critical reviewer PASS；`git diff --check` PASS。
- 安装包：`build/deliverables/v1.1.1/coffee-journal-1.1.1-debug.apk`，versionCode=3，包名与签名保持同1.1，支持覆盖升级；尚未真机安装，本轮未提交或推送。

最后更新：2026-09-25
