package com.lolog.app.data.mock

import com.lolog.app.data.model.*
import com.lolog.app.data.repo.LologException

/**
 * 화자 그림 목업(LOLog-docs spec/characters.md). 규칙은 MockLologRepository 위 주석에 적어 뒀어요.
 * 안드로이드 없이 테스트하려고 따로 떼었어요.
 */
internal object MockCharacters {

    /** 명세 레포의 공개 썸네일. 목업에서만 써요(실서버는 10분 서명 주소) */
    fun thumb(id: String) = "https://raw.githubusercontent.com/LOLog-2026/LOLog-docs/main/assets/default-characters/${id}_thumb.webp"

    /** label은 명세 레포 assets/default-characters/characters.json 값 그대로 */
    val defaults: List<DefaultCharacter> = listOf(
        DefaultCharacter("kid-f-01", "분홍 머리띠 긴 생머리 소녀", AgeGroup.CHILD, Gender.F, false, thumb("kid-f-01")),
        DefaultCharacter("kid-f-02", "동그란 안경 양갈래 소녀", AgeGroup.CHILD, Gender.F, true, thumb("kid-f-02")),
        DefaultCharacter("kid-f-03", "앞머리 단발 소녀", AgeGroup.CHILD, Gender.F, false, thumb("kid-f-03")),
        DefaultCharacter("kid-f-04", "보라 안경 곱슬 포니테일 소녀", AgeGroup.CHILD, Gender.F, true, thumb("kid-f-04")),
        DefaultCharacter("kid-m-01", "까까머리 소년", AgeGroup.CHILD, Gender.M, false, thumb("kid-m-01")),
        DefaultCharacter("kid-m-02", "동그란 안경 바가지 머리 소년", AgeGroup.CHILD, Gender.M, true, thumb("kid-m-02")),
        DefaultCharacter("kid-m-03", "야구모자 소년", AgeGroup.CHILD, Gender.M, false, thumb("kid-m-03")),
        DefaultCharacter("kid-m-04", "사각 안경 가르마 소년", AgeGroup.CHILD, Gender.M, true, thumb("kid-m-04")),
        DefaultCharacter("young-f-01", "긴 웨이브 머리 청년 여성", AgeGroup.YOUNG, Gender.F, false, thumb("young-f-01")),
        DefaultCharacter("young-f-02", "금테 안경 단발 청년 여성", AgeGroup.YOUNG, Gender.F, true, thumb("young-f-02")),
        DefaultCharacter("young-f-03", "포니테일 청재킷 청년 여성", AgeGroup.YOUNG, Gender.F, false, thumb("young-f-03")),
        DefaultCharacter("young-f-04", "뿔테 안경 애쉬 머리 청년 여성", AgeGroup.YOUNG, Gender.F, true, thumb("young-f-04")),
        DefaultCharacter("young-m-01", "투블럭 청년 남성", AgeGroup.YOUNG, Gender.M, false, thumb("young-m-01")),
        DefaultCharacter("young-m-02", "뿔테 안경 곱슬 파마 청년 남성", AgeGroup.YOUNG, Gender.M, true, thumb("young-m-02")),
        DefaultCharacter("young-m-03", "헤어밴드 운동복 청년 남성", AgeGroup.YOUNG, Gender.M, false, thumb("young-m-03")),
        DefaultCharacter("young-m-04", "사각 안경 가르마 청년 남성", AgeGroup.YOUNG, Gender.M, true, thumb("young-m-04")),
        DefaultCharacter("middle-f-01", "파마머리 중년 여성", AgeGroup.MIDDLE, Gender.F, false, thumb("middle-f-01")),
        DefaultCharacter("middle-f-02", "돋보기 안경 회색 단발 중년 여성", AgeGroup.MIDDLE, Gender.F, true, thumb("middle-f-02")),
        DefaultCharacter("middle-f-03", "앞치마 쪽머리 중년 여성", AgeGroup.MIDDLE, Gender.F, false, thumb("middle-f-03")),
        DefaultCharacter("middle-f-04", "빨간 안경 정장 중년 여성", AgeGroup.MIDDLE, Gender.F, true, thumb("middle-f-04")),
        DefaultCharacter("middle-m-01", "콧수염 중년 남성", AgeGroup.MIDDLE, Gender.M, false, thumb("middle-m-01")),
        DefaultCharacter("middle-m-02", "금테 안경 회색 머리 중년 남성", AgeGroup.MIDDLE, Gender.M, true, thumb("middle-m-02")),
        DefaultCharacter("middle-m-03", "민머리 등산복 중년 남성", AgeGroup.MIDDLE, Gender.M, false, thumb("middle-m-03")),
        DefaultCharacter("middle-m-04", "뿔테 안경 넥타이 중년 남성", AgeGroup.MIDDLE, Gender.M, true, thumb("middle-m-04")),
    )

    /** 기본 화자를 화자 그림으로 (처음부터 그림이 붙은 목업 카드용) */
    fun defaultRef(id: String): SpeakerCharacter {
        val d = defaults.first { it.id == id }
        return SpeakerCharacter(CharacterType.DEFAULT, d.id, d.label, d.thumbnailUrl)
    }

    /**
     * 붙이려는 그림을 서버처럼 확인하고 label·thumbnailUrl을 채워요.
     *  - 목록에 없는 기본 id, 나도 친구도 아닌 회원 ID → UNKNOWN_CHARACTER
     *  - 그림이 없는 친구·나 → CHARACTER_NOT_READY
     */
    fun resolve(ref: SpeakerCharacter, friends: List<Friend>, me: Profile): SpeakerCharacter = when (ref.type) {
        CharacterType.DEFAULT -> defaults.firstOrNull { it.id == ref.id }
            ?.let { ref.copy(label = it.label, thumbnailUrl = it.thumbnailUrl) }
            ?: unknown()
        CharacterType.PROFILE -> {
            val (label, pic) = when {
                me.userId != null && ref.id == me.userId -> me.nickname to me.character?.thumbnailUrl
                else -> friends.firstOrNull { it.userId.toString() == ref.id }?.let { it.nickname.ifBlank { null } to it.thumbnailUrl }
                    ?: unknown()
            }
            if (pic == null) throw LologException(ErrorCodes.CHARACTER_NOT_READY, "아직 쓸 수 있는 그림이 없어요")
            ref.copy(label = label, thumbnailUrl = pic)
        }
    }

    /**
     * 카드 PATCH의 화자 고치기를 서버처럼 덮어써요. 이름(edit.speakers)과 그림(edit.characters)은 따로 바꿔요.
     * 다 바꾼 뒤 한 카드에서 같은 그림(type+id)이 두 화자에 붙어 있으면 DUPLICATE_CHARACTER
     */
    fun applySpeakers(speakers: List<Speaker>, edit: CardEdit, friends: List<Friend>, me: Profile): List<Speaker> {
        val names = edit.speakers?.associateBy { it.key }.orEmpty()
        val pics = edit.characters.orEmpty().mapValues { (_, ref) -> ref?.let { resolve(it, friends, me) } }
        val updated = speakers.map { old ->
            val named = names[old.key]?.let { old.copy(name = it.name) } ?: old
            if (old.key in pics) named.copy(character = pics[old.key]) else named
        }
        val refs = updated.mapNotNull { it.character }.map { it.type to it.id }
        if (refs.size != refs.toSet().size) throw LologException(ErrorCodes.DUPLICATE_CHARACTER, "같은 그림을 두 화자에 붙일 수 없어요")
        return updated
    }

    private fun unknown(): Nothing = throw LologException(ErrorCodes.UNKNOWN_CHARACTER, "없는 캐릭터예요")
}
