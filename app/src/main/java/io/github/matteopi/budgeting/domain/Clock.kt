package io.github.matteopi.budgeting.domain

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The current date/time. Everything that needs "today" goes through this, not `LocalDate.now()`. */
class Clock {
    fun today(): LocalDate = LocalDate.now()

    fun nowMillis(): Long = System.currentTimeMillis()

    /** Emits the current date immediately, then again just after each local midnight. */
    val todayFlow: Flow<LocalDate> = flow {
        while (true) {
            val now = LocalDate.now()
            emit(now)
            val nextMidnight = now.plusDays(1).atStartOfDay(ZoneId.systemDefault())
            val delayMillis = nextMidnight.toInstant().toEpochMilli() - System.currentTimeMillis()
            delay(delayMillis.coerceAtLeast(1_000L))
        }
    }
}
