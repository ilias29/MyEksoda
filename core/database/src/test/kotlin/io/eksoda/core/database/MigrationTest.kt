package io.eksoda.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every schema change since the first release (version 4) keeps the user's data. Each migration
 * starts from a database written with the old exported schema (core/database/schemas), migrates
 * it and checks the result against the new schema.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EksodaDatabase::class.java,
    )

    @Test
    fun `4 to 5 keeps the data and adds budgets and the annual income`() {
        helper.createDatabase(NAME, 4).use { db ->
            db.execSQL(
                """INSERT INTO household (id, name, default_currency, timezone, created_at,
                   updated_at) VALUES ('h1', 'Σπίτι', 'EUR', 'Europe/Athens', 1, 1)""",
            )
            db.execSQL(
                """INSERT INTO category (id, slug, name, is_system, created_at, updated_at)
                   VALUES ('c1', 'groceries', 'Groceries', 1, 1, 1)""",
            )
        }

        helper.runMigrationsAndValidate(NAME, 5, true).use { db ->
            db.query("SELECT name, annual_income_minor FROM household").use { row ->
                assertThat(row.moveToFirst()).isTrue()
                assertThat(row.getString(0)).isEqualTo("Σπίτι")
                assertThat(row.isNull(1)).isTrue()
            }
            assertThat(db.count("category")).isEqualTo(1)

            // A category's budget goes with the category.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(
                """INSERT INTO budget (id, category_id, amount_minor, created_at, updated_at)
                   VALUES ('total', NULL, 50000, 1, 1), ('c1', 'c1', 30000, 1, 1)""",
            )
            db.execSQL("DELETE FROM category WHERE id = 'c1'")
            assertThat(db.count("budget")).isEqualTo(1)
        }
    }

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use {
            it.moveToFirst()
            it.getInt(0)
        }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
