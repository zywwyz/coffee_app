# Insights Long Name Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make brand-share legends and Top3 brand/product names fully readable by changing the four summary metric cards to full-width rows with wrapping labels.

**Architecture:** Keep the existing `Dashboard`, calculator, ViewModel, navigation, and semantics unchanged. Limit production changes to `DashboardContent`, `DonutCard`, and `RankingCard`; Compose layout owns the fix because all upstream data already contains complete names.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI test, Robolectric, Gradle 8.13.

---

## File Structure

- Modify `app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt`: stack the four cards vertically and allow legend/ranking names to wrap.
- Modify `app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt`: reproduce the 320dp truncation and verify full-width wrapping within card bounds.

### Task 1: Add failing narrow-screen layout tests

**Files:**
- Test: `app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt`

- [ ] **Step 1: Add a full-width card geometry test**

Add a test that renders the existing long-name state, scrolls each metric card into view, and compares its width with the insights surface width:

```kotlin
@Test fun `breakdown and ranking cards use full available width`() {
    compose.setContent { CoffeeTheme { InsightsScreen(longNameState(), {}, {}) } }
    val surfaceWidth = compose.onNodeWithTag(TestTags.InsightsSurface)
        .fetchSemanticsNode().boundsInRoot.width

    listOf(
        TestTags.InsightsCoffeeTypeDonut,
        TestTags.InsightsBrandDonut,
        TestTags.InsightsTopBrands,
        TestTags.InsightsTopProducts,
    ).forEach { tag ->
        compose.onNodeWithTag(tag).performScrollTo()
        val cardWidth = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.width
        org.junit.Assert.assertTrue(cardWidth >= surfaceWidth - with(compose.density) { 40.dp.toPx() })
    }
}
```

- [ ] **Step 2: Add a wrapping and containment test**

Add a fixture containing `星巴克臻选上海烘焙工坊` and `星巴克臻选 · 哥伦比亚雪莉酒桶冷萃`, then verify both text nodes are taller than one line and remain within their cards:

```kotlin
@Test fun `long brand and product names wrap completely inside full width cards`() {
    val state = longNameState()
    compose.setContent { CoffeeTheme { InsightsScreen(state, {}, {}) } }

    listOf(
        TestTags.InsightsBrandDonut to "星巴克臻选上海烘焙工坊",
        TestTags.InsightsTopProducts to "星巴克臻选 · 哥伦比亚雪莉酒桶冷萃",
    ).forEach { (tag, label) ->
        compose.onNodeWithTag(tag).performScrollTo()
        val card = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(text.height > with(compose.density) { 16.dp.toPx() })
        org.junit.Assert.assertTrue(text.left >= card.left && text.right <= card.right)
        org.junit.Assert.assertTrue(text.top >= card.top && text.bottom <= card.bottom)
    }
}
```

Implement `longNameState()` by copying the existing `state()` fixture and replacing `brandShares`, `topBrands`, and `topProducts` with the two long values. Do not change production code in this step.

- [ ] **Step 3: Run the new tests and verify RED**

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

Expected: FAIL because the four cards are only about half the available width and the current `Text` nodes are constrained to one line.

- [ ] **Step 4: Commit the failing tests**

```bash
git add app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt
git commit -m "test: reproduce truncated insights names"
```

### Task 2: Implement full-width wrapping cards

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt:126-176`
- Test: `app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt`

- [ ] **Step 1: Stack each metric card at full width**

Replace the two paired `Row` containers in `DashboardContent` with four direct calls:

```kotlin
DonutCard("咖啡类型", data.types, TestTags.InsightsCoffeeTypeDonut, Modifier.fillMaxWidth())
DonutCard("常喝品牌", data.brands, TestTags.InsightsBrandDonut, Modifier.fillMaxWidth())
RankingCard("Top3 品牌", data.topBrands, TestTags.InsightsTopBrands, Modifier.fillMaxWidth())
RankingCard("Top3 产品", data.topProducts, TestTags.InsightsTopProducts, Modifier.fillMaxWidth())
```

- [ ] **Step 2: Put each donut beside a flexible legend**

Keep the donut canvas unchanged, but place it and the legend in one row. The legend name must not specify `maxLines` or `TextOverflow.Ellipsis`; the statistic remains a single line:

```kotlin
Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        // Existing Canvas and total-cup Text.
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shares.forEach { share ->
            val label = donutLabel(share.label)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(label, Modifier.weight(1f), color = CoffeeVisuals.secondaryText)
                Text(
                    "${share.cups}杯 · ${percent(share)}",
                    color = CoffeeVisuals.secondaryText,
                    maxLines = 1,
                )
            }
        }
    }
}
```

Preserve each row's existing complete `contentDescription`.

- [ ] **Step 3: Allow ranking names to grow vertically**

Change `RankingCard` so the name no longer uses `maxLines = 1` or ellipsis and align the row to the top:

```kotlin
Row(
    Modifier.fillMaxWidth().semantics {
        contentDescription = "$title 第${i + 1}名 ${value.name} ${value.cups}杯"
    },
    verticalAlignment = Alignment.Top,
) {
    Text("${i + 1}", color = CoffeeVisuals.peach)
    Text(value.name, Modifier.weight(1f).padding(horizontal = 6.dp))
    Text("${value.cups}杯", color = CoffeeVisuals.secondaryText, maxLines = 1)
}
```

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the Task 1 Gradle command again.

Expected: `InsightsScreenRobolectricTest` PASS, including the new full-width and wrapping assertions.

- [ ] **Step 5: Run the insights package regression tests**

Run:

```bash
env JAVA_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/jdk/Contents/Home \
ANDROID_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/android-sdk \
GRADLE_USER_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.gradle \
/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/gradle-8.13/bin/gradle \
-p /Users/niumi/Documents/Codex/projects/coffee_app :app:testDebugUnitTest \
--tests 'com.niumi.coffeejournal.insights.*' \
--offline --no-daemon --console=plain
```

Expected: all Insights calculator, ViewModel, and screen tests PASS.

- [ ] **Step 6: Check the diff and commit**

```bash
git diff --check
git add app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt \
  app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt
git commit -m "fix: show complete insights names"
```

### Task 3: Review and final verification

**Files:**
- Review: `app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt`
- Review: `app/src/test/java/com/niumi/coffeejournal/insights/InsightsScreenRobolectricTest.kt`

- [ ] **Step 1: Review the behavior against the design**

Confirm that all four cards are full width, both name locations have no maximum line count, count/percentage labels remain single line, accessibility descriptions remain complete, and no data/model/API changes were introduced.

- [ ] **Step 2: Run final relevant verification**

Run:

```bash
env JAVA_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/jdk/Contents/Home \
ANDROID_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/android-sdk \
GRADLE_USER_HOME=/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.gradle \
/Users/niumi/Documents/Codex/projects/coffee_app/.worktrees/codex-coffee-journal/.local-tools/gradle-8.13/bin/gradle \
-p /Users/niumi/Documents/Codex/projects/coffee_app :app:testDebugUnitTest :app:lintDebug \
--offline --no-daemon --console=plain
```

Expected: unit tests and lint PASS with zero lint errors.

- [ ] **Step 3: Confirm worktree scope**

```bash
git status --short
git diff --check
```

Expected: only the user's pre-existing `.codex` deletions may remain; there are no uncommitted implementation files.
