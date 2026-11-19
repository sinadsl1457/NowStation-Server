package com.nowstation.server.subway

import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Clock
import java.time.Instant

/** 받은 응답 원문과 받은 시각. 녹화는 원문을 그대로 남기고, 해석은 [RealtimePositionParser]가 한다. */
data class RawPositions(val line: SubwayLine, val body: String, val fetchedAt: Instant)

/** 서울시 실시간 열차 위치 API. 호출 전에 하루 한도를 확인한다. */
class SeoulSubwayClient(
    private val restClient: RestClient,
    private val apiKey: String,
    private val budget: ApiCallBudget,
    private val clock: Clock,
) {
    /** 한도에 닿았거나 네트워크 오류면 null. 응답이 오면(오류 응답 포함) 원문을 돌려준다. */
    fun fetchRaw(line: SubwayLine): RawPositions? {
        if (!budget.tryUse()) return null
        val body = try {
            restClient.get()
                .uri("/{key}/json/realtimePosition/{start}/{end}/{line}", apiKey, 0, MAX_ROWS, line.apiName)
                .retrieve()
                .body(String::class.java)
        } catch (e: RestClientException) {
            return null
        } ?: return null
        return RawPositions(line, body, Instant.now(clock))
    }

    /** 한 역의 실시간 도착정보 원문(급행 여부를 알려고). 한도·네트워크 문제면 null. */
    fun fetchArrivalsRaw(station: String): String? {
        if (!budget.tryUse()) return null
        return try {
            restClient.get()
                .uri("/{key}/json/realtimeStationArrival/{start}/{end}/{station}", apiKey, 0, 30, station)
                .retrieve()
                .body(String::class.java)
        } catch (e: RestClientException) {
            null
        }
    }

    private companion object {
        /** 한 노선에 동시에 다니는 열차는 60대 안팎(1일차 실측 최대 53대). 넉넉히 받는다. */
        const val MAX_ROWS = 200
    }
}
