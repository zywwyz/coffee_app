package com.niumi.coffeejournal.core.database

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoffeeDatabaseMigrationTest {
    @Test fun `migration 1 to 2 backfills record and draft times`() {
        val database = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        ).writableDatabase
        database.apply {
            execSQL("CREATE TABLE drink_records (id TEXT PRIMARY KEY NOT NULL, occurredAtEpochMillis INTEGER NOT NULL)")
            execSQL("CREATE TABLE draft_records (id TEXT PRIMARY KEY NOT NULL, updatedAtEpochMillis INTEGER NOT NULL)")
            execSQL(
                "INSERT INTO drink_records (id,occurredAtEpochMillis) VALUES ('r',123)",
            )
            execSQL(
                "INSERT INTO draft_records (id,updatedAtEpochMillis) VALUES ('current',456)",
            )
        }

        CoffeeDatabase.MIGRATION_1_2.migrate(database)
        database.apply {
            query("SELECT createdAtEpochMillis,updatedAtEpochMillis,revision FROM drink_records").use {
                it.moveToFirst()
                assertEquals(123L, it.getLong(0))
                assertEquals(123L, it.getLong(1))
                assertEquals(0, it.getInt(2))
            }
            query("SELECT consumedAtEpochMillis,editingRecordId,expectedRecordRevision FROM draft_records").use {
                it.moveToFirst()
                assertEquals(456L, it.getLong(0))
                assertEquals(true, it.isNull(1))
                assertEquals(true, it.isNull(2))
            }
            close()
        }
    }
}
