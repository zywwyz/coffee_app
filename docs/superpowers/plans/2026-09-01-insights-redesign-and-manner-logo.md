# Insights Redesign and MANNER Logo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the monthly/yearly summary into a cream-and-forest habit story, persist historical coffee type in Room v4 with v1–v3 backup compatibility, and replace the bundled MANNER logo with the user-approved undistorted artwork.

**Architecture:** Add a snapshot-only `CoffeeType` to every drink record so insights remain historically stable after catalog edits, then calculate all summary cards as pure projections over records. Keep Compose rendering split into focused cards and reuse the calendar's image fallback and visual tokens. Upgrade restore validation/copying before any UI work so old backups and malformed archives have deterministic behavior.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Room/SQLite, Kotlin Coroutines/Flow, Robolectric Compose tests, Android resource PNGs, Pillow normalization script, Gradle 8.13.

---

## File map and ownership

- `core/model/Models.kt`: owns the new four-value historical `CoffeeType` and snapshot field.
- `core/database/Entities.kt`, `CoffeeDatabase.kt`, `app/schemas/.../4.json`: own Room v4 storage and 3→4 migration.
- `journal/JournalRepository.kt`: maps catalog classification into immutable drink snapshots.
- `backup/CoffeeDatabaseSchema.kt`, `BackupManager.kt`: own v4 schema contracts, old-backup derivation, strict validation, and copying.
- `insights/InsightsCalculator.kt`: owns pure period statistics and presentation-neutral result models.
- `insights/InsightsViewModel.kt`: owns selected period, current/previous streams, and navigation boundaries.
- `insights/InsightsScreen.kt`: owns summary composition and focused card components.
- `navigation/AppNavigation.kt`: supplies the image resolver and record-detail callback to insights.
- `TestTags.kt`: exposes stable semantics for chart/card acceptance tests.
- `scripts/normalize_brand_logos.py`: mechanically derives the approved MANNER PNG.
- `docs/brand-logo-sources.md`: records source provenance and hashes.
- Focused tests remain beside their current modules; add one real Compose preview renderer for summary screenshots.

## Task 1: Persist historical coffee type in Room v4

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/core/model/Models.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/core/database/Entities.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/core/database/CoffeeDatabase.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/JournalRepository.kt`
- Create: `app/schemas/com.niumi.coffeejournal.core.database.CoffeeDatabase/4.json`
- Test: `app/src/test/java/com/niumi/coffeejournal/core/database/CoffeeDatabaseMigrationTest.kt`
- Test: `app/src/test/java/com/niumi/coffeejournal/journal/JournalRepositoryTest.kt`

- [ ] **Step 1: Add failing domain and migration tests**

Add assertions covering all four values and the exact 3→4 backfill order:

```kotlin
@Test fun migration3To4BackfillsSnapshotCoffeeType() {
    // Insert: personal bean, catalog BLACK/FRUIT/MILK, deleted fruit-name item,
    // and unrecognized legacy chain item into a version-3 database.
    migrateAndOpen(3, 4).use { db ->
        assertThat(type(db, "personal")).isEqualTo("HAND_BREW")
        assertThat(type(db, "catalog-fruit")).isEqualTo("FRUIT")
        assertThat(type(db, "deleted-fruit-name")).isEqualTo("FRUIT")
        assertThat(type(db, "unknown-chain")).isEqualTo("BLACK")
    }
}

@Test fun editingWithoutChangingItemPreservesCoffeeType() = runTest {
    val original = saveChainRecord(kind = ChainProductKind.FRUIT)
    catalogRepository.updateProductKind(original.sourceItemId!!, ChainProductKind.MILK)
    repository.save(editDraft(original).copy(note = "仍是原快照"))
    assertThat(repository.get(original.id)!!.snapshot.coffeeType).isEqualTo(CoffeeType.FRUIT)
}
```

- [ ] **Step 2: Run the focused tests and confirm RED**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle :app:testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.core.database.CoffeeDatabaseMigrationTest' \
  --tests 'com.niumi.coffeejournal.journal.JournalRepositoryTest' \
  --no-daemon --rerun-tasks
```

Expected: compilation fails because `CoffeeType` and `snapshotCoffeeType` do not exist, or migration assertions fail on schema version 3.

- [ ] **Step 3: Add the domain field and Room column**

Use one enum that can represent personal beans without overloading catalog migration state:

```kotlin
enum class CoffeeType { BLACK, FRUIT, MILK, HAND_BREW }

data class DrinkSnapshot(
    // existing fields unchanged
    val coffeeType: CoffeeType,
)

@Entity(tableName = "drink_records")
data class DrinkRecordEntity(
    // existing columns unchanged
    val snapshotCoffeeType: CoffeeType,
)
```

Increment `CoffeeDatabase` to version 4 and register `MIGRATION_3_4`. The migration must add a non-null text column, then update rows in this exact order:

```sql
ALTER TABLE drink_records
ADD COLUMN snapshotCoffeeType TEXT NOT NULL DEFAULT 'BLACK';

UPDATE drink_records
SET snapshotCoffeeType = 'HAND_BREW'
WHERE itemType = 'PERSONAL_BEAN';

UPDATE drink_records
SET snapshotCoffeeType = (
  SELECT chainProductKind FROM catalog_items
  WHERE catalog_items.id = drink_records.sourceItemId
    AND chainProductKind IN ('BLACK', 'FRUIT', 'MILK')
)
WHERE itemType = 'CHAIN_PRODUCT'
  AND EXISTS (
    SELECT 1 FROM catalog_items
    WHERE catalog_items.id = drink_records.sourceItemId
      AND chainProductKind IN ('BLACK', 'FRUIT', 'MILK')
  );
```

Apply the existing legacy name classifier only to remaining chain rows still equal to the migration default, with fruit keywords before milk keywords before black keywords. Leave truly unknown legacy chain rows as `BLACK`.

- [ ] **Step 4: Map snapshots at repository boundaries**

Add one exhaustive mapper and use it only when a new snapshot is created:

```kotlin
private fun CatalogItem.snapshotCoffeeType(): CoffeeType = when (itemType) {
    ItemType.PERSONAL_BEAN -> CoffeeType.HAND_BREW
    ItemType.CHAIN_PRODUCT -> when (requireNotNull(chainProductKind)) {
        ChainProductKind.BLACK -> CoffeeType.BLACK
        ChainProductKind.FRUIT -> CoffeeType.FRUIT
        ChainProductKind.MILK -> CoffeeType.MILK
        ChainProductKind.PENDING -> error("PENDING cannot be saved to a drink snapshot")
    }
}
```

Update entity/domain conversion in both directions. Preserve the entire previous snapshot, including `coffeeType`, when editing without changing `sourceItemId`; create a new snapshot only after an explicit product change.

- [ ] **Step 5: Generate and inspect Room schema 4**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle :app:kspDebugKotlin --no-daemon --rerun-tasks
```

Expected: `app/schemas/com.niumi.coffeejournal.core.database.CoffeeDatabase/4.json` exists, declares version 4, and `drink_records.snapshotCoffeeType` is non-null `TEXT`.

- [ ] **Step 6: Run focused tests and commit**

Run the command from Step 2. Expected: PASS.

```bash
git add app/src/main/java/com/niumi/coffeejournal/core/model/Models.kt \
  app/src/main/java/com/niumi/coffeejournal/core/database/Entities.kt \
  app/src/main/java/com/niumi/coffeejournal/core/database/CoffeeDatabase.kt \
  app/src/main/java/com/niumi/coffeejournal/journal/JournalRepository.kt \
  app/src/test/java/com/niumi/coffeejournal/core/database/CoffeeDatabaseMigrationTest.kt \
  app/src/test/java/com/niumi/coffeejournal/journal/JournalRepositoryTest.kt \
  app/schemas/com.niumi.coffeejournal.core.database.CoffeeDatabase/4.json
git commit -m "feat: persist historical coffee type"
```

## Task 2: Make backup validation and restore compatible with v4

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/backup/CoffeeDatabaseSchema.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/backup/BackupManager.kt`
- Test: `app/src/test/java/com/niumi/coffeejournal/backup/BackupManagerTest.kt`

- [ ] **Step 1: Add failing restore and rejection tests**

Create real archive fixtures for each supported source schema and assert restored types:

```kotlin
@Test fun restoresV1V2V3BackupsIntoV4WithCoffeeTypeBackfill() = runTest {
    listOf(1, 2, 3).forEach { sourceVersion ->
        val archive = archiveAtVersion(sourceVersion) {
            insertPersonalBeanRecord("personal")
            insertChainRecord("fruit", sourceItemId = "deleted", itemName = "西柚冰萃")
        }
        manager.validate(archive).also { manager.restore(it) }
        assertThat(record("personal").snapshot.coffeeType).isEqualTo(CoffeeType.HAND_BREW)
        assertThat(record("fruit").snapshot.coffeeType).isEqualTo(CoffeeType.FRUIT)
        resetActiveDatabase()
    }
}

@Test fun rejectsInvalidV4CoffeeTypeBeforeAnyWrite() = runTest {
    forEachInvalidV4Domain(
        "snapshotCoffeeType = 'OTHER'",
        "itemType = 'PERSONAL_BEAN', snapshotCoffeeType = 'MILK'",
        "itemType = 'CHAIN_PRODUCT', snapshotCoffeeType = 'HAND_BREW'",
    ) { archive -> assertValidationFailureAndZeroWrites(archive) }
}
```

- [ ] **Step 2: Run BackupManager tests and confirm RED**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle :app:testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.backup.BackupManagerTest' \
  --no-daemon --rerun-tasks
```

Expected: v4 is unsupported, old-row copying misses the new non-null column, or invalid v4 domains are accepted.

- [ ] **Step 3: Register the exact v4 schema contract**

Set `CoffeeDatabaseSchema.CURRENT = 4`, add schema 4's Room identity hash from the generated JSON, and add:

```kotlin
private val ADDED_COLUMNS = mapOf(
    // existing v2/v3 entries
    4 to mapOf("drink_records" to setOf("snapshotCoffeeType")),
)
```

Keep actual untrusted schema inspection strict: only expected schema contracts may omit future columns; never filter columns from the source database being validated.

- [ ] **Step 4: Add explicit version-aware drink record copying**

Replace generic `copyCursor("drink_records")` with `copyDrinkRecords`. It must insert the complete v4 destination column list and derive the new field for source versions 1–3:

```kotlin
private fun deriveCoffeeType(
    itemType: String,
    sourceItemId: String?,
    snapshotItemName: String,
    catalogKinds: Map<String, String>,
): String = when {
    itemType == "PERSONAL_BEAN" -> "HAND_BREW"
    catalogKinds[sourceItemId] in setOf("BLACK", "FRUIT", "MILK") -> catalogKinds.getValue(sourceItemId)
    else -> classifyLegacyCoffeeName(snapshotItemName) ?: "BLACK"
}
```

For source version 4, copy `snapshotCoffeeType` verbatim only after schema and domain validation. Retain row cancellation checks and the existing transaction boundary.

- [ ] **Step 5: Validate source and destination domains**

Extend validation before restore and the in-transaction post-copy validation:

```sql
SELECT COUNT(*) FROM drink_records
WHERE snapshotCoffeeType NOT IN ('BLACK','FRUIT','MILK','HAND_BREW')
   OR (itemType = 'PERSONAL_BEAN' AND snapshotCoffeeType != 'HAND_BREW')
   OR (itemType = 'CHAIN_PRODUCT' AND snapshotCoffeeType = 'HAND_BREW');
```

Any non-zero count must throw `BackupValidationException` before replacing active data or images.

- [ ] **Step 6: Run backup tests and request critical review**

Run the command from Step 2. Expected: PASS for v1–v4 positive restore and all malformed v4 zero-write tests.

Ask `critical_reviewer` to inspect only: version-smuggling, schema whitelist direction, source-version derivation, transaction/rollback behavior, and domain consistency. Resolve all Critical/Important findings before committing.

- [ ] **Step 7: Commit backup v4 support**

```bash
git add app/src/main/java/com/niumi/coffeejournal/backup/CoffeeDatabaseSchema.kt \
  app/src/main/java/com/niumi/coffeejournal/backup/BackupManager.kt \
  app/src/test/java/com/niumi/coffeejournal/backup/BackupManagerTest.kt
git commit -m "feat: restore historical coffee types from backups"
```

## Task 3: Replace summary calculations with habit-story projections

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/insights/InsightsCalculator.kt`
- Test: `app/src/test/java/com/niumi/coffeejournal/insights/InsightsCalculatorTest.kt`

- [ ] **Step 1: Replace old weekly/spend expectations with failing behavior tests**

Add focused tests for the selected rules:

```kotlin
@Test fun monthlyTrendIsDailyCumulativeAndComparesSameDayRange() {
    val result = monthlyInsights(
        selected = ym(2026, 8), today = date(2026, 8, 20),
        currentDays = listOf(1, 1, 3, 20), previousDays = listOf(1, 2, 20, 25),
    )
    assertThat(result.trend.current.map { it.value }).containsExactly(
        2, 2, 3, /* carry forward through day 19 */, 4,
    )
    assertThat(result.trend.previous).hasSize(20)
}

@Test fun topRanksAreLimitedToThreeAndTiesPreferRecent() { /* exact ordered ids */ }
@Test fun personalBeansAlwaysCountAsHandBrew() { /* four type shares */ }
@Test fun brandShareKeepsTopFourAndMergesTheRestAsOther() { /* cups + percent */ }
@Test fun equalRatingsShowOnlyBestAndTieCountUsesDistinctProducts() { /* result */ }
```

Also cover: leap/non-leap month lengths, historical full month, current year cutoff, 12 monthly current/prior values, unique drink days, longest local-date streak, missing-price denominator, and stable final tie-break key.

- [ ] **Step 2: Run calculator tests and confirm RED**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle :app:testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.insights.InsightsCalculatorTest' \
  --no-daemon --rerun-tasks
```

Expected: old weekly spend/rating model cannot satisfy the new types and assertions.

- [ ] **Step 3: Define presentation-neutral insight models**

Replace the old mixed-axis types with explicit values:

```kotlin
data class ComparisonPoint(val index: Int, val current: Int?, val previous: Int?)
data class ShareValue(val key: String, val label: String, val cups: Int, val fraction: Float)
data class RankedValue(val key: String, val name: String, val cups: Int, val latestAt: Long)
data class HighlightRecord(
    val recordId: String,
    val brandName: String,
    val itemName: String,
    val ratingHalfStars: Int,
    val imageAssetId: String?,
    val brandLogoAssetId: String?,
    val tiedProductCount: Int,
)
data class HabitSummary(
    val cups: Int,
    val drinkingDays: Int,
    val longestStreak: Int,
    val averageRating: Double?,
    val totalSpendFen: Long?,
    val averagePriceFen: Long?,
    val cupDelta: Int,
)
```

`MonthlyInsights` and `YearlyInsights` must both contain `habit`, `trend`, `coffeeTypeShares`, `brandShares`, `topBrands`, `topProducts`, `best`, and `worst`; yearly trend uses monthly counts, monthly trend uses daily cumulative counts.

- [ ] **Step 4: Implement deterministic aggregations**

Use `DrinkRecord.localDate` for day grouping/streaks and `occurredAt` for recency. For product keys use `sourceItemId` when non-null, otherwise `brandName + '\u0000' + itemName`. Sort ranks by cups descending, latest occurrence descending, then stable key ascending. Limit rankings to three. Brand shares keep four brands then append one `OTHER` slice.

For best/worst, first reduce repeated drinks to distinct product keys, choose the most recent representative among products tied on rating, and expose `tiedProductCount = distinctTiedProducts - 1`. When min equals max, return `worst = null`.

- [ ] **Step 5: Run tests and commit**

Run the command from Step 2. Expected: PASS.

```bash
git add app/src/main/java/com/niumi/coffeejournal/insights/InsightsCalculator.kt \
  app/src/test/java/com/niumi/coffeejournal/insights/InsightsCalculatorTest.kt
git commit -m "feat: calculate coffee habit insights"
```

## Task 4: Load current and comparison periods in InsightsViewModel

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/insights/InsightsViewModel.kt`
- Test: `app/src/test/java/com/niumi/coffeejournal/insights/InsightsViewModelTest.kt`

- [ ] **Step 1: Add failing stream and cutoff tests**

```kotlin
@Test fun yearlyModeObservesSelectedAndPreviousYear() = runTest {
    val vm = viewModel(clock = fixedClock("2026-08-20"))
    vm.selectYearly()
    assertThat(repository.observedMonths).containsAtLeast(
        YearMonth.of(2026, 1), YearMonth.of(2025, 1),
        YearMonth.of(2026, 12), YearMonth.of(2025, 12),
    )
}

@Test fun currentMonthTrendStopsAtTodayAndHistoricalMonthIsComplete() = runTest { /* exact sizes */ }
```

- [ ] **Step 2: Run ViewModel tests and confirm RED**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle :app:testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.insights.InsightsViewModelTest' \
  --no-daemon --rerun-tasks
```

Expected: yearly mode observes only the selected year and cannot produce prior-year comparison.

- [ ] **Step 3: Inject current date and combine comparison streams**

Reuse the app clock abstraction rather than reading `GregorianCalendar` inside calculation. Monthly state combines selected and previous month records. Yearly state combines 24 streams or one repository range abstraction that still emits Room-backed updates for all selected/prior months. Apply cutoff only when selected period contains the injected current date.

Keep navigation bounds at year 1..9999 and preserve the selected month/year when switching modes.

- [ ] **Step 4: Run tests and commit**

Run the command from Step 2. Expected: PASS.

```bash
git add app/src/main/java/com/niumi/coffeejournal/insights/InsightsViewModel.kt \
  app/src/test/java/com/niumi/coffeejournal/insights/InsightsViewModelTest.kt
git commit -m "feat: compare summary periods"
```

## Task 5: Build the cream-and-forest summary UI

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/TestTags.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/navigation/AppNavigation.kt`
- Test: `app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt`
- Test: `app/src/test/java/com/niumi/coffeejournal/navigation/AppNavigationTest.kt`

- [ ] **Step 1: Add failing UI semantics tests**

Add stable tags:

```kotlin
const val InsightsHabitHero = "insights-habit-hero"
const val InsightsTrendChart = "insights-trend-chart"
const val InsightsCoffeeTypeDonut = "insights-coffee-type-donut"
const val InsightsBrandDonut = "insights-brand-donut"
const val InsightsTopBrands = "insights-top-brands"
const val InsightsTopProducts = "insights-top-products"
const val InsightsBestCard = "insights-best-card"
const val InsightsWorstCard = "insights-worst-card"
```

Test exact visible content and interaction:

```kotlin
compose.onNodeWithTag(TestTags.InsightsHabitHero).assertTextContains("12 杯")
compose.onNodeWithTag(TestTags.InsightsTrendChart)
    .assertContentDescriptionContains("本月累计 12 杯，上月同期 9 杯")
compose.onNodeWithTag(TestTags.InsightsTopBrands).onChildren().assertCountEquals(3)
compose.onNodeWithTag(TestTags.InsightsBestCard).performClick()
assertThat(openedRecordId).isEqualTo("best-record")
```

Add separate fixtures for empty, no-rating, no-price, one-share-at-100%, long product names, and broken product-image fallback.

- [ ] **Step 2: Run UI tests and confirm RED**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle :app:testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.insights.InsightsScreenRobolectricTest' \
  --tests 'com.niumi.coffeejournal.navigation.AppNavigationTest' \
  --no-daemon --rerun-tasks
```

Expected: new tags/cards and image resolver wiring are absent.

- [ ] **Step 3: Split and compose focused cards**

Within `InsightsScreen.kt`, keep the public feature surface small and extract private composables with one responsibility:

```kotlin
@Composable private fun HabitHeroCard(habit: HabitSummary)
@Composable private fun ComparisonTrendCard(points: List<ComparisonPoint>, mode: InsightsMode)
@Composable private fun ShareDonutCard(title: String, values: List<ShareValue>, tag: String)
@Composable private fun TopThreeCard(title: String, values: List<RankedValue>, tag: String)
@Composable private fun HighlightCard(highlight: HighlightRecord, imageResolver: ImagePathResolver, onOpen: (String) -> Unit)
```

Use `CoffeeVisuals.cream` page background, `white` cards, `forest` primary line/text, `peach` emphasis, `mint` secondary segments, `warmLine` borders, and existing 12/18/24dp radii. Do not add a chart dependency.

Draw line charts with Compose `Canvas`: current period solid forest, previous period dashed warm gray, text legend and accessible summary. Draw two donuts with the same palette; legends must contain label, cups, and percentage so color is never the only encoding.

- [ ] **Step 4: Wire historical image fallback and record opening**

Change the feature boundary to:

```kotlin
@Composable
fun InsightsFeature(
    repository: JournalRepository,
    imageResolver: ImagePathResolver,
    onOpenRecord: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
)
```

Render highlight images with the calendar's existing product asset → historical brand asset → built-in bundled brand resource → placeholder behavior. Use `ContentScale.Fit` so product photos and logos are never cropped. Pass the app image store/resolver and existing record-detail route from `AppNavigation`.

- [ ] **Step 5: Implement exact layout and empty states**

Order content exactly as: title, month/year segmented control, period navigation, hero, trend, two side-by-side donuts, Top 3 brand/product columns, best/worst cards. On narrow widths keep each pair in equal weighted columns and apply `maxLines = 1, overflow = TextOverflow.Ellipsis` to names.

Render no-data text instead of fake chart geometry; render `—` for absent prices; hide highlights and show “记录评分后可查看本期高光” when unrated; show “本期评分一致” instead of a duplicate worst card.

- [ ] **Step 6: Run UI tests and commit**

Run the command from Step 2. Expected: PASS.

```bash
git add app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt \
  app/src/main/java/com/niumi/coffeejournal/TestTags.kt \
  app/src/main/java/com/niumi/coffeejournal/navigation/AppNavigation.kt \
  app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt \
  app/src/test/java/com/niumi/coffeejournal/navigation/AppNavigationTest.kt
git commit -m "feat: redesign coffee summary"
```

## Task 6: Replace MANNER with the approved undistorted logo

**Files:**
- Create: `assets/brand-logos/reference/manner-user-approved.jpeg`
- Modify: `scripts/normalize_brand_logos.py`
- Modify: `scripts/test_normalize_brand_logos.py`
- Modify: `app/src/main/res/drawable-nodpi/brand_logo_manner.png`
- Modify: `app/src/test/java/com/niumi/coffeejournal/catalog/BundledBrandLogoTest.kt`
- Modify: `docs/brand-logo-sources.md`

- [ ] **Step 1: Copy the approved source and add failing normalization tests**

Copy `/Users/niumi/Pictures/f1fd74bd84c5a4e66963a65c7e01ea1f-1.jpeg` byte-for-byte to the repository audit path. Add tests that assert:

```python
def test_manner_output_preserves_foreground_aspect_and_safe_border():
    output = normalize_manner(APPROVED_MANNER)
    assert output.size == (512, 512)
    assert all_alpha_zero_on_outer_border(output, border=2)
    assert aspect_ratio(output_foreground_bbox(output)) == pytest.approx(
        aspect_ratio(source_foreground_bbox(APPROVED_MANNER)), rel=0.01
    )
```

- [ ] **Step 2: Run script tests and confirm RED**

Run:

```bash
python3 -m unittest scripts/test_normalize_brand_logos.py
```

Expected: the current MANNER input/output fingerprint or aspect ratio does not match the approved JPEG.

- [ ] **Step 3: Implement mechanical background removal and normalization**

In `normalize_brand_logos.py`, select the audited JPEG specifically for `chain-manner`. Flood-fill only edge-connected near-white pixels to transparent, retain every disconnected dark pixel, compute the foreground bounding box, scale uniformly to fit the existing safe-media box, and center on transparent 512×512. Do not resize width and height independently and do not crop foreground pixels.

- [ ] **Step 4: Regenerate, inspect, and update audit hashes**

Run the normalization script twice and assert the second run produces no diff. Update the decoded-pixel SHA-256 mapping in `BundledBrandLogoTest` and both source/output hashes plus transformation description in `docs/brand-logo-sources.md`.

Generate the existing brand contact sheet and visually check that the entire top triangle, MANNER word box, and lower triangle are present without compression.

- [ ] **Step 5: Run logo tests and commit**

Run:

```bash
python3 -m unittest scripts/test_normalize_brand_logos.py
./.local-tools/gradle-8.13/bin/gradle :app:testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.catalog.BundledBrandLogoTest' \
  --no-daemon --rerun-tasks
```

Expected: PASS.

```bash
git add assets/brand-logos/reference/manner-user-approved.jpeg \
  scripts/normalize_brand_logos.py scripts/test_normalize_brand_logos.py \
  app/src/main/res/drawable-nodpi/brand_logo_manner.png \
  app/src/test/java/com/niumi/coffeejournal/catalog/BundledBrandLogoTest.kt \
  docs/brand-logo-sources.md
git commit -m "fix: replace distorted MANNER logo"
```

## Task 7: Render real monthly and yearly summary previews

**Files:**
- Create: `app/src/test/java/com/niumi/coffeejournal/InsightsPreviewRenderTest.kt`
- Create: `app/src/test/resources/insights-preview/product-best.png`
- Create: `app/src/test/resources/insights-preview/product-worst.png`
- Modify: `app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt`

- [ ] **Step 1: Add a failing real Compose preview test**

Seed real in-memory Room records for current/prior month and current/prior year, including all four coffee types, five brands, long product names, prices, ratings, ties, a personal bean, user-approved MANNER, and two real decodable product images. Launch real `MainActivity`, navigate to 总结, and assert all important surfaces are visible before capture.

```kotlin
assertVisible(TestTags.InsightsHabitHero)
assertVisible(TestTags.InsightsTrendChart)
assertVisible(TestTags.InsightsCoffeeTypeDonut)
assertVisible(TestTags.InsightsBrandDonut)
captureToFile("app/build/reports/previews/insights-monthly-cream-forest.png")
selectYearly()
captureToFile("app/build/reports/previews/insights-yearly-cream-forest.png")
```

- [ ] **Step 2: Run preview test and confirm RED**

Run:

```bash
./.local-tools/gradle-8.13/bin/gradle :app:testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.InsightsPreviewRenderTest' \
  -PinsightsPreview --no-daemon --rerun-tasks
```

Expected: fixture or capture assertions fail until all real image and navigation wiring is complete.

- [ ] **Step 3: Complete fixture synchronization and acceptance assertions**

Wait on semantic state rather than sleeps. Assert the preview shows real product pixels, the bundled MANNER resource, current/previous chart legend, both donuts, exactly three brand/product ranks, tie text, and the custom bottom navigation. Extend `ReleaseAcceptanceRobolectricTest` with the same real MainActivity path but without writing files.

- [ ] **Step 4: Run, visually inspect, and commit previews tests**

Run the command from Step 2 and:

```bash
./.local-tools/gradle-8.13/bin/gradle :app:testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.ReleaseAcceptanceRobolectricTest' \
  --no-daemon --rerun-tasks
```

Expected: PASS; both PNGs are non-empty, full-page, and show uncropped MANNER/product art. Inspect both images before accepting.

```bash
git add app/src/test/java/com/niumi/coffeejournal/InsightsPreviewRenderTest.kt \
  app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt \
  app/src/test/resources/insights-preview/product-best.png \
  app/src/test/resources/insights-preview/product-worst.png
git commit -m "test: render redesigned coffee summary"
```

## Task 8: Final verification, review, project state, and installable APK

**Files:**
- Modify: `docs/PROJECT_STATE.md`
- Modify: `README.md` only if backup schema/current summary behavior described there is now stale

- [ ] **Step 1: Run the focused regression set**

```bash
./.local-tools/gradle-8.13/bin/gradle :app:testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.core.database.CoffeeDatabaseMigrationTest' \
  --tests 'com.niumi.coffeejournal.backup.BackupManagerTest' \
  --tests 'com.niumi.coffeejournal.journal.JournalRepositoryTest' \
  --tests 'com.niumi.coffeejournal.insights.*' \
  --tests 'com.niumi.coffeejournal.catalog.BundledBrandLogoTest' \
  --tests 'com.niumi.coffeejournal.ReleaseAcceptanceRobolectricTest' \
  --no-daemon --rerun-tasks
```

Expected: PASS with zero failures/errors/skips.

- [ ] **Step 2: Request final reviews**

Use `critical_reviewer` for Room v4, migration, backup validation/copy, rollback, and historical snapshot consistency. Use `reviewer` for calculator determinism, Compose state/lifecycle, accessibility, and preview fidelity. Resolve every Critical/Important finding and rerun the smallest affected tests.

- [ ] **Step 3: Run the release matrix**

```bash
./.local-tools/gradle-8.13/bin/gradle clean testDebugUnitTest lintDebug \
  assembleDebug assembleDebugAndroidTest assembleRelease \
  --no-daemon
```

Expected: BUILD SUCCESSFUL; lint has zero errors; debug, androidTest, and unsigned release APKs exist.

- [ ] **Step 4: Check schema, package boundaries, and artifacts**

Verify:

```bash
git diff --check
rg '"version": 4' app/schemas/com.niumi.coffeejournal.core.database.CoffeeDatabase/4.json
unzip -l app/build/outputs/apk/debug/app-debug.apk | rg 'insights-preview|InsightsPreviewRenderTest|product-best'
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
```

Expected: diff check clean; schema version 4 found; preview fixtures/tests absent from production APK; APK SHA printed.

- [ ] **Step 5: Update durable project state**

Record only durable facts in `docs/PROJECT_STATE.md`: Room/backup version 4, historical `CoffeeType`, summary layout/statistical rules, MANNER asset provenance, exact verification result, preview paths, APK path/hash, and any remaining true-device-only checks. Do not record temporary failures or full logs.

- [ ] **Step 6: Commit documentation and report artifacts**

```bash
git add docs/PROJECT_STATE.md README.md
git commit -m "docs: record insights redesign release"
git status --short
```

Expected: only the user's pre-existing `.codex` deletions remain; no feature files are uncommitted. Report the two preview PNG paths and `app/build/outputs/apk/debug/app-debug.apk`. Do not claim on-device installation unless a connected device was actually used.

## Plan self-review result

- Spec coverage: every confirmed requirement maps to Tasks 1–8, including Room v4, v1–v3 backup restore, daily/monthly comparison charts, both donut charts, Top 3 limits, image-backed highlights, equal-rating behavior, cream/forest styling, MANNER normalization, real Compose previews, and release artifacts.
- Placeholder scan: no deferred markers or unspecified generic implementation steps remain.
- Type consistency: `CoffeeType`, `snapshotCoffeeType`, `ComparisonPoint`, `ShareValue`, `RankedValue`, `HighlightRecord`, and `HabitSummary` are introduced once and reused consistently by later tasks.
- Scope control: no network dependency, third-party chart library, full ranking page, or unrelated root-screen redesign is included.
