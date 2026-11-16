package com.nowstation.server.subway

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PollPacingTest {
    private val base = Duration.ofSeconds(15)
    private val hours = { h: Long -> Duration.ofHours(h) }

    @Test
    fun `넉넉하면 기본 간격 그대로`() {
        assertEquals(base, PollPacing.interval(base, remaining = 600, lines = 2, untilMidnight = hours(10)))
    }

    @Test
    fun `얼마 안 남으면 남은 호출로 자정까지 버티게 늘린다`() {
        // 2시간 = 7200초, 노선 1개, 남은 200건 → 36초마다
        assertEquals(Duration.ofSeconds(36), PollPacing.interval(base, remaining = 200, lines = 1, untilMidnight = hours(2)))
    }

    @Test
    fun `아무리 모자라도 1분보다 늘리지 않는다(그보다 늦으면 따라갈 수 없다)`() {
        assertEquals(Duration.ofSeconds(60), PollPacing.interval(base, remaining = 50, lines = 2, untilMidnight = hours(10)))
    }

    @Test
    fun `자정이 가까우면 모자라도 기본 간격`() {
        assertEquals(base, PollPacing.interval(base, remaining = 100, lines = 1, untilMidnight = Duration.ofMinutes(10)))
    }

    @Test
    fun `다 썼으면 오늘은 부르지 않는다`() {
        assertNull(PollPacing.interval(base, remaining = 0, lines = 1, untilMidnight = hours(3)))
    }
}
