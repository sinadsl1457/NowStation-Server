package com.nowstation.server.subway

import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 한 역의 도착정보에서 급행 열차 번호. 위치정보의 급행 표시(directAt)는 믿을 수 없어서(2026-11-20: 도착정보가 급행이라 한 6308을
 * 위치정보는 일반(0)으로 줌) 도착정보의 btrainSttus로 가린다. 재생 모드·테스트에서는 빈 집합.
 */
fun interface ExpressTrains {
    fun at(line: SubwayLine, station: String): Set<String>
}

/** 열차를 고르는 동안에만 부르므로 역마다 [ttl] 동안 같이 쓴다(하루 한도). 못 받으면 빈 집합(거르지 못할 뿐 고르기는 된다). */
class LiveExpressTrains(
    private val client: SeoulSubwayClient,
    private val clock: Clock,
    private val ttl: Duration = Duration.ofSeconds(30),
    private val json: JsonMapper = JsonMapper.builder().build(),
) : ExpressTrains {
    private val cache = ConcurrentHashMap<String, Pair<Instant, Map<String, Set<String>>>>()

    override fun at(line: SubwayLine, station: String): Set<String> {
        val now = Instant.now(clock)
        val hit = cache[station]?.takeIf { Duration.between(it.first, now) < ttl }
        val byLine = hit?.second ?: (client.fetchArrivalsRaw(station)?.let(::parse) ?: emptyMap()).also { cache[station] = now to it }
        return byLine[line.subwayId].orEmpty()
    }

    /** 노선 ID → 급행 열차 번호들. */
    fun parse(body: String): Map<String, Set<String>> = try {
        json.readTree(body).path("realtimeArrivalList")
            .filter { it.path("btrainSttus").asString("") == "급행" }
            .groupBy({ it.path("subwayId").asString("") }, { it.path("btrainNo").asString("") })
            .mapValues { (_, v) -> v.filter { it.isNotBlank() }.toSet() }
    } catch (e: Exception) {
        emptyMap()
    }
}
