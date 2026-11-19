package com.nowstation.server.ride

import com.nowstation.server.subway.Heading
import com.nowstation.server.subway.StationMaps
import com.nowstation.server.subway.SubwayLine.LINE_2
import com.nowstation.server.subway.SubwayLine.SINBUNDANG
import com.nowstation.server.subway.SubwayLine.SUIN_BUNDANG
import kotlin.test.Test
import kotlin.test.assertEquals

/** 실제 역 데이터로. 기대값은 이번정거장·지도 앱 "지하철만" 첫 결과와 같게. */
class RoutePlannerTest {
    private val maps = StationMaps.load()
    private fun route(from: String, to: String) = RoutePlanner.plan(maps, from, to)!!.legs.map { "${it.line.apiName} ${it.from}→${it.to}" }

    @Test
    fun `태평 → 강남은 수인분당선 왕십리 방면으로 선릉까지, 2호선으로 강남`() {
        assertEquals(listOf("수인분당선 태평→선릉", "2호선 선릉→강남"), route("태평", "강남"))
        val first = RoutePlanner.plan(maps, "태평", "강남")!!.legs.first()
        assertEquals(Heading.DESCENDING, first.heading, "수인분당 왕십리 방면은 역 ID가 작아지는 쪽")
        assertEquals(listOf("태평", "가천대", "복정", "수서", "대모산입구", "개포동", "구룡", "도곡", "한티", "선릉"), first.stations)
        assertEquals(listOf("선릉", "역삼", "강남"), RoutePlanner.plan(maps, "태평", "강남")!!.legs[1].stations)
    }

    @Test
    fun `같은 노선은 갈아타지 않는다`() {
        assertEquals(listOf("수인분당선 가천대→선릉"), route("가천대", "선릉"))
        assertEquals(listOf("수인분당선 태평→야탑"), route("태평", "야탑"))
    }

    @Test
    fun `정자 → 강남은 신분당선 한 번에`() {
        assertEquals(listOf("신분당선 정자→강남"), route("정자", "강남"))
        assertEquals(SINBUNDANG, RoutePlanner.plan(maps, "정자", "강남")!!.legs.single().line)
    }

    @Test
    fun `2호선은 도는 노선이라 가까운 쪽으로`() {
        val leg = RoutePlanner.plan(maps, "강남", "역삼")!!.legs.single()
        assertEquals(LINE_2, leg.line)
        assertEquals(1, leg.stops)
    }
}
