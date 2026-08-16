package com.niumi.coffeejournal.catalog

import androidx.annotation.DrawableRes
import com.niumi.coffeejournal.R
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.MaintenanceMode

data class BundledBrandDefinition(
    val brand: Brand,
    @DrawableRes val logoRes: Int,
    val order: Int,
)

val BUNDLED_CHAIN_BRANDS = listOf(
    bundled("seed-chain-luckin", "瑞幸", R.drawable.brand_logo_luckin, 0),
    bundled("seed-chain-cotti", "库迪", R.drawable.brand_logo_cotti, 1),
    bundled("seed-chain-nowwa", "NOWWA", R.drawable.brand_logo_nowwa, 2),
    bundled("seed-chain-lucky-cup", "幸运咖", R.drawable.brand_logo_lucky_cup, 3),
    bundled("seed-chain-starbucks", "星巴克", R.drawable.brand_logo_starbucks, 4),
    bundled("seed-chain-kcoffee", "肯悦咖啡", R.drawable.brand_logo_kcoffee, 5),
    bundled("seed-chain-manner", "MANNER", R.drawable.brand_logo_manner, 6),
    bundled("seed-chain-hucoffee", "沪咖", R.drawable.brand_logo_hucoffee, 7),
    bundled("seed-chain-tims", "Tims", R.drawable.brand_logo_tims, 8),
    bundled("seed-chain-mstand", "M Stand", R.drawable.brand_logo_mstand, 9),
    bundled("seed-chain-peets", "Peet's", R.drawable.brand_logo_peets, 10),
    bundled("seed-chain-arabica", "%Arabica", R.drawable.brand_logo_arabica, 11),
)

private fun bundled(id: String, name: String, @DrawableRes logoRes: Int, order: Int) = BundledBrandDefinition(
    brand = Brand(id, BrandType.CHAIN, name, null, MaintenanceMode.MANUAL_ONLY, null),
    logoRes = logoRes,
    order = order,
)
