package com.niumi.coffeejournal.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.niumi.coffeejournal.backup.BackupManager
import com.niumi.coffeejournal.backup.ValidatedBackup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicReference

internal class ValidatedBackupLeaseHolder(initial: ValidatedBackup? = null) {
    private val reference = AtomicReference(initial)

    fun register(backup: ValidatedBackup): ValidatedBackup? = reference.getAndSet(backup)
    fun take(): ValidatedBackup? = reference.getAndSet(null)
    fun take(expected: ValidatedBackup): ValidatedBackup? =
        if (reference.compareAndSet(expected, null)) expected else null
}

@Composable
fun SettingsScreen(manager: BackupManager, initialValidatedBackup: ValidatedBackup? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val validatedLease = remember { ValidatedBackupLeaseHolder(initialValidatedBackup) }
    var validated by remember { mutableStateOf(initialValidatedBackup) }
    var running by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastBackup by remember { mutableLongStateOf(prefs.getLong(LAST_BACKUP, 0)) }
    val cleanupScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    DisposableEffect(Unit) {
        onDispose {
            validatedLease.take()?.let { pending ->
                cleanupScope.launch(start = CoroutineStart.UNDISPATCHED) { manager.discard(pending) }
            }
            cleanupScope.cancel()
        }
    }

    fun launch(block: suspend () -> Unit) {
        error = null
        running = scope.launch {
            try { block() }
            catch (_: CancellationException) { status = "操作已取消" }
            catch (failure: Exception) { error = failure.message ?: "操作失败" }
            finally { running = null }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) launch {
            manager.export(uri)
            val time = System.currentTimeMillis()
            prefs.edit { putLong(LAST_BACKUP, time) }
            lastBackup = time
            status = "备份已导出"
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) launch {
            val backup = manager.validate(uri)
            validatedLease.register(backup)?.let { previous -> manager.discard(previous) }
            validated = backup
            status = null
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("备份与恢复", style = MaterialTheme.typography.headlineSmall)
        Text("备份保存在你选择的位置，包含全部记录、豆库和本地图片。备份未加密，请妥善保管。")
        Text(if (lastBackup == 0L) "尚未成功备份" else "上次成功备份：${formatTime(lastBackup)}")
        Button(
            onClick = { exportLauncher.launch("coffee-journal-${SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())}.zip") },
            enabled = running == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("导出完整备份") }
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
            enabled = running == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("导入备份") }
        if (running != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { running?.cancel() }) { Text("取消") }
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text("失败：$it", color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        Text("数据说明", style = MaterialTheme.typography.titleMedium)
        Text("应用数据仅保存在本机；除手动更新公开产品信息外，不会上传个人记录。卸载前请先导出备份。")
    }

    validated?.let { backup ->
        AlertDialog(
            onDismissRequest = {
                validatedLease.take(backup)?.let { owned ->
                    validated = null
                    launch { manager.discard(owned) }
                }
            },
            title = { Text("替换全部本地数据？") },
            text = {
                val c = backup.manifest.counts
                Text("备份时间：${formatTime(backup.manifest.exportedAtEpochMillis)}\n格式版本：${backup.manifest.formatVersion}\n记录 ${c.records} · 品牌 ${c.brands} · 产品 ${c.catalogItems} · 图片 ${c.images}\n\n继续会替换当前全部本地数据，且无法撤销。")
            },
            confirmButton = {
                Button(onClick = {
                    validatedLease.take(backup)?.let { owned ->
                        validated = null
                        launch { manager.restore(owned); status = "恢复完成" }
                    }
                }) { Text("确认替换") }
            },
            dismissButton = {
                TextButton(onClick = {
                    validatedLease.take(backup)?.let { owned -> validated = null; launch { manager.discard(owned) } }
                }) { Text("取消") }
            },
        )
    }
}

private fun formatTime(epochMillis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(epochMillis))
private const val PREFERENCES = "backup_preferences"
private const val LAST_BACKUP = "last_successful_backup_at"
