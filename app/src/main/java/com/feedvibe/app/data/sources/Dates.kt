package com.feedvibe.app.data.sources

import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Dates {
    private val rfc822Patterns = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss zzz",
        "dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm Z",
        "EEE, dd MMM yyyy HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    )

    fun parse(value: String?): Long? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return OffsetDateTime.parse(v).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        for (p in rfc822Patterns) {
            runCatching {
                return SimpleDateFormat(p, Locale.ENGLISH).apply { isLenient = true }.parse(v)?.time
            }
        }
        return null
    }

    /** "1:02:03", "62:03", "3723" -> segundos. */
    fun parseDuration(value: String?): Long {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return 0
        val parts = v.split(':').map { it.trim().toDoubleOrNull() ?: return 0 }
        return parts.fold(0.0) { acc, p -> acc * 60 + p }.toLong()
    }
}
