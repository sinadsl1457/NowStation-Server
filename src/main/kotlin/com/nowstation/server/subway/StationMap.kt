package com.nowstation.server.subway

/** 역 하나. id는 실시간 API의 statnId(예: 1002000222 강남). */
data class Station(val id: String, val name: String)

/** 역 ID가 커지는 쪽(ASCENDING)인지 작아지는 쪽인지. 노선마다 updnLine 0/1의 뜻이 달라 여기로 맞춘다. */
enum class Heading { ASCENDING, DESCENDING }

/**
 * 한 노선의 역 순서. [paths]는 각각 역 ID가 커지는 순서로 늘어선 구간이다.
 * - 2호선 본선은 [loop]=true(충정로 다음이 시청).
 * - 지선(성수·신정)은 갈라지는 역부터 시작한다: [성수, 용답, 신답, 용두, 신설동].
 */
data class StationPath(val name: String, val stations: List<Station>, val loop: Boolean = false)

class StationMap(
    val line: SubwayLine,
    val paths: List<StationPath>,
    /** updnLine 값 → 방향. 녹화한 실제 이동으로 확인한 값(docs/PLAN.md, CLAUDE.md). */
    private val headings: Map<String, Heading>,
) {
    private val byId: Map<String, Station> = paths.flatMap { it.stations }.associateBy { it.id }

    fun station(id: String): Station? = byId[id]

    fun heading(updnLine: String): Heading? = headings[updnLine]

    /**
     * [stationId]에서 [heading] 쪽으로 다음 역. 종점이면 null.
     * 갈라지는 역(성수·신도림)에서는 열차의 종착역이 지선에 있으면 지선, 아니면 본선을 따른다.
     */
    fun next(stationId: String, heading: Heading, terminalId: String? = null): Station? {
        val path = pathOf(stationId, terminalId) ?: return null
        val index = path.stations.indexOfFirst { it.id == stationId }
        val nextIndex = if (heading == Heading.ASCENDING) index + 1 else index - 1
        return when {
            nextIndex in path.stations.indices -> path.stations[nextIndex]
            path.loop -> path.stations[Math.floorMod(nextIndex, path.stations.size)]
            else -> null
        }
    }

    /**
     * [stationId]에서 [heading] 쪽으로 가면 [stationName]이 나오는지. 2호선 본선처럼 도는 노선은 어느 쪽이든 나온다.
     * 종착역에서 멈추지 않는다: 중간에 끝나는 열차라도 그쪽으로 가는 열차를 탔을 수 있다.
     */
    fun reaches(stationId: String, heading: Heading, stationName: String, terminalId: String? = null): Boolean {
        var id = stationId
        repeat(byId.size) {
            val next = next(id, heading, terminalId) ?: return false
            if (next.name == stationName) return true
            id = next.id
        }
        return false
    }

    private fun pathOf(stationId: String, terminalId: String?): StationPath? {
        val candidates = paths.filter { p -> p.stations.any { it.id == stationId } }
        if (candidates.size <= 1) return candidates.firstOrNull()
        return candidates.firstOrNull { p -> !p.loop && p.stations.drop(1).any { it.id == terminalId } }
            ?: candidates.first()
    }
}
