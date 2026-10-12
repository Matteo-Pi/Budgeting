package io.github.matteopi.budgeting.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Receipt
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.repo.toEntity
import io.github.matteopi.budgeting.data.repo.toModel
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Room 1 -> 2 auto-migration (receipts, receipt items, products, product aliases and
 * `transactions.receipt_id`) must keep every existing row. The version-1 schema it migrates from is
 * committed at `app/schemas/.../BudgetDatabase/1.json`; the Room Gradle plugin puts the schema
 * folder into the androidTest assets, which is where [MigrationTestHelper] reads it.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BudgetDatabase::class.java)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun deleteTestDatabase() {
        context.deleteDatabase(DB_NAME)
    }

    private val day = LocalDate.of(2026, 9, 1).toEpochDay()

    /** A small version-1 database: one account, one category, three transactions (one with import and transfer ids). */
    private fun createVersion1Database() {
        val db = helper.createDatabase(DB_NAME, 1)
        db.execSQL(
            "INSERT INTO accounts (id, name, type, overdraft_limit, include_in_pool, archived, sort_order) " +
                "VALUES (1, 'Current', 'CURRENT', 50000, 1, 0, 0)",
        )
        db.execSQL(
            "INSERT INTO categories (id, name, flow, forecast_mode, color_argb, is_system, system_key, " +
                "is_major_income_default, archived, sort_order) " +
                "VALUES (1, 'Groceries', 'OUT', 'LEARNED', NULL, 0, NULL, 0, 0, 0)",
        )
        db.execSQL(
            "INSERT INTO transactions (id, account_id, amount, date, created_at, category_id, label, note, kind, " +
                "transfer_group_id, recurring_rule_id, scheduled_item_id, import_batch_id, import_key) VALUES " +
                "(1, 1, 100000, ${day - 10}, 1000, NULL, 'Opening balance', '', 'OPENING_BALANCE', NULL, NULL, NULL, NULL, NULL)",
        )
        db.execSQL(
            "INSERT INTO transactions (id, account_id, amount, date, created_at, category_id, label, note, kind, " +
                "transfer_group_id, recurring_rule_id, scheduled_item_id, import_batch_id, import_key) VALUES " +
                "(2, 1, -4235, ${day - 1}, 2000, 1, 'Tesco', 'weekly shop', 'NORMAL', NULL, NULL, NULL, NULL, 'key-1')",
        )
        db.execSQL(
            "INSERT INTO transactions (id, account_id, amount, date, created_at, category_id, label, note, kind, " +
                "transfer_group_id, recurring_rule_id, scheduled_item_id, import_batch_id, import_key) VALUES " +
                "(3, 1, -500, $day, 3000, NULL, 'Move', '', 'TRANSFER', 'group-1', NULL, NULL, NULL, NULL)",
        )
        db.close()
    }

    private fun SupportSQLiteDatabase.longs(sql: String): List<Long> = query(sql).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getLong(0))
        }
    }

    private fun SupportSQLiteDatabase.strings(sql: String): List<String> = query(sql).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    @Test
    fun migratingToVersion2KeepsEveryExistingRowAndAddsTheReceiptTables() {
        createVersion1Database()

        val db = helper.runMigrationsAndValidate(DB_NAME, 2, true)

        assertEquals(listOf(1L), db.longs("SELECT id FROM accounts"))
        assertEquals(listOf("Groceries"), db.strings("SELECT name FROM categories"))
        assertEquals(listOf(1L, 2L, 3L), db.longs("SELECT id FROM transactions ORDER BY id"))
        assertEquals(listOf(100_000L, -4_235L, -500L), db.longs("SELECT amount FROM transactions ORDER BY id"))
        assertEquals(
            listOf("Opening balance", "Tesco", "Move"),
            db.strings("SELECT label FROM transactions ORDER BY id"),
        )
        assertEquals(listOf("weekly shop"), db.strings("SELECT note FROM transactions WHERE id = 2"))
        assertEquals(listOf("key-1"), db.strings("SELECT import_key FROM transactions WHERE import_key IS NOT NULL"))
        assertEquals(listOf("group-1"), db.strings("SELECT transfer_group_id FROM transactions WHERE kind = 'TRANSFER'"))
        assertEquals(listOf(1L), db.longs("SELECT category_id FROM transactions WHERE category_id IS NOT NULL"))
        assertEquals(listOf(3L), db.longs("SELECT COUNT(*) FROM transactions"))
        assertEquals(listOf(0L), db.longs("SELECT COUNT(*) FROM transactions WHERE receipt_id IS NOT NULL"), "receipt_id starts NULL")

        val tables = db.strings("SELECT name FROM sqlite_master WHERE type = 'table'")
        for (table in listOf("receipts", "receipt_items", "products", "product_aliases")) {
            assertTrue(table in tables, "the $table table exists; have $tables")
        }
        val indices = db.strings("SELECT name FROM sqlite_master WHERE type = 'index'")
        assertTrue("index_transactions_receipt_id" in indices, "the receipt_id index exists; have $indices")

        // The new tables accept rows, and a transaction can be linked to a receipt.
        db.execSQL("INSERT INTO products (id, name, category_id) VALUES (1, 'Semi-skimmed milk 2 pints', 1)")
        db.execSQL("INSERT INTO product_aliases (id, store, raw_name_key, product_id) VALUES (1, 'TESCO', 'ts semi skmd mlk 2pt', 1)")
        db.execSQL(
            "INSERT INTO receipts (id, store, date, total, source, transaction_id, created_at) " +
                "VALUES (1, 'TESCO', ${day - 1}, 4235, 'PHOTO', 2, 5000)",
        )
        db.execSQL(
            "INSERT INTO receipt_items (id, receipt_id, product_id, raw_name, quantity, weight_grams, unit_price, " +
                "price, discount, category_id) VALUES (1, 1, 1, 'TS SEMI SKMD MLK 2PT', 2, NULL, 150, 300, -50, 1)",
        )
        db.execSQL("UPDATE transactions SET receipt_id = 1 WHERE id = 2")
        assertEquals(listOf(1L), db.longs("SELECT COUNT(*) FROM receipt_items"))
        assertEquals(listOf(2L), db.longs("SELECT id FROM transactions WHERE receipt_id = 1"))
        db.close()
    }

    @Test
    fun theMigratedFileOpensWithTheRealDatabaseClass() = runBlocking<Unit> {
        createVersion1Database()
        helper.runMigrationsAndValidate(DB_NAME, 2, true).close()

        val db = Room.databaseBuilder(context, BudgetDatabase::class.java, DB_NAME).build()
        try {
            val transactions = db.transactionDao().observeAll().first().map { it.toModel() }.sortedBy { it.id }
            assertEquals(listOf(1L, 2L, 3L), transactions.map { it.id })
            assertEquals(listOf(Money(100_000), Money(-4_235), Money(-500)), transactions.map { it.amount })
            assertEquals(listOf(TxKind.OPENING_BALANCE, TxKind.NORMAL, TxKind.TRANSFER), transactions.map { it.kind })
            assertEquals(LocalDate.of(2026, 9, 1), transactions[2].date)
            assertEquals("key-1", transactions[1].importKey)
            assertEquals("group-1", transactions[2].transferGroupId)
            assertTrue(transactions.all { it.receiptId == null })
            assertTrue(db.receiptDao().observeReceipts().first().isEmpty())

            // Writing receipts through the real DAOs works on the migrated file too.
            val receipt = Receipt(
                store = "TESCO", date = LocalDate.of(2026, 8, 31), total = Money(4_235), source = "PHOTO",
                transactionId = 2, createdAtEpochMillis = 5_000,
            )
            val receiptId = db.receiptDao().insertReceipt(receipt.toEntity())
            val shop = checkNotNull(db.transactionDao().get(2))
            db.transactionDao().update(shop.copy(receiptId = receiptId))
            assertEquals(receiptId, db.transactionDao().get(2)?.receiptId)
            assertNull(db.transactionDao().get(1)?.receiptId)
            assertEquals(listOf(receipt.copy(id = receiptId)), db.receiptDao().observeReceipts().first().map { it.toModel() })
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
