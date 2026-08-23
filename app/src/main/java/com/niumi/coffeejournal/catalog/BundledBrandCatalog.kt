package com.niumi.coffeejournal.catalog

import androidx.annotation.DrawableRes
import com.niumi.coffeejournal.R
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import java.text.Normalizer
import java.util.Locale

data class BundledBrandDefinition(
    val brand: Brand,
    @DrawableRes val logoRes: Int,
    val order: Int,
    val aliases: Set<String> = emptySet(),
)

val BUNDLED_CHAIN_BRANDS = listOf(
    bundled("seed-chain-luckin", "瑞幸", R.drawable.brand_logo_luckin, 0),
    bundled("seed-chain-cotti", "库迪", R.drawable.brand_logo_cotti, 1, "库迪咖啡"),
    bundled("seed-chain-nowwa", "NOWWA", R.drawable.brand_logo_nowwa, 2, "挪瓦", "挪瓦咖啡"),
    bundled("seed-chain-lucky-cup", "幸运咖", R.drawable.brand_logo_lucky_cup, 3),
    bundled("seed-chain-starbucks", "星巴克", R.drawable.brand_logo_starbucks, 4, "星巴克咖啡"),
    bundled("seed-chain-kcoffee", "肯悦咖啡", R.drawable.brand_logo_kcoffee, 5, "肯悦", "kcoffee"),
    bundled("seed-chain-manner", "MANNER", R.drawable.brand_logo_manner, 6, "Manner Coffee"),
    bundled("seed-chain-hucoffee", "沪咖", R.drawable.brand_logo_hucoffee, 7),
    bundled("seed-chain-tims", "Tims", R.drawable.brand_logo_tims, 8, "天好", "天好咖啡", "Tims Coffee"),
    bundled("seed-chain-mstand", "M Stand", R.drawable.brand_logo_mstand, 9, "Mstand", "M Stand Coffee"),
    bundled("seed-chain-peets", "Peet's", R.drawable.brand_logo_peets, 10),
    bundled("seed-chain-arabica", "%Arabica", R.drawable.brand_logo_arabica, 11),
)

internal fun BundledBrandDefinition.catalogNames(): Set<String> =
    (aliases + brand.name).mapTo(linkedSetOf(), ::normalizeCatalogName)

fun bundledBrandLogoRes(brandName: String?): Int? {
    val rawName = brandName ?: return null
    val normalizedName = try {
        normalizeCatalogName(rawName)
    } catch (_: InvalidCatalogNameException) {
        return null
    }
    return BUNDLED_CHAIN_BRANDS.firstOrNull { normalizedName in it.catalogNames() }?.logoRes
}

internal fun normalizeCatalogName(raw: String): String {
    val compatible = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val normalized = StringBuilder()
    var pendingSpace = false
    var index = 0
    while (index < compatible.length) {
        val codePoint = compatible.codePointAt(index)
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            pendingSpace = normalized.isNotEmpty()
        } else {
            if (pendingSpace) normalized.append(' ')
            normalized.appendCodePoint(codePoint)
            pendingSpace = false
        }
        index += Character.charCount(codePoint)
    }
    if (normalized.isEmpty()) throw InvalidCatalogNameException(raw)
    return normalized.toString()
}

private fun bundled(id: String, name: String, @DrawableRes logoRes: Int, order: Int, vararg aliases: String) = BundledBrandDefinition(
    brand = Brand(id, BrandType.CHAIN, name, null, MaintenanceMode.MANUAL_ONLY, null),
    logoRes = logoRes,
    order = order,
    aliases = aliases.toSet(),
)
