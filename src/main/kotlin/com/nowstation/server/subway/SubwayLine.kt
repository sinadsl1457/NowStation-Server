package com.nowstation.server.subway

/** 지원하는 노선. [apiName]은 실시간 API 주소에 들어가는 이름, [subwayId]는 응답의 노선 ID. */
enum class SubwayLine(val apiName: String, val subwayId: String) {
    LINE_2("2호선", "1002"),
    LINE_3("3호선", "1003"),
    SUIN_BUNDANG("수인분당선", "1075"),
    SINBUNDANG("신분당선", "1077"),
    ;

    companion object {
        fun ofSubwayId(id: String): SubwayLine? = entries.firstOrNull { it.subwayId == id }
    }
}
