package com.nowstation.server.ride

import com.nowstation.server.subway.StationMaps
import com.nowstation.server.subway.SubwayLine.SUIN_BUNDANG
import com.nowstation.server.subway.TrainStatus
import com.nowstation.server.subway.TrainStatus.ARRIVED
import com.nowstation.server.subway.TrainStatus.ENTERING
import com.nowstation.server.tracking.TrainKey
import com.nowstation.server.tracking.TrainState
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** 실제 수인분당선 역 순서 위에서. 태평 → 왕십리 방면(updnLine 0)은 모란 → 태평 → 가천대 → 복정 → 수서 … 순서. */
class TrainChoicesTest {
    private val maps = StationMaps.load()
    private val now: Instant = Instant.parse("2026-11-19T09:50:00Z")
    private val map = maps[SUIN_BUNDANG]

    private fun train(no: String, station: String, status: TrainStatus = ARRIVED, updn: String = "0", terminal: String = "왕십리", seenAgo: Long = 10) =
        TrainState(TrainKey(SUIN_BUNDANG, no), map.stationId(station)!!, status, updn, map.stationId(terminal)!!, now, now.minusSeconds(seenAgo))

    private fun near(vararg trains: TrainState, origin: String = "태평", destination: String? = "왕십리") =
        TrainChoices.near(maps, trains.toList(), origin, destination, now, Duration.ofMinutes(2)).map { it.key.trainNo to it.stopsFromOrigin }

    @Test
    fun `출발역 한 정거장 전부터 내릴 역 직전까지, 출발역에 가까운 순서`() {
        val result = near(
            train("AHEAD", "복정"),
            train("AT", "태평"),
            train("BEFORE", "모란", ENTERING), // 데이터가 늦어 방금 탄 열차가 아직 전 역에 보임
            train("FAR_BEFORE", "야탑"),
            train("AT_DEST", "왕십리"),
        )
        assertEquals(listOf("BEFORE" to -1, "AT" to 0, "AHEAD" to 2), result)
    }

    @Test
    fun `반대 방향 열차와 오래 소식 없는 열차는 뺀다`() {
        val result = near(
            train("OPPOSITE", "가천대", updn = "1", terminal = "인천"),
            train("STALE", "가천대", seenAgo = 300),
            train("OK", "가천대"),
        )
        assertEquals(listOf("OK" to 1), result)
    }

    @Test
    fun `늦게 켜서 출발역에서 멀리 간 열차도 내릴 역 전이면 후보`() {
        assertEquals(listOf("MINE" to 5), near(train("MINE", "개포동"), origin = "태평"))
    }

    @Test
    fun `내릴 역이 다른 노선이면 출발역에서 3정거장까지 양쪽 방향`() {
        val result = near(
            train("UP3", "수서"),
            train("UP4", "대모산입구"),
            train("DOWN1", "모란", updn = "1", terminal = "인천"),
            destination = "강남",
        )
        assertEquals(listOf("DOWN1" to 1, "UP3" to 3), result)
    }
}
