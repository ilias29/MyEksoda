package io.eksoda.core.ui.text

import androidx.compose.runtime.Composable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** [dayLabel] and the time, in the phone's timezone: "Today, 14:20", "11 Nov, 09:05". */
@Composable
fun dayTimeLabel(instant: Instant): String {
    val zone = ZoneId.systemDefault()
    val time = instant.atZone(zone)
    val day = dayLabel(time.toLocalDate(), LocalDate.now(zone))
    return "$day, ${time.format(dateFormatter("HH:mm", appLocale()))}"
}
