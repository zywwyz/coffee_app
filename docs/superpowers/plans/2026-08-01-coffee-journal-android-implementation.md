# Coffee Journal Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an installable, local-first Android coffee journal with a photo calendar, reusable chain/personal-bean catalogs, reviewed imports, monthly/yearly insights, and complete backup.

**Architecture:** Use one Android app module with feature-focused packages, Compose UI, Room as the single source of truth, and repositories between UI and data sources. Brand adapters, OCR, image storage, insights, and backup stay behind interfaces so their failures never block manual recording.

**Tech Stack:** Kotlin 2.2.21, AGP 8.13.0, Gradle 8.13, SDK 36/min SDK 23, Compose BOM 2026.06.00, Navigation 3 1.1.4, Room 2.8.4, KSP 2.2.21-2.0.5, kotlinx.serialization 1.9.0, bundled ML Kit Chinese OCR 16.0.1, JUnit 4, AndroidX Test, Compose UI tests.

---

## Delivery order and file map

Implement in working increments: foundation → database/repositories → diary → catalogs → import → insights → backup/release. Do not begin a later increment while the previous one has failing tests or cannot assemble an APK.

```text
app/src/main/java/com/niumi/coffeejournal/
├── CoffeeJournalApp.kt                 # dependency container
├── MainActivity.kt                     # single Compose host
├── navigation/AppNavigation.kt         # root destinations
├── ui/theme/CoffeeTheme.kt             # design tokens
├── core/model/Models.kt                # stable value/domain types
├── core/database/{Entities,Daos,CoffeeDatabase}.kt
├── core/image/ImageStore.kt            # private image files and hashes
├── journal/{JournalRepository,JournalViewModel,JournalScreen,RecordDrinkScreen}.kt
├── catalog/{CatalogRepository,CatalogViewModel,CatalogScreen}.kt
├── importer/{CatalogSource,CatalogDiff,OfficialSources,ScreenshotImporter,ImportReviewScreen}.kt
├── insights/{InsightsCalculator,InsightsViewModel,InsightsScreen}.kt
├── backup/BackupManager.kt
└── settings/SettingsScreen.kt
```

Tests mirror feature packages under `app/src/test` and `app/src/androidTest`. Keep each source file focused and below roughly 300 lines; extract private UI components when a screen exceeds that size.

### Task 1: Bootstrap and prove the Android toolchain

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/niumi/coffeejournal/CoffeeJournalApp.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/MainActivity.kt`
- Test: `app/src/test/java/com/niumi/coffeejournal/ToolchainSmokeTest.kt`

- [ ] **Step 1: Select the local Android environment**

Use Android Studio's JDK 17 and SDK Manager to install Platform 36, Build Tools 36.0.0, Platform Tools, and Command-line Tools. Verify:

```bash
java -version
sdkmanager --list_installed
```

Expected: Java 17+ and installed entries for `platforms;android-36` and `build-tools;36.0.0`. The current machine has no discoverable Java runtime or Android SDK, so obtain user approval before downloading Android Studio.

- [ ] **Step 2: Pin build plugins and libraries**

Create `gradle/libs.versions.toml` with:

```toml
[versions]
agp = "8.13.0"
kotlin = "2.2.21"
ksp = "2.2.21-2.0.5"
composeBom = "2026.06.00"
room = "2.8.4"
navigation3 = "1.1.4"
serialization = "1.9.0"
mlkit = "16.0.1"

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room = { id = "androidx.room", version.ref = "room" }

[libraries]
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
activity-compose = { module = "androidx.activity:activity-compose", version = "1.12.3" }
lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version = "2.10.0" }
navigation3-runtime = { module = "androidx.navigation3:navigation3-runtime", version.ref = "navigation3" }
navigation3-ui = { module = "androidx.navigation3:navigation3-ui", version.ref = "navigation3" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
mlkit-chinese = { module = "com.google.mlkit:text-recognition-chinese", version.ref = "mlkit" }
junit = { module = "junit:junit", version = "4.13.2" }
```

Configure namespace `com.niumi.coffeejournal`, Java/Kotlin 17, `minSdk = 23`, `targetSdk = 36`, and Room schemas at `app/schemas`.

- [ ] **Step 3: Write the smoke test before the app shell**

```kotlin
package com.niumi.coffeejournal

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolchainSmokeTest {
    @Test fun arithmetic_runs_on_jvm() = assertEquals(4, 2 + 2)
}
```

- [ ] **Step 4: Generate wrapper, test, and build**

```bash
gradle wrapper --gradle-version 8.13
./gradlew testDebugUnitTest assembleDebug
```

Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle gradlew gradlew.bat app
git commit -m "build: bootstrap Android coffee journal"
```

### Task 2: Define domain types and snapshot invariants

**Files:**
- Create: `app/src/main/java/com/niumi/coffeejournal/core/model/Models.kt`
- Test: `app/src/test/java/com/niumi/coffeejournal/core/model/ModelsTest.kt`

- [ ] **Step 1: Write failing money, rating, and snapshot tests**

```kotlin
class ModelsTest {
    @Test fun money_uses_integer_fen() = assertEquals("¥9.90", Money(990).formatCny())
    @Test fun rating_accepts_half_stars() = assertEquals(4.5, Rating(9).stars, 0.0)
    @Test fun invalid_rating_fails() = assertThrows(IllegalArgumentException::class.java) { Rating(11) }
    @Test fun snapshot_keeps_display_name() {
        assertEquals("生椰拿铁", DrinkSnapshot("瑞幸", "生椰拿铁", null, null, "img-1").itemName)
    }
}
```

- [ ] **Step 2: Verify red**

Run `./gradlew testDebugUnitTest --tests '*ModelsTest'`.

Expected: compilation fails because the domain types do not exist.

- [ ] **Step 3: Implement complete value types**

```kotlin
@JvmInline value class Money(val fen: Long) {
    init { require(fen >= 0) }
    fun formatCny() = "¥%.2f".format(Locale.CHINA, fen / 100.0)
}

@JvmInline value class Rating(val halfStars: Int) {
    init { require(halfStars in 1..10) }
    val stars: Double get() = halfStars / 2.0
}

enum class BrandType { CHAIN, ROASTER }
enum class ItemType { CHAIN_PRODUCT, PERSONAL_BEAN }
enum class ItemStatus { ACTIVE, NEEDS_IMAGE, DISCONTINUED, ARCHIVED }
enum class MaintenanceMode { PUBLIC_SOURCE, MANUAL_ONLY }

@Serializable
data class DrinkSnapshot(
    val brandName: String,
    val itemName: String,
    val origin: String?,
    val processing: String?,
    val imageAssetId: String?,
    val roastLevel: String? = null,
    val flavorNotes: String? = null
)

@Serializable
data class DrinkRecord(
    val id: String,
    val occurredAtEpochMillis: Long,
    val localDate: String,
    val itemType: ItemType,
    val sourceItemId: String,
    val brewMethod: String?,
    val ratingHalfStars: Int?,
    val actualPriceFen: Long?,
    val note: String?,
    val snapshot: DrinkSnapshot
)

data class Brand(
    val id: String,
    val type: BrandType,
    val name: String,
    val logoAssetId: String?,
    val maintenanceMode: MaintenanceMode,
    val publicSourceUrl: String?
)

data class CatalogItem(
    val id: String,
    val brandId: String,
    val type: ItemType,
    val name: String,
    val imageAssetId: String?,
    val origin: String?,
    val processing: String?,
    val roastLevel: String?,
    val flavorNotes: String?,
    val brewMethod: String?,
    val status: ItemStatus,
    val caffeineMg: Double? = null,
    val officialDescription: String? = null,
    val purchaseDate: String? = null,
    val roastDate: String? = null,
    val sourceUrl: String? = null,
    val sourceFetchedAt: Long? = null,
    val informationCompleteness: Int = 0
)

data class DrinkDraft(
    val itemType: ItemType,
    val sourceItemId: String,
    val brewMethod: String?,
    val ratingHalfStars: Int?,
    val actualPriceFen: Long?,
    val note: String
)
```

- [ ] **Step 4: Verify green and commit**

```bash
./gradlew testDebugUnitTest --tests '*ModelsTest'
git add app/src/main/java/com/niumi/coffeejournal/core/model app/src/test/java/com/niumi/coffeejournal/core/model
git commit -m "feat: define coffee journal domain models"
```

Expected: four tests pass before commit.

### Task 3: Persist local data with Room

**Files:**
- Create: `app/src/main/java/com/niumi/coffeejournal/core/database/Entities.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/core/database/Daos.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/core/database/CoffeeDatabase.kt`
- Test: `app/src/androidTest/java/com/niumi/coffeejournal/core/database/CoffeeDatabaseTest.kt`

- [ ] **Step 1: Write a failing snapshot isolation test**

```kotlin
@Test fun catalog_update_does_not_change_record_snapshot() = runTest {
    db.brandDao().upsert(brand("b1", "瑞幸"))
    db.itemDao().upsert(item("p1", "b1", "旧名称"))
    db.drinkDao().insert(drink("d1", "p1", snapshotName = "旧名称"))
    db.itemDao().upsert(item("p1", "b1", "新名称"))
    assertEquals("旧名称", db.drinkDao().get("d1")!!.snapshotItemName)
}
```

- [ ] **Step 2: Verify red**

Run the single connected test. Expected: database symbols are missing.

- [ ] **Step 3: Implement entities and narrow DAOs**

Create `BrandEntity`, `CatalogItemEntity`, `DrinkRecordEntity`, `ImageAssetEntity`, `CatalogUpdateEntity`, and `DraftRecordEntity`. Use UUID string keys, integer fen/half-stars, a unique `(brandId, normalizedName)` item index, and explicit snapshot columns on records.

```kotlin
@Dao
interface DrinkDao {
    @Insert suspend fun insert(record: DrinkRecordEntity)
    @Update suspend fun update(record: DrinkRecordEntity)
    @Delete suspend fun delete(record: DrinkRecordEntity)
    @Query("SELECT * FROM drink_records WHERE id = :id") suspend fun get(id: String): DrinkRecordEntity?
    @Query("SELECT * FROM drink_records WHERE localDate BETWEEN :start AND :end ORDER BY occurredAtEpochMillis")
    fun observeRange(start: String, end: String): Flow<List<DrinkRecordEntity>>
}
```

Declare schema version 1, export schemas, expose every DAO, and do not enable destructive migration.

- [ ] **Step 4: Verify database and schema output**

```bash
./gradlew testDebugUnitTest connectedDebugAndroidTest
test -f app/schemas/com.niumi.coffeejournal.core.database.CoffeeDatabase/1.json
```

Expected: tests pass and schema JSON exists.

- [ ] **Step 5: Commit**

```bash
git add app/src/main app/src/androidTest app/schemas
git commit -m "feat: persist coffee journal data with Room"
```

### Task 4: Add catalog and journal repositories

**Files:**
- Create: `app/src/main/java/com/niumi/coffeejournal/catalog/CatalogRepository.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/journal/JournalRepository.kt`
- Test: `app/src/test/java/com/niumi/coffeejournal/journal/JournalRepositoryTest.kt`

- [ ] **Step 1: Write a failing snapshot and last-price test**

```kotlin
@Test fun save_copies_catalog_and_reuses_price() = runTest {
    val catalog = FakeCatalogRepository("生椰拿铁", lastPriceFen = 990)
    val store = FakeDrinkStore()
    val repository = DefaultJournalRepository(catalog, store, FixedClock("2026-08-01", 1L))
    val draft = repository.newDraft(ItemType.CHAIN_PRODUCT, "p1")
    assertEquals(990L, draft.actualPriceFen)
    repository.save(draft.copy(ratingHalfStars = 9))
    assertEquals("生椰拿铁", store.saved.single().snapshot.itemName)
}
```

- [ ] **Step 2: Verify red**

Run `./gradlew testDebugUnitTest --tests '*JournalRepositoryTest'`.

- [ ] **Step 3: Implement repository contracts and atomic save**

```kotlin
interface JournalRepository {
    fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>>
    suspend fun newDraft(type: ItemType, itemId: String): DrinkDraft
    suspend fun save(draft: DrinkDraft): String
    suspend fun saveDraft(draft: DrinkDraft)
    suspend fun delete(recordId: String)
}

interface CatalogRepository {
    fun observeBrands(type: BrandType): Flow<List<Brand>>
    fun observeItems(brandId: String): Flow<List<CatalogItem>>
    suspend fun getItem(itemId: String): CatalogItem
    suspend fun upsertBrand(brand: Brand)
    suspend fun upsertItem(item: CatalogItem)
    suspend fun lastPriceFen(itemId: String): Long?
}

interface Clock {
    fun nowEpochMillis(): Long
    fun todayLocalDate(): String
}

object SystemClock : Clock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
    override fun todayLocalDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
}
```

`save` fetches the current catalog item, builds `DrinkSnapshot`, inserts the record, and clears the draft in one Room transaction. Rating, price, brew method, and note are optional; item selection is required.

- [ ] **Step 4: Verify green and commit**

```bash
./gradlew testDebugUnitTest connectedDebugAndroidTest
git add app/src/main/java/com/niumi/coffeejournal/catalog app/src/main/java/com/niumi/coffeejournal/journal app/src/test
git commit -m "feat: add catalog and journal repositories"
```

### Task 5: Add theme, navigation, and dependency container

**Files:**
- Create: `app/src/main/java/com/niumi/coffeejournal/ui/theme/CoffeeTheme.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/CoffeeJournalApp.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/MainActivity.kt`
- Test: `app/src/androidTest/java/com/niumi/coffeejournal/navigation/AppNavigationTest.kt`

- [ ] **Step 1: Write the failing navigation test**

```kotlin
@Test fun bottom_bar_opens_three_roots() {
    compose.onNodeWithText("日记").assertIsDisplayed()
    compose.onNodeWithText("豆库").performClick()
    compose.onNodeWithText("连锁品牌").assertIsDisplayed()
    compose.onNodeWithText("总结").performClick()
    compose.onNodeWithText("月度总结").assertIsDisplayed()
}
```

- [ ] **Step 2: Verify red**

Run the single `AppNavigationTest`. Expected: root nodes are missing.

- [ ] **Step 3: Implement the shell**

Define serializable `Journal`, `Catalog`, and `Insights` navigation keys. Use a single `rememberNavBackStack(Journal)`, `NavDisplay`, and three-item Material bottom bar. Theme colors are cream `#FAF7F1`, espresso `#2E241A`, evergreen `#2F5D50`, and caramel `#C78956`.

```kotlin
class CoffeeJournalApp : Application() {
    val database by lazy { CoffeeDatabase.create(this) }
    val catalogRepository by lazy { RoomCatalogRepository(database) }
    val journalRepository by lazy {
        DefaultJournalRepository(database, catalogRepository, SystemClock)
    }
}
```

- [ ] **Step 4: Verify green and commit**

```bash
./gradlew connectedDebugAndroidTest assembleDebug
git add app/src/main app/src/androidTest
git commit -m "feat: add coffee journal app shell"
```

### Task 6: Build the photo calendar and fast record flow

**Files:**
- Create: `app/src/main/java/com/niumi/coffeejournal/journal/JournalViewModel.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/journal/JournalScreen.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/journal/RecordDrinkScreen.kt`
- Test: `app/src/test/java/com/niumi/coffeejournal/journal/JournalViewModelTest.kt`
- Test: `app/src/androidTest/java/com/niumi/coffeejournal/journal/JournalScreenTest.kt`

- [ ] **Step 1: Write failing calendar rules**

```kotlin
@Test fun same_day_uses_last_image_and_count() {
    val records = listOf(record("2026-08-05", 100, "first.webp"), record("2026-08-05", 200, "last.webp"))
    val cell = projectMonth(2026, 8, records).single { it.localDate == "2026-08-05" }
    assertEquals("last.webp", cell.imagePath)
    assertEquals(2, cell.drinkCount)
}

@Test fun missing_product_image_uses_logo() {
    assertEquals("logo.webp", calendarImage(null, "logo.webp"))
}
```

- [ ] **Step 2: Verify red**

Run `./gradlew testDebugUnitTest --tests '*JournalViewModelTest'`.

- [ ] **Step 3: Implement calendar/editor state**

```kotlin
data class CalendarDayUi(
    val localDate: String,
    val dayNumber: Int,
    val inDisplayedMonth: Boolean,
    val imagePath: String?,
    val brandLogoPath: String?,
    val drinkCount: Int
)

data class RecordEditorUi(
    val selectedItemId: String? = null,
    val ratingHalfStars: Int? = null,
    val actualPriceFen: Long? = null,
    val brewMethod: String? = null,
    val note: String = "",
    val needsImagePrompt: Boolean = false,
    val saving: Boolean = false
)
```

Project a fixed six-row month. The last drink timestamp supplies the representative image. Save a draft after every editor change and reject duplicate save taps.

- [ ] **Step 4: Implement Compose behavior**

`JournalScreen` renders weekday headings, numeric empty dates, product image/Logo recorded dates, `×N`, month cup/spend/rating values, a day-detail sheet, and “记录一杯”. `RecordDrinkScreen` renders chain/bean selection, half-stars, safe fen input, brew method, note, and the missing-image prompt with screenshot/select/skip.

- [ ] **Step 5: Verify and commit**

```bash
./gradlew testDebugUnitTest connectedDebugAndroidTest
git add app/src/main/java/com/niumi/coffeejournal/journal app/src/test app/src/androidTest
git commit -m "feat: add photo calendar and fast recording"
```

Expected: projection, fallback, multi-cup, draft, and save UI tests pass.

### Task 7: Build chain and personal-bean catalog management

**Files:**
- Create: `app/src/main/java/com/niumi/coffeejournal/catalog/CatalogViewModel.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/catalog/CatalogScreen.kt`
- Test: `app/src/test/java/com/niumi/coffeejournal/catalog/CatalogViewModelTest.kt`
- Test: `app/src/androidTest/java/com/niumi/coffeejournal/catalog/CatalogScreenTest.kt`

- [ ] **Step 1: Write failing seed and duplicate tests**

```kotlin
@Test fun seed_has_five_chains() {
    assertEquals(listOf("瑞幸", "Manner", "M Stand", "Peet's", "% Arabica"), seedBrands().map { it.name })
}

@Test fun normalized_names_collapse_case_and_spaces() {
    assertEquals(normalizeName("M Stand 澳白"), normalizeName(" m  stand  澳白 "))
}
```

- [ ] **Step 2: Verify red**

Run `./gradlew testDebugUnitTest --tests '*CatalogViewModelTest'`.

- [ ] **Step 3: Implement editors and seed policy**

```kotlin
data class BrandEditor(
    val type: BrandType,
    val name: String,
    val logoAssetId: String?,
    val maintenanceMode: MaintenanceMode,
    val publicSourceUrl: String?
)

data class ItemEditor(
    val brandId: String,
    val type: ItemType,
    val name: String,
    val imageAssetId: String?,
    val origin: String?,
    val processing: String?,
    val roastLevel: String?,
    val flavorNotes: String?,
    val brewMethod: String?,
    val status: ItemStatus,
    val caffeineMg: Double? = null,
    val officialDescription: String? = null,
    val purchaseDate: String? = null,
    val roastDate: String? = null,
    val sourceUrl: String? = null
)
```

Seed only the five brand records and legally usable Logos; do not seed guessed bean facts. Support custom brands/Logos, manual products, roaster groups, beans, and `ACTIVE/ARCHIVED/DISCONTINUED` state changes without hard deletion.

- [ ] **Step 4: Implement UI and verify**

Build “连锁品牌/我的豆子” tabs, brand counts/update times, brand/item editors, “正在喝/已喝完/归档” filters, and image replacement. Test custom chain creation, bean creation, editing without snapshot mutation, and archive behavior.

```bash
./gradlew testDebugUnitTest connectedDebugAndroidTest
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/niumi/coffeejournal/catalog app/src/test app/src/androidTest
git commit -m "feat: add chain and personal bean catalogs"
```

### Task 8: Add image storage, screenshot OCR, and crop review

**Files:**
- Create: `app/src/main/java/com/niumi/coffeejournal/core/image/ImageStore.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/importer/ScreenshotImporter.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/importer/ImportReviewScreen.kt`
- Test: `app/src/test/java/com/niumi/coffeejournal/importer/ScreenshotImporterTest.kt`
- Test: `app/src/androidTest/java/com/niumi/coffeejournal/importer/ImportReviewScreenTest.kt`

- [ ] **Step 1: Write failing price/privacy tests**

```kotlin
@Test fun actual_payment_label_wins() {
    val blocks = listOf(block("原价 ¥32"), block("实付 ¥9.90"))
    assertEquals(990L, normalizeScreenshot(blocks).actualPriceFen)
}

@Test fun confirmed_result_does_not_keep_source_uri() {
    val result = ConfirmedScreenshotImport("生椰拿铁", 990, "images/crop.webp")
    assertFalse(result.toString().contains("content://"))
}
```

- [ ] **Step 2: Verify red**

Run `./gradlew testDebugUnitTest --tests '*ScreenshotImporterTest'`.

- [ ] **Step 3: Implement boundaries**

```kotlin
interface ImageStore {
    suspend fun importCropped(source: Uri, crop: CropRect): ImageAsset
    suspend fun importWhole(source: Uri, kind: ImageKind): ImageAsset
    suspend fun deleteIfUnreferenced(assetId: String): Boolean
}

enum class ImageKind { PRODUCT, BRAND_LOGO, BEAN_PACKAGE, RECORD_SNAPSHOT }
data class ImageAsset(val id: String, val localPath: String, val sha256: String, val kind: ImageKind)
data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class TextBlock(val text: String, val bounds: CropRect)

interface ScreenshotTextRecognizer {
    suspend fun recognize(uri: Uri): List<TextBlock>
}

data class ScreenshotCandidate(
    val productName: String?,
    val actualPriceFen: Long?,
    val proposedCrop: CropRect?,
    val lowConfidenceFields: Set<String>
)
```

Use bundled `ChineseTextRecognizerOptions`. Copy only the confirmed crop to `filesDir/images/<sha256>.webp`; never copy the original screenshot. Before deleting an image, query all catalog and snapshot references.

- [ ] **Step 4: Implement crop/confirmation UI and verify**

Show the selected screenshot temporarily, draggable crop, detected products, editable name/price, low-confidence marks, and explicit confirm. Cancel creates no database row or image file.

```bash
./gradlew testDebugUnitTest connectedDebugAndroidTest
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/niumi/coffeejournal/core/image app/src/main/java/com/niumi/coffeejournal/importer app/src/test app/src/androidTest
git commit -m "feat: import product images from screenshots"
```

### Task 9: Add reviewed public-source updates

**Files:**
- Create: `app/src/main/java/com/niumi/coffeejournal/importer/CatalogSource.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/importer/CatalogDiff.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/importer/OfficialSources.kt`
- Test: `app/src/test/java/com/niumi/coffeejournal/importer/CatalogDiffTest.kt`
- Test fixture: `app/src/test/resources/sources/luckin-products.html`
- Test fixture: `app/src/test/resources/sources/mstand-product.html`

- [ ] **Step 1: Write the failing deterministic diff test**

```kotlin
@Test fun reports_add_modify_and_missing_without_delete() {
    val old = listOf(item("a", "拿铁", "old.webp"), item("b", "澳白", null))
    val fresh = listOf(candidate("a", "拿铁", "new.webp"), candidate("c", "桂花拿铁", "c.webp"))
    assertEquals(
        setOf(ChangeType.MODIFIED, ChangeType.ADDED, ChangeType.POSSIBLY_DISCONTINUED),
        diffCatalog(old, fresh).map { it.type }.toSet()
    )
}
```

- [ ] **Step 2: Verify red**

Run `./gradlew testDebugUnitTest --tests '*CatalogDiffTest'`.

- [ ] **Step 3: Implement source/diff contracts**

```kotlin
interface CatalogSource {
    val brandKey: String
    suspend fun fetch(): SourceResult
}

sealed interface SourceResult {
    data class Success(val fetchedAt: Long, val sourceUrl: String, val items: List<CatalogCandidate>) : SourceResult
    data class Failure(val kind: FailureKind, val message: String) : SourceResult
}

enum class FailureKind { OFFLINE, HTTP, PARSE_CHANGED, NO_PUBLIC_CATALOG }
enum class ChangeType { ADDED, MODIFIED, POSSIBLY_DISCONTINUED }
```

At implementation time, use adapters only for currently accessible public pages. A brand without a stable page returns `NO_PUBLIC_CATALOG` and opens screenshot/manual import. Parsers use committed HTML fixtures and required-field validation; never infer origin, processing, or bean identity.

- [ ] **Step 4: Add transactional review and failure tests**

Persist nothing before explicit confirmation. Apply selected additions, modifications, and discontinuation marks in `CoffeeDatabase.withTransaction`; store source URL/time. Verify offline, HTTP, and parser-change results preserve local rows and expose fallback actions.

```bash
./gradlew testDebugUnitTest connectedDebugAndroidTest
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/niumi/coffeejournal/importer app/src/test
git commit -m "feat: add reviewed public catalog updates"
```

### Task 10: Calculate and display monthly/yearly insights

**Files:**
- Create: `app/src/main/java/com/niumi/coffeejournal/insights/InsightsCalculator.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/insights/InsightsViewModel.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt`
- Test: `app/src/test/java/com/niumi/coffeejournal/insights/InsightsCalculatorTest.kt`
- Test: `app/src/androidTest/java/com/niumi/coffeejournal/insights/InsightsScreenTest.kt`

- [ ] **Step 1: Write failing null-rating and tie tests**

```kotlin
@Test fun unrated_counts_for_spend_not_rating() {
    val report = monthlyReport(listOf(record(price = 990, rating = 9), record(price = 2000, rating = null)))
    assertEquals(2990L, report.totalSpendFen)
    assertEquals(4.5, report.averageRating!!, 0.0)
}

@Test fun ties_are_preserved() {
    val report = monthlyReport(listOf(record(brand = "瑞幸"), record(brand = "Manner")))
    assertEquals(setOf("瑞幸", "Manner"), report.topBrands.map { it.name }.toSet())
}
```

- [ ] **Step 2: Verify red**

Run `./gradlew testDebugUnitTest --tests '*InsightsCalculatorTest'`.

- [ ] **Step 3: Implement pure report types/calculation**

```kotlin
data class PeriodInsights(
    val cupCount: Int,
    val totalSpendFen: Long,
    val averagePriceFen: Long?,
    val averageRating: Double?,
    val topBrands: List<RankedValue>,
    val topProducts: List<RankedValue>,
    val topBeans: List<RankedValue>,
    val topBrewMethods: List<RankedValue>,
    val bestRecordIds: List<String>,
    val worstRecordIds: List<String>,
    val points: List<TrendPoint>
)

data class RankedValue(val name: String, val count: Int)
data class TrendPoint(val label: String, val spendFen: Long, val averageRating: Double?)
```

Use non-null actual prices for spend averages, all records for cup counts, non-null ratings for rating averages, and preserve ties. Fewer than two populated periods yields facts only, not trend prose.

- [ ] **Step 4: Implement screens and verify**

Monthly: spend, prior-month delta, average price, ranked preferences, best/worst, weekly spend/rating, brand spend share. Yearly: total/monthly-average spend, twelve points, high/low months, top five ratings, rating trend, and year switcher.

```bash
./gradlew testDebugUnitTest connectedDebugAndroidTest
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/niumi/coffeejournal/insights app/src/test app/src/androidTest
git commit -m "feat: add monthly and yearly coffee insights"
```

### Task 11: Implement versioned backup and atomic restore

**Files:**
- Create: `app/src/main/java/com/niumi/coffeejournal/backup/BackupManager.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/settings/SettingsScreen.kt`
- Test: `app/src/test/java/com/niumi/coffeejournal/backup/BackupManagerTest.kt`

- [ ] **Step 1: Write failing round-trip and corruption tests**

```kotlin
@Test fun round_trip_preserves_records_and_images() = runTest {
    val archive = codec.encode(sampleDatabase(), mapOf("images/a.webp" to byteArrayOf(1, 2, 3)))
    val restored = codec.decode(archive)
    assertEquals(1, restored.records.size)
    assertArrayEquals(byteArrayOf(1, 2, 3), restored.images.getValue("images/a.webp"))
}

@Test fun bad_checksum_writes_nothing() = runTest {
    assertThrows<BackupValidationException> { codec.decode(corruptArchive()) }
    assertEquals(0, fakeDatabase.writeCount)
}
```

- [ ] **Step 2: Verify red**

Run `./gradlew testDebugUnitTest --tests '*BackupManagerTest'`.

- [ ] **Step 3: Implement archive contract**

```kotlin
@Serializable
data class BackupManifest(
    val formatVersion: Int = 1,
    val exportedAtEpochMillis: Long,
    val databaseSha256: String,
    val imageSha256: Map<String, String>
)

interface BackupManager {
    suspend fun export(target: Uri): BackupSummary
    suspend fun validate(source: Uri): ValidatedBackup
    suspend fun restore(backup: ValidatedBackup): RestoreSummary
}

interface BackupArchiveCodec {
    fun encode(database: ExportedDatabase, images: Map<String, ByteArray>): ByteArray
    fun decode(bytes: ByteArray): ValidatedBackup
}
```

Archive `manifest.json`, the SQLite database, and referenced images. Export with `ACTION_CREATE_DOCUMENT`; import with `ACTION_OPEN_DOCUMENT`. Validate format/checksums in a private temporary directory before an exclusive atomic restore. Always remove temporary files.

- [ ] **Step 4: Implement settings UI and verify**

Show export/import, last backup time, and a restore confirmation with record/brand/item/image counts. A failed validation leaves the active database unchanged.

```bash
./gradlew testDebugUnitTest connectedDebugAndroidTest
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/niumi/coffeejournal/backup app/src/main/java/com/niumi/coffeejournal/settings app/src/test app/src/androidTest
git commit -m "feat: add complete local backup and restore"
```

### Task 12: Harden failures and produce an installable APK

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/niumi/coffeejournal/importer/OfficialSources.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/JournalViewModel.kt`
- Create: `app/src/androidTest/java/com/niumi/coffeejournal/AcceptanceTest.kt`
- Create: `README.md`

- [ ] **Step 1: Write the failing end-to-end acceptance test**

Seed a chain product with Logo but no product image; record it at 4.5 stars and ¥9.90; verify Logo fallback, the missing-image prompt, and `×2` after a second record; update catalog name/image; verify the old snapshot; open monthly insights and assert ¥19.80.

```kotlin
object TestTags {
    const val Calendar = "journal-calendar"
    const val RecordButton = "record-drink"
    const val MissingImagePrompt = "missing-image-prompt"
    const val ConfirmSave = "confirm-save"
    const val MonthlySpend = "monthly-spend"
}
```

- [ ] **Step 2: Verify red**

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.niumi.coffeejournal.AcceptanceTest
```

Expected: a missing tag or incomplete cross-feature connection fails.

- [ ] **Step 3: Implement only the acceptance gaps**

Manifest permission is only `INTERNET`; system photo/document pickers require no broad storage permission. Add retry and screenshot/manual fallback for offline, HTTP, parser, and image failures. Optional image, rating, price, brew method, and note never block save; missing item selection does.

- [ ] **Step 4: Run the full matrix and install**

```bash
./gradlew clean testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: all Gradle tasks succeed, APK exists, and installation succeeds. On the phone manually verify offline record, screenshot OCR/crop, custom brand/Logo, personal bean, insights, and backup restore.

- [ ] **Step 5: Document exact usage and safety**

`README.md` must state prerequisites, build command, APK path, `adb install`, backup workflow, offline behavior, public-source limitations, screenshot privacy, and that uninstalling before backup removes app-private data.

- [ ] **Step 6: Commit**

```bash
git add app/src README.md
git commit -m "test: verify coffee journal release candidate"
```

## Final completion checklist

- [ ] `git status --short` is empty.
- [ ] `./gradlew clean testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug` exits 0.
- [ ] The APK installs and launches on the user's Android phone.
- [ ] Backup restores records, catalogs, Logos, product images, and bean photos.
- [ ] No private WeChat Mini Program interface is called.
- [ ] No unconfirmed website/OCR candidate enters the catalog.
- [ ] Catalog updates never mutate historical snapshots.
- [ ] Five initial chain brands exist and custom brands can be added.
- [ ] Calendar uses product image, then brand Logo, then generic fallback.
- [ ] Monthly/yearly fixture totals match hand calculations.
