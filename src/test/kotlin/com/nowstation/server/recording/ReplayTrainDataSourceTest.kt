package com.nowstation.server.recording

import com.nowstation.server.subway.RawPositions
import com.nowstation.server.subway.RealtimePositionParser
import com.nowstation.server.subway.SubwayLine.LINE_2
import com.nowstation.server.subway.SubwayLine.SUIN_BUNDANG
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayTrainDataSourceTest {
    private val t0 = Instant.parse("2026-11-16T00:40:00Z")
    private val responses = listOf(
        RawPositions(LINE_2, "{}", t0),
        RawPositions(SUIN_BUNDANG, "{}", t0.plusSeconds(5)),
        RawPositions(LINE_2, "{}", t0.plusSeconds(20)),
    )

    private class Movable(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = now
    }

    @Test
    fun `지금까지 녹화된 그 노선의 가장 최근 응답을 녹화 시각 그대로 주고, 같은 응답은 다시 주지 않는다`() {
        val clock = Movable(t0.minusSeconds(1))
        val source = ReplayTrainDataSource(responses, RealtimePositionParser(), clock)

        assertNull(source.fetch(LINE_2), "녹화 시작 전")
        clock.now = t0.plusSeconds(10)
        assertEquals(t0, source.fetch(LINE_2)?.first)
        assertNull(source.fetch(LINE_2), "새 응답 없음")
        assertEquals(t0.plusSeconds(5), source.fetch(SUIN_BUNDANG)?.first)
        clock.now = t0.plusSeconds(25)
        assertEquals(t0.plusSeconds(20), source.fetch(LINE_2)?.first)
    }

    @Test
    fun `재생 시계는 지금을 시작 시각으로 해서 실제 속도로 흐른다`() {
        val base = Movable(Instant.parse("2026-11-17T05:00:00Z"))
        val clock = ReplayProperties(enabled = true, start = OffsetDateTime.parse("2026-11-16T09:42:00+09:00")).clock(base)

        assertEquals(Instant.parse("2026-11-16T00:42:00Z"), clock.instant())
        base.now = base.now.plusSeconds(30)
        assertEquals(Instant.parse("2026-11-16T00:42:30Z"), clock.instant())
        assertTrue(ReplayProperties().directory.toFile().isDirectory, "기본 녹화 폴더가 저장소에 있다")
    }
}
