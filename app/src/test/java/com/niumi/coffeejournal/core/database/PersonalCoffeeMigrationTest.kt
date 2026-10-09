package com.niumi.coffeejournal.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalCoffeeMigrationTest {
    @Test fun `room validates v5 migration and preserves original record and draft`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "cost-migration-${System.nanoTime()}.db"
        val current = Room.databaseBuilder(context, CoffeeDatabase::class.java, name).allowMainThreadQueries().build()
        current.brandDao().upsert(BrandEntity("brand", "ROASTER", "烘焙商", "烘焙商", null, "MANUAL_ONLY", null))
        current.catalogItemDao().upsert(CatalogItemEntity("bean", "brand", "PERSONAL_BEAN", "豆", "豆", status = "ACTIVE"))
        current.drinkDao().insert(DrinkRecordEntity("record", 1, "2026-10-01", "PERSONAL_BEAN", "bean", actualPriceFen = 777, note = "历史备注", ratingHalfStars = 9, snapshotBrandName = "烘焙商", snapshotItemName = "旧豆", snapshotCoffeeType = "HAND_BREW", revision = 3))
        current.draftDao().upsert(DraftRecordEntity("current", "draft", "PERSONAL_BEAN", "bean", null, 8, 888, "草稿", 1))
        current.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            listOf("catalog_items", "drink_records", "draft_records").forEach { table ->
                val fields = listOf("personalProductType", "purchasePriceFen", "packageWeightMg", "packageCount") + if (table == "catalog_items") emptyList() else listOf("consumedWeightMg", "consumedCount", "priceMode")
                fields.forEach { old.execSQL("ALTER TABLE $table DROP COLUMN $it") }
            }
            old.execSQL("UPDATE room_master_table SET identity_hash='43a8d014125551ba31cfb63ccb7a166d' WHERE id=42")
            old.execSQL("PRAGMA user_version=5")
        }
        val migrated = Room.databaseBuilder(context, CoffeeDatabase::class.java, name).addMigrations(CoffeeDatabase.MIGRATION_5_6).allowMainThreadQueries().build()
        try {
            val record = migrated.drinkDao().get("record")!!
            assertEquals(777L, record.actualPriceFen)
            assertEquals(9, record.ratingHalfStars)
            assertEquals("历史备注", record.note)
            assertEquals(3, record.revision)
            assertEquals("HAND_BREW", record.snapshotCoffeeType)
            assertEquals("MANUAL", record.priceMode)
            assertNull(record.purchasePriceFen)
            assertEquals("BEANS", migrated.catalogItemDao().get("bean")?.personalProductType)
            assertEquals(888L, migrated.draftDao().get("current")?.actualPriceFen)
            migrated.openHelper.writableDatabase.query("PRAGMA user_version").use { it.moveToFirst(); assertEquals(6, it.getInt(0)) }
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
