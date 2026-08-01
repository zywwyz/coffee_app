package com.niumi.coffeejournal.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BrandEntity::class,
        CatalogItemEntity::class,
        DrinkRecordEntity::class,
        ImageAssetEntity::class,
        CatalogUpdateEntity::class,
        DraftRecordEntity::class,
    ],
    version = 1,
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
            ).build()
    }
}
