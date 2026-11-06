package com.lolog.app.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 토큰 보관. 지금은 SharedPreferences에 그대로 넣어요.
 * 출시 전에는 EncryptedSharedPreferences나 DataStore + Keystore로 바꾸는 게 좋아요.
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("lolog_auth", Context.MODE_PRIVATE)

    private val _loggedIn = MutableStateFlow(prefs.getString(KEY_ACCESS, null) != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn

    val accessToken: String? get() = prefs.getString(KEY_ACCESS, null)
    val refreshToken: String? get() = prefs.getString(KEY_REFRESH, null)
    var fcmToken: String?
        get() = prefs.getString(KEY_FCM, null)
        set(value) { prefs.edit().putString(KEY_FCM, value).apply() }

    fun save(access: String, refresh: String) {
        prefs.edit().putString(KEY_ACCESS, access).putString(KEY_REFRESH, refresh).apply()
        _loggedIn.value = true
    }

    fun clear() {
        prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).apply()
        _loggedIn.value = false
    }

    /** 토큰을 새로 받지 못해 로그아웃됐을 때 true → "다시 로그인해 주세요" 창 (로그인 ⑦) */
    private val _expired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _expired

    /** refresh까지 실패: 토큰을 지우고 안내 창을 띄우게 해요 */
    fun expire() {
        clear()
        _expired.value = true
    }

    fun expiredShown() { _expired.value = false }

    private companion object {
        const val KEY_ACCESS = "access"
        const val KEY_REFRESH = "refresh"
        const val KEY_FCM = "fcm"
    }
}
