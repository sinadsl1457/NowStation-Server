package com.nowstation.server.ride

import com.nowstation.server.subway.Heading
import com.nowstation.server.subway.StationMaps
import com.nowstation.server.subway.SubwayLine
import java.util.PriorityQueue

/** 경로의 한 구간: [line]을 [heading] 쪽으로 [from]에서 [to]까지(환승역 또는 내릴 역). [stations]는 지나는 역 이름(양 끝 포함). */
data class Leg(val line: SubwayLine, val from: String, val to: String, val heading: Heading, val stops: Int, val stations: List<String> = listOf(from, to))

data class Route(val legs: List<Leg>) {
    fun legOn(line: SubwayLine): Leg? = legs.firstOrNull { it.line == line }
}

/**
 * 지도 앱 "지하철만" 첫 결과처럼 경로를 하나로 정한다. 방향이 경로에서 나오므로 반대 방향 열차를 보여 줄 일이 없다
 * (2026-11-19: 내릴 역이 다른 노선이면 양쪽 방향을 다 보여 줘서 태평 → 강남에 모란·이매 열차가 섞였다).
 * 비용 = 정거장마다 노선별 평균 시간 + 갈아타기. 태평 → 강남은 선릉 환승(이번정거장과 같음)이 정자 환승보다 짧다.
 */
object RoutePlanner {
    /** 정거장 하나 평균 분(신분당선은 역 사이가 길다: 정자 → 강남 5정거장 약 16분). */
    private fun minutesPerStop(line: SubwayLine) = when (line) {
        SubwayLine.LINE_2 -> 2.0
        SubwayLine.SUIN_BUNDANG -> 2.2
        SubwayLine.SINBUNDANG -> 3.2
    }
    private const val TRANSFER_MINUTES = 5.0

    private data class Node(val line: SubwayLine, val stationId: String)
    private data class Step(val from: Node, val heading: Heading?)

    fun plan(maps: StationMaps, origin: String, destination: String): Route? {
        if (origin == destination) return null
        val dist = mutableMapOf<Node, Double>()
        val prev = mutableMapOf<Node, Step>()
        val queue = PriorityQueue<Pair<Double, Node>>(compareBy { it.first })
        for (line in maps.linesServing(origin)) {
            val id = maps[line].stationId(origin) ?: continue
            val n = Node(line, id)
            dist[n] = 0.0
            queue += 0.0 to n
        }
        var end: Node? = null
        while (queue.isNotEmpty()) {
            val (d, n) = queue.poll()
            if (d > (dist[n] ?: Double.MAX_VALUE)) continue
            val map = maps[n.line]
            val name = map.station(n.stationId)?.name ?: continue
            if (name == destination) { end = n; break }
            fun relax(to: Node, cost: Double, step: Step) {
                val nd = d + cost
                if (nd < (dist[to] ?: Double.MAX_VALUE)) { dist[to] = nd; prev[to] = step; queue += nd to to }
            }
            for (h in Heading.entries) {
                map.next(n.stationId, h)?.let { relax(Node(n.line, it.id), minutesPerStop(n.line), Step(n, h)) }
            }
            for (other in maps.linesServing(name)) {
                if (other == n.line) continue
                maps[other].stationId(name)?.let { relax(Node(other, it), TRANSFER_MINUTES, Step(n, null)) }
            }
        }
        val last = end ?: return null

        // 거꾸로 따라가며 같은 노선·방향 이동을 한 구간으로 묶는다
        val moves = mutableListOf<Triple<Node, Node, Heading>>()
        var cur = last
        while (true) {
            val step = prev[cur] ?: break
            if (step.heading != null) moves += Triple(step.from, cur, step.heading)
            cur = step.from
        }
        moves.reverse()
        val legs = mutableListOf<Leg>()
        for ((from, to, heading) in moves) {
            val map = maps[from.line]
            val toName = map.station(to.stationId)!!.name
            val lastLeg = legs.lastOrNull()
            if (lastLeg != null && lastLeg.line == from.line && lastLeg.heading == heading && lastLeg.to == map.station(from.stationId)!!.name) {
                legs[legs.lastIndex] = lastLeg.copy(to = toName, stops = lastLeg.stops + 1, stations = lastLeg.stations + toName)
            } else {
                val fromName = map.station(from.stationId)!!.name
                legs += Leg(from.line, fromName, toName, heading, 1, listOf(fromName, toName))
            }
        }
        return Route(legs)
    }
}
