# Calendar Visual Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the real Android UI match the confirmed coffee-calendar previews: complete uncropped brand/product images, no date number on recorded days, custom preview-style calendar controls and bottom navigation, consistent 12-brand presentation, sharper thumbnails, and date-only drink entry.

**Architecture:** Keep Room v3 and backup v1/v2/v3 unchanged. Carry the representative record's stable `brandName` through the existing month projection, resolve known seed brands to checked-in resources at render time, and retain local image paths for custom brands and product photos. Centralize the complete-image rendering contract as `ContentScale.Fit`; raise bounded thumbnail decoding to 512 px. Treat the persisted epoch as an internal compatibility field: new drafts and changed dates use local noon, while untouched historical records keep their original epoch.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Navigation3, Room v3, Robolectric/Compose UI tests, Android resource drawables, Gradle 8.13.

---

## Scope and guardrails

- Do not change Room entities, database version, exported schemas, backup archive format, or restore rules.
- Do not restore networking, website update, OCR, screenshot import, crop UI, or `INTERNET` permission.
- Preserve the original bytes of user-uploaded product and custom-brand images.
- Do not AI-redraw any trademark. Continue using the 12 audited real marks listed in `docs/brand-logo-sources.md`.
- Do not stage or commit the user's existing `.codex/**` deletions.
- Preserve existing `TestTags.Bottom*`, `RootScreenTitle`, `RootScreenSettings`, calendar mode, and record-flow semantics unless this plan explicitly adds a tag.

## Shared test environment

Before running Gradle in a fresh shell:

```bash
export COFFEE_JAVA_HOME="$PWD/.local-tools/jdk/Contents/Home"
export COFFEE_ANDROID_HOME="$PWD/.local-tools/android-sdk"
export JAVA_HOME="$COFFEE_JAVA_HOME"
export ANDROID_HOME="$COFFEE_ANDROID_HOME"
export GRADLE_USER_HOME="$PWD/.gradle"
export PATH="$COFFEE_JAVA_HOME/bin:$COFFEE_ANDROID_HOME/platform-tools:$PATH"
```

Run Gradle with `--no-daemon`. If sandboxed execution cannot create the Gradle lock socket, rerun the same bounded command with the user's approved elevated execution; do not change caches or dependencies to work around it.

### Task 1: Add stable built-in brand-logo resolution to calendar projection

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/catalog/BundledBrandCatalog.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/JournalProjection.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/catalog/BundledBrandLogoTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/journal/JournalViewModelTest.kt`

- [ ] **Step 1: Write failing alias-resolution and projection tests**

Add cases proving canonical names and aliases resolve to the same resource, while unknown custom brands do not:

```kotlin
@Test fun `bundled logo resolves canonical names and aliases`() {
    assertEquals(R.drawable.brand_logo_luckin, bundledBrandLogoRes(" 瑞幸 "))
    assertEquals(R.drawable.brand_logo_cotti, bundledBrandLogoRes("库迪咖啡"))
    assertEquals(R.drawable.brand_logo_manner, bundledBrandLogoRes("manner coffee"))
    assertEquals(R.drawable.brand_logo_mstand, bundledBrandLogoRes("Mstand"))
    assertNull(bundledBrandLogoRes("我的社区咖啡"))
}
```

Extend the month projection test with a representative record whose snapshot brand is `瑞幸`, and assert its `CalendarDayUi.brandName == "瑞幸"`. Also assert an empty day has `brandName == null`.

- [ ] **Step 2: Run the red tests**

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.catalog.BundledBrandLogoTest' \
  --tests 'com.niumi.coffeejournal.journal.JournalViewModelTest' \
  --no-daemon
```

Expected: FAIL because `bundledBrandLogoRes` and `CalendarDayUi.brandName` do not exist.

- [ ] **Step 3: Implement normalized brand lookup**

Move the existing private `BundledBrandDefinition.catalogNames()` extension out of `CatalogRepository.kt` into `BundledBrandCatalog.kt` as an `internal` extension. Add one normalization path there and reuse it rather than creating a second alias list:

```kotlin
private fun normalizeBrandName(value: String) = value.trim().lowercase(Locale.ROOT)

@DrawableRes
fun bundledBrandLogoRes(brandName: String?): Int? {
    val target = brandName?.let(::normalizeBrandName) ?: return null
    return BUNDLED_CHAIN_BRANDS.firstOrNull { definition ->
        definition.catalogNames().any { normalizeBrandName(it) == target }
    }?.logoRes
}
```

Update `CatalogRepository.sortedForCatalog` and seed reconciliation to call the moved extension. Do not leave a duplicate private extension or change current seed adoption behavior.

- [ ] **Step 4: Carry brand name through the projection without schema changes**

Add `val brandName: String?` to `CalendarDayUi`, populated only from the latest representative record:

```kotlin
brandName = latest?.snapshot?.brandName,
```

Keep `brandLogoPath` unchanged so custom-brand snapshots and existing local logos still work. Do not add a brand ID to `DrinkSnapshot`, Room, or backup data.

- [ ] **Step 5: Re-run targeted tests and commit**

Run the Step 2 command. Expected: PASS.

```bash
git add app/src/main/java/com/niumi/coffeejournal/catalog/BundledBrandCatalog.kt \
  app/src/main/java/com/niumi/coffeejournal/journal/JournalProjection.kt \
  app/src/test/java/com/niumi/coffeejournal/catalog/BundledBrandLogoTest.kt \
  app/src/test/java/com/niumi/coffeejournal/journal/JournalViewModelTest.kt
git commit -m "feat: resolve bundled logos in calendar projection"
```

### Task 2: Render recorded calendar days without cropping or date overlays

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/TestTags.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/core/image/LocalAssetImage.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/JournalScreen.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/journal/JournalScreenRobolectricTest.kt`
- Create: `app/src/test/java/com/niumi/coffeejournal/core/image/ImagePresentationContractTest.kt`

- [ ] **Step 1: Write recorded-day semantics and complete-image contract tests**

Add stable tags:

```kotlin
const val CalendarDayPrefix = "calendar-day-"
const val CalendarDayNumberPrefix = "calendar-day-number-"
const val CalendarCountBadgePrefix = "calendar-count-"
const val CalendarImagePrefix = "calendar-image-"
```

In `JournalScreenRobolectricTest`, render one empty day, one single-cup day, and one three-cup day. Assert:

```kotlin
compose.onNodeWithTag(TestTags.CalendarDayNumberPrefix + emptyDate).assertTextEquals("14")
compose.onNodeWithTag(TestTags.CalendarDayNumberPrefix + recordedDate).assertDoesNotExist()
compose.onNodeWithTag(TestTags.CalendarCountBadgePrefix + recordedDate).assertDoesNotExist()
compose.onNodeWithTag(TestTags.CalendarCountBadgePrefix + multiDate).assertTextEquals("×3")
```

Add an internal shared constant in `LocalAssetImage.kt` and a unit test:

```kotlin
internal val CompleteImageContentScale: ContentScale = ContentScale.Fit

@Test fun `calendar and catalog image contract is complete fit`() {
    assertSame(ContentScale.Fit, CompleteImageContentScale)
}
```

- [ ] **Step 2: Run the red tests**

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.journal.JournalScreenRobolectricTest' \
  --tests 'com.niumi.coffeejournal.core.image.ImagePresentationContractTest' \
  --no-daemon
```

Expected: compile/test FAIL because tags and the Fit contract do not exist, and recorded days still render the day number.

- [ ] **Step 3: Make resource fallback explicit in `LocalAssetImage`**

Extend the existing composable with an optional `Painter` fallback while preserving source compatibility:

```kotlin
@Composable
fun LocalAssetImage(
    primaryPath: String?,
    fallbackPath: String?,
    contentDescription: String,
    contentScale: ContentScale = ContentScale.Crop,
    modifier: Modifier = Modifier,
    fallbackPainter: Painter? = null,
) {
    // load primary/fallback local paths as today
    when {
        bitmap != null -> Image(
            bitmap = checkNotNull(bitmap),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        fallbackPainter != null -> Image(
            painter = fallbackPainter,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        else -> CoffeePlaceholder(modifier)
    }
}
```

Do not change the default `Crop` globally because unrelated call sites may rely on it; calendar and catalog call sites must opt into `CompleteImageContentScale`.

- [ ] **Step 4: Implement calendar image precedence and no-date rendering**

For a recorded day compute `bundledLogoRes = bundledBrandLogoRes(day.brandName)`:

- brand mode, known built-in brand: render `painterResource(bundledLogoRes)` directly;
- brand mode, custom brand: load `day.brandLogoPath`;
- coffee mode: load `day.imagePath`, then `day.brandLogoPath`, then the built-in resource painter, then the generic placeholder.

Every image uses `CompleteImageContentScale`, a centered square/rounded warm tile, and internal padding. Render the day number only in the `drinkCount == 0` branch. Render `×N` only when `N > 1`, in a high-contrast rounded badge at bottom-end. Keep `contentDescription = "日期 ${day.localDate}"` on the cell so hiding visual date text does not reduce accessibility.

- [ ] **Step 5: Re-run tests and commit**

Run the Step 2 command. Expected: PASS.

```bash
git add app/src/main/java/com/niumi/coffeejournal/TestTags.kt \
  app/src/main/java/com/niumi/coffeejournal/core/image/LocalAssetImage.kt \
  app/src/main/java/com/niumi/coffeejournal/journal/JournalScreen.kt \
  app/src/test/java/com/niumi/coffeejournal/journal/JournalScreenRobolectricTest.kt \
  app/src/test/java/com/niumi/coffeejournal/core/image/ImagePresentationContractTest.kt
git commit -m "fix: show complete calendar images without date overlays"
```

### Task 3: Match the preview's calendar controls and adaptive layout

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/TestTags.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/JournalScreen.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/journal/JournalScreenRobolectricTest.kt`

- [ ] **Step 1: Add failing control semantics tests**

Add tags `PreviousMonth`, `NextMonth`, `CalendarModeIndicator`, and `MonthSummaryCard`. Assert:

- the mode control contains exactly `品牌` and `咖啡`, with no checkmark text/content description;
- selected/unselected semantics change after tapping each half;
- buttons are labeled exactly `上一月` and `下一月` and invoke their callbacks once;
- a 360 dp-wide test viewport displays all seven weekday headings and both month buttons;
- the existing `RecordButton`, monthly spend, settings, and calendar tags remain reachable.

- [ ] **Step 2: Run the red test**

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.journal.JournalScreenRobolectricTest' \
  --no-daemon
```

Expected: FAIL because the screen still uses Material's default segmented button with a check mark, bare `TextButton`s, and old labels `上月`/`下月`.

- [ ] **Step 3: Replace the mode control with a custom two-cell capsule**

Use a single rounded border container and two equal-weight clickable boxes. The selected half uses the project's deep forest green with white text; the unselected half uses warm ivory with the existing dark foreground. Apply `Modifier.selectable(..., role = Role.RadioButton)` and `selectableGroup()` so the visual replacement retains correct accessibility semantics.

Do not use `SingleChoiceSegmentedButtonRow`, `SegmentedButton`, or the Material selected check icon.

- [ ] **Step 4: Replace month controls and tune the calendar geometry**

Render a three-part row:

```kotlin
PreviewPillButton("上一月", previous, Modifier.testTag(TestTags.PreviousMonth))
Text("${year}年${month}月", ...)
PreviewPillButton("下一月", next, Modifier.testTag(TestTags.NextMonth))
```

Use green filled rounded pills with white text and bounded horizontal padding. Keep the 7-column `weight(1f)` grid; tune only spacing, corner radius, surface colors, and cell aspect ratio so a 320–600 dp width stays within bounds. Do not hardcode screenshot pixel coordinates.

Style the month summary and “记录一杯” action with the confirmed warm-beige/forest-green palette while preserving all current callbacks and tags.

- [ ] **Step 5: Re-run and commit**

Run the Step 2 command. Expected: PASS.

```bash
git add app/src/main/java/com/niumi/coffeejournal/TestTags.kt \
  app/src/main/java/com/niumi/coffeejournal/journal/JournalScreen.kt \
  app/src/test/java/com/niumi/coffeejournal/journal/JournalScreenRobolectricTest.kt
git commit -m "feat: match coffee calendar preview controls"
```

### Task 4: Normalize catalog logo/photo presentation and validate all 12 real assets

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/catalog/CatalogScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/catalog/BrandProductsScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/catalog/ManualProductEditorDialog.kt`
- Modify if an audited source requires replacement: `app/src/main/res/drawable-nodpi/brand_logo_*.png`
- Modify if any asset bytes change: `docs/brand-logo-sources.md`
- Modify: `app/src/test/java/com/niumi/coffeejournal/catalog/BundledBrandLogoTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/catalog/CatalogScreenRobolectricTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/catalog/BrandProductsScreenTest.kt`

- [ ] **Step 1: Strengthen logo and grid tests before changing production**

Update `BundledBrandLogoTest` to require all 12 resources to decode as exactly 512×512, remain unique, and retain the audited stable ID order. The normalized resource canvas and the UI frame are both square; the logo artwork inside the canvas remains proportional.

Add Compose assertions that every built-in/custom brand card and product card has a square media frame tag and a content description. Use the shared `CompleteImageContentScale` contract in all three production call sites.

- [ ] **Step 2: Run the red tests**

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.catalog.BundledBrandLogoTest' \
  --tests 'com.niumi.coffeejournal.catalog.CatalogScreenRobolectricTest' \
  --tests 'com.niumi.coffeejournal.catalog.BrandProductsScreenTest' \
  --no-daemon
```

Expected: FAIL because catalog brand/product images still use `ContentScale.Crop` and do not expose the uniform frame contract.

- [ ] **Step 3: Apply one square presentation system**

In the three-column chain-brand root, place every built-in or custom logo in the same `aspectRatio(1f)` rounded warm/brand-neutral tile with 10–12 dp internal padding and `CompleteImageContentScale`. Keep the brand name below the tile.

In the two-column product grid and product editor preview, keep a square frame but render the uploaded photo with `CompleteImageContentScale`; do not create or persist a cropped copy. Preserve product-photo → brand-logo → placeholder fallback behavior.

- [ ] **Step 4: Audit the checked-in brand assets against the source ledger**

Mechanically normalize and then visually inspect these exact resources at 1:1 and in the 3-column grid:

```text
brand_logo_luckin.png       brand_logo_cotti.png
brand_logo_nowwa.png        brand_logo_lucky_cup.png
brand_logo_starbucks.png    brand_logo_kcoffee.png
brand_logo_manner.png       brand_logo_hucoffee.png
brand_logo_tims.png         brand_logo_mstand.png
brand_logo_peets.png        brand_logo_arabica.png
```

Each output is a 512×512 PNG. Scale the genuine mark proportionally into a maximum 420×420 artwork box, center it on a transparent canvas when the mark has its own dark/color artwork, or extend the already documented solid brand-color background for white marks. This leaves at least 46 px breathing room on the constrained axis. For KCOFFEE and 沪咖, retain the verified genuine sign mark, remove no letters, and center the full sign crop rather than inventing a vector. Update every changed dimension/SHA-256 in `docs/brand-logo-sources.md` and rerun the uniqueness/decode test. Do not fetch a favicon, substitute a text recreation, or AI-generate a mark.

- [ ] **Step 5: Re-run and commit**

Run the Step 2 command. Expected: PASS.

```bash
git add app/src/main/java/com/niumi/coffeejournal/catalog/CatalogScreen.kt \
  app/src/main/java/com/niumi/coffeejournal/catalog/BrandProductsScreen.kt \
  app/src/main/java/com/niumi/coffeejournal/catalog/ManualProductEditorDialog.kt \
  app/src/main/res/drawable-nodpi/brand_logo_*.png \
  docs/brand-logo-sources.md \
  app/src/test/java/com/niumi/coffeejournal/catalog/BundledBrandLogoTest.kt \
  app/src/test/java/com/niumi/coffeejournal/catalog/CatalogScreenRobolectricTest.kt \
  app/src/test/java/com/niumi/coffeejournal/catalog/BrandProductsScreenTest.kt
git commit -m "fix: standardize catalog image presentation"
```

Before staging, omit unchanged assets/documentation from the command. Confirm `git diff --cached --name-only` contains no `.codex/**` paths.

### Task 5: Raise thumbnail clarity while preserving memory bounds and raw originals

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/core/image/ThumbnailLoader.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/core/image/ThumbnailLoaderTest.kt`
- Verify unchanged behavior: `app/src/test/java/com/niumi/coffeejournal/core/image/ImageStoreTest.kt`

- [ ] **Step 1: Write a failing high-density thumbnail test**

Generate a real 1600×1200 PNG fixture in the test and assert the decoded thumbnail's longest edge is in `512..1024` and its shortest edge remains proportional. Add a portrait 1200×1600 case and retain the existing EXIF rotation cases.

Also keep an assertion that `ImageStore.importWhole` preserves source bytes/SHA; thumbnail changes must not rewrite the stored file.

- [ ] **Step 2: Run the red tests**

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.core.image.ThumbnailLoaderTest' \
  --tests 'com.niumi.coffeejournal.core.image.ImageStoreTest' \
  --no-daemon
```

Expected: the new size assertion FAILS because the current power-of-two sampling stops near 256 px.

- [ ] **Step 3: Introduce an explicit bounded target**

Replace the magic number with:

```kotlin
internal const val THUMBNAIL_TARGET_EDGE_PX = 512

while (
    bounds.outWidth / sampleSize > THUMBNAIL_TARGET_EDGE_PX ||
    bounds.outHeight / sampleSize > THUMBNAIL_TARGET_EDGE_PX
) sampleSize *= 2
```

Keep the existing bounded LRU, single decode mutex, file magic checks, and EXIF transform. Do not call `BitmapFactory.decodeFile` without sampling for large files.

- [ ] **Step 4: Re-run and commit**

Run the Step 2 command. Expected: PASS.

```bash
git add app/src/main/java/com/niumi/coffeejournal/core/image/ThumbnailLoader.kt \
  app/src/test/java/com/niumi/coffeejournal/core/image/ThumbnailLoaderTest.kt
git commit -m "fix: sharpen local coffee thumbnails"
```

### Task 6: Make drink entry date-only with local-noon compatibility semantics

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/JournalRepository.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/JournalViewModel.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/RecordDrinkScreen.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/journal/JournalRepositoryTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/journal/JournalViewModelTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/journal/JournalScreenRobolectricTest.kt`
- Verify unchanged: `app/src/test/java/com/niumi/coffeejournal/backup/BackupManagerTest.kt`
- Verify unchanged: `app/src/test/java/com/niumi/coffeejournal/core/database/CoffeeDatabaseMigrationTest.kt`

- [ ] **Step 1: Write local-noon and date-only red tests**

Add a pure helper test with an explicit timezone:

```kotlin
@Test fun `selected date is stored at local noon`() {
    val zone = TimeZone.getTimeZone("Asia/Shanghai")
    val epoch = localNoonEpoch("2026-08-22", zone)
    val value = Calendar.getInstance(zone).apply { timeInMillis = epoch }
    assertEquals(12, value.get(Calendar.HOUR_OF_DAY))
    assertEquals(0, value.get(Calendar.MINUTE))
    assertEquals("2026-08-22", localDateForEpoch(epoch, zone))
}
```

Add repository tests proving a new draft uses noon for `ClockReading.localDate`, an untouched edited record retains its historical epoch, and a same-day noon value is accepted even when the fake clock is 08:00. Add ViewModel tests that reject tomorrow by local date but accept today-noon. Add UI assertions that `饮用日期` exists and `饮用日期与时间`, `HH:mm`, and any time-picker button do not exist.

- [ ] **Step 2: Run the red tests**

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.journal.JournalRepositoryTest' \
  --tests 'com.niumi.coffeejournal.journal.JournalViewModelTest' \
  --tests 'com.niumi.coffeejournal.journal.JournalScreenRobolectricTest' \
  --no-daemon
```

Expected: FAIL because new drafts use the exact current instant, future validation rejects today-noon before noon, and the time picker remains visible.

- [ ] **Step 3: Add deterministic local-date conversion**

In `JournalRepository.kt` add:

```kotlin
internal fun localNoonEpoch(localDate: String, timeZone: TimeZone = TimeZone.getDefault()): Long {
    val match = requireNotNull(Regex("(\\d{4})-(\\d{2})-(\\d{2})").matchEntire(localDate))
    val (year, month, day) = match.destructured
    return GregorianCalendar(timeZone).apply {
        isLenient = false
        clear()
        set(year.toInt(), month.toInt() - 1, day.toInt(), 12, 0, 0)
    }.timeInMillis
}
```

Use `localNoonEpoch(clock.read().localDate)` in `newDraft`. In repository save and `JournalViewModel.setConsumedAt`, validate `localDateForEpoch(consumedAt) <= clock.read().localDate` instead of comparing epoch with a five-minute skew. This permits today's noon at 08:00 but still rejects tomorrow. Keep existing historical edit epochs untouched unless the user changes the date.

- [ ] **Step 4: Remove the time control and normalize DatePicker results**

Delete the `TimePickerDialog` import/button and rename the heading to `饮用日期`. The DatePicker callback must call `localNoonEpoch` for the selected year/month/day instead of copying the prior hour/minute:

```kotlin
val localDate = "%04d-%02d-%02d".format(Locale.ROOT, year, month + 1, day)
onConsumedAtChange(localNoonEpoch(localDate))
```

Continue displaying `yyyy-MM-dd`. Do not display an internal time anywhere in the editor.

- [ ] **Step 5: Run journal plus compatibility tests**

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.journal.*' \
  --tests 'com.niumi.coffeejournal.backup.BackupManagerTest' \
  --tests 'com.niumi.coffeejournal.core.database.CoffeeDatabaseMigrationTest' \
  --no-daemon
```

Expected: PASS. Confirm `git status --short app/schemas` is empty; any schema change means the implementation violated scope.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/niumi/coffeejournal/journal/JournalRepository.kt \
  app/src/main/java/com/niumi/coffeejournal/journal/JournalViewModel.kt \
  app/src/main/java/com/niumi/coffeejournal/journal/RecordDrinkScreen.kt \
  app/src/test/java/com/niumi/coffeejournal/journal/JournalRepositoryTest.kt \
  app/src/test/java/com/niumi/coffeejournal/journal/JournalViewModelTest.kt \
  app/src/test/java/com/niumi/coffeejournal/journal/JournalScreenRobolectricTest.kt
git commit -m "feat: record coffee by date only"
```

### Task 7: Replace the Material default bottom bar with the preview navigation

**Files:**
- Create: `app/src/main/res/drawable/ic_calendar_outline.xml`
- Create: `app/src/main/res/drawable/ic_catalog_outline.xml`
- Create: `app/src/main/res/drawable/ic_insights_outline.xml`
- Modify: `app/src/main/java/com/niumi/coffeejournal/navigation/AppNavigation.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/navigation/AppNavigationTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/navigation/MainActivityAppNavigationTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt`

- [ ] **Step 1: Write failing navigation semantics tests**

Using real `MainActivity` + `InMemoryCoffeeJournalApp`, assert all three bottom nodes:

- have exact labels `咖啡日历`, `豆库`, `总结`;
- expose selected semantics only for the active root;
- remain equal-width and clickable;
- switch to the correct owned page title;
- stay absent on Settings and `ChainBrandProducts` child routes;
- return to the same root after opening/closing Settings.

Add tags for each icon and selected capsule only if needed for unambiguous assertions; retain the existing bottom-tab tags on the whole clickable item.

- [ ] **Step 2: Run the red tests**

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.navigation.AppNavigationTest' \
  --tests 'com.niumi.coffeejournal.navigation.MainActivityAppNavigationTest' \
  --tests 'com.niumi.coffeejournal.ReleaseAcceptanceRobolectricTest' \
  --no-daemon
```

Expected: FAIL on the new preview-style semantics because production still uses `NavigationBarItem` and text glyph icons.

- [ ] **Step 3: Add three code-owned outline vector icons**

Create 24×24 viewport vector resources for a calendar, coffee/catalog container, and summary chart. Use only simple strokes/paths owned by the app; do not introduce Material Icons Extended or a network dependency.

- [ ] **Step 4: Implement the custom bottom navigation**

Replace `NavigationBar`/`NavigationBarItem` with a warm-surface `Row` of three equal-weight `Column`s using `Modifier.selectable(..., role = Role.Tab)`. Each item renders:

- selected: icon inside a caramel rounded capsule plus caramel label;
- unselected: dark outline icon without capsule plus dark label;
- label below icon with bounded vertical spacing.

Keep current back-stack replacement behavior exactly: selecting a root clears the root stack and adds the chosen root; child screens do not show the bar.

- [ ] **Step 5: Re-run and commit**

Run the Step 2 command. Expected: PASS.

```bash
git add app/src/main/res/drawable/ic_calendar_outline.xml \
  app/src/main/res/drawable/ic_catalog_outline.xml \
  app/src/main/res/drawable/ic_insights_outline.xml \
  app/src/main/java/com/niumi/coffeejournal/navigation/AppNavigation.kt \
  app/src/test/java/com/niumi/coffeejournal/navigation/AppNavigationTest.kt \
  app/src/test/java/com/niumi/coffeejournal/navigation/MainActivityAppNavigationTest.kt \
  app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt
git commit -m "feat: match preview bottom navigation"
```

### Task 8: Add integrated visual-contract coverage and produce the trial APK

**Files:**
- Modify: `app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt`
- Modify: `app/src/androidTest/java/com/niumi/coffeejournal/AcceptanceTest.kt`
- Modify: `README.md`
- Modify: `docs/PROJECT_STATE.md`

- [ ] **Step 1: Extend the real MainActivity acceptance journey**

Seed four August 2026 records matching the phone trial pattern:

- M Stand on the 6th;
- Luckin on the 15th and 18th;
- MANNER on the 20th;
- at least one day with two records to assert `×2`.

Use real decodable product-photo fixtures with portrait and landscape aspect ratios. Through the real MainActivity UI, assert:

1. brand mode resolves M Stand, Luckin, and MANNER to bundled resources and has no date-number tag on recorded days;
2. coffee mode exposes product images, falls back to brand logo for a missing image, and still has no date-number tag;
3. mode switching preserves both labels and selected semantics;
4. month controls, summary, record action, and custom bottom tabs remain usable;
5. the record editor exposes a date but no time UI.

Mirror the same critical path in `AcceptanceTest.kt` so the instrumentation APK compiles against the real application graph. Do not claim it ran unless a device is connected.

- [ ] **Step 2: Run the focused cross-feature suite**

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.ReleaseAcceptanceRobolectricTest' \
  --tests 'com.niumi.coffeejournal.journal.*' \
  --tests 'com.niumi.coffeejournal.catalog.*' \
  --tests 'com.niumi.coffeejournal.core.image.*' \
  --tests 'com.niumi.coffeejournal.navigation.*' \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 3: Update user-facing and project-state documentation**

In `README.md`, state that calendar/product images are fit without crop, recorded days hide the date number, the editor is date-only, and built-in-brand display prefers bundled real logos. In `docs/PROJECT_STATE.md`, record the no-schema design decision, local-noon semantics, thumbnail target, and exact focused/full verification status. Remove no still-valid instructions.

- [ ] **Step 4: Run the clean release matrix**

```bash
./.local-tools/gradle-8.13/bin/gradle \
  clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease \
  --no-daemon
```

Expected: BUILD SUCCESSFUL, unit/Robolectric tests all pass, lint has zero errors, Debug/androidTest/unsigned Release APKs are generated.

Then verify:

```bash
git diff --check
git status --short app/schemas
rg -n 'android.permission.INTERNET|TimePickerDialog|饮用日期与时间' \
  app/src/main app/src/test app/src/androidTest
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose app/build/outputs/apk/debug/app-debug.apk
adb devices -l
```

Expected:

- `git diff --check` has no output;
- schema status is empty;
- forbidden-text search has no production match (test assertions may mention removed text only as negative expectations);
- Debug APK has valid v1/v2 signatures;
- if no device is listed, explicitly report that installation, SAF, and on-device visual verification remain manual.

- [ ] **Step 5: Request review and fix only concrete findings**

Use `superpowers:requesting-code-review` with a normal `reviewer`. Review scope:

- calendar resource/local-image precedence;
- date-number and `×N` semantics;
- date-only validation around today-before-noon and timezone boundaries;
- custom navigation state/back-stack behavior;
- thumbnail memory bound and EXIF behavior;
- no accidental schema/permission/network changes.

If the reviewer finds a reproducible issue, add a failing regression test, make the smallest fix, and rerun the focused suite plus any affected release task.

- [ ] **Step 6: Commit the acceptance/docs work**

```bash
git add app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt \
  app/src/androidTest/java/com/niumi/coffeejournal/AcceptanceTest.kt \
  README.md docs/PROJECT_STATE.md
git commit -m "test: verify calendar visual parity release"
```

Before committing, run `git diff --cached --name-only` and confirm no `.codex/**`, `.kotlin/**`, build output, APK, or user photo is staged.

## Final manual acceptance on the user's phone

Install the generated Debug APK over the prior Debug build so Room v3 data is preserved. Reopen August 2026 and verify against the two supplied screenshots:

- brand view: Luckin, M Stand, and MANNER marks are complete, consistent with the catalog, and not covered by date numbers;
- coffee view: all four real photos are complete and noticeably sharper, with no date-number overlay;
- the only recorded-day overlay is `×N` when the day has multiple cups;
- brand/coffee capsule, month pills, month summary/action, and three bottom tabs match the confirmed preview style;
- catalog shows all 12 real brand marks in uniform square tiles;
- record/edit flow shows only `yyyy-MM-dd`, and saving today works before local noon.

If this manual check finds a visual difference, capture the exact screen, device resolution, font-size/display-size settings, and affected mode before changing layout values. Do not compensate with device-specific absolute coordinates.
