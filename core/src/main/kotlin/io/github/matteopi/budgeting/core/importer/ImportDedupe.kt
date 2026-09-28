package io.github.matteopi.budgeting.core.importer

import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.security.MessageDigest
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class RowStatus { NEW, POSSIBLE_DUPLICATE, ALREADY_IMPORTED }

/** Dedupes freshly-parsed statement rows against each other and against already-saved data. */
object ImportDedupe {

    /**
     * A stable SHA-256 hex key per row: `accountId|date|pence|merchantKey|occurrence`, where
     * occurrence counts identical (date, amount, key) rows earlier in the same file.
     */
    fun importKeys(accountId: Long, rows: List<ParsedRow>): List<String> {
        val seen = mutableMapOf<Triple<String, Long, String>, Int>()
        return rows.map { row ->
            val merchantKey = MerchantKey.normalise(row.description)
            val dedupeKey = Triple(row.date.toString(), row.amount.pence, merchantKey)
            val occurrence = seen.getOrDefault(dedupeKey, 0)
            seen[dedupeKey] = occurrence + 1
            sha256Hex("$accountId|${row.date}|${row.amount.pence}|$merchantKey|$occurrence")
        }
    }

    /**
     * ALREADY_IMPORTED if [keys] already exists on an [existing] transaction; POSSIBLE_DUPLICATE
     * if an [existing] NORMAL, not-yet-imported transaction on the same account has the same
     * amount within +/-2 days (each existing transaction matches at most one row); else NEW.
     */
    fun classify(
        accountId: Long,
        rows: List<ParsedRow>,
        keys: List<String>,
        existing: List<Transaction>,
    ): List<RowStatus> {
        val existingKeys = existing.mapNotNull { it.importKey }.toSet()
        val candidates = existing.filter {
            it.accountId == accountId && it.kind == TxKind.NORMAL && it.importKey == null
        }
        val used = mutableSetOf<Long>()

        return rows.indices.map { i ->
            val row = rows[i]
            val key = keys.getOrNull(i)
            when {
                key != null && key in existingKeys -> RowStatus.ALREADY_IMPORTED
                else -> {
                    val match = candidates.firstOrNull { tx ->
                        tx.id !in used &&
                            tx.amount == row.amount &&
                            abs(ChronoUnit.DAYS.between(tx.date, row.date)) <= 2
                    }
                    if (match != null) {
                        used += match.id
                        RowStatus.POSSIBLE_DUPLICATE
                    } else {
                        RowStatus.NEW
                    }
                }
            }
        }
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
