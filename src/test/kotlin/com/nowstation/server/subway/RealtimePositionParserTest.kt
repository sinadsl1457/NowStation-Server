package com.nowstation.server.subway

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RealtimePositionParserTest {
    private val parser = RealtimePositionParser()

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    @Test
    fun `실제 응답(수인분당선)을 열차 위치로 바꾼다`() {
        val result = parser.parse(SubwayLine.SUIN_BUNDANG, fixture("realtime_position_suinbundang.json"))

        val trains = assertIs<PositionsResult.Ok>(result).trains
        assertEquals(3, trains.size)
        val first = trains.first()
        assertEquals("6025", first.trainNo)
        assertEquals("1075075246", first.stationId)
        assertEquals("고색", first.stationName)
        assertEquals(TrainStatus.ARRIVED, first.status)
        assertEquals("1", first.direction)
        assertEquals(LocalDateTime.of(2026, 11, 16, 7, 37, 15), first.reportedAt)
        assertEquals(false, first.express)
    }

    @Test
    fun `데이터 없음(INFO-200)은 오류가 아니다`() {
        val body = """{"status":500,"code":"INFO-200","message":"해당하는 데이터가 없습니다.","total":0}"""

        assertEquals(PositionsResult.NoData, parser.parse(SubwayLine.LINE_2, body))
    }

    @Test
    fun `키 오류는 코드와 메시지를 남긴다`() {
        val body = """{"status":500,"code":"INFO-100","message":"인증키가 유효하지 않습니다.","total":0}"""

        val failure = assertIs<PositionsResult.Failure>(parser.parse(SubwayLine.LINE_2, body))
        assertEquals("INFO-100", failure.code)
    }

    @Test
    fun `JSON이 아니면 실패로 돌려준다`() {
        assertIs<PositionsResult.Failure>(parser.parse(SubwayLine.LINE_2, "<html>점검 중</html>"))
    }

    @Test
    fun `다른 노선 열차나 필수 값이 빈 열차는 버린다`() {
        val body = """
            {"errorMessage":{"code":"INFO-000"},"realtimePositionList":[
              {"subwayId":"1077","trainNo":"9","statnId":"1077006888","trainSttus":"1"},
              {"subwayId":"1002","trainNo":"","statnId":"1002000222","trainSttus":"1"},
              {"subwayId":"1002","trainNo":"2084","statnId":"1002000216","statnNm":"잠실","trainSttus":"2","updnLine":"0","recptnDt":"잘못된 시각"}
            ]}
        """.trimIndent()

        val trains = assertIs<PositionsResult.Ok>(parser.parse(SubwayLine.LINE_2, body)).trains
        assertEquals(listOf("2084"), trains.map { it.trainNo })
        assertEquals(TrainStatus.DEPARTED, trains.single().status)
        assertEquals(null, trains.single().reportedAt)
    }
}
