package com.lolog.app.data.model

/**
 * 카드·만화 목록 정렬 (LOLog-docs#37: GET /api/cards, /api/comics의 sort=latest|oldest).
 * 최신순은 서버 기본값이라 쿼리를 빼고 보내요(query = null).
 */
enum class SortOrder(val query: String?) { NEWEST(null), OLDEST("oldest") }
