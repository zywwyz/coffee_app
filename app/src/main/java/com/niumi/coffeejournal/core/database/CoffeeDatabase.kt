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
    version = 2,
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
            ).addMigrations(MIGRATION_1_2).build()

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
    }
}
