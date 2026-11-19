package com.nowstation.server.ride

import com.nowstation.server.subway.StationMaps
import com.nowstation.server.subway.SubwayLine.SUIN_BUNDANG
import com.nowstation.server.subway.TrainStatus
import com.nowstation.server.subway.TrainStatus.ARRIVED
import com.nowstation.server.subway.TrainStatus.DEPARTED
import com.nowstation.server.subway.TrainStatus.ENTERING
import com.nowstation.server.tracking.TrainKey
import com.nowstation.server.tracking.TrainState
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** 실제 수인분당선 역 순서 위에서. 태평 → 강남은 수인분당 왕십리 방면(updnLine 0)으로 선릉까지. */
class TrainChoicesTest {
    private val maps = StationMaps.load()
    private val now: Instant = Instant.parse("2026-11-19T09:50:00Z")
    private val map = maps[SUIN_BUNDANG]
    private val leg = RoutePlanner.plan(maps, "태평", "강남")!!.legs.first()

    private fun train(no: String, station: String, status: TrainStatus = ARRIVED, updn: String = "0", terminal: String = "왕십리", seenAgo: Long = 10) =
        TrainState(TrainKey(SUIN_BUNDANG, no), map.stationId(station)!!, status, updn, map.stationId(terminal)!!, now, now.minusSeconds(seenAgo))

    private fun strip(vararg trains: TrainState) = TrainChoices.strip(maps, trains.toList(), leg, now, Duration.ofMinutes(2))

    @Test
    fun `노선도는 타는 역 네 정거장 전부터 환승역 직전까지, 경로 방향 순서`() {
        val s = strip()
        assertEquals(listOf("서현", "이매", "야탑", "모란", "태평", "가천대", "복정", "수서"), s.stations.take(8))
        assertEquals("선릉", s.stations.last())
        assertEquals(4, s.boardIndex)
    }

    @Test
    fun `경로 방향 열차만, 타는 역에서 가까운 순으로 올린다(반대 방향·오래된 소식·노선도 밖은 뺀다)`() {
        val s = strip(
            train("6208", "이매", ENTERING),
            train("6206", "모란"),
            train("6204", "복정"),
            train("OPP", "가천대", updn = "1", terminal = "인천"),
            train("STALE", "가천대", seenAgo = 300),
            train("FAR", "정자"),
        )
        assertEquals(listOf("6208" to -3, "6206" to -1, "6204" to 2), s.trains.map { it.key.trainNo to it.stopsFromBoard })
    }

    @Test
    fun `골라 둘 열차는 타는 역에 서 있거나 가장 가까이 들어오는 열차`() {
        assertEquals("6206", TrainChoices.suggest(strip(train("6208", "이매"), train("6206", "모란"), train("6204", "복정")))?.key?.trainNo)
        assertEquals("AT", TrainChoices.suggest(strip(train("AT", "태평"), train("6206", "모란")))?.key?.trainNo)
        assertEquals("6206", TrainChoices.suggest(strip(train("GONE", "태평", DEPARTED), train("6206", "모란")))?.key?.trainNo, "이미 떠난 열차는 고르지 않는다")
    }
}
