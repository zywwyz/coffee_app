package com.niumi.coffeejournal.catalog

import com.niumi.coffeejournal.importer.ImportedAssetSelection

internal class CatalogScreenshotImportSession(
    private val leaseId: String,
    private val previousAssetId: String?,
    private val retain: suspend (String, String?) -> Boolean,
    private val stage: suspend (String, String?, String) -> Boolean,
    private val discard: (String) -> Unit,
    private val applyToEditor: (ImportedAssetSelection) -> Unit,
) {
    private var active = true
    private var retained = false
    private var screenshotStarted = false

    suspend fun retain(): Boolean {
        if (!active) return false
        if (!retained) retained = retain(leaseId, previousAssetId)
        if (!active && retained) discard(leaseId)
        return active && retained
    }

    suspend fun startScreenshot(requester: CatalogAssetPicker) {
        if (screenshotStarted || !retain()) return
        screenshotStarted = true
        requester(previousAssetId, CatalogAssetKind.CHAIN_PRODUCT_IMAGE, ::accept)
    }

    suspend fun accept(selection: ImportedAssetSelection): Boolean {
        if (!active || !retained) return false
        if (!stage(leaseId, previousAssetId, selection.assetId)) return false
        if (!active) return false
        applyToEditor(selection)
        return true
    }

    fun close() {
        if (!active) return
        active = false
        discard(leaseId)
    }
}
