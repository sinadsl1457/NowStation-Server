package com.nowstation.server.subway

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeoulSubwayClientTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-16T09:00:00Z"), ZoneOffset.UTC)
    private val builder = RestClient.builder().baseUrl("http://api.test/api/subway")
    private val server = MockRestServiceServer.bindTo(builder).build()

    private fun client(budget: ApiCallBudget = ApiCallBudget(clock)) =
        SeoulSubwayClient(builder.build(), "KEY", budget, clock)

    @Test
    fun `노선 이름을 주소에 넣어 부르고 응답 원문과 받은 시각을 돌려준다`() {
        server.expect(requestTo("http://api.test/api/subway/KEY/json/realtimePosition/0/200/%EC%88%98%EC%9D%B8%EB%B6%84%EB%8B%B9%EC%84%A0"))
            .andExpect(method(org.springframework.http.HttpMethod.GET))
            .andRespond(withSuccess("""{"errorMessage":{"code":"INFO-000"},"realtimePositionList":[]}""", MediaType.APPLICATION_JSON))

        val raw = client().fetchRaw(SubwayLine.SUIN_BUNDANG)

        assertEquals(SubwayLine.SUIN_BUNDANG, raw?.line)
        assertEquals(Instant.parse("2026-11-16T09:00:00Z"), raw?.fetchedAt)
        server.verify()
    }

    @Test
    fun `한도에 닿았으면 부르지 않는다`() {
        val budget = ApiCallBudget(clock, dailyLimit = 1, reserve = 0).also { it.tryUse() }

        assertNull(client(budget).fetchRaw(SubwayLine.LINE_2))
        server.verify() // 요청이 하나도 없어야 한다
    }

    @Test
    fun `서버 오류면 null`() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://api.test"))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertNull(client().fetchRaw(SubwayLine.LINE_2))
    }
}
