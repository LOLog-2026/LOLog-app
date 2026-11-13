package com.lolog.app.util

import android.content.Context

/**
 * 글로 만들기 "직접 쓰기"의 임시 저장(폰에만, SharedPreferences "text_draft").
 * 쓰는 중에는 0.5초 뒤 저장하고, 화면을 다시 열면 불러와요. 보내면 지우고, 만들지 못했으면 다시 넣어요
 */
object TextDraft {
    private const val PREFS = "text_draft"
    private const val TITLE = "title"
    private const val BODY = "body"
    private const val PLACE = "place"
    private const val SITUATION = "situation"

    data class Draft(val title: String = "", val body: String = "", val place: String = "", val situation: String = "") {
        val isEmpty: Boolean get() = title.isEmpty() && body.isEmpty() && place.isEmpty() && situation.isEmpty()
    }

    fun load(context: Context): Draft {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Draft(
            p.getString(TITLE, null).orEmpty(), p.getString(BODY, null).orEmpty(),
            p.getString(PLACE, null).orEmpty(), p.getString(SITUATION, null).orEmpty(),
        )
    }

    /** 다 비었으면 저장 대신 지워요 */
    fun save(context: Context, draft: Draft) {
        if (draft.isEmpty) return clear(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(TITLE, draft.title).putString(BODY, draft.body)
            .putString(PLACE, draft.place).putString(SITUATION, draft.situation)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
