package com.lolog.app.data.mock

import com.lolog.app.data.model.*

/**
 * 시안(lolog-api/index.html)에 나오는 예시 데이터예요(앱 모델로 바로 만들어요).
 * 서버가 붙으면 이 파일은 지워도 돼요.
 */
object MockData {
    const val EMAIL = "minji@lolog.app"

    /** 실시간 자막 흉내용 문장 (시안 05번) */
    val liveSentences = listOf(
        "아니 그래서 어제 저녁에 뭐 먹었는데",
        "카레 했지 근데 좀 심심해서",
        "뭐 넣었어 치즈?",
        "아니 민트초코",
        "카레에? 민트초코를?",
        "누가 그렇게 먹으면 맛있대서 넣었는데",
        "누가 그래?",
        "…내 꿈에서.",
        "아 진짜 미쳤다 ㅋㅋㅋㅋ",
        "근데 맛은 어땠는데",
        "생각보다 괜찮았어 진짜로",
        "거짓말하지 마",
    )

    fun cards(): MutableList<CardDetail> = mutableListOf(
        CardDetail(
            id = 12, kind = CardKind.FUNNY, summary = "카레에 민트초코를 넣은 이유", place = "홍대 카페",
            startMs = 1_200, endMs = 95_000,
            speakers = listOf(Speaker("A", "민지"), Speaker("B", "준호"), Speaker("C", null)),
            lines = listOf(
                Line(1, "A", "카레에? 민트초코를?"),
                Line(2, "B", "누가 그렇게 먹으면 맛있대서 넣었는데"),
                Line(3, "A", "누가 그래?"),
                Line(4, "B", "…내 꿈에서."),
            ),
            comics = listOf(ComicRef(31, ComicStatus.DONE), ComicRef(33, ComicStatus.FAILED)),
            createdAt = "2026-11-04T19:40:00+09:00", recordedAt = "2026-11-04T19:20:00+09:00",
        ),
        CardDetail(
            id = 11, kind = CardKind.MEMORY, summary = "첫 MT 버스에서 부른 노래", place = null,
            startMs = 300_000, endMs = 420_000,
            speakers = listOf(Speaker("A"), Speaker("B"), Speaker("C"), Speaker("D")),
            lines = listOf(
                Line(1, "A", "그때 버스에서 누가 먼저 노래 시작했지?"),
                Line(2, "B", "과대가 마이크 잡았잖아"),
                Line(3, "C", "아 그 노래 아직도 생각나"),
                Line(4, "D", "다 같이 떼창했던 거 진짜 웃겼는데"),
            ),
            comics = listOf(ComicRef(35, ComicStatus.DRAWING)),
            createdAt = "2026-11-02T22:10:00+09:00", recordedAt = "2026-11-02T21:30:00+09:00",
        ),
        CardDetail(
            id = 10, kind = CardKind.ABSURD, summary = "엘리베이터에서 교수님께 \"엄마\"라고 부름", place = "공학관",
            startMs = 12_000, endMs = 80_000,
            speakers = listOf(Speaker("A", "나"), Speaker("B", "서연")),
            lines = listOf(
                Line(1, "B", "그래서 엘리베이터에서 뭐라고 했다고?"),
                // 일부러 긴 대사: 목업에서는 40자가 넘는 대사가 있으면 만화가 실패해요(21번 화면 흐름 확인용)
                Line(2, "A", "문 열리자마자 교수님 보고 너무 반가워서 나도 모르게 엄마 다녀왔어 이랬어 진짜로 크게"),
                Line(3, "B", "교수님 반응은?"),
                Line(4, "A", "\"그래, 밥은 먹었니?\" 하셨어"),
            ),
            comics = listOf(ComicRef(28, ComicStatus.FAILED)),
            createdAt = "2026-11-03T14:05:00+09:00", recordedAt = "2026-11-03T13:40:00+09:00",
        ),
        CardDetail(
            id = 9, kind = CardKind.FUNNY, summary = "고양이가 줌 수업에 출석함", place = "자취방",
            startMs = 50_000, endMs = 120_000,
            speakers = listOf(Speaker("A", "민지"), Speaker("B", null)),
            lines = listOf(
                Line(1, "B", "어제 수업 중에 갑자기 출석 부르는데"),
                Line(2, "B", "고양이가 키보드 밟아서 '네' 쳤잖아"),
                Line(3, "A", "교수님이 뭐라셨어?"),
                Line(4, "B", "\"나비 학생 출석\""),
            ),
            comics = emptyList(),
            createdAt = "2026-11-01T20:00:00+09:00", recordedAt = "2026-11-01T19:45:00+09:00",
        ),
        CardDetail(
            id = 7, kind = CardKind.MEMORY, summary = "택시 기사님의 인생 상담", place = "택시 안",
            startMs = 10_000, endMs = 200_000,
            speakers = listOf(Speaker("A", "나"), Speaker("B", "기사님")),
            lines = listOf(
                Line(1, "B", "학생, 요즘 고민 있어요?"),
                Line(2, "A", "아 그냥 진로가 좀…"),
                Line(3, "B", "나도 그 나이 땐 택시 할 줄 몰랐어"),
                Line(4, "A", "갑자기 마음이 편해졌어요"),
            ),
            // 3번 다 그린 카드 (24번 화면 확인용)
            comics = listOf(ComicRef(22, ComicStatus.DONE), ComicRef(24, ComicStatus.DONE), ComicRef(26, ComicStatus.DONE)),
            createdAt = "2026-10-29T23:30:00+09:00", recordedAt = "2026-10-29T23:10:00+09:00",
        ),
    )

    fun comics(): MutableList<ComicDetail> = mutableListOf(
        ComicDetail(35, 11, ComicStatus.DRAWING, null, "2026-11-05T15:58:00+09:00"),
        ComicDetail(33, 12, ComicStatus.FAILED, null, "2026-11-04T20:10:00+09:00", "말풍선 배치 실패"),
        ComicDetail(31, 12, ComicStatus.DONE, "mock://comic/31", "2026-11-04T19:32:00+09:00", width = 1024, height = 4096),
        ComicDetail(28, 10, ComicStatus.FAILED, null, "2026-11-03T14:20:00+09:00", "대사가 말풍선에 들어가지 않아요"),
        ComicDetail(26, 7, ComicStatus.DONE, "mock://comic/26", "2026-11-01T10:10:00+09:00", width = 1024, height = 4096),
        ComicDetail(24, 7, ComicStatus.DONE, "mock://comic/24", "2026-10-31T09:00:00+09:00", width = 1024, height = 4096),
        ComicDetail(22, 7, ComicStatus.DONE, "mock://comic/22", "2026-10-29T23:50:00+09:00", width = 1024, height = 4096),
    )
}
