package com.nowstation.server.tracking

import com.nowstation.server.subway.SubwayLine
import com.nowstation.server.subway.TrainPosition
import com.nowstation.server.subway.TrainStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainTrackerTest {
    private val t0: Instant = Instant.parse("2026-11-16T00:00:00Z")
    private fun at(seconds: Long) = t0.plusSeconds(seconds)

    private fun pos(train: String, station: String, status: TrainStatus, line: SubwayLine = SubwayLine.SUIN_BUNDANG) =
        TrainPosition(line, train, station, station, status, "0", "T", "T", null, express = false, lastTrain = false)

    @Test
    fun `처음 보는 열차는 사건 없이 상태만 기록한다`() {
        val tracker = TrainTracker()

        val events = tracker.update(SubwayLine.SUIN_BUNDANG, listOf(pos("1", "태평", TrainStatus.ARRIVED)), at(0))

        assertTrue(events.isEmpty())
        assertEquals("태평", tracker.state(TrainKey(SubwayLine.SUIN_BUNDANG, "1"))?.stationId)
    }

    @Test
    fun `출발 상태가 없는 노선은 다음 역에서 보이는 순간을 이전 역 출발로 본다`() {
        val tracker = TrainTracker()
        tracker.update(SubwayLine.SUIN_BUNDANG, listOf(pos("1", "태평", TrainStatus.ARRIVED)), at(0))

        val events = tracker.update(SubwayLine.SUIN_BUNDANG, listOf(pos("1", "가천대", TrainStatus.ENTERING)), at(20))

        val key = TrainKey(SubwayLine.SUIN_BUNDANG, "1")
        assertEquals(
            listOf(TrainEvent.Departed(key, "태평", at(20), explicit = false, arrivedObservedAt = at(0)), TrainEvent.Arrived(key, "가천대", at(20))),
            events,
        )
    }

    @Test
    fun `같은 역에서 출발 상태가 되면 출발, 이후 다음 역에서는 도착만 낸다`() {
        val tracker = TrainTracker()
        val key = TrainKey(SubwayLine.LINE_2, "2084")
        tracker.update(SubwayLine.LINE_2, listOf(pos("2084", "선릉", TrainStatus.ARRIVED, SubwayLine.LINE_2)), at(0))

        val departed = tracker.update(SubwayLine.LINE_2, listOf(pos("2084", "선릉", TrainStatus.DEPARTED, SubwayLine.LINE_2)), at(20))
        val arrived = tracker.update(SubwayLine.LINE_2, listOf(pos("2084", "역삼", TrainStatus.ENTERING, SubwayLine.LINE_2)), at(40))

        assertEquals(listOf(TrainEvent.Departed(key, "선릉", at(20), explicit = true, arrivedObservedAt = at(0))), departed)
        assertEquals(listOf(TrainEvent.Arrived(key, "역삼", at(40))), arrived)
    }

    @Test
    fun `같은 역에 오래 서 있으면 사건 없이 마지막 관측 시각만 바뀐다`() {
        val tracker = TrainTracker()
        tracker.update(SubwayLine.SUIN_BUNDANG, listOf(pos("1", "한티", TrainStatus.ARRIVED)), at(0))

        val events = (1..15).flatMap { tracker.update(SubwayLine.SUIN_BUNDANG, listOf(pos("1", "한티", TrainStatus.ARRIVED)), at(it * 20L)) }

        assertTrue(events.isEmpty())
        val state = tracker.state(TrainKey(SubwayLine.SUIN_BUNDANG, "1"))!!
        assertEquals(at(0), state.since)
        assertEquals(at(300), state.lastSeen)
    }

    @Test
    fun `응답에서 사라진 지 오래된 열차는 잊는다`() {
        val tracker = TrainTracker()
        tracker.update(SubwayLine.SUIN_BUNDANG, listOf(pos("1", "인천", TrainStatus.ARRIVED)), at(0))

        tracker.update(SubwayLine.SUIN_BUNDANG, emptyList(), at(301))

        assertNull(tracker.state(TrainKey(SubwayLine.SUIN_BUNDANG, "1")))
    }

    @Test
    fun `출발 사건에 그 역에 온 것을 처음 본 시각을 담는다(진입에서 도착으로 바뀌어도 유지)`() {
        val tracker = TrainTracker()
        tracker.update(SubwayLine.SUIN_BUNDANG, listOf(pos("1", "모란", TrainStatus.ARRIVED)), at(0))
        tracker.update(SubwayLine.SUIN_BUNDANG, listOf(pos("1", "태평", TrainStatus.ENTERING)), at(20))
        tracker.update(SubwayLine.SUIN_BUNDANG, listOf(pos("1", "태평", TrainStatus.ARRIVED)), at(40))

        val events = tracker.update(SubwayLine.SUIN_BUNDANG, listOf(pos("1", "가천대", TrainStatus.ENTERING)), at(160))

        val departed = events.filterIsInstance<TrainEvent.Departed>().single()
        assertEquals(at(20), departed.arrivedObservedAt)
        assertEquals(false, departed.explicit)
    }
}
