package com.lolog.app.data

import com.lolog.app.data.model.BadgeKind
import com.lolog.app.data.model.PushType
import com.lolog.app.data.model.VoiceClone
import com.lolog.app.data.model.VoiceCloneStatus
import com.lolog.app.data.remote.ApiMapper.toModel
import com.lolog.app.data.remote.LologJson
import com.lolog.app.data.remote.ProfileResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 프로필 응답의 character.voiceClone(LOLog-api #86)을 앱 모델로 바꾸고, 내 목소리 푸시를 맞는 점에 넣는지 본다. */
class VoiceCloneMappingTest {

    private fun clone(characterJson: String) =
        LologJson.decodeFromString(ProfileResponse.serializer(), """{"character": $characterJson}""").character?.voiceClone?.toModel()

    @Test
    fun 서버의_voiceClone을_그대로_읽는다() {
        assertEquals(
            VoiceClone(VoiceCloneStatus.READY, "https://s3/preview.wav", null, true),
            clone("""{"status":"READY","voice":"self","voiceClone":{"status":"READY","previewUrl":"https://s3/preview.wav","failReason":null,"shareWithFriends":true}}"""),
        )
        assertEquals(
            VoiceClone(VoiceCloneStatus.FAILED, null, "VOICE_TOO_QUIET", false),
            clone("""{"status":"READY","voiceClone":{"status":"FAILED","previewUrl":"","failReason":"VOICE_TOO_QUIET","shareWithFriends":false}}"""),
        )
        assertEquals(VoiceCloneStatus.PROCESSING, clone("""{"status":"READY","voiceClone":{"status":"processing"}}""")?.status)
    }

    @Test
    fun 만든_적이_없거나_모르는_상태면_null이다() {
        assertNull(clone("""{"status":"READY","voiceClone":null}"""))
        assertNull(clone("""{"status":"READY"}"""))
        assertNull(clone("""{"status":"READY","voiceClone":{"status":"WHAT"}}"""))
        assertNull(clone("null"))
    }

    @Test
    fun 내_목소리_푸시는_프로필_캐릭터_점이다() {
        assertEquals(BadgeKind.PROFILE_CHARACTER, BadgeKind.of(PushType.VOICE_CLONE_READY))
        assertEquals(BadgeKind.PROFILE_CHARACTER, BadgeKind.of(PushType.VOICE_CLONE_FAILED))
    }
}
