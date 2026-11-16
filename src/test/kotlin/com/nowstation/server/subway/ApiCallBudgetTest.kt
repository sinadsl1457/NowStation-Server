package com.nowstation.server.subway

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiCallBudgetTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        override fun instant() = now
    }

    @Test
    fun `한도에서 여유분을 뺀 만큼만 쓸 수 있다`() {
        val budget = ApiCallBudget(Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC), dailyLimit = 10, reserve = 2)

        val allowed = (1..10).count { budget.tryUse() }

        assertEquals(8, allowed)
        assertFalse(budget.tryUse())
    }

    @Test
    fun `서울 날짜가 바뀌면 다시 센다`() {
        // 2026-11-16 23:50 KST
        val clock = MutableClock(Instant.parse("2026-11-16T14:50:00Z"))
        val budget = ApiCallBudget(clock, dailyLimit = 3, reserve = 0)
        repeat(3) { budget.tryUse() }
        assertFalse(budget.tryUse())

        clock.now = Instant.parse("2026-11-16T15:01:00Z") // 11-17 00:01 KST

        assertTrue(budget.tryUse())
        assertEquals(1, budget.usedToday())
    }

    @Test
    fun `남은 횟수와 서울 자정까지 남은 시간`() {
        // 2026-11-16 22:00 KST
        val budget = ApiCallBudget(Clock.fixed(Instant.parse("2026-11-16T13:00:00Z"), ZoneOffset.UTC), dailyLimit = 10, reserve = 2)
        repeat(3) { budget.tryUse() }

        assertEquals(5, budget.remaining())
        assertEquals(java.time.Duration.ofHours(2), budget.untilReset())
    }
}
