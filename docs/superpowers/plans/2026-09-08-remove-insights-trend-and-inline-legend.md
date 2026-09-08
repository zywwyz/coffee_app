# Remove Insights Trend and Keep Legend Values Inline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the monthly/yearly drinking-trend card and keep each Donut legend label, cup count, separator, and percentage on one aligned row.

**Architecture:** This is a presentation-only change in `InsightsScreen`; the calculator and stored monthly/yearly trend data remain unchanged so no Room, schema, backup, or repository work is required. The Donut keeps its current 96dp/90dp geometry and 16dp chart-to-legend gap, while the legend always uses a single five-column row whose value widths are measured once per card.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI tests with Robolectric, real-Activity preview tests, Gradle 8.13.

---

### Task 1: Remove the monthly and yearly trend card

**Files:**
- Modify: `app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt:91-99,201-217`
- Modify: `app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt:69-80`
- Modify: `app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt:99-169`

- [ ] **Step 1: Write the failing screen tests**

Replace the monthly accessibility test with a Donut-only test that also proves the trend surface is absent:

```kotlin
@Test fun `trend is absent and donut legends keep non color accessibility facts`() {
    compose.setContent { CoffeeTheme { InsightsScreen(state(), {}, {}) } }

    compose.onAllNodesWithTag(TestTags.InsightsTrendChart).assertCountEquals(0)
    compose.onAllNodesWithText("饮用趋势").assertCountEquals(0)
    compose.onNodeWithTag(TestTags.InsightsCoffeeTypeDonut).performScrollTo().assertIsDisplayed()
    compose.onNodeWithContentDescription("黑咖 · 4杯 · 57%").assertIsDisplayed()
    compose.onNodeWithTag(TestTags.InsightsBrandDonut).performScrollTo().assertIsDisplayed()
    compose.onNodeWithContentDescription("瑞幸 · 5杯 · 71%").assertIsDisplayed()
}
```

Replace the yearly trend-specific test with:

```kotlin
@Test fun `yearly dashboard omits the drinking trend card`() {
    val monthly = state().monthly!!
    val yearly = YearlyInsights(
        year = 2026,
        period = monthly.period,
        averageMonthlySpendFen = 0,
        monthlyPoints = emptyList(),
        highestSpendMonths = emptyList(),
        lowestSpendMonths = emptyList(),
        topRatedRecordIds = emptyList(),
        ratingTrendText = null,
        habit = monthly.habit,
        trend = listOf(ComparisonPoint(1, 3, 2)),
        coffeeTypeShares = monthly.coffeeTypeShares,
        brandShares = monthly.brandShares,
        topBrands = monthly.topBrands,
        topProducts = monthly.topProducts,
        best = monthly.best,
        worst = monthly.worst,
    )
    compose.setContent {
        CoffeeTheme {
            InsightsScreen(state().copy(mode = InsightsMode.YEARLY, monthly = null, yearly = yearly), {}, {})
        }
    }

    compose.onAllNodesWithTag(TestTags.InsightsTrendChart).assertCountEquals(0)
    compose.onAllNodesWithText("饮用趋势").assertCountEquals(0)
    compose.onNodeWithTag(TestTags.InsightsCoffeeTypeDonut).performScrollTo().assertIsDisplayed()
}
```

Update the real-Activity acceptance journey after selecting 年度 so it waits for `InsightsCoffeeTypeDonut`, asserts that Donut is displayed, and asserts zero nodes with `InsightsTrendChart`:

```kotlin
compose.onNodeWithText("年度").performClick().assertIsSelected()
compose.waitUntil(10_000) {
    compose.onAllNodesWithTag(TestTags.InsightsCoffeeTypeDonut).fetchSemanticsNodes().isNotEmpty()
}
compose.onNodeWithTag(TestTags.InsightsCoffeeTypeDonut).assertIsDisplayed()
compose.onAllNodesWithTag(TestTags.InsightsTrendChart).assertCountEquals(0)
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
JAVA_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/jdk/Contents/Home \
ANDROID_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/android-sdk \
GRADLE_USER_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.gradle \
/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/gradle-8.13/bin/gradle \
  :app:testDebugUnitTest \
  --tests com.niumi.coffeejournal.insights.InsightsScreenRobolectricTest \
  --tests com.niumi.coffeejournal.ReleaseAcceptanceRobolectricTest \
  --offline --no-daemon --rerun-tasks
```

Expected: FAIL because the existing UI still contains one `InsightsTrendChart` and the “饮用趋势” text.

- [ ] **Step 3: Remove only the trend presentation**

In `InsightsScreen.kt`, stop copying trend data into the private UI-only `Dashboard`:

```kotlin
val report = if (state.mode == InsightsMode.MONTHLY) {
    state.monthly?.let {
        it.period to Dashboard(
            it.habit,
            it.coffeeTypeShares,
            it.brandShares,
            it.topBrands,
            it.topProducts,
            it.best,
            it.worst,
        )
    }
} else {
    state.yearly?.let {
        it.period to Dashboard(
            it.habit,
            it.coffeeTypeShares,
            it.brandShares,
            it.topBrands,
            it.topProducts,
            it.best,
            it.worst,
        )
    }
}
```

Change the private `Dashboard` and `DashboardContent` signatures to:

```kotlin
private data class Dashboard(
    val habit: HabitSummary,
    val types: List<ShareValue>,
    val brands: List<ShareValue>,
    val topBrands: List<RankedValue>,
    val topProducts: List<RankedValue>,
    val best: HighlightRecord?,
    val worst: HighlightRecord?,
)

@Composable
private fun DashboardContent(
    data: Dashboard,
    resolver: ImagePathResolver,
    onOpenRecord: (String) -> Unit,
) {
    HabitHero(data.habit)
    DonutCard("咖啡类型", data.types, TestTags.InsightsCoffeeTypeDonut, Modifier.fillMaxWidth())
    DonutCard("常喝品牌", data.brands, TestTags.InsightsBrandDonut, Modifier.fillMaxWidth())
    RankingCard("Top3 品牌", data.topBrands, TestTags.InsightsTopBrands, Modifier.fillMaxWidth())
    RankingCard("Top3 产品", data.topProducts, TestTags.InsightsTopProducts, Modifier.fillMaxWidth())
    data.best?.let { HighlightCard("本期最好", it, TestTags.InsightsBestCard, resolver, onOpenRecord) }
    if (data.worst != null) {
        HighlightCard("本期最差", data.worst, TestTags.InsightsWorstCard, resolver, onOpenRecord)
    } else if (data.best != null) {
        Text("本期评分一致", color = CoffeeVisuals.secondaryText)
    } else {
        Text("本期暂无评分记录", color = CoffeeVisuals.secondaryText)
    }
}
```

Update the call at the screen level to:

```kotlin
DashboardContent(report.second, imagePathResolver, onOpenRecord)
```

Delete the private `TrendChart` composable. Remove the now-unused `Offset` and `StrokeCap` imports. Keep the calculator/ViewModel trend fields unchanged.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the same command from Step 2.

Expected: PASS with no trend tag or trend text in either period.

- [ ] **Step 5: Commit the trend removal**

```bash
git add app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt \
  app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt \
  app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt
git commit -m "fix: remove insights drinking trend"
```

### Task 2: Keep every legend label and cup count on the same line

**Files:**
- Modify: `app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt:101-199`
- Modify: `app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt:73,171-213`

- [ ] **Step 1: Write the failing first-line alignment test**

In the existing 320dp / fontScale 1.3 extreme legend test, replace the old 96dp minimum label-width assertion with direct first-line alignment assertions:

```kotlin
val label = compose.onNodeWithTag(
    "${TestTags.InsightsCoffeeTypeDonut}-legend-label-0",
    useUnmergedTree = true,
).fetchSemanticsNode().boundsInRoot
val cups = compose.onNodeWithTag(
    legendCupsTag(TestTags.InsightsCoffeeTypeDonut, 0),
    useUnmergedTree = true,
).fetchSemanticsNode().boundsInRoot
val percentage = compose.onNodeWithTag(
    legendPercentTag(TestTags.InsightsCoffeeTypeDonut, 0),
    useUnmergedTree = true,
).fetchSemanticsNode().boundsInRoot
val firstLineTolerance = with(compose.density) { 1.dp.toPx() }

assertNoVisualOverflow("${TestTags.InsightsCoffeeTypeDonut}-legend-label-0")
assertNoVisualOverflow(legendCupsTag(TestTags.InsightsCoffeeTypeDonut, 0))
assertNoVisualOverflow(legendPercentTag(TestTags.InsightsCoffeeTypeDonut, 0))
org.junit.Assert.assertTrue(kotlin.math.abs(label.top - cups.top) <= firstLineTolerance)
org.junit.Assert.assertTrue(kotlin.math.abs(cups.top - percentage.top) <= firstLineTolerance)
listOf(label, cups, percentage).forEach { assertInside(it, card) }
```

Keep the existing assertions that all cup columns share their left/right bounds, all percentage columns share their right bound, the row exposes one combined content description, and the Donut-to-dot gap is at least 16dp.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
JAVA_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/jdk/Contents/Home \
ANDROID_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/android-sdk \
GRADLE_USER_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.gradle \
/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/gradle-8.13/bin/gradle \
  :app:testDebugUnitTest \
  --tests com.niumi.coffeejournal.insights.InsightsScreenRobolectricTest \
  --offline --no-daemon --rerun-tasks
```

Expected: FAIL because the current narrow-screen branch places cup count and percentage below the label.

- [ ] **Step 3: Replace the two-layout branch with one five-column row**

Delete `minimumDonutLegendLabelWidth`, remove the `BoxWithConstraints` import, and replace each legend item's conditional body with:

```kotlin
Box(
    Modifier
        .fillMaxWidth()
        .clearAndSetSemantics {
            contentDescription = "$label · ${share.cups}杯 · ${percent(share)}"
        },
) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(8.dp)
                .background(donutColors[index % donutColors.size], CircleShape)
                .testTag("$tag-legend-dot-$index"),
        )
        Text(
            label,
            Modifier
                .weight(1f)
                .padding(start = 6.dp)
                .alignByBaseline()
                .testTag("$tag-legend-label-$index"),
            color = CoffeeVisuals.secondaryText,
        )
        Text(
            "${share.cups}杯",
            Modifier
                .width(cupsColumnWidth)
                .alignByBaseline()
                .testTag("$tag-legend-cups-$index"),
            color = CoffeeVisuals.secondaryText,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
        Text(
            "·",
            Modifier.width(14.dp).alignByBaseline(),
            color = CoffeeVisuals.secondaryText,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Text(
            percent(share),
            Modifier
                .width(percentColumnWidth)
                .alignByBaseline()
                .testTag("$tag-legend-percent-$index"),
            color = CoffeeVisuals.secondaryText,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}
```

The label has no `maxLines` limit, so only its own column wraps. The measured cup and percentage widths remain shared by every row in the card.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: PASS; the long label wraps without visual overflow, its first line aligns with the cup and percentage, numeric columns remain aligned, and the chart gap remains at least 16dp.

- [ ] **Step 5: Commit the legend layout**

```bash
git add app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt \
  app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt
git commit -m "fix: keep donut legend values inline"
```

### Task 3: Regenerate real previews and update project state

**Files:**
- Modify: `app/src/test/java/com/niumi/coffeejournal/InsightsPreviewRenderTest.kt:48-153`
- Modify: `docs/PROJECT_STATE.md:32,74`

- [ ] **Step 1: Update the real-Activity preview contract**

Replace the monthly and yearly `captureTop` calls with:

```kotlin
captureHero("insights-monthly-hero-cream-forest.png")
```

and:

```kotlin
captureHero("insights-yearly-hero-cream-forest.png")
```

After switching to 年度, wait for `InsightsCoffeeTypeDonut` instead of the removed “今年每月杯数” text:

```kotlin
compose.onNodeWithText("年度").performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
compose.waitUntil(10_000) {
    compose.onAllNodesWithTag(TestTags.InsightsCoffeeTypeDonut).fetchSemanticsNodes().isNotEmpty()
}
```

Replace `captureTop` with:

```kotlin
private fun captureHero(name: String) {
    compose.onNodeWithTag(TestTags.RootScreenTitle).performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag(TestTags.InsightsHabitHero).assertIsDisplayed()
    compose.onAllNodesWithTag(TestTags.InsightsTrendChart).assertCountEquals(0)
    compose.onAllNodesWithText("饮用趋势").assertCountEquals(0)
    capture(name)
}
```

Update `assertOnlyExpectedPreviews` so the exact eight expected files are:

```kotlin
val expected = setOf(
    "insights-monthly-hero-cream-forest.png",
    "insights-monthly-coffee-breakdown-cream-forest.png",
    "insights-monthly-brand-breakdown-cream-forest.png",
    "insights-monthly-highlights-cream-forest.png",
    "insights-yearly-hero-cream-forest.png",
    "insights-yearly-coffee-breakdown-cream-forest.png",
    "insights-yearly-brand-breakdown-cream-forest.png",
    "insights-yearly-highlights-cream-forest.png",
)
```

Keep `clearInsightPreviews()` so the obsolete `hero-trend` images are deleted before capture.

- [ ] **Step 2: Update the durable project summary**

In `docs/PROJECT_STATE.md`:

- Remove “月累计日趋势/年逐月趋势” from the implemented summary scope.
- Record that the trend card is intentionally absent in both modes.
- Replace the two `hero-trend` preview filenames with `hero`; keep the total at eight images.
- Record that each Donut legend uses a single aligned row and long names wrap only in their label column.

- [ ] **Step 3: Generate and inspect the real previews**

Run:

```bash
JAVA_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/jdk/Contents/Home \
ANDROID_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/android-sdk \
GRADLE_USER_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.gradle \
/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/gradle-8.13/bin/gradle \
  :app:testDebugUnitTest \
  --tests com.niumi.coffeejournal.InsightsPreviewRenderTest \
  -PinsightsPreview --offline --no-daemon --rerun-tasks
```

Expected: PASS and exactly eight `insights-*.png` files in `app/build/reports/previews/`. Inspect the monthly and yearly coffee/brand breakdown images and verify:

- no trend card appears;
- the Donut and first legend dot remain separated;
- each name and cup count share a first line;
- cup and percentage columns align vertically.

- [ ] **Step 4: Commit previews contract and state documentation**

```bash
git add app/src/test/java/com/niumi/coffeejournal/InsightsPreviewRenderTest.kt docs/PROJECT_STATE.md
git commit -m "test: refresh insights previews without trends"
```

### Task 4: Review and final verification

**Files:**
- Review: `app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt`
- Review: `app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt`
- Review: `app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt`
- Review: `app/src/test/java/com/niumi/coffeejournal/InsightsPreviewRenderTest.kt`
- Review: `docs/PROJECT_STATE.md`

- [ ] **Step 1: Request a reviewer pass**

Ask the reviewer to check the complete feature diff against the design document, focusing on:

- trend removal in both modes without changing statistics;
- first-line label/cup alignment under 320dp and fontScale 1.3;
- value-column alignment and 16dp chart gap;
- long-label containment and accessibility semantics;
- stale preview removal and the exact eight-file preview contract.

Fix every Critical or Important finding with a new failing regression test before changing production code.

- [ ] **Step 2: Run the full release-facing verification**

Run:

```bash
JAVA_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/jdk/Contents/Home \
ANDROID_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/android-sdk \
GRADLE_USER_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.gradle \
/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/gradle-8.13/bin/gradle \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  --offline --no-daemon --rerun-tasks
```

Expected: all unit/Robolectric tests PASS, lint has 0 errors, and `app/build/outputs/apk/debug/app-debug.apk` is generated.

- [ ] **Step 3: Verify the final diff and workspace**

```bash
git diff --check
git status --short
git log --oneline --decorate -6
```

Expected: no whitespace errors; only explicitly preserved user-owned changes may remain outside the isolated implementation worktree.
