package com.nowstation.server.ride

import com.nowstation.server.subway.Heading
import com.nowstation.server.subway.StationMaps
import com.nowstation.server.subway.TrainStatus
import com.nowstation.server.tracking.TrainKey
import com.nowstation.server.tracking.TrainState
import java.time.Duration
import java.time.Instant

/** 노선도 위 열차 하나. [stopsFromBoard]: 타는 역에서 몇 정거장 갔는지(음수는 아직 타는 역 전 = 들어오는 중). */
data class TrainChoice(
    val key: TrainKey,
    val stationId: String,
    val stationName: String,
    val status: TrainStatus,
    val terminalName: String?,
    val seenAt: Instant,
    val stopsFromBoard: Int,
)

/** 가로 노선도: 구간 방향으로 늘어선 역들과 그 위의 열차들. */
data class TrainStrip(val leg: Leg, val stations: List<String>, val boardIndex: Int, val trains: List<TrainChoice>)

/**
 * 이번정거장처럼 경로 방향의 노선도에 열차를 올려 고르게 한다(2026-11-19 사용자가 보여 준 화면).
 * - 역: 타는 역 [before]정거장 전부터 구간 끝(환승역·내릴 역)까지. 방향은 경로에서 정해져 반대 방향 열차는 없다.
 * - 열차: 그 방향으로 가는 열차 중 노선도 안에 있는 것.
 * - 골라 둘 열차([suggest]): 타는 역에 서 있거나 가장 가까이 들어오는 열차.
 */
object TrainChoices {
    const val BEFORE = 4

    fun strip(maps: StationMaps, states: List<TrainState>, leg: Leg, now: Instant, staleAfter: Duration): TrainStrip {
        val map = maps[leg.line]
        val boardId = map.stationId(leg.from)!!
        val opposite = if (leg.heading == Heading.ASCENDING) Heading.DESCENDING else Heading.ASCENDING
        val behind = generateSequence(map.station(boardId)) { map.next(it.id, opposite) }.drop(1).take(BEFORE).map { it.name }.toList().reversed()
        val ahead = generateSequence(map.station(boardId)) { s -> if (s.name == leg.to) null else map.next(s.id, leg.heading) }.map { it.name }.toList()
        val stations = behind + ahead
        val trains = states.mapNotNull { s ->
            if (s.key.line != leg.line || Duration.between(s.lastSeen, now) > staleAfter) return@mapNotNull null
            if (map.heading(s.direction) != leg.heading) return@mapNotNull null
            val here = map.station(s.stationId) ?: return@mapNotNull null
            val index = stations.indexOf(here.name)
            if (index < 0 || here.name == leg.to) return@mapNotNull null
            TrainChoice(s.key, here.id, here.name, s.status, map.station(s.terminalStationId)?.name, s.lastSeen, index - behind.size)
        }.sortedBy { it.stopsFromBoard }
        return TrainStrip(leg, stations, behind.size, trains)
    }

    /** 다음에 탈 열차: 타는 역에 있거나(이미 떠난 것으로 보이지 않는) 가장 가까이 들어오는 열차. */
    fun suggest(strip: TrainStrip): TrainChoice? =
        strip.trains.filter { it.stopsFromBoard < 0 || (it.stopsFromBoard == 0 && it.status != TrainStatus.DEPARTED) }
            .maxByOrNull { it.stopsFromBoard }
}
