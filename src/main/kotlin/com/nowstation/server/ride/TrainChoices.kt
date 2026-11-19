package com.nowstation.server.ride

import com.nowstation.server.subway.StationMaps
import com.nowstation.server.subway.TrainStatus
import com.nowstation.server.tracking.TrainKey
import com.nowstation.server.tracking.TrainState
import java.time.Duration
import java.time.Instant

/** 사용자가 "지금 탄 열차"로 고를 수 있는 열차 하나. [stopsFromOrigin]은 출발역에서 몇 정거장 갔는지(-1은 출발역 직전). */
data class TrainChoice(
    val key: TrainKey,
    val stationId: String,
    val stationName: String,
    val status: TrainStatus,
    val terminalName: String?,
    val seenAt: Instant,
    val stopsFromOrigin: Int,
)

/**
 * 폰 움직임으로 탄 열차를 맞히는 대신 사용자가 고르게 한다(걷는 중·늦게 켬에서 자동 판단이 실패: 2026-11-19 실기기).
 * 후보: 출발역 한 정거장 전부터 내릴 역 직전까지, 내릴 역 쪽으로 가는 열차. 출발역에 가까운 순서.
 * - 한 정거장 전까지 넣는 이유: 공공 데이터가 늦어(수인분당선 중간 49초) 방금 탄 열차가 아직 전 역에 있는 것으로 나온다.
 * - 내릴 역이 이 노선에 없으면(갈아타기) 출발역에서 [maxAheadWithoutDestination]정거장까지 양쪽 방향.
 */
object TrainChoices {
    private const val maxAheadWithoutDestination = 3

    fun near(
        maps: StationMaps,
        states: List<TrainState>,
        origin: String,
        destination: String?,
        now: Instant,
        staleAfter: Duration,
    ): List<TrainChoice> = states.mapNotNull { s ->
        if (Duration.between(s.lastSeen, now) > staleAfter) return@mapNotNull null
        val map = maps[s.key.line]
        val heading = map.heading(s.direction) ?: return@mapNotNull null
        val here = map.station(s.stationId) ?: return@mapNotNull null
        val originId = map.stationId(origin) ?: return@mapNotNull null
        val stops = map.stopsTo(originId, heading, here.name, s.terminalStationId)
            ?: if (map.stopsTo(here.id, heading, origin, s.terminalStationId) == 1) -1 else return@mapNotNull null
        val limit = destination?.let { map.stopsTo(originId, heading, it, s.terminalStationId) }
        val onLine = destination != null && map.stationId(destination) != null
        val inRange = when {
            onLine -> limit != null && stops < limit // 그쪽으로 안 가거나 이미 내릴 역이면 아님
            else -> stops <= maxAheadWithoutDestination
        }
        if (!inRange) return@mapNotNull null
        TrainChoice(s.key, here.id, here.name, s.status, map.station(s.terminalStationId)?.name, s.lastSeen, stops)
    }.sortedWith(compareBy({ it.stopsFromOrigin }, { it.key.line.ordinal }, { it.key.trainNo }))
}
