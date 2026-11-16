package com.nowstation.server.subway

import java.time.Instant

/** 한 노선의 열차 위치를 받아 오는 곳. 실제 API 또는 녹화 재생(5일차)으로 바꿔 끼운다. */
fun interface TrainDataSource {
    /** 받아 온 시각과 결과. 한도·네트워크 문제로 못 받았으면 null. */
    fun fetch(line: SubwayLine): Pair<Instant, PositionsResult>?
}

class LiveTrainDataSource(
    private val client: SeoulSubwayClient,
    private val parser: RealtimePositionParser,
) : TrainDataSource {
    override fun fetch(line: SubwayLine): Pair<Instant, PositionsResult>? =
        client.fetchRaw(line)?.let { it.fetchedAt to parser.parse(line, it.body) }
}
