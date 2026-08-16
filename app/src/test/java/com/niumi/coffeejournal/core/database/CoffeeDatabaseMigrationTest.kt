package com.niumi.coffeejournal.core.database

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoffeeDatabaseMigrationTest {
    @Test fun `migration 2 to 3 classifies legacy chain products and leaves personal beans unset`() {
        val database = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null).callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        ).writableDatabase
        database.apply {
            execSQL("CREATE TABLE catalog_items (id TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, category TEXT)")
            listOf(
                arrayOf("fruit", "CHAIN_PRODUCT", "柠檬气泡美式", "鲜果咖啡"),
                arrayOf("milk", "CHAIN_PRODUCT", "生椰拿铁", "拿铁"),
                arrayOf("black", "CHAIN_PRODUCT", "冰美式", "美式"),
                arrayOf("pending", "CHAIN_PRODUCT", "季节限定", null),
                arrayOf("bean", "PERSONAL_BEAN", "个人豆", "拿铁"),
            ).forEach { row -> execSQL("INSERT INTO catalog_items (id,type,name,category) VALUES (?,?,?,?)", row) }
        }

        CoffeeDatabase.MIGRATION_2_3.migrate(database)

        database.apply {
            query("PRAGMA user_version").use { it.moveToFirst(); assertEquals(3, it.getInt(0)) }
            query("SELECT id,chainProductKind FROM catalog_items ORDER BY id").use { cursor ->
                val kinds = mutableMapOf<String, String?>()
                while (cursor.moveToNext()) kinds[cursor.getString(0)] = if (cursor.isNull(1)) null else cursor.getString(1)
                assertEquals("FRUIT", kinds["fruit"])
                assertEquals("MILK", kinds["milk"])
                assertEquals("BLACK", kinds["black"])
                assertEquals("PENDING", kinds["pending"])
                assertNull(kinds["bean"])
            }
            close()
        }
    }

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
