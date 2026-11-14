package com.lolog.app.ui

import com.lolog.app.data.model.BadgeKind
import com.lolog.app.data.model.PushType
import com.lolog.app.ui.screens.friends.PROFILE_CHARACTER_SCREEN_PUSHES
import org.junit.Assert.assertEquals
import org.junit.Test

/** 내 캐릭터 화면이 내 목소리 푸시에도 다시 받는지 본다(#136). 같은 새 소식 점(profileCharacter)의 푸시와 같아야 해요 */
class ProfileCharacterPushTest {

    @Test
    fun 내_캐릭터_점에_들어가는_푸시를_모두_듣는다() {
        val sameBadge = PushType.entries.filter { BadgeKind.of(it) == BadgeKind.PROFILE_CHARACTER }.toSet()
        assertEquals(sameBadge, PROFILE_CHARACTER_SCREEN_PUSHES.toSet())
    }
}
