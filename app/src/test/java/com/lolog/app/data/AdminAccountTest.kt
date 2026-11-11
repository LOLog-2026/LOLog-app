package com.lolog.app.data

import com.lolog.app.data.mock.mockCheckLogin
import com.lolog.app.data.mock.mockIsAdmin
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.remote.ApiMapper.toModel
import com.lolog.app.data.remote.LologJson
import com.lolog.app.data.remote.MeDto
import com.lolog.app.data.repo.LologException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 관리자 구분(role)과 정지된 계정 로그인(docs PR #59 admin.md 1·2장 초안) */
class AdminAccountTest {

    private fun me(json: String) = LologJson.decodeFromString(MeDto.serializer(), json).toModel()

    private fun errorCode(block: () -> Unit): String? = try {
        block(); null
    } catch (e: LologException) {
        e.code
    }

    @Test
    fun role이_ADMIN이면_관리자() {
        assertTrue(me("""{"email":"admin@lolog.app","role":"ADMIN"}""").isAdmin)
    }

    @Test
    fun role이_USER이거나_없으면_일반_회원() {
        assertFalse(me("""{"email":"minji@lolog.app","role":"USER"}""").isAdmin)
        // 서버가 아직 role을 안 보내도 일반 회원으로 봐서 아무것도 안 바뀐다
        assertFalse(me("""{"email":"minji@lolog.app"}""").isAdmin)
    }

    @Test
    fun 목업은_admin_이메일만_관리자() {
        assertTrue(mockIsAdmin("admin@lolog.app"))
        assertTrue(mockIsAdmin("Admin@LOLog.app"))
        assertFalse(mockIsAdmin("minji@lolog.app"))
    }

    @Test
    fun 목업_정지_이메일은_ACCOUNT_SUSPENDED() {
        assertEquals(ErrorCodes.ACCOUNT_SUSPENDED, errorCode { mockCheckLogin("suspended@lolog.app", "abcd1234") })
    }

    @Test
    fun 목업_정지_이메일도_비밀번호가_틀리면_INVALID_CREDENTIALS() {
        assertEquals(ErrorCodes.INVALID_CREDENTIALS, errorCode { mockCheckLogin("suspended@lolog.app", "abc") })
    }

    @Test
    fun 목업_보통_이메일은_그대로_로그인() {
        assertEquals(null, errorCode { mockCheckLogin("minji@lolog.app", "abcd1234") })
        assertEquals(null, errorCode { mockCheckLogin("admin@lolog.app", "abcd1234") })
    }
}
