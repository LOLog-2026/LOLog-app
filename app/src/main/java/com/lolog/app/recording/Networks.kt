package com.lolog.app.recording

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** 음성 올리기에서 보는 연결 종류(명세 audio.md 1장 "모바일 데이터 창") */
object Networks {
    /** Wi-Fi(또는 유선)로 연결돼 있으면 true. 이때만 "Wi-Fi에서 올리기" 음성을 올린다. */
    fun onWifi(context: Context): Boolean {
        val caps = caps(context) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** 모바일 데이터로 연결돼 있으면 true. 음성 저장을 켠 채 녹음을 시작할 때 창을 띄울지 정한다. */
    fun onMobileData(context: Context): Boolean {
        val caps = caps(context) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !onWifi(context)
    }

    private fun caps(context: Context): NetworkCapabilities? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull()
    }
}
