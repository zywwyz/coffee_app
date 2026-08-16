# Manual Chain Catalog Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the unreliable network/OCR catalog with an offline manual brand/product library, ship 12 real bundled brand logos, support record-time quick product creation and two persistent calendar image modes, migrate Room/backups safely to v3, rename the App to “咖啡日历”, and remove the native ActionBar that covers all three root tabs.

**Architecture:** Keep Room and the content-addressed private image store as the source of truth. Add a nullable persisted `chainProductKind` whose domain values are `BLACK`, `FRUIT`, `MILK`, and migration-only `PENDING`; keep legacy catalog columns and the unused `catalog_updates` table solely for backup compatibility. Install bundled logos through the normal `ImageStore` so logos participate in existing image references and backups, use Navigation3 for a real brand-products child route, and share one manual chain-product editor ViewModel between catalog and journal flows.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Navigation3, Room/SQLite migrations, Android resource images, Robolectric/Compose UI tests, Android instrumentation tests, Gradle 8.13.

---

## File structure and ownership

- `app/src/main/res/values/themes.xml` — native Activity NoActionBar window theme.
- `app/src/main/AndroidManifest.xml` — apply the window theme and remove the final network permission.
- `app/src/main/res/drawable-nodpi/brand_logo_*.png|webp` — 12 checked-in real logos.
- `docs/brand-logo-sources.md` — exact source page, retrieval date, trademark owner, and SHA-256 for every bundled logo.
- `core/model/Models.kt` — `ChainProductKind` and catalog item domain invariant.
- `core/database/Entities.kt`, `Daos.kt`, `CoffeeDatabase.kt` — Room v3 column, migration, seed-logo compare-and-set.
- `core/image/ImageStore.kt` — preserve whole-image bytes for gallery/bundled images; keep content addressing.
- `core/image/WholeImageImportHost.kt` — gallery-only reusable picker and association cleanup.
- `core/image/LocalAssetImage.kt` — reusable product → brand Logo → generic placeholder rendering.
- `journal/CalendarDisplayPreference.kt` — persisted `品牌／咖啡` calendar choice.
- `catalog/BundledBrandCatalog.kt` — 12 stable seed IDs, order, names, and drawable IDs.
- `catalog/CatalogRepository.kt` — seed reconciliation, deterministic ordering, manual validation.
- `catalog/ManualProductEditorViewModel.kt` — reusable product form, image lease lifecycle, and saved-item event.
- `catalog/ManualProductEditorDialog.kt` — common name/photo/type UI.
- `catalog/CatalogViewModel.kt` — root brand/bean state only; no update logic.
- `catalog/CatalogScreen.kt` — three-column brand grid and existing personal-bean surface.
- `catalog/BrandProductsScreen.kt` — independent two-column product-photo page and filters.
- `navigation/AppNavigation.kt` — brand child destination and whole-image picker wiring.
- `journal/JournalScreen.kt`, `journal/RecordDrinkScreen.kt` — quick-add entry, auto-selection, image prompt wording.
- `backup/CoffeeDatabaseSchema.kt`, `BackupManager.kt`, `BackupArchive.kt` — v3 schema/hash/domain and old-backup mapping.
- `CoffeeJournalApp.kt`, `MainActivity.kt`, Gradle catalog/build files — remove network/OCR construction and dependencies.

## Shared test environment

Before running commands in a fresh shell, set:

```bash
export COFFEE_JAVA_HOME="$PWD/.local-tools/jdk/Contents/Home"
export COFFEE_ANDROID_HOME="$PWD/.local-tools/android-sdk"
export JAVA_HOME="$COFFEE_JAVA_HOME"
export ANDROID_HOME="$COFFEE_ANDROID_HOME"
export GRADLE_USER_HOME="$PWD/.gradle"
export PATH="$COFFEE_JAVA_HOME/bin:$COFFEE_ANDROID_HOME/platform-tools:$PATH"
```

### Task 1: Rename the App and remove native/global top bars

**Files:**
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/niumi/coffeejournal/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/JournalScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/catalog/CatalogScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/TestTags.kt`
- Create: `app/src/test/java/com/niumi/coffeejournal/MainActivityWindowThemeTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/navigation/AppNavigationTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt`

- [ ] **Step 1: Write the failing window-theme regression test**

Create a Robolectric test that uses the existing `InMemoryCoffeeJournalApp`, launches the real `MainActivity`, and proves the label, manifest theme, and runtime window are correct:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = InMemoryCoffeeJournalApp::class)
class MainActivityWindowThemeTest {
    @Test fun main_activity_uses_the_explicit_no_action_bar_theme() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(R.style.Theme_CoffeeJournal, activity.applicationInfo.theme)
        assertEquals("咖啡日历", activity.applicationInfo.loadLabel(activity.packageManager).toString())
        assertNull(activity.actionBar)
    }
}
```

Add a real-Activity Compose assertion to visit 咖啡日历、豆库、总结 and assert their owned headings are displayed (`咖啡日历`, `我的咖啡豆库`, `咖啡回顾`). Assert the first bottom label is exactly `咖啡日历`, no node has text `Coffee Journal`, and each root heading has its own settings semantics.

- [ ] **Step 2: Run the test and verify the current theme fails**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest --tests 'com.niumi.coffeejournal.MainActivityWindowThemeTest' --no-daemon
```

Expected: FAIL because `Theme_CoffeeJournal`/`app_name` do not exist, the first root is still `日记`, and the default platform theme creates the `Coffee Journal` ActionBar.

- [ ] **Step 3: Add the single source-level fix**

Create:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.CoffeeJournal" parent="android:style/Theme.Material.Light.NoActionBar">
        <item name="android:windowNoTitle">true</item>
        <item name="android:windowActionModeOverlay">true</item>
        <item name="android:fontFamily">sans</item>
    </style>
</resources>
```

Create `strings.xml` with `<string name="app_name">咖啡日历</string>`. Apply `android:label="@string/app_name"` and `android:theme="@style/Theme.CoffeeJournal"` on `<application>`. Do not add padding to Journal, Catalog, or Insights to hide the symptom.

Remove the outer `Scaffold.topBar` from `AppNavigation`. Rename the first `RootDestination` label to `咖啡日历`. Give Journal, Catalog, and Insights their own title row plus a settings icon/button callback; give Settings its own back action. These title rows are page content, not a shared/global top bar.

- [ ] **Step 4: Verify the root cause is closed**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest --tests 'com.niumi.coffeejournal.MainActivityWindowThemeTest' --tests 'com.niumi.coffeejournal.navigation.AppNavigationTest' --tests 'com.niumi.coffeejournal.ReleaseAcceptanceRobolectricTest' --no-daemon
```

Expected: PASS; label is `咖啡日历`, `activity.actionBar == null`, no shared top bar exists, and all three owned root headings are displayed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/values/themes.xml app/src/main/res/values/strings.xml app/src/main/java/com/niumi/coffeejournal/navigation/AppNavigation.kt app/src/main/java/com/niumi/coffeejournal/journal/JournalScreen.kt app/src/main/java/com/niumi/coffeejournal/catalog/CatalogScreen.kt app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt app/src/main/java/com/niumi/coffeejournal/settings/SettingsScreen.kt app/src/main/java/com/niumi/coffeejournal/TestTags.kt app/src/test/java/com/niumi/coffeejournal/MainActivityWindowThemeTest.kt app/src/test/java/com/niumi/coffeejournal/navigation/AppNavigationTest.kt app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt
git commit -m "fix: rename app and remove overlapping top bars"
```

### Task 2: Add Room v3 chain-product classification and backup compatibility atomically

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/core/model/Models.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/core/database/Entities.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/core/database/CoffeeDatabase.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/catalog/CatalogRepository.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/backup/CoffeeDatabaseSchema.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/backup/BackupManager.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/backup/BackupArchive.kt`
- Create after KSP: `app/schemas/com.niumi.coffeejournal.core.database.CoffeeDatabase/3.json`
- Modify: `app/src/test/java/com/niumi/coffeejournal/core/database/CoffeeDatabaseMigrationTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/catalog/CatalogRepositoryTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/backup/BackupManagerTest.kt`

- [ ] **Step 1: Write v2→v3 migration and invariant tests**

Add tests that create a real v2 SQLite database and insert these representative rows before migration:

```kotlin
listOf(
    Triple("柠檬气泡美式", "鲜果咖啡", "FRUIT"),
    Triple("生椰拿铁", "拿铁", "MILK"),
    Triple("冰美式", "美式", "BLACK"),
    Triple("季节限定", null, "PENDING"),
)
```

After `MIGRATION_2_3`, assert `PRAGMA user_version=3`, `chainProductKind` exists, the four values match, and personal beans have `NULL`. Add repository tests that reject saving a new chain product with `null` or `PENDING`, accept the three public values, and require personal beans to keep `chainProductKind=null`.

- [ ] **Step 2: Write old/new backup tests before implementation**

Extend `BackupManagerTest` with:

- valid v1 → v3 restore and legacy category mapping;
- valid v2 → v3 restore and mapping;
- valid v3 validate → restore;
- v3 `chainProductKind='OTHER'` rejected before active DB/image mutation;
- v3 chain item with `NULL` rejected;
- v3 personal bean with non-null chain kind rejected;
- v3 manifest/user-version/identity mismatch rejected.

Expected initial result: compile failures for missing v3 symbols and test failures because current backup support stops at v2.

- [ ] **Step 3: Add the domain type and persisted column**

Add:

```kotlin
@Serializable
enum class ChainProductKind { BLACK, FRUIT, MILK, PENDING }
```

Add `val chainProductKind: ChainProductKind? = null` to `CatalogItem` and `val chainProductKind: String? = null` to `CatalogItemEntity`. Enforce structural consistency in mapping code:

```kotlin
require((type == ItemType.CHAIN_PRODUCT) == (chainProductKind != null))
```

Repository writes must additionally reject `PENDING`; reading migrated rows may retain it until the user edits them.

- [ ] **Step 4: Implement one deterministic legacy classifier**

Define `legacyChainProductKind(name: String, category: String?): ChainProductKind` with priority `FRUIT → MILK → BLACK → PENDING`. Use these exact keyword groups:

```kotlin
FRUIT = listOf("果", "柠檬", "橙", "葡萄", "莓", "桃", "气泡")
MILK = listOf("拿铁", "澳白", "卡布", "dirty", "奶", "乳")
BLACK = listOf("黑咖", "美式", "浓缩", "冷萃", "手冲")
```

Classify against normalized `"$name ${category.orEmpty()}"`. Keep this function in `Models.kt` or a focused `ChainProductKind.kt`, and use the same priority in SQL migration and old-backup restore.

- [ ] **Step 5: Implement Room v3 migration**

Set `@Database(version = 3)` and register both migrations:

```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3)
```

`MIGRATION_2_3` must add nullable `chainProductKind TEXT`, set personal beans to `NULL`, and update every chain row to `FRUIT`, `MILK`, `BLACK`, or `PENDING` with the priority above. Do not rebuild unrelated tables.

- [ ] **Step 6: Upgrade strict backup validation and copying**

Set `CoffeeDatabaseSchema.CURRENT = 3`. Generate schema 3, read its exported `identityHash`, and add that exact value to `identityHash(3)`.

Replace `V2_COLUMNS` with version-aware additions:

```kotlin
private val ADDED_COLUMNS = mapOf(
    2 to mapOf(
        "drink_records" to setOf("createdAtEpochMillis", "updatedAtEpochMillis", "revision"),
        "draft_records" to setOf("consumedAtEpochMillis", "editingRecordId", "expectedRecordRevision"),
    ),
    3 to mapOf("catalog_items" to setOf("chainProductKind")),
)
```

When comparing an older input schema, filter every column added after that input version. During restore, copy old `catalog_items` rows with an explicit destination column list and derive `chainProductKind` with `legacyChainProductKind`; do not depend on a null/default and do not mutate the read-only source DB. Add v3 domain SQL equivalent to:

```sql
SELECT 1 FROM catalog_items
WHERE (type='CHAIN_PRODUCT' AND chainProductKind NOT IN ('BLACK','FRUIT','MILK','PENDING'))
   OR (type='PERSONAL_BEAN' AND chainProductKind IS NOT NULL)
LIMIT 1
```

Retain the `catalog_updates` table in `TABLES` and the six-table atomic restore order.

- [ ] **Step 7: Run focused high-risk tests**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest --tests 'com.niumi.coffeejournal.core.database.CoffeeDatabaseMigrationTest' --tests 'com.niumi.coffeejournal.catalog.CatalogRepositoryTest' --tests 'com.niumi.coffeejournal.backup.BackupArchiveCodecTest' --tests 'com.niumi.coffeejournal.backup.BackupManagerTest' --no-daemon
git diff --check
```

Expected: PASS, schema 3 generated, `git diff --check` clean.

- [ ] **Step 8: Commit the atomic schema/backup change**

```bash
git add app/src/main/java/com/niumi/coffeejournal/core app/src/main/java/com/niumi/coffeejournal/catalog/CatalogRepository.kt app/src/main/java/com/niumi/coffeejournal/backup app/src/test/java/com/niumi/coffeejournal/core/database app/src/test/java/com/niumi/coffeejournal/catalog/CatalogRepositoryTest.kt app/src/test/java/com/niumi/coffeejournal/backup app/schemas/com.niumi.coffeejournal.core.database.CoffeeDatabase/3.json
git commit -m "feat: classify chain products in room v3"
```

### Task 3: Preserve whole selected images and keep them safe/content-addressed

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/core/image/ImageStore.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/core/image/ImagePathResolver.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/core/image/ThumbnailLoader.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/backup/BackupArchive.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/core/image/ImageStoreTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/core/image/ImagePathResolverTest.kt`
- Create: `app/src/test/java/com/niumi/coffeejournal/core/image/ThumbnailLoaderTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/backup/BackupArchiveCodecTest.kt`

- [ ] **Step 1: Add failing byte-preservation tests**

For PNG, JPEG, and WebP fixtures, call `importWhole`, then assert:

```kotlin
assertContentEquals(source.readBytes(), File(asset.localPath).readBytes())
assertEquals(sha256(source), asset.sha256)
```

Also test identical bytes deduplicate, invalid images are rejected, an image over 20 MiB is rejected without a DB row/file, cancellation cleans temporary files, and the managed filename uses the magic-derived extension.

- [ ] **Step 2: Implement a streaming original-byte path for `importWhole`**

Keep `importCropped` temporarily until Task 9 removes old callers. Change only `importWhole` to:

1. stream the content URI into a private temporary file with a 20 MiB bound;
2. verify decodeable bounds;
3. detect `png`, `jpg`, or `webp` from magic bytes, not display name/MIME alone;
4. hash the exact temporary bytes;
5. atomically rename to `<sha256>.<extension>`;
6. insert/deduplicate `image_assets` under `ImageMutationCoordinator.mutex`;
7. preserve the existing NonCancellable DB/file rollback semantics.

Update the safe managed filename regex to:

```kotlin
Regex("[0-9a-f]{64}\\.(png|jpg|jpeg|webp)")
```

- [ ] **Step 3: Keep display and backup compatible**

Make `ImagePathResolver` and `ThumbnailLoader` accept all four extensions and remain bounds/decode checked. Keep `BackupArchive.IMAGE_ENTRY`/magic validation aligned. Add an EXIF-rotation thumbnail test for JPEG and apply Exif orientation during thumbnail decode so preserved camera files display upright.

- [ ] **Step 4: Run image and backup codec tests**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest --tests 'com.niumi.coffeejournal.core.image.*' --tests 'com.niumi.coffeejournal.backup.BackupArchiveCodecTest' --no-daemon
```

Expected: all targeted tests PASS and previously stored `.webp` files still resolve.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/niumi/coffeejournal/core/image app/src/main/java/com/niumi/coffeejournal/backup/BackupArchive.kt app/src/test/java/com/niumi/coffeejournal/core/image app/src/test/java/com/niumi/coffeejournal/backup/BackupArchiveCodecTest.kt
git commit -m "feat: preserve manually selected product photos"
```

### Task 4: Bundle, verify, and install all 12 real brand logos

**Files:**
- Create: `app/src/main/res/drawable-nodpi/brand_logo_luckin.*`
- Create: `app/src/main/res/drawable-nodpi/brand_logo_cotti.*`
- Create: `app/src/main/res/drawable-nodpi/brand_logo_nowwa.*`
- Create: `app/src/main/res/drawable-nodpi/brand_logo_lucky_cup.*`
- Create: `app/src/main/res/drawable-nodpi/brand_logo_starbucks.*`
- Create: `app/src/main/res/drawable-nodpi/brand_logo_kcoffee.*`
- Create: `app/src/main/res/drawable-nodpi/brand_logo_manner.*`
- Create: `app/src/main/res/drawable-nodpi/brand_logo_hucoffee.*`
- Create: `app/src/main/res/drawable-nodpi/brand_logo_tims.*`
- Create: `app/src/main/res/drawable-nodpi/brand_logo_mstand.*`
- Create: `app/src/main/res/drawable-nodpi/brand_logo_peets.*`
- Create: `app/src/main/res/drawable-nodpi/brand_logo_arabica.*`
- Create: `app/src/main/java/com/niumi/coffeejournal/catalog/BundledBrandCatalog.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/core/database/Daos.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/catalog/CatalogRepository.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/CoffeeJournalApp.kt`
- Create: `docs/brand-logo-sources.md`
- Create: `app/src/test/java/com/niumi/coffeejournal/catalog/BundledBrandLogoTest.kt`
- Create: `app/src/test/java/com/niumi/coffeejournal/catalog/SeedBrandLogoRoomTest.kt`

- [ ] **Step 1: Acquire and audit real logo assets**

For each of the 12 brands, obtain a square or transparent official mark from the brand’s official public site, official public account/media kit, or an authoritative registered-trademark record when the brand has no downloadable media kit. Do not use Google favicon, Clearbit, remote runtime URLs, generated lettermarks, or screenshots from the brainstorm preview.

Normalize only canvas/padding and maximum dimension (512 px); do not redraw the mark or alter colors. In `docs/brand-logo-sources.md`, record brand, exact page URL, retrieval date `2026-08-16`, trademark owner, local filename, dimensions, and output of `shasum -a 256`.

- [ ] **Step 2: Write failing resource completeness/uniqueness tests**

Create `BundledBrandLogoTest` that asserts:

```kotlin
assertEquals(12, BUNDLED_CHAIN_BRANDS.size)
BUNDLED_CHAIN_BRANDS.forEach { assertNotNull(BitmapFactory.decodeResource(resources, it.logoRes)) }
assertEquals(12, BUNDLED_CHAIN_BRANDS.map { decodedPixelSha256(it.logoRes) }.toSet().size)
```

Also assert stable IDs and exact display order:

```text
luckin, cotti, nowwa, lucky-cup, starbucks, kcoffee, manner, hucoffee, tims, mstand, peets, arabica
```

- [ ] **Step 3: Define bundled brand metadata**

Create:

```kotlin
data class BundledBrandDefinition(
    val brand: Brand,
    @DrawableRes val logoRes: Int,
    val order: Int,
)
```

All brands are `BrandType.CHAIN`, `MaintenanceMode.MANUAL_ONLY`, `publicSourceUrl=null`. Preserve existing stable IDs for 瑞幸, MANNER, M Stand, Peet's, and %Arabica; add stable `seed-chain-*` IDs for the other seven.

- [ ] **Step 4: Install logos through the normal image store without overwrites**

Add DAO compare-and-set:

```kotlin
@Query("UPDATE brands SET logoAssetId=:assetId WHERE id=:brandId AND logoAssetId IS NULL")
suspend fun attachLogoIfMissing(brandId: String, assetId: String): Int
```

Inject `ImageStore` and a testable `(Int) -> Uri` resource URI factory into `RoomCatalogRepository`. Under a repository seed `Mutex`:

1. `INSERT IGNORE` all 12 brand rows;
2. for each current row whose Logo is null, `importWhole(resourceUri, BRAND_LOGO)`;
3. attach with `attachLogoIfMissing`;
4. if compare-and-set loses a race, call `deleteIfUnreferenced` for the imported asset;
5. never overwrite current name, Logo, or other user edits.

Sort chain observations by bundled order then custom normalized name; keep roaster sorting unchanged.

- [ ] **Step 5: Add idempotence and persistence tests**

Using real Room and `LocalImageStore`, assert first install creates 12 brands/12 decodeable Logo references, second install creates no duplicates, a user-replaced Logo remains unchanged, partial failure retries only missing logos, and custom brands sort after the 12 seeds.

- [ ] **Step 6: Run targeted tests and commit**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest --tests 'com.niumi.coffeejournal.catalog.BundledBrandLogoTest' --tests 'com.niumi.coffeejournal.catalog.SeedBrandLogoRoomTest' --tests 'com.niumi.coffeejournal.catalog.CatalogRepositoryTest' --tests 'com.niumi.coffeejournal.core.image.ImageStoreTest' --tests 'com.niumi.coffeejournal.backup.BackupManagerTest' --no-daemon
```

Expected: PASS.

```bash
git add app/src/main/res/drawable-nodpi app/src/main/java/com/niumi/coffeejournal/catalog/BundledBrandCatalog.kt app/src/main/java/com/niumi/coffeejournal/catalog/CatalogRepository.kt app/src/main/java/com/niumi/coffeejournal/core/database/Daos.kt app/src/main/java/com/niumi/coffeejournal/CoffeeJournalApp.kt app/src/test/java/com/niumi/coffeejournal/catalog docs/brand-logo-sources.md
git commit -m "feat: bundle top chain brand logos"
```

### Task 5: Build one reusable manual chain-product editor

**Files:**
- Create: `app/src/main/java/com/niumi/coffeejournal/catalog/ManualProductEditorViewModel.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/catalog/ManualProductEditorDialog.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/catalog/CatalogViewModel.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/TestTags.kt`
- Create: `app/src/test/java/com/niumi/coffeejournal/catalog/ManualProductEditorViewModelTest.kt`
- Create: `app/src/test/java/com/niumi/coffeejournal/catalog/ManualProductEditorDialogTest.kt`

- [ ] **Step 1: Write editor validation and image-lifecycle tests**

Cover required trimmed name, required BLACK/FRUIT/MILK, PENDING refusal, same-brand duplicate message, optional photo, replace/remove photo, cancel cleanup, failed-save cleanup, successful-save retention, and edit preservation of hidden legacy fields.

- [ ] **Step 2: Define focused editor state/events**

Use:

```kotlin
data class ManualProductEditorState(
    val open: Boolean = false,
    val brand: Brand? = null,
    val editing: CatalogItem? = null,
    val name: String = "",
    val kind: ChainProductKind? = null,
    val imageAssetId: String? = null,
    val saving: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface ManualProductEditorEvent {
    data class Saved(val itemId: String, val brandId: String) : ManualProductEditorEvent
}
```

The ViewModel exposes `openNew`, `openEdit`, field setters, `acceptImportedAsset`, `removePhoto`, `save`, `dismiss`, and a buffered event Flow. Use a single operation Mutex and NonCancellable cleanup so cancellation cannot leave a newly imported unreferenced image.

- [ ] **Step 3: Implement minimal manual save semantics**

New items use `ItemType.CHAIN_PRODUCT`, `ItemStatus.ACTIVE`, the selected kind, and null legacy network metadata. Edits copy the existing item and change only name, image, and kind. After DB commit, delete a replaced old image only through `deleteIfUnreferenced`.

- [ ] **Step 4: Build the shared dialog**

The dialog contains:

- product name field;
- three `FilterChip`s labelled 黑咖、果咖、奶咖;
- square preview using product image then brand Logo;
- 选择／更换实拍图;
- 移除实拍图 when present;
- 保存／取消.

No origin, processing, caffeine, official description, source URL, OCR, crop, or screenshot controls are shown for chain products.

- [ ] **Step 5: Run editor tests and commit**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest --tests 'com.niumi.coffeejournal.catalog.ManualProductEditorViewModelTest' --tests 'com.niumi.coffeejournal.catalog.ManualProductEditorDialogTest' --no-daemon
```

Expected: PASS with cancellation/image cleanup cases included.

```bash
git add app/src/main/java/com/niumi/coffeejournal/catalog/ManualProductEditor* app/src/main/java/com/niumi/coffeejournal/catalog/CatalogViewModel.kt app/src/main/java/com/niumi/coffeejournal/TestTags.kt app/src/test/java/com/niumi/coffeejournal/catalog/ManualProductEditor*
git commit -m "feat: add manual chain product editor"
```

### Task 6: Replace the chain catalog UI with brand and product grids

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/catalog/CatalogScreen.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/catalog/BrandProductsScreen.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/core/image/LocalAssetImage.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/navigation/AppNavigation.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/catalog/CatalogScreenRobolectricTest.kt`
- Create: `app/src/test/java/com/niumi/coffeejournal/catalog/BrandProductsScreenTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/navigation/AppNavigationTest.kt`
- Modify: `app/src/androidTest/java/com/niumi/coffeejournal/catalog/CatalogScreenTest.kt`

- [ ] **Step 1: Add failing navigation/grid tests**

Assert the chain root:

- shows exactly three columns at phone width;
- displays all 12 brand Logo semantics and names;
- contains no 门店, 排名, 更新该品牌, 最后更新, 官网, or OCR text;
- opens a brand child destination on Logo click;
- supports system/back button to return to the same catalog root;
- places 新增品牌 after built-ins.

Assert the child page:

- two-column product cards;
- center-cropped product photo and brand Logo fallback;
- visible name/type labels;
- filters all/black/fruit/milk and conditional pending;
- empty state and add action;
- product click opens the shared editor.

- [ ] **Step 2: Extract reusable local image rendering**

Move the Journal-only `LocalCoffeeImage` pattern into `core/image/LocalAssetImage.kt` as two type-consistent entry points:

```kotlin
@Composable fun LocalAssetImage(
    primaryPath: String?, fallbackPath: String?, contentDescription: String,
    contentScale: ContentScale = ContentScale.Crop,
)

@Composable fun ResolvedLocalAssetImage(
    primaryAssetId: String?, fallbackAssetId: String?, resolver: ImagePathResolver,
    contentDescription: String, contentScale: ContentScale = ContentScale.Crop,
)
```

The resolved form resolves IDs and delegates to the path form. Both render a generic coffee placeholder only after primary and fallback fail. Journal uses the path form because `JournalViewModel` already resolves immutable snapshot IDs; catalog/editor screens use the resolved form.

- [ ] **Step 3: Implement the three-column brand root**

Use `LazyVerticalGrid(GridCells.Fixed(3))`. Cards have a square Logo region plus a one-line ellipsized brand name; no counts/status. Keep the personal bean tab and its existing editor behavior, but split chain-specific rendering out of the old expanded-card loop. Require Logo on new chain brand save; keep custom brand edit accessible from the brand child header.

- [ ] **Step 4: Implement the brand child route**

Add:

```kotlin
@Serializable data class ChainBrandProducts(val brandId: String) : NavKey
```

Add a Navigation3 entry that loads the brand/items, passes `onBack`, hides the bottom root navigation while the child is active, and displays `BrandProductsScreen`. Do not model the child as a permanently expanded card on the root.

- [ ] **Step 5: Connect the shared editor and gallery request**

The root/child use `ManualProductEditorViewModel`; collect `Saved` events to close/refresh. Pass only a whole-image `AssetImportRequester`. The child editor preview uses the real imported image or brand Logo.

- [ ] **Step 6: Run UI/navigation tests and commit**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest --tests 'com.niumi.coffeejournal.catalog.CatalogScreenRobolectricTest' --tests 'com.niumi.coffeejournal.catalog.BrandProductsScreenTest' --tests 'com.niumi.coffeejournal.navigation.AppNavigationTest' --no-daemon
```

Expected: PASS at the phone-width Robolectric qualifier.

```bash
git add app/src/main/java/com/niumi/coffeejournal/catalog app/src/main/java/com/niumi/coffeejournal/core/image/LocalAssetImage.kt app/src/main/java/com/niumi/coffeejournal/navigation/AppNavigation.kt app/src/test/java/com/niumi/coffeejournal/catalog app/src/test/java/com/niumi/coffeejournal/navigation app/src/androidTest/java/com/niumi/coffeejournal/catalog
git commit -m "feat: show compact brand and product grids"
```

### Task 7: Add persistent 品牌／咖啡 calendar views

**Files:**
- Create: `app/src/main/java/com/niumi/coffeejournal/journal/CalendarDisplayPreference.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/JournalProjection.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/JournalViewModel.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/JournalScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/CoffeeJournalApp.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/MainActivity.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/TestTags.kt`
- Create: `app/src/test/java/com/niumi/coffeejournal/journal/CalendarDisplayPreferenceTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/journal/JournalViewModelTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/journal/JournalScreenRobolectricTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt`

- [ ] **Step 1: Write persistence/projection/UI red tests**

Cover:

- empty/corrupt preference defaults to `COFFEE`;
- write `BRAND`, construct a fresh preference/ViewModel, and read `BRAND`;
- `BRAND` selects only `brandLogoPath` and falls directly to generic placeholder;
- `COFFEE` selects `imagePath`, then `brandLogoPath`, then generic placeholder;
- switching modes leaves `records`, summary, representative record, day count, and `×N` unchanged;
- month navigation preserves the selected mode;
- UI labels are exactly `品牌` and `咖啡`, with only one selected;
- switching does not call any Journal/Catalog repository write method.

- [ ] **Step 2: Define the preference boundary**

Create:

```kotlin
enum class CalendarDisplayMode { BRAND, COFFEE }

interface CalendarDisplayPreference {
    fun read(): CalendarDisplayMode
    fun write(mode: CalendarDisplayMode)
}
```

`SharedPreferencesCalendarDisplayPreference` uses private preferences named `calendar_ui`, key `display_mode`, `COFFEE` as the default, and `runCatching { valueOf(raw) }.getOrDefault(COFFEE)` for corrupted values. This is UI preference state, not Room/backup business data.

- [ ] **Step 3: Add mode to state without re-projecting records**

Add `calendarDisplayMode: CalendarDisplayMode = COFFEE` to `JournalUiState`. Inject the preference into `JournalViewModel`; initialize from `read()`. Implement:

```kotlin
fun setCalendarDisplayMode(mode: CalendarDisplayMode) {
    if (mutableState.value.calendarDisplayMode == mode) return
    mutableState.value = mutableState.value.copy(calendarDisplayMode = mode)
    calendarDisplayPreference.write(mode)
}
```

Preserve the field in `changeMonth`. Keep `projectMonth`, latest-record tie-breaking, and `drinkCount` unchanged; mode changes only which already-resolved path the composable requests.

- [ ] **Step 4: Render the exact two-option control and fallback rules**

Directly below the owned `咖啡日历` page title, add Material 3 `SingleChoiceSegmentedButtonRow` with two `SegmentedButton`s labelled exactly `品牌` and `咖啡`.

For each recorded day:

```kotlin
val primary = if (mode == CalendarDisplayMode.BRAND) day.brandLogoPath else day.imagePath
val fallback = if (mode == CalendarDisplayMode.COFFEE) day.brandLogoPath else null
LocalAssetImage(primaryPath = primary, fallbackPath = fallback, contentScale = ContentScale.Crop)
```

Keep the date number and `×N` overlay in both modes.

- [ ] **Step 5: Inject the production preference**

Expose one `calendarDisplayPreference` from `CoffeeJournalApp`, pass it through `MainActivity → AppNavigation → JournalFeature → JournalViewModel.factory`, and override/fake it in acceptance tests so tests never share host preferences.

- [ ] **Step 6: Run targeted calendar tests and commit**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest --tests 'com.niumi.coffeejournal.journal.CalendarDisplayPreferenceTest' --tests 'com.niumi.coffeejournal.journal.JournalViewModelTest' --tests 'com.niumi.coffeejournal.journal.JournalScreenRobolectricTest' --tests 'com.niumi.coffeejournal.ReleaseAcceptanceRobolectricTest' --no-daemon
```

Expected: PASS in both modes with persistence and no repository writes.

```bash
git add app/src/main/java/com/niumi/coffeejournal/journal app/src/main/java/com/niumi/coffeejournal/CoffeeJournalApp.kt app/src/main/java/com/niumi/coffeejournal/MainActivity.kt app/src/main/java/com/niumi/coffeejournal/navigation/AppNavigation.kt app/src/main/java/com/niumi/coffeejournal/TestTags.kt app/src/test/java/com/niumi/coffeejournal/journal app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt
git commit -m "feat: switch calendar between brand and coffee views"
```

### Task 8: Add products from the daily record and auto-select them

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/JournalScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/RecordDrinkScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/JournalViewModel.kt` only if a small explicit helper is needed
- Modify: `app/src/test/java/com/niumi/coffeejournal/journal/JournalViewModelTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/journal/JournalScreenRobolectricTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt`

- [ ] **Step 1: Write the end-to-end quick-add regression**

Using real `MainActivity → Room → repositories → ViewModels → UI`:

1. open 记录一杯;
2. select a chain brand;
3. enter date/time, rating `4.5`, price `9.90`, brew, and note;
4. tap 添加新产品;
5. enter name, select 果咖, choose a fixture photo, save;
6. assert return to record form, the new item is selected, and every prior draft field is unchanged;
7. save record and assert Room snapshot contains the new item/photo.

Add cancellation/failure tests proving no selection or draft field changes.

- [ ] **Step 2: Add the record-screen entry**

When source type is `CHAIN_PRODUCT` and a brand is selected, show `添加新产品` next to/below product selection. Disable it while the record editor is saving/selecting/attaching.

- [ ] **Step 3: Reuse the editor and select after persisted save**

Create the editor ViewModel with a distinct Navigation/Compose ViewModel key for the journal flow. On `ManualProductEditorEvent.Saved`, call:

```kotlin
journalViewModel.selectItem(ItemType.CHAIN_PRODUCT, event.itemId)
```

Only close the product dialog after the repository commit and selection succeeds. Existing `replaceDraftForItem` must preserve consumed time, rating, price, brew, note, and edit metadata exactly as covered by current concurrency tests.

- [ ] **Step 4: Run journal and acceptance tests and commit**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest --tests 'com.niumi.coffeejournal.journal.*' --tests 'com.niumi.coffeejournal.ReleaseAcceptanceRobolectricTest' --no-daemon
```

Expected: PASS, including quick-add draft preservation and immutable history.

```bash
git add app/src/main/java/com/niumi/coffeejournal/journal app/src/test/java/com/niumi/coffeejournal/journal app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt
git commit -m "feat: quick add products while recording"
```

### Task 9: Delete website update, OCR, screenshot, and crop logic

**Files:**
- Create: `app/src/main/java/com/niumi/coffeejournal/core/image/WholeImageImportHost.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/JournalScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/RecordDrinkScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/catalog/CatalogScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/catalog/CatalogViewModel.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/CoffeeJournalApp.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/MainActivity.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/core/image/ImageStore.kt`
- Delete: `app/src/main/java/com/niumi/coffeejournal/importer/`
- Delete: `app/src/main/java/com/niumi/coffeejournal/catalog/CatalogScreenshotImportSession.kt`
- Delete: `app/src/test/java/com/niumi/coffeejournal/importer/`
- Delete: `app/src/test/java/com/niumi/coffeejournal/catalog/CatalogScreenshotImportSessionTest.kt`
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/test/java/com/niumi/coffeejournal/OfflineManifestTest.kt`

- [ ] **Step 1: Move the only retained generic picker behavior**

Create `WholeImageImportHost` with one `GetContent("image/*")` launcher. Retain the proven `associateImportedAsset` behavior: imported asset is kept only if the supplied association callback returns true; false, cancellation, or exception deletes it through `deleteIfUnreferenced`. Its requester signature is:

```kotlin
typealias AssetImportRequester = (
    ImageKind,
    String?,
    suspend (ImportedAssetSelection) -> Boolean,
) -> Unit
```

There is no mode enum, ASK dialog, screenshot branch, OCR review, or crop surface.

- [ ] **Step 2: Rewire production constructors**

Remove screenshot recognizer, source provider, and update gateway parameters/properties from `CoffeeJournalApp`, `MainActivity`, `AppNavigation`, `CatalogFeature`, and tests/fakes. Journal missing-image UI becomes “选择实拍图片” or “使用品牌 Logo”; no screenshot wording remains.

- [ ] **Step 3: Delete dead implementations and tests**

Delete the importer package after generic picker code is moved. Remove `CatalogScreenshotImportSession`. Remove `ImageStore.importCropped`, `CropRect`, crop exceptions, and their tests only after `rg 'importCropped|SCREENSHOT|CatalogUpdate|ScreenshotTextRecognizer|OfficialSource' app/src` shows no production callers.

Keep `CatalogUpdateEntity`, `CatalogUpdateDao`, and the `catalog_updates` Room table for strict old-backup compatibility, but no production feature may query or write it.

- [ ] **Step 4: Remove dependencies and permission**

Delete ML Kit and OkHttp aliases/versions and both implementation lines. Remove `<uses-permission android:name="android.permission.INTERNET"/>`. Do not add replacement network libraries.

- [ ] **Step 5: Add offline/static absence tests**

Assert merged manifest requests no `INTERNET`, camera, location, or storage runtime permission. Run:

```bash
rg -n '更新该品牌|官网更新|截图识别|上传完整截图|MlKit|OkHttp|SCREENSHOT|importCropped' app/src/main app/build.gradle.kts gradle/libs.versions.toml
```

Expected: no matches except migration/compatibility comments that do not contain executable references.

- [ ] **Step 6: Run affected tests and commit**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest --tests 'com.niumi.coffeejournal.core.image.*' --tests 'com.niumi.coffeejournal.catalog.*' --tests 'com.niumi.coffeejournal.journal.*' --tests 'com.niumi.coffeejournal.navigation.*' --tests 'com.niumi.coffeejournal.backup.*' --tests 'com.niumi.coffeejournal.OfflineManifestTest' --no-daemon
```

Expected: PASS.

```bash
git add -A app/src/main app/src/test app/build.gradle.kts gradle/libs.versions.toml
git commit -m "refactor: remove catalog network and ocr flows"
```

### Task 10: Final docs, cross-feature verification, and reviews

**Files:**
- Modify: `README.md`
- Modify: `docs/PROJECT_STATE.md`
- Modify: `docs/superpowers/specs/2026-08-16-manual-chain-catalog-redesign-design.md` status only
- Modify: acceptance tests if review finds a missing regression

- [ ] **Step 1: Update user-facing documentation**

Rewrite README sections for:

- three-column brand grid and brand child product page;
- App/launcher name and first bottom Tab renamed to `咖啡日历`, with no global top bar;
- persistent `品牌／咖啡` calendar switch and both fallback rules;
- 12 preinstalled Logo-only brands;
- manual brand and product creation/edit;
- black/fruit/milk types and pending legacy products;
- whole original photo selection and Logo fallback;
- daily-record quick add;
- fully offline/no OCR/no website update/no Internet permission;
- unchanged backup/export requirement before uninstall.

Remove ML Kit diagnostic disclosure and official-update instructions because those dependencies no longer ship.

- [ ] **Step 2: Run focused suites in risk order**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest --tests 'com.niumi.coffeejournal.core.database.*' --tests 'com.niumi.coffeejournal.backup.*' --tests 'com.niumi.coffeejournal.core.image.*' --tests 'com.niumi.coffeejournal.catalog.*' --tests 'com.niumi.coffeejournal.journal.*' --tests 'com.niumi.coffeejournal.navigation.*' --no-daemon
```

Expected: PASS with no failures/errors/skips.

- [ ] **Step 3: Request normal and critical reviews**

Use a normal reviewer for Compose/navigation/editor state and a critical reviewer for Room v3, v1/v2/v3 restore, image file/row consistency, seed races, cancellation, and history snapshot invariants. Give each only the spec, change summary, modified-file list, and test evidence. Return clear findings to the implementer; rerun targeted tests after every fix.

- [ ] **Step 4: Run the clean release matrix**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --no-daemon
```

Expected: BUILD SUCCESSFUL, lint 0 errors, schema 3 checked in, debug/androidTest/release APKs present.

- [ ] **Step 5: Inspect the packaged result**

Verify:

- merged Debug Manifest has NoActionBar theme and no INTERNET;
- APK contains all 12 `brand_logo_*` resources;
- `apkanalyzer`/AAPT resource dump resolves each Logo;
- Debug APK v1/v2 signature is valid;
- record APK byte size and SHA-256;
- `git diff --check` and `git status --short` are clean.

If an Android device is connected, install with `adb install -r`, confirm the launcher and first Tab both say `咖啡日历`, visit all three root tabs to verify each owned heading and no ActionBar/global-top-bar overlap, switch `品牌／咖啡` and restart to verify persistence, open all 12 brands in airplane mode and visually confirm each real Logo, add/edit a real product photo, quick-add from the record page, and export/validate/restore a backup. If no device is connected, report those checks as explicitly unrun.

- [ ] **Step 6: Update project state and commit**

Set the design spec status to implemented only after all required reviews and verification pass. Record the final commit, schema version, test count, APK hash, permissions, and remaining true-device gaps in `PROJECT_STATE.md`.

```bash
git add README.md docs/PROJECT_STATE.md docs/superpowers/specs/2026-08-16-manual-chain-catalog-redesign-design.md
git commit -m "docs: describe offline manual catalog"
```
