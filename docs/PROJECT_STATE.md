# 项目状态

> 本文件只保存后续开发需要的高密度状态，不记录临时日志或完整会话历史。

## 项目简介

个人使用、完全离线的 Android“咖啡日历”。记录连锁咖啡与个人咖啡豆，在月历按品牌或咖啡显示每日饮用图片，提供月度/年度统计、手动目录和本地备份恢复。无服务端、账号、登录或云同步，业务数据在应用私有目录；用户可自行把导出的备份传到外部存储。

## 当前开发阶段

纯手动连锁豆库改版已实现并完成发布矩阵验证；规格状态为 implemented。Release APK 尚未签名，仅供后续配置个人签名后发布。

## 技术栈与架构

- Kotlin、Jetpack Compose、Material 3；ViewModel + StateFlow；Room / SQLite；Coil。
- Room 数据库为 v3，包含 v1→v2、v2→v3 迁移。
- `journal`：记录、草稿、日历显示模式、详情与不可变快照。
- `catalog`：12 个内置 Logo、连锁品牌/产品的手动 CRUD、个人豆库。
- `core/image`：本地原始图片字节、引用、产品图→Logo→占位图回退及变更协调。
- `backup` / `settings`：版本化 ZIP、校验、原子恢复与 SAF 设置页；恢复协调器支持旧配置恢复。
- 已删除 `importer` 和网络相关架构：无官网更新、OCR、截图裁剪、ML Kit、OkHttp 或 `INTERNET` 权限。

## 已实现范围

- App 与首 Tab 改名“咖啡日历”，无全局顶栏；底部根 Tab 为咖啡日历、豆库、总结。
- 月历“品牌／咖啡”显示模式持久化；图片原字节保存、CenterCrop 显示并按三级回退。
- 12 个预置连锁品牌 Logo，三列品牌网格与双列产品网格。
- 手动新增/编辑/删除自定义连锁品牌与 Logo；手动新增/编辑/删除产品名称、黑咖/果咖/奶咖分类和可选实拍图。内置品牌不可删，自定义品牌需先删除产品。
- 个人豆库、记录补记/编辑/删除、草稿保留与快捷新增产品后自动选中。
- 月度/年度总结。
- Room v3，旧备份 v1/v2/v3 兼容；离线图片导入协调、配置恢复和原子备份恢复。

## 关键数据流与决策

记录：`JournalViewModel` 持久化草稿 → `JournalRepository` Room 事务写入记录和目录/图片快照 → 日历及总结 Flow 自动刷新。目录更新不得改写历史快照；只有显式更换产品才产生新快照。

目录：用户在本地选择 Logo/整张实拍图并手动维护。连锁产品仅有黑咖、果咖、奶咖三类；无图回退到品牌 Logo。图片只通过系统 picker 获得单文件授权，原字节保存。

恢复：备份先在 staging 进行格式、数据库和图片校验，再通过图片协调锁和单一 Room 事务替换数据；失败或取消保持当前数据。ZIP 不加密，卸载或清除数据前必须导出。

## 已知风险 / 后续事项

1. Debug APK 使用 debug 签名且可调试；Release APK 当前为 unsigned。签名不同的升级需卸载，未导出数据会丢失。
2. ADB 命令在沙箱外可运行但本次没有连接设备；仍需在目标手机验证安装、系统 picker、真机图片显示、完整备份恢复与 Android 6+ 侧载体验。

## 重要文件

- `app/src/main/java/com/niumi/coffeejournal/core/database/CoffeeDatabase.kt` — Room v3 与迁移。
- `app/src/main/java/com/niumi/coffeejournal/catalog/BundledBrandCatalog.kt` — 12 个内置品牌 Logo。
- `app/src/main/java/com/niumi/coffeejournal/catalog` — 手动连锁目录与个人豆库。
- `app/src/main/java/com/niumi/coffeejournal/journal` — 记录、草稿、日历与显示偏好。
- `app/src/main/java/com/niumi/coffeejournal/backup` — 备份校验、兼容与恢复。
- `README.md` — 安装、使用、离线/隐私说明。
- `docs/superpowers/specs/2026-08-16-manual-chain-catalog-redesign-design.md` — 已实施规格。

## 构建与验证

```bash
export JAVA_HOME="$PWD/.local-tools/jdk/Contents/Home"
export ANDROID_HOME="$PWD/.local-tools/android-sdk"
export GRADLE_USER_HOME="$PWD/.gradle"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

./.local-tools/gradle-8.13/bin/gradle clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --no-daemon
```

发布矩阵（2026-08-22）：

- `clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease`：PASS，137 tasks。
- 单元／Robolectric：305 tests，0 fail／error／skip；lint：0 errors、20 warnings。
- Room schema：v3 已导出；12 个内置品牌 Logo 已打包。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，13,399,186 bytes，SHA-256 `c77034ede25b9bcfc86d7b18b688a6a8a3793b288aca60a8b545ee546bdcd41c`；v1/v2 签名均为 true，debug 证书 SHA-256 `60d2e7…a3713`。
- AndroidTest APK：1,143,024 bytes，SHA-256 `ab118…932f`。
- Release APK（unsigned）：9,578,339 bytes，SHA-256 `080c…84bd`。
- 合并 Manifest：minSdk 23、targetSdk 36；仅有包内 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，无 `INTERNET`／相机／定位／存储权限；`allowBackup`、`fullBackupContent`、`cleartextTraffic` 均为 false，并声明 `dataExtractionRules`。

最后更新：2026-08-22
