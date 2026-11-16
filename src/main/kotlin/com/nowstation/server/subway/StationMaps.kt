package com.nowstation.server.subway

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

/**
 * 노선별 역 순서(resources/stations/{LINE}.json). scripts/build_stations.py가 녹화 데이터로 검증하며 만든다.
 */
class StationMaps(private val maps: Map<SubwayLine, StationMap>) {

    operator fun get(line: SubwayLine): StationMap = maps.getValue(line)

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
