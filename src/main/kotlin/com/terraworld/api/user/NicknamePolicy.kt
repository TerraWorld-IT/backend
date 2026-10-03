package com.terraworld.api.user

import java.text.Normalizer

/**
 * 닉네임 금칙어 정책 (App Store 가이드라인 1.2 — 사용자 생성 콘텐츠 필터링).
 *
 * 프론트 Nuxt 서버(가입 시 검사)와 같은 목록·정규화 규칙을 쓴다. 한쪽을 바꾸면 반드시 다른 쪽도 맞춘다.
 *
 * 정규화: NFKC → 소문자화 → (영문/한글 음절/자모) 이외 문자와 한글 채움 문자 제거 → 다시 NFKC.
 *  - 공백·숫자·특수문자·이모지·제로폭 문자가 사이에 끼워진 변형("시1발", "ㅅ.ㅂ", "F u c k")을 같은 단어로 본다.
 *  - 분해 자모 사이에 구분자를 넣은 입력("ᄉ.ᅵ.ᄇ.ᅡ.ᆯ")은 구분자를 지운 뒤 두 번째 NFKC 로 음절("시발")로 재조합한다.
 *  - 한글 채움 문자(U+115F, U+1160, U+3164, U+FFA0 은 NFKC 후 U+1160)는 폭이 없어 보이는 구분자이므로 제거한다("시ㅤ발").
 *  - 추가로 leet 치환(0→o, 1→i, 3→e, 4→a, 5→s, @→a, $→s)을 적용한 형태도 함께 검사한다("sh1t", "b1tch").
 *
 * 판정: [SUBSTRING_WORDS] 는 정규화 결과에 부분 일치하면 거부,
 * [EXACT_WORDS] 는 정규화 결과 전체가 그 단어일 때만 거부한다(오탐이 많은 짧은 단어).
 */
object NicknamePolicy {
    /** 한글 채움 문자 — 글자처럼 보이지 않으면서 자모 사이를 끊는 구분자로 쓰인다. */
    private val HANGUL_FILLERS: Set<Char> = setOf('\u115F', '\u1160', '\u3164', '\uFFA0')

    // 주의: 아래 금칙어 목록이 초기화 시점에 normalize 를 호출하므로 normalize 가 쓰는 상수는 목록보다 먼저 선언해야 한다.

    /** 정규화 후 부분 일치로 막는 단어. */
    private val SUBSTRING_WORDS: List<String> =
        listOf(
            // 한국어 욕설·비하
            "시발",
            "씨발",
            "씨빨",
            "ㅅㅂ",
            "ㅆㅂ",
            "병신",
            "ㅄ",
            "ㅂㅅ",
            "개새끼",
            "개새",
            "좆",
            "존나",
            "지랄",
            "미친놈",
            "미친년",
            "꺼져",
            "닥쳐",
            "엿먹",
            "느금마",
            "니애미",
            // 한국어 성적 표현
            "섹스",
            "야동",
            "걸레",
            "창녀",
            // 영어
            "fuck",
            "shit",
            "bitch",
            "asshole",
            "pussy",
            "cunt",
            "nigger",
            "faggot",
            "porn",
            // 운영 사칭
            "운영자",
            "관리자",
            "admin",
            "terraworld운영",
            "테라월드운영",
        ).map { normalize(it) }.onEach { require(it.isNotEmpty()) { "금칙어가 정규화 후 비어 있다" } }

    /**
     * 정규화 결과 전체가 일치할 때만 막는 단어 — 부분 일치로 막으면 정상 닉네임이 걸리는 짧은 단어.
     *  - 시바: "시바견"(견종)  - 새끼: "새끼고양이"  - 졸라: "졸라맨"
     *  - 애미/애비: 이름·외래어("애비뉴")  - 보지/자지: "보지마"·"안자지" 등 일상어
     *  - sex/dick: "Essex"·"Sussex"·"Dickens"
     */
    private val EXACT_WORDS: Set<String> =
        setOf(
            "시바",
            "새끼",
            "졸라",
            "애미",
            "애비",
            "보지",
            "자지",
            "sex",
            "dick",
        ).map { normalize(it) }.onEach { require(it.isNotEmpty()) { "금칙어가 정규화 후 비어 있다" } }.toSet()

    /** leet 치환 표 — 숫자 제거만으로는 놓치는 영문 변형용. */
    private val LEET: Map<Char, Char> =
        mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '@' to 'a', '$' to 's')

    /** 닉네임이 금칙어(욕설·비하·성적 표현·운영자 사칭)에 걸리면 true. */
    fun isForbidden(nickname: String): Boolean =
        candidates(nickname).any { form ->
            form.isNotEmpty() && (form in EXACT_WORDS || SUBSTRING_WORDS.any { form.contains(it) })
        }

    /** 검사 대상 형태: ① 숫자·특수문자 제거형 ② leet 치환 후 제거형. */
    private fun candidates(nickname: String): Set<String> {
        val folded = Normalizer.normalize(nickname, Normalizer.Form.NFKC).lowercase()
        val leet = folded.map { LEET[it] ?: it }.joinToString("")
        return setOf(strip(folded), strip(leet))
    }

    private fun normalize(word: String): String = strip(Normalizer.normalize(word, Normalizer.Form.NFKC).lowercase())

    /**
     * 영문 소문자·한글 음절·자모(NFKC 가 호환 자모를 조합형 자모로 바꾸므로 U+1100 대도 포함)만 남기고,
     * 구분자가 빠져 이어 붙은 분해 자모를 NFKC 로 음절로 재조합한다.
     */
    private fun strip(text: String): String {
        val kept =
            buildString {
                for (c in text) {
                    if (c in HANGUL_FILLERS) continue
                    if (c in 'a'..'z' || c in '가'..'힣' || c in 'ㄱ'..'ㆎ' || c in 'ᄀ'..'ᇿ') append(c)
                }
            }
        return Normalizer.normalize(kept, Normalizer.Form.NFKC)
    }
}
