package com.lolog.app.util

import android.content.Context
import java.time.Duration
import java.time.OffsetDateTime

/** 만든 지 24시간 안이면 NEW (API에 "읽음" 표시가 없어서 앱이 시간으로 정해요) */
fun isNew(createdAt: String?): Boolean {
    val t = createdAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return false
    return Duration.between(t, OffsetDateTime.now()).toHours() < 24
}

/**
 * "새로!" 기준(11/10 확정): 사용자가 마지막으로 에피소드 탭을 연 때보다 나중에 만든 카드.
 * 그 시간은 폰에만 저장해요(SharedPreferences). 한 번도 안 열었으면 24시간 기준([isNew])을 써요.
 */
object EpisodeSeen {
    private const val PREFS = "lolog_seen"
    private const val KEY = "episodes_opened_at"

    /** 마지막으로 에피소드 탭을 연 시간(epoch ms). 없으면 null */
    fun lastOpened(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, -1L).takeIf { it > 0 }

    /** 에피소드 탭을 열 때 불러요. 저장 전 값을 돌려줘서 이번 화면의 "새로!" 기준으로 써요 */
    fun open(context: Context): Long? {
        val before = lastOpened(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, System.currentTimeMillis()).apply()
        return before
    }

    /** [since] 뒤에 만든 카드면 true. [since]가 없으면 24시간 기준 */
    fun isNewSince(createdAt: String?, since: Long?): Boolean {
        if (since == null) return isNew(createdAt)
        val t = createdAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return false
        return t.toInstant().toEpochMilli() > since
    }
}
