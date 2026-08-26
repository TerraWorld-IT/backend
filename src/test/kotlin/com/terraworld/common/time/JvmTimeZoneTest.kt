package com.terraworld.common.time

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone

class JvmTimeZoneTest {
    private lateinit var original: TimeZone

    @BeforeEach
    fun saveDefault() {
        original = TimeZone.getDefault()
    }

    @AfterEach
    fun restoreDefault() {
        TimeZone.setDefault(original)
    }

    @Test
    fun `KST 로 시작한 JVM 이라도 pinUtc 이후 기본 시간대는 UTC`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))

        val previous = JvmTimeZone.pinUtc()

        assertEquals("Asia/Seoul", previous.id)
        assertEquals("UTC", TimeZone.getDefault().id)
    }

    @Test
    fun `pinUtc 이후 무인자 now 는 UTC wall-clock 과 같다`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        JvmTimeZone.pinUtc()

        val noArg = LocalDateTime.now()
        val utc = LocalDateTime.now(ZoneOffset.UTC)

        // 두 호출 사이 지연은 1초 미만 — 9시간 차이가 나면 기본 시간대가 KST 로 남은 것이다.
        val gapSeconds =
            Duration
                .between(noArg, utc)
                .abs()
                .seconds
        assertEquals(0L, gapSeconds / 60, "무인자 now() 와 UTC now() 가 분 단위로 달라서는 안 된다")
    }
}
