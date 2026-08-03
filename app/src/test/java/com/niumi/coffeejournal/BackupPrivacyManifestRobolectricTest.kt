package com.niumi.coffeejournal

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupPrivacyManifestRobolectricTest {
    @Test
    fun `manifest disables legacy backup and excludes every private domain on Android 12 plus`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)

        assertFalse(applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
        val rulesId = context.resources.getIdentifier("data_extraction_rules", "xml", context.packageName)
        assertNotEquals(0, rulesId)

        val excludes = mutableMapOf<String, MutableSet<String>>()
        val parser = context.resources.getXml(rulesId)
        var section: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "cloud-backup", "device-transfer" -> section = parser.name
                    "exclude" -> excludes.getOrPut(requireNotNull(section)) { mutableSetOf() }
                        .add(parser.getAttributeValue(null, "domain"))
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == section) {
                section = null
            }
            parser.next()
        }

        val privateDomains = setOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
        )
        assertEquals(privateDomains, excludes["cloud-backup"])
        assertEquals(privateDomains, excludes["device-transfer"])
    }

    @Test
    fun `application declares a real launcher icon`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)

        assertNotEquals(0, applicationInfo.icon)
        assertTrue(context.resources.getDrawable(applicationInfo.icon, context.theme).intrinsicWidth > 0)
    }
}
