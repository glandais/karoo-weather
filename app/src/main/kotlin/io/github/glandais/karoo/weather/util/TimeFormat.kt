package io.github.glandais.karoo.weather.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Clock and elapsed-time formatting for the companion app.
 *
 * Pure JVM (`java.time`, available from API 26 and the module's `minSdk` is 26), so every function
 * here is unit-testable without Robolectric. The 24-hour clock is fixed on purpose: the Karoo UI is
 * 24-hour throughout and a mixed 12/24-hour app beside it reads as a bug.
 */
object TimeFormat {

    private const val MINUTE_SEC = 60L
    private const val HOUR_SEC = 3_600L
    private const val DAY_SEC = 86_400L

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("HH", Locale.ROOT)

    /** `14:03`. */
    fun clock(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        CLOCK.format(Instant.ofEpochSecond(epochSec).atZone(zone))

    /** `14` — the column label of the hourly strip. */
    fun hour(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        HOUR.format(Instant.ofEpochSecond(epochSec).atZone(zone))

    /**
     * The argument of `R.string.app_updated_ago` ("Updated %1$s ago").
     *
     * Deliberately a bare quantity with a unit suffix and never a word: the string list in
     * `strings.xml` (PLAN WP0) has no "just now" entry, and "Updated just now ago" would be worse
     * than "Updated <1 min ago". A future clock skew that puts [thenSec] ahead of [nowSec] is
     * clamped rather than rendered as a negative age.
     */
    fun ago(nowSec: Long, thenSec: Long): String {
        val elapsed = (nowSec - thenSec).coerceAtLeast(0L)
        return when {
            elapsed < MINUTE_SEC -> "<1 min"
            elapsed < HOUR_SEC -> "${elapsed / MINUTE_SEC} min"
            elapsed < DAY_SEC -> "${elapsed / HOUR_SEC} h"
            else -> "${elapsed / DAY_SEC} d"
        }
    }

    /** Whole minutes between two epoch-second instants, never negative. */
    fun minutesBetween(fromSec: Long, toSec: Long): Long =
        ((toSec - fromSec).coerceAtLeast(0L)) / MINUTE_SEC
}
