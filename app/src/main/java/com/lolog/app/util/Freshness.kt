package com.lolog.app.util

import java.time.Duration
import java.time.OffsetDateTime

/** 만든 지 24시간 안이면 NEW (API에 "읽음" 표시가 없어서 앱이 시간으로 정해요) */
fun isNew(createdAt: String?): Boolean {
    val t = createdAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return false
    return Duration.between(t, OffsetDateTime.now()).toHours() < 24
}
