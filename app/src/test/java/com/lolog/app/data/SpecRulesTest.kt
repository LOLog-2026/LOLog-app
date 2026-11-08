package com.lolog.app.data

import com.lolog.app.data.model.CardEdit
import com.lolog.app.data.model.LineEdit
import com.lolog.app.data.model.isValidPassword
import com.lolog.app.data.remote.ApiMapper
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 명세(api-dto)의 입력 규칙을 앱이 그대로 따르는지 본다. */
class SpecRulesTest {

    @Test
    fun 비밀번호는_영문과_숫자를_넣고_72바이트를_넘지_않는다() {
        assertTrue(isValidPassword("abcd1234"))
        // 한글은 영문으로 치지 않는다
        assertFalse(isValidPassword("가나다라1234"))
        assertFalse(isValidPassword("abcdefgh"))
        assertFalse(isValidPassword("12345678"))
        // 64자 안이어도 한글이 섞이면 72바이트를 넘을 수 있다(한 글자 3바이트)
        assertFalse(isValidPassword("a1" + "가".repeat(24)))
        assertTrue(isValidPassword("a1" + "가".repeat(23)))
    }

    @Test
    fun 화자만_바꾼_대사는_text를_보내지_않는다() {
        val body = with(ApiMapper) {
            CardEdit(lines = listOf(LineEdit(id = 3, speaker = "화자2"), LineEdit(id = 5, text = "고친 대사"))).patchBody()
        }
        val lines = body["lines"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("id", "speaker"), lines[0].keys)
        assertEquals(setOf("id", "text"), lines[1].keys)
    }
}
