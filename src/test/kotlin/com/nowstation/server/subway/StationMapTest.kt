package com.nowstation.server.subway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StationMapTest {
    private fun s(id: String, name: String) = Station(id, name)

    // 2호선을 줄인 모양: 본선 순환 4역 + 성수지선
    private val main = listOf(s("201", "시청"), s("211", "성수"), s("222", "강남"), s("243", "충정로"))
    private val branch = listOf(s("211", "성수"), s("2111", "용답"), s("2114", "신설동"))
    private val map = StationMap(
        SubwayLine.LINE_2,
        listOf(StationPath("본선", main, loop = true), StationPath("성수지선", branch)),
        mapOf("0" to Heading.ASCENDING, "1" to Heading.DESCENDING),
    )

    @Test
    fun `방향 값을 노선 규칙대로 해석한다`() {
        assertEquals(Heading.ASCENDING, map.heading("0"))
        assertNull(map.heading("9"))
    }

    @Test
    fun `순환선은 끝에서 처음으로 이어진다`() {
        assertEquals("시청", map.next("243", Heading.ASCENDING)?.name)
        assertEquals("충정로", map.next("201", Heading.DESCENDING)?.name)
    }

    @Test
    fun `갈라지는 역에서는 종착역이 지선에 있으면 지선으로 간다`() {
        assertEquals("용답", map.next("211", Heading.ASCENDING, terminalId = "2114")?.name)
        assertEquals("강남", map.next("211", Heading.ASCENDING, terminalId = "222")?.name)
    }

    @Test
    fun `지선 끝은 다음 역이 없다`() {
        assertNull(map.next("2114", Heading.ASCENDING))
        assertEquals("성수", map.next("2111", Heading.DESCENDING)?.name)
    }

    @Test
    fun `모르는 역은 null`() {
        assertNull(map.next("999", Heading.ASCENDING))
    }

    @Test
    fun `가는 쪽에 그 역이 있는지, 순환선은 어느 쪽이든, 지선은 종착역이 지선일 때만`() {
        assertTrue(map.reaches("201", Heading.ASCENDING, "강남"))
        assertTrue(map.reaches("201", Heading.DESCENDING, "강남"))
        assertFalse(map.reaches("201", Heading.ASCENDING, "용답"), "본선 열차는 지선에 가지 않는다")
        assertTrue(map.reaches("211", Heading.ASCENDING, "신설동", terminalId = "2114"))

        val line = StationMap(SubwayLine.SUIN_BUNDANG, listOf(StationPath("본선", listOf(s("1", "가천대"), s("2", "태평"), s("3", "모란")))), emptyMap())
        assertTrue(line.reaches("2", Heading.ASCENDING, "모란"))
        assertFalse(line.reaches("2", Heading.DESCENDING, "모란"))
    }
}
