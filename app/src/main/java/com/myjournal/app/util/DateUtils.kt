package com.myjournal.app.util
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
object DateUtils {
    private val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val shortFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val fullFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)
    fun todayIso(): String = LocalDate.now().format(isoFormatter)
    fun formatCardHeader(entryDateIso: String, timeLabel: String): String {
        return try {
            val date = LocalDate.parse(entryDateIso, isoFormatter)
            val today = LocalDate.now()
            val prefix = when (date) {
                today -> "Today \u00B7 ${date.format(shortFormatter)}"
                today.minusDays(1) -> "Yesterday \u00B7 ${date.format(shortFormatter)}"
                else -> date.format(fullFormatter)
            }
            "$prefix \u00B7 $timeLabel"
        } catch (e: Exception) {
            "$entryDateIso \u00B7 $timeLabel"
        }
    }
}