package com.niumi.coffeejournal.importer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkManifestRobolectricTest {
    @Test
    fun `manual official updates have internet permission`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val androidPermissions = info.requestedPermissions.orEmpty().filter { it.startsWith("android.permission.") }.toSet()
        assertTrue(Manifest.permission.INTERNET in androidPermissions)
        assertTrue("Unexpected broad/runtime permission: $androidPermissions", androidPermissions == setOf(Manifest.permission.INTERNET))
    }
}
