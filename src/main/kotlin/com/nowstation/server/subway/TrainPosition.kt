package com.nowstation.server.subway

import java.time.LocalDateTime

/** 실시간 API가 알려 준 열차 하나의 위치. 노선마다 다른 응답을 이 모양으로 맞춘다. */
data class TrainPosition(
    val line: SubwayLine,
    val trainNo: String,
    val stationId: String,
    val stationName: String,
    val status: TrainStatus,
    /** 응답의 updnLine 그대로("0"/"1"). 노선마다 뜻이 달라 해석은 역 데이터 쪽에서 한다. */
    val direction: String,
    val terminalStationId: String,
    val terminalName: String,
    /**
     * API가 적어 준 기록 시각. 신분당선은 열차별 시각이 아니라 응답 생성 시각이라 믿지 않는다(1일차 실측).
     * 상태가 바뀐 시각은 서버가 받은 시각으로 따로 기록한다.
     */
    val reportedAt: LocalDateTime?,
    val express: Boolean,
    val lastTrain: Boolean,
)

/** 응답의 trainSttus. 수인분당선은 DEPARTED를 주지 않는다(1일차 실측). */
enum class TrainStatus {
    ENTERING,
    ARRIVED,
    DEPARTED,
    DEPARTED_PREVIOUS,
    UNKNOWN,
    ;

    companion object {
        fun of(code: String?): TrainStatus = when (code) {
            "0" -> ENTERING
            "1" -> ARRIVED
            "2" -> DEPARTED
            "3" -> DEPARTED_PREVIOUS
            else -> UNKNOWN
        }
    }
}
