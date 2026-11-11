package com.lolog.app.ui

import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.repo.LologException
import com.lolog.app.ui.components.errorText
import com.lolog.app.ui.social.socialErrorText
import org.junit.Assert.assertEquals
import org.junit.Test

/** 정지된 계정 로그인 문구(시안 3, docs PR #59 admin.md "2. 계정 정지"). 기한·이유는 넣지 않는다 */
class SuspendedLoginTest {

    private val suspended = LologException(ErrorCodes.ACCOUNT_SUSPENDED, "suspended until 2026-12-11")

    @Test
    fun 정지된_계정은_정해진_문구만_보여_준다() {
        assertEquals("정지된 계정이에요.", errorText(suspended))
    }

    @Test
    fun 소셜_로그인도_같은_문구() {
        assertEquals("정지된 계정이에요.", socialErrorText(suspended))
    }
}
