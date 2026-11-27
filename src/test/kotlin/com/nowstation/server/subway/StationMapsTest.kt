package com.nowstation.server.subway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals(44, maps[SubwayLine.LINE_3].paths.single().stations.size)
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

    @Test
    fun `3호선 대화 방면(0) - 도곡 다음은 매봉, 오금 방면(1)은 대치, 끝에서는 다음 역이 없다`() {
        assertEquals("매봉", next(SubwayLine.LINE_3, "도곡", "0"))
        assertEquals("대치", next(SubwayLine.LINE_3, "도곡", "1"))
        assertNull(next(SubwayLine.LINE_3, "대화", "0"))
        assertNull(next(SubwayLine.LINE_3, "오금", "1"))
    }

    @Test
    fun `3호선 실제 응답 47대 모두 방향 값대로 가면 자기 종착역이 나온다(구파발·수서에서 끝나는 열차 포함)`() {
        val map = maps[SubwayLine.LINE_3]
        val body = javaClass.getResource("/fixtures/realtime_position_line3.json")!!.readText()
        val trains = assertIs<PositionsResult.Ok>(RealtimePositionParser().parse(SubwayLine.LINE_3, body)).trains
        assertEquals(47, trains.size)
        for (t in trains) {
            assertNotNull(map.station(t.stationId), "${t.trainNo} ${t.stationName}: 역 데이터에 없는 ID")
            val heading = assertNotNull(map.heading(t.direction), "${t.trainNo}: 모르는 방향 값 ${t.direction}")
            if (t.stationId != t.terminalStationId) {
                assertTrue(map.reaches(t.stationId, heading, t.terminalName, t.terminalStationId), "${t.trainNo} ${t.stationName} → ${t.terminalName}")
            }
        }
    }
}
