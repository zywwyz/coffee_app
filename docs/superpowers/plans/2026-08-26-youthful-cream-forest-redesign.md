# Youthful Cream-Forest Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the approved youthful cream-and-forest visual system to the real Android app, normalize all 12 bundled logos for calendar/catalog use, and generate verified real-code previews containing actual logos and the user's product photo.

**Architecture:** Centralize colors, shapes, and reusable image-card presentation in the existing Compose UI layer without changing persistence. Normalize audited logo assets mechanically onto transparent 512×512 canvases, then render both bundled and user images through the existing complete-image contract. Preserve all existing ViewModel/repository behavior and prove the redesign with semantics tests plus real Compose preview rendering.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Android drawable resources, Robolectric Compose testing, Gradle 8.13.

---

## Guardrails and verification environment

- Work only in `.worktrees/calendar-visual-parity` on `codex/calendar-visual-parity`.
- Do not change Room entities, version, migrations, backup format, or permissions.
- Do not restore networking/OCR/screenshot-import code.
- Do not redraw trademarks or bake card background colors into logo files.
- Preserve the original bytes of user-uploaded images.
- Use TDD and commit each task separately.

Set the toolchain before Gradle commands:

```bash
export JAVA_HOME="$PWD/.local-tools/jdk/Contents/Home"
export ANDROID_HOME="$PWD/.local-tools/android-sdk"
export GRADLE_USER_HOME="$PWD/.gradle"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

### Task 1: Centralize the cream-forest visual tokens

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/ui/theme/Theme.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/ui/theme/Type.kt`
- Create: `app/src/main/java/com/niumi/coffeejournal/ui/CoffeeVisuals.kt`
- Create: `app/src/test/java/com/niumi/coffeejournal/ui/CoffeeVisualsTest.kt`

- [ ] **Step 1: Write a failing token contract test**

Assert exact stable ARGB values for cream background `#FAF7F0`, white surface, forest `#1F5B49`, peach `#EEA56E`, mint `#DDEDE6`, dark coffee text `#271C17`, and secondary text `#766A63`. Assert calendar/card corner constants are ordered from small to large.

- [ ] **Step 2: Run the red test**

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.ui.CoffeeVisualsTest' --no-daemon
```

Expected: FAIL because the centralized tokens do not exist.

- [ ] **Step 3: Implement the tokens and Material mapping**

Create `CoffeeVisuals` with named colors and 12/18/24dp corner radii. Map the light color scheme so `background=cream`, `surface=white`, `primary=forest`, `secondary=peach`, `tertiary=mint`, `onBackground=darkCoffee`, and `outline` is a low-alpha warm gray. Keep dark-mode behavior deterministic by using the same approved light visual system for this personal app rather than inventing an unapproved dark palette.

- [ ] **Step 4: Run the token test and theme-focused tests**

Run the Step 2 command plus `MainActivityWindowThemeTest`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/niumi/coffeejournal/ui app/src/test/java/com/niumi/coffeejournal/ui
git commit -m "feat: define youthful coffee visual system"
```

### Task 2: Normalize the 12 audited logo canvases

**Files:**
- Modify: `app/src/main/res/drawable-nodpi/brand_logo_*.png`
- Modify: `docs/brand-logo-sources.md`
- Create: `scripts/normalize_brand_logos.py`
- Modify: `app/src/test/java/com/niumi/coffeejournal/catalog/BundledBrandLogoTest.kt`

- [ ] **Step 1: Add failing normalized-canvas assertions**

For each bundled resource, assert decode succeeds, dimensions are exactly 512×512, transparent pixels exist near all four edges, and the non-transparent artwork bounding box does not touch the outer 6% safety margin. Keep the existing resource uniqueness and stable ordering assertions.

- [ ] **Step 2: Run the red logo test**

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.catalog.BundledBrandLogoTest' --no-daemon
```

Expected: FAIL for non-square or edge-touching current assets.

- [ ] **Step 3: Add a deterministic normalization script**

Use Pillow to trim only transparent outer pixels, preserve the original artwork aspect ratio, resize with Lanczos, and center it on a transparent 512×512 canvas. Support a per-brand maximum-width/height table so horizontal marks use up to 84% width while tall/square marks stay within 76–80%. Never crop opaque artwork and never synthesize missing trademark details.

- [ ] **Step 4: Normalize and visually inspect all resources**

Run the script against all 12 audited files. Specifically confirm the output uses the existing black `Cotti Coffee` wordmark and horizontal “肯悦咖啡 KCOFFEE” mark. Update the source ledger with the normalization command and retained source provenance.

- [ ] **Step 5: Re-run and commit**

Run the Step 2 command. Expected: PASS.

```bash
git add scripts/normalize_brand_logos.py app/src/main/res/drawable-nodpi/brand_logo_*.png \
  app/src/test/java/com/niumi/coffeejournal/catalog/BundledBrandLogoTest.kt docs/brand-logo-sources.md
git commit -m "fix: normalize bundled brand logo canvases"
```

### Task 3: Redesign the real coffee calendar surfaces and controls

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/JournalScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/core/image/LocalAssetImage.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/TestTags.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/journal/JournalScreenRobolectricTest.kt`

- [ ] **Step 1: Write failing visual-contract semantics tests**

Add stable tags for mode container, selected mode, month icon buttons, recorded/empty day cards, summary metrics, and count badge. Assert the two mode labels and selected semantics, icon-button content descriptions `上一月`/`下一月`, recorded-day absence of the day-number tag, and existing callback invocation.

- [ ] **Step 2: Run the red journal UI tests**

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.journal.JournalScreenRobolectricTest' --no-daemon
```

Expected: FAIL for the new young-style component tags/contracts.

- [ ] **Step 3: Implement cream-forest controls and cards**

Replace the heavy mode/month control surfaces with a shallow mint/white mode capsule and circular forest-tinted arrow buttons. Use white recorded-day cards with a subtle outline, near-background empty-day cards, and 6–8dp image padding. Use peach only for `×N` badges; keep all bundled/user images on white rather than colored image backplates. Split the month summary into three equal white metric cards. Preserve all existing callbacks, adaptive seven-column layout, accessibility text, and recorded-day no-number behavior.

- [ ] **Step 4: Run journal UI and projection tests**

Run the Step 2 command plus `JournalViewModelTest`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/niumi/coffeejournal/journal/JournalScreen.kt \
  app/src/main/java/com/niumi/coffeejournal/core/image/LocalAssetImage.kt \
  app/src/main/java/com/niumi/coffeejournal/TestTags.kt \
  app/src/test/java/com/niumi/coffeejournal/journal/JournalScreenRobolectricTest.kt
git commit -m "feat: refresh coffee calendar visual hierarchy"
```

### Task 4: Apply the same presentation to catalog, summary, forms, and bottom navigation

**Files:**
- Modify: `app/src/main/java/com/niumi/coffeejournal/catalog/CatalogScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/catalog/BrandProductsScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/catalog/ManualProductEditorDialog.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/journal/RecordDrinkScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt`
- Modify: `app/src/main/java/com/niumi/coffeejournal/navigation/AppNavigation.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/catalog/CatalogScreenRobolectricTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/catalog/BrandProductsScreenTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/navigation/AppNavigationTest.kt`

- [ ] **Step 1: Write failing cross-screen contracts**

Assert catalog logo/product media frames expose square tags and complete-image content descriptions, bottom navigation retains all three exact labels/route callbacks with the new custom item tags, and record entry still exposes only the date action. Keep delete/edit/quick-add tests unchanged as regression coverage.

- [ ] **Step 2: Run the red cross-screen tests**

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.catalog.*' \
  --tests 'com.niumi.coffeejournal.navigation.*' \
  --tests 'com.niumi.coffeejournal.journal.JournalScreenRobolectricTest' --no-daemon
```

Expected: FAIL for the new visual frame/navigation contracts while existing behavior remains green.

- [ ] **Step 3: Apply the shared visual system without business rewrites**

Use white, lightly outlined logo/product cards with `CompleteImageContentScale`; apply forest/peach/mint only to labels and actions. Restyle summary and form containers using the shared card radii. Replace the bottom bar's large orange selection block with a compact pale-peach capsule, forest selected icon/text, and muted unselected items. Do not change repositories, ViewModels, navigation destinations, delete rules, or image ownership.

- [ ] **Step 4: Run the targeted package tests and commit**

Run the Step 2 command. Expected: PASS.

```bash
git add app/src/main/java/com/niumi/coffeejournal/catalog \
  app/src/main/java/com/niumi/coffeejournal/journal/RecordDrinkScreen.kt \
  app/src/main/java/com/niumi/coffeejournal/insights/InsightsScreen.kt \
  app/src/main/java/com/niumi/coffeejournal/navigation/AppNavigation.kt \
  app/src/test/java/com/niumi/coffeejournal/catalog \
  app/src/test/java/com/niumi/coffeejournal/navigation
git commit -m "feat: unify youthful coffee app surfaces"
```

### Task 5: Render real previews and verify the deliverable

**Files:**
- Modify: `app/src/test/java/com/niumi/coffeejournal/journal/CalendarPreviewRenderTest.kt`
- Modify: `app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt`
- Modify: `docs/PROJECT_STATE.md`
- Generate: `build/reports/previews/calendar-brand-cream-forest.png`
- Generate: `build/reports/previews/calendar-coffee-cream-forest.png`

- [ ] **Step 1: Strengthen the real-code preview fixture**

Seed all 12 built-in brands across August 2026 and use the user's checked-in/fixture product photo for coffee mode. Assert the real MainActivity route displays decoded Logo resources and the product image, not placeholders; assert recorded-day number tags are absent and count badges remain correct.

- [ ] **Step 2: Run focused acceptance and render tests**

```bash
./.local-tools/gradle-8.13/bin/gradle testDebugUnitTest \
  --tests 'com.niumi.coffeejournal.ReleaseAcceptanceRobolectricTest' \
  --tests 'com.niumi.coffeejournal.journal.CalendarPreviewRenderTest' \
  --rerun-tasks --no-daemon
```

Expected: PASS and two PNG files generated from real Compose UI.

- [ ] **Step 3: Visually inspect both generated PNGs**

Verify all 12 Logo marks are readable, especially Cotti/KCOFFEE; product subject is complete; white image cards do not create dirty color blocks; no recorded date number overlays; mode/month/bottom controls use the approved cream/forest/peach hierarchy. If inspection fails, return to the owning task and add a regression assertion before adjusting production.

- [ ] **Step 4: Run the bounded release matrix**

```bash
./.local-tools/gradle-8.13/bin/gradle clean testDebugUnitTest lintDebug \
  assembleDebug assembleDebugAndroidTest assembleRelease --no-daemon
```

Expected: BUILD SUCCESSFUL, zero unit-test failures, zero lint errors, and Debug/AndroidTest/unsigned Release APKs present.

- [ ] **Step 5: Update state and commit**

Record the approved visual system, normalized logo contract, preview paths, test counts, APK path/hash, and remaining no-device limitation in `docs/PROJECT_STATE.md`.

```bash
git add app/src/test/java/com/niumi/coffeejournal/journal/CalendarPreviewRenderTest.kt \
  app/src/test/java/com/niumi/coffeejournal/ReleaseAcceptanceRobolectricTest.kt \
  docs/PROJECT_STATE.md
git commit -m "test: verify cream forest release previews"
```

## Plan self-review

- Spec coverage: theme tokens, 12 real Logo variants, image backgrounds, calendar controls/cells/summary, catalog, form, insights, bottom navigation, real-code previews, and release verification each map to a task.
- Placeholder scan: passed; every production and test step names an exact behavior and command.
- Type consistency: the plan reuses existing `CompleteImageContentScale`, `BundledBrandCatalog`, `CalendarPreviewRenderTest`, `TestTags`, and current navigation/repository APIs; it introduces only `CoffeeVisuals` as the shared UI token owner.
- Data safety: all steps stay above repositories/Room and explicitly prohibit schema, backup, permission, and image-byte changes.
