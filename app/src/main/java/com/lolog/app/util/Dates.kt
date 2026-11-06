package com.lolog.app.util

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val zone: ZoneId get() = ZoneId.systemDefault()
private val dayFmt = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)
private val timeFmt = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)

private fun parse(iso: String?): OffsetDateTime? =
    iso?.takeIf { it.isNotBlank() }?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

/** 목록용: "오늘" · "어제" · "11월 3일" */
fun shortDate(iso: String?): String {
    val t = parse(iso)?.atZoneSameInstant(zone)?.toLocalDate() ?: return ""
    val today = LocalDate.now(zone)
    return when (t) {
        today -> "오늘"
        today.minusDays(1) -> "어제"
        else -> t.format(dayFmt)
    }
}

/** 상세용: "11월 4일 오후 7:20" */
fun longDate(iso: String?): String {
    val t = parse(iso)?.atZoneSameInstant(zone) ?: return ""
    return "${t.format(dayFmt)} ${t.format(timeFmt)}"
}

/** "오후 7:32" */
fun timeOnly(iso: String?): String = parse(iso)?.atZoneSameInstant(zone)?.format(timeFmt) ?: ""

/** 01:14:32 */
fun clock(totalSec: Long): String = "%02d:%02d:%02d".format(totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)

/** "1시간 14분" */
fun durationLabel(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h > 0 -> "${h}시간 ${m}분"
        m > 0 -> "${m}분"
        else -> "${totalSec}초"
    }
}

/** "11월 4일" */
fun dayLabel(iso: String?): String = parse(iso)?.atZoneSameInstant(zone)?.format(dayFmt) ?: ""
