package com.terraworld.api.user

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NicknamePolicy 금칙어 판정 — 변형 우회는 거부하고 일반 닉네임·알려진 오탐 사례는 통과시킨다.
 */
class NicknamePolicyTest {
    @Test
    fun `금칙어 원형은 거부`() {
        listOf(
            "시발",
            "씨발",
            "씨빨",
            "ㅅㅂ",
            "ㅆㅂ",
            "병신",
            "ㅄ",
            "ㅂㅅ",
            "개새끼",
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
            "섹스",
            "야동",
            "걸레",
            "창녀",
            "fuck",
            "shit",
            "bitch",
            "asshole",
            "pussy",
            "cunt",
            "nigger",
            "faggot",
            "porn",
            "운영자",
            "관리자",
            "admin",
            "terraworld운영",
            "테라월드운영",
        ).forEach { assertTrue(NicknamePolicy.isForbidden(it), "거부되어야 함: $it") }
    }

    @Test
    fun `공백·숫자·특수문자 삽입 변형은 거부`() {
        listOf(
            "시 발",
            "시1발",
            "시.발",
            "씨_발",
            "시!@#발",
            "병1신",
            "병 신",
            "ㅅ ㅂ",
            "ㅅ.ㅂ",
            "개 새 끼",
            "시\u200B발", // 제로폭 공백
            "ＦＵＣＫ", // 전각 영문
            "F u c k",
            "f.u.c.k",
            "Sh1t",
            "b1tch",
            "a\$\$hole",
            "FUCK123",
            "관 리 자",
            "관리자1",
            "Admin",
            "ADMIN",
            "Admin_01",
            "테라월드 운영",
            "TerraWorld 운영팀",
            "착한시발",
            "테라시발이", // 앞뒤에 다른 글자가 붙어도 부분 일치
        ).forEach { assertTrue(NicknamePolicy.isForbidden(it), "거부되어야 함: $it") }
    }

    @Test
    fun `분해 자모 사이에 구분자를 넣어도 재조합되어 거부`() {
        listOf(
            "\u1109.\u1175.\u1107.\u1161.\u11AF", // 분해 자모 + 구분자 삽입
            "\u1109 \u1175 \u1107 \u1161 \u11AF",
            "\u1109\u1175\u1107\u1161\u11AF", // 구분자 없는 분해형
            "\u1109\u1175\u11071\u1161\u11AF",
        ).forEach { assertTrue(NicknamePolicy.isForbidden(it), "거부되어야 함: ${it.map { c -> "U+%04X".format(c.code) }}") }
    }

    @Test
    fun `한글 채움 문자와 폭 없는 문자를 끼워도 거부`() {
        listOf(
            "시\u3164발", // 한글 채움 문자(U+3164)
            "시\u1160발", // 한글 중성 채움 문자(U+1160)
            "시\u115F발", // 한글 초성 채움 문자(U+115F)
            "시\uFFA0발", // 반각 한글 채움 문자(U+FFA0)
            "시\u200B발",
            "시\u200C발",
            "시\u200D발",
            "시\uFEFF발",
            "ㅅ\u3164ㅂ",
        ).forEach { assertTrue(NicknamePolicy.isForbidden(it), "거부되어야 함: ${it.map { c -> "U+%04X".format(c.code) }}") }
    }

    @Test
    fun `채움 문자만 있는 입력은 금칙어가 아니다`() {
        assertFalse(NicknamePolicy.isForbidden("\u3164\u1160\u115F\u200B"))
        assertFalse(NicknamePolicy.isForbidden("초\u3164록\u200B이"))
    }

    @Test
    fun `대소문자 섞어도 거부`() {
        listOf("FuCk", "SHIT", "BiTcH", "PoRn").forEach {
            assertTrue(NicknamePolicy.isForbidden(it), "거부되어야 함: $it")
        }
    }

    @Test
    fun `일반 닉네임은 통과`() {
        listOf(
            "테라",
            "초록이",
            "김철수",
            "Tera 22",
            "tera_22",
            "선인장🌵",
            "🌱새싹🌱",
            "🌵",
            "Terra",
            "푸른숲",
            "민트초코",
            "행복한토끼",
            "123",
            "a",
            "이끼정원사",
            "Luna",
            "Moss",
        ).forEach { assertFalse(NicknamePolicy.isForbidden(it), "통과해야 함: $it") }
    }

    @Test
    fun `오탐 판단 — 정상 단어가 들어간 닉네임은 통과`() {
        listOf(
            "시바견", // 견종 (시바는 단독일 때만 거부)
            "시바견 보리",
            "새끼고양이", // 새끼는 단독일 때만 거부
            "새끼손가락",
            "졸라맨", // 졸라는 단독일 때만 거부
            "2020년",
            "년", // 년은 목록 제외(숫자 제거 시 단독 판정 불가·오탐 다수). 욕설 용법은 미친년 등 복합어로 차단
            "새해복많이받으세요",
            "애비뉴", // 애미/애비는 단독일 때만 거부
            "보지마세요", // 보지/자지는 단독일 때만 거부
            "Essex",
            "Sussex",
            "Dickens", // sex/dick 은 단독일 때만 거부
            "Tera 22",
        ).forEach { assertFalse(NicknamePolicy.isForbidden(it), "통과해야 함: $it") }
    }

    @Test
    fun `오탐 방지용 짧은 단어도 단독으로는 거부`() {
        listOf("시바", "시 바", "새끼", "졸라", "애미", "애비", "보지", "자지", "sex", "S3X", "dick")
            .forEach { assertTrue(NicknamePolicy.isForbidden(it), "거부되어야 함: $it") }
    }

    @Test
    fun `빈 문자열·기호만 있는 입력은 금칙어가 아니다`() {
        assertFalse(NicknamePolicy.isForbidden(""))
        assertFalse(NicknamePolicy.isForbidden("   "))
        assertFalse(NicknamePolicy.isForbidden("!!!"))
    }
}
