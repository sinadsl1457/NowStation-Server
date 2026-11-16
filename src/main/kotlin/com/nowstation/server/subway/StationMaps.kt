package com.nowstation.server.subway

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

/**
 * 노선별 역 순서(resources/stations/{LINE}.json). scripts/build_stations.py가 녹화 데이터로 검증하며 만든다.
 */
data class StationSummary(val name: String, val lines: List<SubwayLine>)

class StationMaps(private val maps: Map<SubwayLine, StationMap>) {

    operator fun get(line: SubwayLine): StationMap = maps.getValue(line)

    /** 이 이름의 역이 있는 노선들(선릉 → 2호선, 수인분당선). */
    fun linesServing(stationName: String): List<SubwayLine> =
        maps.filterValues { m -> m.paths.any { p -> p.stations.any { it.name == stationName } } }.keys.toList()

    /** 역 이름 검색(앞부분·포함). 여러 노선에 있는 역은 한 번만. */
    fun search(query: String, limit: Int = 20): List<StationSummary> {
        val q = query.trim()
        return maps.values.flatMap { m -> m.paths.flatMap { it.stations }.map { it.name to m.line } }
            .groupBy({ it.first }, { it.second })
            .map { (name, lines) -> StationSummary(name, lines.distinct()) }
            .filter { q.isEmpty() || it.name.contains(q) }
            .sortedWith(compareBy({ !it.name.startsWith(q) }, { it.name }))
            .take(limit)
    }

    private data class FileStation(val id: String, val name: String)
    private data class FilePath(val name: String, val loop: Boolean, val stations: List<FileStation>)
    private data class FileLine(val line: SubwayLine, val headings: Map<String, Heading>, val paths: List<FilePath>)

    companion object {
        fun load(json: JsonMapper = JsonMapper.builder().build()): StationMaps = StationMaps(
            SubwayLine.entries.associateWith { line ->
                val text = requireNotNull(StationMaps::class.java.getResource("/stations/${line.name}.json")) {
                    "역 데이터 없음: stations/${line.name}.json"
                }.readText()
                val file = json.readValue<FileLine>(text)
                StationMap(
                    line,
                    file.paths.map { p -> StationPath(p.name, p.stations.map { Station(it.id, it.name) }, p.loop) },
                    file.headings,
                )
            },
        )
    }
}
