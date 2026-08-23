package com.niumi.coffeejournal.catalog

import android.graphics.BitmapFactory
import com.niumi.coffeejournal.R
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BundledBrandLogoTest {
    @Test fun `bundled logo resolves canonical names and aliases`() {
        assertEquals(R.drawable.brand_logo_luckin, bundledBrandLogoRes("  瑞幸  "))
        assertEquals(R.drawable.brand_logo_cotti, bundledBrandLogoRes("库迪咖啡"))
        assertEquals(R.drawable.brand_logo_manner, bundledBrandLogoRes("manner coffee"))
        assertEquals(R.drawable.brand_logo_mstand, bundledBrandLogoRes("Mstand"))
        assertNull(bundledBrandLogoRes("自定义咖啡"))
    }

    @Test fun `catalog names use the shared trimmed lowercase normalization`() {
        val definition = BUNDLED_CHAIN_BRANDS.first { it.brand.name == "MANNER" }
            .copy(aliases = setOf("  MANNER   Coffee  "))

        assertEquals(setOf("manner", "manner   coffee"), definition.catalogNames())
    }

    @Test fun `bundled chain logos are complete unique and stable`() {
        val resources = RuntimeEnvironment.getApplication().resources
        assertEquals(12, BUNDLED_CHAIN_BRANDS.size)
        assertEquals(
            listOf("luckin", "cotti", "nowwa", "lucky-cup", "starbucks", "kcoffee", "manner", "hucoffee", "tims", "mstand", "peets", "arabica"),
            BUNDLED_CHAIN_BRANDS.map { it.brand.id.removePrefix("seed-chain-") },
        )
        assertEquals((0..11).toList(), BUNDLED_CHAIN_BRANDS.map(BundledBrandDefinition::order))
        BUNDLED_CHAIN_BRANDS.forEach {
            assertEquals(BrandType.CHAIN, it.brand.type)
            assertEquals(MaintenanceMode.MANUAL_ONLY, it.brand.maintenanceMode)
            assertNull(it.brand.publicSourceUrl)
            val bitmap = BitmapFactory.decodeResource(resources, it.logoRes)
            assertNotNull(bitmap)
            checkNotNull(bitmap).also { decoded ->
                org.junit.Assert.assertTrue(decoded.width <= 512)
                org.junit.Assert.assertTrue(decoded.height <= 512)
            }
        }
        assertEquals(12, BUNDLED_CHAIN_BRANDS.map { decodedPixelSha256(resources, it.logoRes) }.toSet().size)
    }

    private fun decodedPixelSha256(resources: android.content.res.Resources, resId: Int): String {
        val bitmap = requireNotNull(BitmapFactory.decodeResource(resources, resId))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return MessageDigest.getInstance("SHA-256")
            .digest(pixels.flatMap { pixel -> listOf((pixel ushr 24).toByte(), (pixel ushr 16).toByte(), (pixel ushr 8).toByte(), pixel.toByte()) }.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
