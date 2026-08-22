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
    version = 3,
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
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

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
    }
}
