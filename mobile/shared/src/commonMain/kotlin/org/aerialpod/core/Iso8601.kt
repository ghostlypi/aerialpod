package org.aerialpod.core

/**
 * The timestamp format gpodder.net episode actions carry.
 *
 * `"%Y-%m-%dT%H:%M:%S"` in UTC, with no zone suffix — exactly what the
 * desktop's `datetime.now(timezone.utc).strftime(...)` produces. The server
 * treats an unsuffixed timestamp as UTC, so writing a local one here would
 * shift every action by the device's offset and quietly reorder the
 * last-writer-wins comparisons on the other end.
 *
 * Hand-rolled rather than taken from a date library: this needs one format in
 * one calendar, and the civil-from-days conversion is exact and testable,
 * where a library dependency would be a moving target across three platforms.
 */
fun iso8601Utc(epochSeconds: Long): String {
    val days = epochSeconds.floorDiv(86_400L)
    val secondOfDay = epochSeconds.mod(86_400L)

    // Howard Hinnant's civil_from_days: shift the epoch to 0000-03-01 so leap
    // days land at the end of the cycle and the month arithmetic stays integral.
    val z = days + 719_468L
    val era = (if (z >= 0) z else z - 146_096L) / 146_097L
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val mp = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * mp + 2L) / 5L + 1L
    val month = mp + if (mp < 10L) 3L else -9L
    val year = yearOfEra + era * 400L + if (month <= 2L) 1L else 0L

    val hour = secondOfDay / 3_600L
    val minute = (secondOfDay % 3_600L) / 60L
    val second = secondOfDay % 60L

    return buildString {
        append(pad(year, 4)); append('-')
        append(pad(month, 2)); append('-')
        append(pad(day, 2)); append('T')
        append(pad(hour, 2)); append(':')
        append(pad(minute, 2)); append(':')
        append(pad(second, 2))
    }
}

private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')

/**
 * Read a gpodder.net action timestamp back to epoch seconds.
 *
 * Deliberately matches the desktop's `_iso_to_epoch`, which parses the civil
 * fields and then *forces* UTC — any offset in the string is discarded rather
 * than applied. gpodder.net sends bare or `Z`-suffixed UTC timestamps, so the
 * two readings only diverge on input the server does not produce; where they
 * would, both installs must still agree, because this value becomes
 * `position_updated_at` and decides every last-writer-wins comparison.
 *
 * Returns 0 for anything unparseable — the desktop's `except ValueError: return 0`
 * — which reads as "infinitely old" and so loses to whatever is stored locally.
 */
fun parseIso8601Utc(text: String): Long {
    val trimmed = text.trim().removeSuffix("Z")
    if (trimmed.length < 19 || trimmed[4] != '-' || trimmed[7] != '-') return 0
    if (trimmed[10] != 'T' && trimmed[10] != ' ') return 0
    if (trimmed[13] != ':' || trimmed[16] != ':') return 0

    val year = trimmed.substring(0, 4).toLongOrNull() ?: return 0
    val month = trimmed.substring(5, 7).toLongOrNull() ?: return 0
    val day = trimmed.substring(8, 10).toLongOrNull() ?: return 0
    val hour = trimmed.substring(11, 13).toLongOrNull() ?: return 0
    val minute = trimmed.substring(14, 16).toLongOrNull() ?: return 0
    val second = trimmed.substring(17, 19).toLongOrNull() ?: return 0
    if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return 0

    return daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second
}

/** The inverse of the conversion in [iso8601Utc]. */
internal fun daysFromCivil(year: Long, month: Long, day: Long): Long {
    val y = year - if (month <= 2) 1 else 0
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}
