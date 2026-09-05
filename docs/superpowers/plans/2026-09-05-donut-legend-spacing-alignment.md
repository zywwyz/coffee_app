# Donut Legend Spacing and Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent donut charts from touching their legends and align cup-count and percentage columns across every legend row.

**Architecture:** Keep the existing `DonutCard` data and chart drawing intact. Add an explicit 16dp boundary between the fixed donut box and flexible legend, then split the combined statistic string into fixed-width, tagged text columns so rows share identical geometry.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI test, Robolectric, Gradle 8.13.

---

## File Structure

- Modify `app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt`: add donut/legend spacing and aligned statistic columns.
- Modify `app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt`: reproduce chart contact and misaligned values at 320dp with enlarged text.

### Task 1: Add failing geometry tests

**Files:**
- Test: `app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt`

- [ ] **Step 1: Add a chart-to-legend spacing assertion**

Render `InsightsScreen` at the existing 320dp qualifier, scroll the coffee-type donut into view, and compare the existing Canvas semantics bounds with the first legend dot:

```kotlin
@Test fun `donut and legend keep a sixteen dp safety gap`() {
    compose.setContent { CoffeeTheme { InsightsScreen(state(), {}, {}) } }
    compose.onNodeWithTag(TestTags.InsightsCoffeeTypeDonut).performScrollTo()

    val donut = compose.onNodeWithContentDescription("咖啡类型：", substring = true)
        .fetchSemanticsNode().boundsInRoot
    val dot = compose.onNodeWithTag("${TestTags.InsightsCoffeeTypeDonut}-legend-dot-0")
        .fetchSemanticsNode().boundsInRoot
    val minimumGap = with(compose.density) { 16.dp.toPx() }

    org.junit.Assert.assertTrue(dot.left - donut.right >= minimumGap)
}
```

- [ ] **Step 2: Add aligned numeric-column assertions**

Use the existing coffee-type fixture, which contains `4杯 · 57%` and `3杯 · 43%`, and extend it with zero-valued rows matching the phone report. Define predictable tags for each new value column:

```kotlin
private fun legendCupsTag(cardTag: String, index: Int) = "$cardTag-legend-cups-$index"
private fun legendPercentTag(cardTag: String, index: Int) = "$cardTag-legend-percent-$index"
```

Assert every cup column has the same left and right bounds and every percentage column has the same right bound:

```kotlin
val cupBounds = (0..3).map { index ->
    compose.onNodeWithTag(legendCupsTag(TestTags.InsightsCoffeeTypeDonut, index))
        .fetchSemanticsNode().boundsInRoot
}
val percentBounds = (0..3).map { index ->
    compose.onNodeWithTag(legendPercentTag(TestTags.InsightsCoffeeTypeDonut, index))
        .fetchSemanticsNode().boundsInRoot
}
cupBounds.drop(1).forEach {
    org.junit.Assert.assertEquals(cupBounds.first().left, it.left, 0.5f)
    org.junit.Assert.assertEquals(cupBounds.first().right, it.right, 0.5f)
}
percentBounds.drop(1).forEach {
    org.junit.Assert.assertEquals(percentBounds.first().right, it.right, 0.5f)
}
```

- [ ] **Step 3: Add an enlarged-font containment case**

Wrap the screen in an explicit font scale and verify the donut gap and all legend children stay inside the card:

```kotlin
val density = compose.density.density
compose.setContent {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density, 1.3f),
    ) {
        CoffeeTheme { InsightsScreen(longLegendState(), {}, {}) }
    }
}
```

Reuse the existing `assertInside` helper for the long label, first color dot, cup count, and percentage. The test must explicitly assert the 16dp donut gap at this font scale.

- [ ] **Step 4: Run the tests and verify RED**

Run:

```bash
env JAVA_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/jdk/Contents/Home \
ANDROID_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/android-sdk \
GRADLE_USER_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.gradle \
/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/gradle-8.13/bin/gradle \
-p /Users/niumi/Documents/Codex/projects/coffee_app :app:testDebugUnitTest \
--tests 'com.niumi.coffeejournal.insights.InsightsScreenRobolectricTest' \
--offline --no-daemon --console=plain
```

Expected: FAIL because the current chart-to-dot gap is only the Canvas inset inside its 96dp box, and the separate numeric-column tags do not exist.

### Task 2: Implement spacing and aligned statistics

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt:162-181`
- Test: `app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt`

- [ ] **Step 1: Add explicit space between chart and legend**

Update the outer `Row` without changing the donut dimensions:

```kotlin
Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        // Existing 90dp Canvas and total Text remain unchanged.
    }
    Column(Modifier.weight(1f)) {
        // Existing share iteration remains here.
    }
}
```

- [ ] **Step 2: Split statistics into fixed aligned columns**

Keep the complete row `contentDescription`, but replace the combined statistic `Text` with three columns. Use explicit tags and end alignment:

```kotlin
Text(
    "${share.cups}杯",
    Modifier.width(36.dp).testTag("$tag-legend-cups-$index"),
    color = CoffeeVisuals.secondaryText,
    maxLines = 1,
    textAlign = TextAlign.End,
)
Text(
    "·",
    Modifier.width(14.dp),
    color = CoffeeVisuals.secondaryText,
    maxLines = 1,
    textAlign = TextAlign.Center,
)
Text(
    percent(share),
    Modifier.width(44.dp).testTag("$tag-legend-percent-$index"),
    color = CoffeeVisuals.secondaryText,
    maxLines = 1,
    textAlign = TextAlign.End,
)
```

Add `androidx.compose.ui.text.style.TextAlign` and reuse the existing `width` import. Do not restore `maxLines` or ellipsis on the legend name.

- [ ] **Step 3: Run the focused screen tests and verify GREEN**

Run the Task 1 command again.

Expected: `InsightsScreenRobolectricTest` PASS, including the 16dp safety gap, fixed numeric columns, enlarged-font containment, and existing long-name behavior.

- [ ] **Step 4: Run all Insights regressions**

Run:

```bash
env JAVA_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/jdk/Contents/Home \
ANDROID_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/android-sdk \
GRADLE_USER_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.gradle \
/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/gradle-8.13/bin/gradle \
-p /Users/niumi/Documents/Codex/projects/coffee_app :app:testDebugUnitTest \
--tests 'com.niumi.coffeejournal.insights.*' :app:lintDebug \
--offline --no-daemon --console=plain
```

Expected: all Insights tests PASS and lint reports zero errors.

- [ ] **Step 5: Check scope and commit**

```bash
git diff --check
git add app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt \
  app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt
git commit -m "fix: separate donut legends from charts"
```

Expected: only the two owned files are included in the implementation commit.

### Task 3: Review and delivery verification

**Files:**
- Review: `app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt`
- Review: `app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt`

- [ ] **Step 1: Review implementation against the design**

Confirm the donut remains 90dp inside its 96dp box, the horizontal gap is at least 16dp, numeric columns share exact geometry, long names remain unrestricted, semantics still report the full combined fact, and no data-layer changes exist.

- [ ] **Step 2: Run merged-scope verification**

Run:

```bash
env JAVA_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/jdk/Contents/Home \
ANDROID_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/android-sdk \
GRADLE_USER_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.gradle \
/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/gradle-8.13/bin/gradle \
-p /Users/niumi/Documents/Codex/projects/coffee_app :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
--offline --no-daemon --console=plain
```

Expected: unit tests, lint, and Debug APK build PASS.
