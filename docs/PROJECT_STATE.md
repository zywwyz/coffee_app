# 项目状态

> 本文件只保存后续开发需要的高密度状态，不记录临时日志或完整会话历史。

## 项目简介

个人使用、完全离线的 Android“咖啡日历”。记录连锁咖啡与个人咖啡豆，在月历按品牌或咖啡显示每日饮用图片，提供月度/年度总结、手动目录和本地备份恢复。无服务端、账号、登录或云同步；业务数据与图片都在应用私有目录。

## 当前开发阶段

日历视觉一致性、纯手动连锁豆库、date-only 记录，以及新版月度/年度总结已实现并完成发布矩阵验证；规格状态为 implemented。Debug APK 可供个人侧载试用；未做真机安装或 connected instrumentation 验证。Release APK 尚未配置个人签名。

## 技术栈与架构

- Kotlin、Jetpack Compose、Material 3；ViewModel + StateFlow；Room / SQLite；本地 Bitmap 缩略图加载。
- Room 数据库为 v4，包含 v1→v2、v2→v3、v3→v4 迁移；schema 已导出。
- `journal`：记录、草稿、日历显示模式、详情与不可变快照。
- `catalog`：12 个内置 Logo、连锁品牌/产品的手动 CRUD、个人豆库。
- `core/image`：本地原始图片字节、引用、产品图→Logo→占位图回退及变更协调。
- `insights`：月/年比较、习惯统计、趋势、占比、排行与历史图片回退。
- `backup` / `settings`：版本化 ZIP、校验、原子恢复与 SAF 设置页。
- 已删除 `importer` 和网络相关架构：无官网更新、OCR、截图裁剪、ML Kit、OkHttp 或 `INTERNET` 权限。

## 已实现范围

- App 与首 Tab 为“咖啡日历”，无全局顶栏；底部根 Tab 为咖啡日历、豆库、总结。
- 月历“品牌／咖啡”显示模式持久化；记录以本地中午的 date-only 语义保存，图片导入缩略图上限为 512px，并按三级回退。
- 12 个预置连锁品牌 Logo，三列品牌网格与双列产品网格；用户可以本地手动维护连锁品牌、产品和产品实拍图。
- 手动新增/编辑/删除自定义连锁品牌与 Logo；手动新增/编辑/删除产品名称、黑咖/果咖/奶咖分类和可选实拍图。个人豆在统计中固定归类为“手冲”。
- `DrinkSnapshot` 固化 `CoffeeType`：历史记录不受目录后续编辑影响；新建个人豆记录写入 `HAND_BREW`。
- 备份格式为 v4：v1–v3 恢复时派生历史 `CoffeeType`，v4 恢复严格校验快照类型与领域规则；恢复仍采用 staging 校验与原子替换。
- 总结支持月度/年度切换：习惯摘要含上期杯数差（月度同日比较、年度同期间比较，年份 1 无基线）、月累计日趋势/年逐月趋势、咖啡类型与品牌双 Donut（黑咖/果咖/奶咖/手冲；Top 4 品牌+其他）、Top 3，以及最好/最差记录卡与历史图片回退。
- MANNER 使用用户确认来源图生成透明 512px 打包 Logo，并保留来源与输出哈希审计；所有内置品牌 Logo 随包提供。
- minSdk 23；统计日期相关实现兼容 API 23。

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

## 构建与验证

```bash
export JAVA_HOME="$PWD/.local-tools/jdk/Contents/Home"
export ANDROID_HOME="$PWD/.local-tools/android-sdk"
export GRADLE_USER_HOME="$PWD/.gradle"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

./.local-tools/gradle-8.13/bin/gradle clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --offline --no-daemon
```

最终发布矩阵（2026-09-04）：

- `clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --offline --no-daemon`：PASS；383 tests，0 failures / errors / skips；lint 0 errors、8 warnings。
- 预览测试 fixture 仅位于测试资源，不会打入 APK。六张真实 Compose 总结评审图输出到 `app/build/reports/previews/`：`insights-monthly-hero-trend-cream-forest.png`、`insights-monthly-breakdown-cream-forest.png`、`insights-monthly-highlights-cream-forest.png`、`insights-yearly-hero-trend-cream-forest.png`、`insights-yearly-breakdown-cream-forest.png`、`insights-yearly-highlights-cream-forest.png`。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，14,314,965 bytes，SHA-256 `b610fab5685b264ce3d9d06f06b99b7a1053762133e7158f2c985df912612f35`；版本 1.0（versionCode 1），Android Debug v1/v2 签名。
- Release APK（unsigned）：`app/build/outputs/apk/release/app-release-unsigned.apk`，SHA-256 `dd99a6603c000829ca8f8c46cfb5698e9ca249895eabc019eed6b28b2e2ad641`。
- 合并 Manifest：minSdk 23、targetSdk 36；无 `INTERNET`／相机／定位／宽泛存储权限；`allowBackup`、`fullBackupContent`、`cleartextTraffic` 均为 false，并声明 `dataExtractionRules`。

最后更新：2026-09-04
