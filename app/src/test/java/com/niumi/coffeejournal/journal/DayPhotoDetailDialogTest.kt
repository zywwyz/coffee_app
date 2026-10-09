package com.niumi.coffeejournal.journal

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.DrinkSnapshot
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayPhotoDetailDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `photo card keeps facts and routes actions to selected record`() {
        val record = record("one")
        var edited: String? = null
        var changed: DrinkRecord? = null
        var deleted: String? = null
        compose.setContent {
            CoffeeTheme {
                DayPhotoDetailDialog("2026-10-07", listOf(record), {}, { edited = it }, { deleted = it }, { changed = it }, ImagePathResolver { null })
            }
        }
        compose.onNodeWithContentDescription("当天实拍图 产品one").assertIsDisplayed()
        compose.onNodeWithText("4.5 星").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("¥3.25").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("更换图片").performScrollTo().performClick()
        assertEquals(record, changed)
        compose.onNodeWithText("编辑").performClick()
        assertEquals("one", edited)
        compose.onNodeWithText("删除").performClick()
        assertNull(deleted)
        compose.onNodeWithText("取消").performClick()
        assertNull(deleted)
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("确认删除").performClick()
        assertEquals("one", deleted)
    }

    @Test
    fun `large font multi record detail can scroll and close remains available`() {
        var closed = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                CoffeeTheme {
                    DayPhotoDetailDialog("2026-10-07", listOf(record("one"), record("two"), record("three")), { closed = true }, {}, {}, {}, ImagePathResolver { null }, { "手冲 · 18g" })
                }
            }
        }
        compose.onNodeWithText("产品three").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
        assertEquals(true, closed)
    }

    private fun record(id: String) = DrinkRecord(
        id = id,
        occurredAtEpochMillis = 100L,
        localDate = "2026-10-07",
        itemType = ItemType.CHAIN_PRODUCT,
        sourceItemId = "item-$id",
        brewMethod = "手冲",
        ratingHalfStars = 9,
        actualPriceFen = 325,
        note = "清爽的风味",
        snapshot = DrinkSnapshot("品牌", "产品$id", null, null, "asset-$id"),
    )
}
