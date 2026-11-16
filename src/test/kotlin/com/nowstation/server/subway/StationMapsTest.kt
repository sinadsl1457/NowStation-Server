package com.nowstation.server.subway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 실제 역 데이터 파일로 확인한다. 기대값은 공식 노선도 기준. */
class StationMapsTest {
    private val maps = StationMaps.load()

    private fun next(line: SubwayLine, name: String, updn: String, terminal: String? = null): String? {
        val map = maps[line]
        val id = map.paths.flatMap { it.stations }.first { it.name == name }.id
        val terminalId = terminal?.let { t -> map.paths.flatMap { it.stations }.first { it.name == t }.id }
        return map.next(id, map.heading(updn)!!, terminalId)?.name
    }

    @Test
    fun `노선마다 역 수가 공식 노선도와 같다`() {
        assertEquals(51, maps[SubwayLine.LINE_2].paths.flatMap { it.stations }.distinctBy { it.id }.size)
        assertEquals(63, maps[SubwayLine.SUIN_BUNDANG].paths.single().stations.size)
        assertEquals(16, maps[SubwayLine.SINBUNDANG].paths.single().stations.size)
    }

    @Test
    fun `수인분당선 왕십리 방면(0) - 태평 다음은 가천대, 한티 다음은 선릉`() {
        assertEquals("가천대", next(SubwayLine.SUIN_BUNDANG, "태평", "0"))
        assertEquals("선릉", next(SubwayLine.SUIN_BUNDANG, "한티", "0"))
        assertEquals("모란", next(SubwayLine.SUIN_BUNDANG, "태평", "1"))
    }

    @Test
    fun `2호선 내선(0) 선릉 다음은 역삼, 외선(1)은 삼성 - 순환 끝도 이어진다`() {
        assertEquals("역삼", next(SubwayLine.LINE_2, "선릉", "0"))
        assertEquals("삼성", next(SubwayLine.LINE_2, "선릉", "1"))
        assertEquals("시청", next(SubwayLine.LINE_2, "충정로", "0"))
    }

    @Test
    fun `2호선 성수에서 신설동행은 지선으로, 신도림에서 까치산행은 지선으로`() {
        assertEquals("용답", next(SubwayLine.LINE_2, "성수", "0", terminal = "신설동"))
        assertEquals("도림천", next(SubwayLine.LINE_2, "신도림", "0", terminal = "까치산"))
    }

    @Test
    fun `신분당선 신사 방면(0) 끝에서는 다음 역이 없다`() {
        assertEquals("신논현", next(SubwayLine.SINBUNDANG, "강남", "0"))
        assertNull(next(SubwayLine.SINBUNDANG, "신사", "0"))
    }
}
