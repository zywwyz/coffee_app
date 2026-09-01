package com.niumi.coffeejournal.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BrandEntity::class,
        CatalogItemEntity::class,
        DrinkRecordEntity::class,
        ImageAssetEntity::class,
        CatalogUpdateEntity::class,
        DraftRecordEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class CoffeeDatabase : RoomDatabase() {
    abstract fun brandDao(): BrandDao

    abstract fun catalogItemDao(): CatalogItemDao

    abstract fun drinkDao(): DrinkDao

    abstract fun imageAssetDao(): ImageAssetDao

    abstract fun catalogUpdateDao(): CatalogUpdateDao

    abstract fun draftDao(): DraftDao

    companion object {
        private const val DATABASE_NAME = "coffee_journal.db"

        fun create(context: Context): CoffeeDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                CoffeeDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE drink_records ADD COLUMN createdAtEpochMillis INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE drink_records ADD COLUMN updatedAtEpochMillis INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE drink_records ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE drink_records SET createdAtEpochMillis=occurredAtEpochMillis, updatedAtEpochMillis=occurredAtEpochMillis")
                database.execSQL("ALTER TABLE draft_records ADD COLUMN consumedAtEpochMillis INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE draft_records ADD COLUMN editingRecordId TEXT")
                database.execSQL("ALTER TABLE draft_records ADD COLUMN expectedRecordRevision INTEGER")
                database.execSQL("UPDATE draft_records SET consumedAtEpochMillis=updatedAtEpochMillis")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE catalog_items ADD COLUMN chainProductKind TEXT")
                database.execSQL("""
                    UPDATE catalog_items SET chainProductKind = CASE
                      WHEN type = 'PERSONAL_BEAN' THEN NULL
                      WHEN name || ' ' || COALESCE(category, '') LIKE '%果%' OR name || ' ' || COALESCE(category, '') LIKE '%柠檬%' OR name || ' ' || COALESCE(category, '') LIKE '%橙%' OR name || ' ' || COALESCE(category, '') LIKE '%葡萄%' OR name || ' ' || COALESCE(category, '') LIKE '%莓%' OR name || ' ' || COALESCE(category, '') LIKE '%桃%' OR name || ' ' || COALESCE(category, '') LIKE '%气泡%' THEN 'FRUIT'
                      WHEN lower(name || ' ' || COALESCE(category, '')) LIKE '%拿铁%' OR lower(name || ' ' || COALESCE(category, '')) LIKE '%澳白%' OR lower(name || ' ' || COALESCE(category, '')) LIKE '%卡布%' OR lower(name || ' ' || COALESCE(category, '')) LIKE '%dirty%' OR lower(name || ' ' || COALESCE(category, '')) LIKE '%奶%' OR lower(name || ' ' || COALESCE(category, '')) LIKE '%乳%' THEN 'MILK'
                      WHEN name || ' ' || COALESCE(category, '') LIKE '%黑咖%' OR name || ' ' || COALESCE(category, '') LIKE '%美式%' OR name || ' ' || COALESCE(category, '') LIKE '%浓缩%' OR name || ' ' || COALESCE(category, '') LIKE '%冷萃%' OR name || ' ' || COALESCE(category, '') LIKE '%手冲%' THEN 'BLACK'
                      ELSE 'PENDING' END
                """.trimIndent())
                database.execSQL("PRAGMA user_version = 3")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE drink_records ADD COLUMN snapshotCoffeeType TEXT NOT NULL DEFAULT 'BLACK'")
                database.execSQL("""
                    UPDATE drink_records SET snapshotCoffeeType = CASE
                      WHEN itemType = 'PERSONAL_BEAN' THEN 'HAND_BREW'
                      WHEN itemType = 'CHAIN_PRODUCT' AND (
                        SELECT chainProductKind FROM catalog_items WHERE id = drink_records.sourceItemId
                      ) IN ('BLACK', 'FRUIT', 'MILK') THEN (
                        SELECT chainProductKind FROM catalog_items WHERE id = drink_records.sourceItemId
                      )
                      WHEN snapshotItemName LIKE '%果%' OR snapshotItemName LIKE '%柠檬%' OR snapshotItemName LIKE '%橙%' OR snapshotItemName LIKE '%葡萄%' OR snapshotItemName LIKE '%莓%' OR snapshotItemName LIKE '%桃%' OR snapshotItemName LIKE '%气泡%' THEN 'FRUIT'
                      WHEN lower(snapshotItemName) LIKE '%拿铁%' OR lower(snapshotItemName) LIKE '%澳白%' OR lower(snapshotItemName) LIKE '%卡布%' OR lower(snapshotItemName) LIKE '%dirty%' OR lower(snapshotItemName) LIKE '%奶%' OR lower(snapshotItemName) LIKE '%乳%' THEN 'MILK'
                      WHEN snapshotItemName LIKE '%黑咖%' OR snapshotItemName LIKE '%美式%' OR snapshotItemName LIKE '%浓缩%' OR snapshotItemName LIKE '%冷萃%' OR snapshotItemName LIKE '%手冲%' THEN 'BLACK'
                      ELSE 'BLACK' END
                """.trimIndent())
                database.execSQL("PRAGMA user_version = 4")
            }
        }
    }
}
