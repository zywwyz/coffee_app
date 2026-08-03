package com.niumi.coffeejournal.settings

import android.net.Uri
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.niumi.coffeejournal.backup.*
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp")
class SettingsScreenRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `small screen exposes SAF backup actions and privacy warning`() {
        compose.setContent { CoffeeTheme { SettingsScreen(UnusedBackupManager) } }

        compose.onNodeWithText("导出完整备份").assertIsDisplayed()
        compose.onNodeWithText("导入备份").assertIsDisplayed()
        compose.onNodeWithText("备份未加密", substring = true).assertIsDisplayed()
        compose.onNodeWithText("应用数据仅保存在本机", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `backup uses create and open document SAF contracts`() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        val create = ActivityResultContracts.CreateDocument("application/zip").createIntent(context, "coffee-journal-20260803.zip")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, create.action)
        assertEquals("application/zip", create.type)
        assertEquals("coffee-journal-20260803.zip", create.getStringExtra(Intent.EXTRA_TITLE))
        val open = ActivityResultContracts.OpenDocument().createIntent(context, arrayOf("application/zip"))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, open.action)
        assertEquals("*/*", open.type)
        assertEquals(listOf("application/zip"), open.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
    }
}

private object UnusedBackupManager : BackupManager {
    override suspend fun export(target: Uri) = error("unused")
    override suspend fun validate(source: Uri) = error("unused")
    override suspend fun restore(backup: ValidatedBackup) = error("unused")
    override suspend fun discard(backup: ValidatedBackup) = Unit
}
