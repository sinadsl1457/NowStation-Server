package com.nowstation.server.ride

import com.nowstation.server.subway.Heading
import com.nowstation.server.subway.StationMaps
import com.nowstation.server.subway.SubwayLine
import com.nowstation.server.subway.TrainStatus
import com.nowstation.server.tracking.TrainKey
import com.nowstation.server.tracking.TrainState
import java.time.Duration
import java.time.Instant

/**
 * 노선도 위 열차 하나. [stopsFromBoard]: 타는 역에서 몇 정거장 갔는지(음수는 아직 타는 역 전 = 들어오는 중).
 * [message]: 승강장 전광판과 같은 말투("[2]번째 전역 (야탑)", "전역 도착", "태평 진입"). [etaMinutes]: 타는 역까지 대략 몇 분(이미 지났으면 null).
 * [atStationSince]: 이 역에 있는 것을 처음 본 시각(앱이 다음 역까지 움직임을 이어 그린다).
 */
data class TrainChoice(
    val key: TrainKey,
    val stationId: String,
    val stationName: String,
    val status: TrainStatus,
    val terminalName: String?,
    val seenAt: Instant,
    val stopsFromBoard: Int,
    val message: String = "",
    val etaMinutes: Int? = null,
    val atStationSince: Instant = seenAt,
)

/** 가로 노선도: 구간 방향으로 늘어선 역들과 그 위의 열차들. */
data class TrainStrip(val leg: Leg, val stations: List<String>, val boardIndex: Int, val trains: List<TrainChoice>)

/**
 * 이번정거장처럼 경로 방향의 노선도에 열차를 올려 고르게 한다(2026-11-19 사용자가 보여 준 화면).
 * - 역: 타는 역 [before]정거장 전부터 구간 끝(환승역·내릴 역)까지. 방향은 경로에서 정해져 반대 방향 열차는 없다.
 * - 열차: 그 방향으로 가는 열차 중 노선도 안에 있는 것.
 * - 골라 둘 열차([suggest]): 타는 역에 서 있거나 가장 가까이 들어오는 열차.
 * - 뺀다: 오래 소식 없는 열차, 급행(타는 역에 서는지 데이터로 알 수 없다: 태평은 급행이 서지 않는데 목록에 섞여 왔다,
 *   서는 역은 [expressStops]에 넣는다), 이번 구간 끝(환승역·내릴 역)까지 가지 않는 열차(종착역이 그 전).
 */
object TrainChoices {
    const val BEFORE = 4

    /** 급행이 서는 역(노선별). 확실한 공개 목록을 아직 못 구해 비워 둔다 → 급행은 모두 뺀다. */
    val expressStops: Map<SubwayLine, Set<String>> = emptyMap()

    /** 정거장 하나 평균 분(RoutePlanner와 같은 값). 전광판의 "약 N분"에 쓴다. */
    fun minutesPerStop(line: SubwayLine) = when (line) {
        SubwayLine.LINE_2 -> 2.0
        SubwayLine.SUIN_BUNDANG -> 2.2
        SubwayLine.SINBUNDANG -> 3.2
    }

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
            if (s.express && leg.from !in expressStops[leg.line].orEmpty()) return@mapNotNull null
            val terminal = map.station(s.terminalStationId)
            // 종착역이 구간 끝보다 앞이면 그 열차로는 못 간다
            if (terminal != null && map.stopsTo(here.id, leg.heading, leg.to, s.terminalStationId)
                    ?.let { toEnd -> (map.stopsTo(here.id, leg.heading, terminal.name, s.terminalStationId) ?: Int.MAX_VALUE) < toEnd } == true
            ) return@mapNotNull null
            val stops = index - behind.size
            TrainChoice(
                s.key, here.id, here.name, s.status, terminal?.name, s.lastSeen, stops,
                message = boardMessage(stops, here.name, s.status, leg.from),
                etaMinutes = if (stops <= 0) Math.round(-stops * minutesPerStop(leg.line)).toInt() else null,
                atStationSince = s.arrivedAt ?: s.since,
            )
        }.sortedBy { it.stopsFromBoard }
        return TrainStrip(leg, stations, behind.size, trains)
    }

    /** 승강장 전광판 말투. 서울시 도착정보와 같은 문구(2026-11-20 태평·선릉 응답으로 확인). */
    fun boardMessage(stopsFromBoard: Int, station: String, status: TrainStatus, board: String): String {
        val k = -stopsFromBoard
        val state = when (status) {
            TrainStatus.ENTERING -> "진입"
            TrainStatus.DEPARTED -> "출발"
            else -> "도착"
        }
        return when {
            k < 0 -> "$board 지나감 ($station)"
            k == 0 -> "$board $state"
            k == 1 -> "전역 $state ($station)"
            else -> "[${k}]번째 전역 ($station)"
        }
    }

    /** 다음에 탈 열차: 타는 역에 있거나(이미 떠난 것으로 보이지 않는) 가장 가까이 들어오는 열차. */
    fun suggest(strip: TrainStrip): TrainChoice? =
        strip.trains.filter { it.stopsFromBoard < 0 || (it.stopsFromBoard == 0 && it.status != TrainStatus.DEPARTED) }
            .maxByOrNull { it.stopsFromBoard }
}
