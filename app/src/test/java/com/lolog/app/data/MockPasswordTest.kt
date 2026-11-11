package com.lolog.app.data

import com.lolog.app.data.mock.MockCodes
import com.lolog.app.data.mock.mockCheckPasswordChange
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.repo.LologException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** 목업의 비밀번호 찾기·바꾸기 규칙(가입 인증번호와 같은 규칙)이 화면 확인용으로 그대로 도는지 본다. */
class MockPasswordTest {

    private fun errorCode(block: () -> Unit): String? = try {
        block(); null
    } catch (e: LologException) {
        e.code
    }

    private fun assertError(code: String, block: () -> Unit) = assertEquals(code, errorCode(block))

    @Test
    fun 번호가_맞으면_통과한다() {
        val codes = MockCodes()
        codes.send("minji@lolog.app")
        // 대문자로 와도 같은 이메일로 본다
        assertEquals(null, errorCode { codes.check("Minji@LOLog.app", "123456") })
    }

    @Test
    fun 다른_번호는_INVALID_CODE() {
        val codes = MockCodes()
        codes.send("minji@lolog.app")
        assertError(ErrorCodes.INVALID_CODE) { codes.check("minji@lolog.app", "482931") }
    }

    @Test
    fun 번호_000000이나_5번_틀리면_CODE_EXPIRED() {
        val codes = MockCodes()
        codes.send("minji@lolog.app")
        assertError(ErrorCodes.CODE_EXPIRED) { codes.check("minji@lolog.app", "000000") }

        val other = MockCodes()
        other.send("minji@lolog.app")
        repeat(4) { assertError(ErrorCodes.INVALID_CODE) { other.check("minji@lolog.app", "111111") } }
        assertError(ErrorCodes.CODE_EXPIRED) { other.check("minji@lolog.app", "111111") }
        // 만료되면 맞는 번호도 못 쓴다
        assertError(ErrorCodes.CODE_EXPIRED) { other.check("minji@lolog.app", "123456") }
    }

    @Test
    fun 번호를_안_받았으면_CODE_EXPIRED() {
        assertError(ErrorCodes.CODE_EXPIRED) { MockCodes().check("minji@lolog.app", "123456") }
    }

    @Test
    fun 번호를_60초_안에_다시_받으면_TOO_MANY_REQUESTS() {
        val codes = MockCodes()
        codes.send("minji@lolog.app")
        assertError(ErrorCodes.TOO_MANY_REQUESTS) { codes.send("minji@lolog.app") }
    }

    @Test
    fun 가입_번호와_찾기_번호는_따로_센다() {
        val signup = MockCodes()
        val reset = MockCodes()
        signup.send("minji@lolog.app")
        // 가입 번호를 받았어도 찾기 번호는 바로 받을 수 있다
        assertEquals(null, errorCode { reset.send("minji@lolog.app") })
    }

    @Test
    fun 바꾸기는_지금_비밀번호가_맞으면_성공한다() {
        assertEquals(null, errorCode { mockCheckPasswordChange("abcd1234", "newpass12") })
    }

    @Test
    fun 바꾸기에서_지금_비밀번호가_4자_미만이면_INVALID_CREDENTIALS() {
        assertError(ErrorCodes.INVALID_CREDENTIALS) { mockCheckPasswordChange("abc", "newpass12") }
    }

    @Test
    fun 바꾸기에서_새_비밀번호가_규칙에_안_맞으면_INVALID_REQUEST() {
        try {
            mockCheckPasswordChange("abcd1234", "short")
            fail("INVALID_REQUEST가 나와야 해요")
        } catch (e: LologException) {
            assertEquals(ErrorCodes.INVALID_REQUEST, e.code)
            assertEquals("newPassword", e.field)
        }
    }
}
