package com.lolog.app.notification

import com.lolog.app.data.model.PushPayload
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * FCM으로 받은 푸시를 앱이 켜져 있는 동안 화면에 넘겨요(#67 7번).
 * LologMessagingService가 알림을 띄운 뒤 여기에 넣고, RemoteLologRepository.pushEvents가 이걸 그대로 보여 줘요.
 * 보고 있는 화면이 없으면 버려요(화면을 다시 열면 어차피 새로 받아요).
 */
object PushBus {
    private val _events = MutableSharedFlow<PushPayload>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<PushPayload> = _events

    fun emit(payload: PushPayload) {
        _events.tryEmit(payload)
    }
}
