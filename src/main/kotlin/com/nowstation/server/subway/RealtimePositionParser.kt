package com.nowstation.server.subway

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** 실시간 열차 위치 API 응답 해석 결과. */
sealed interface PositionsResult {
    data class Ok(val trains: List<TrainPosition>) : PositionsResult

    /** INFO-200. 운행 시간이 아니거나 열차가 없을 때. 오류가 아니다. */
    data object NoData : PositionsResult

    /** 키 오류(INFO-100), 한도 초과, 서버 오류, 모르는 형식. */
    data class Failure(val code: String, val message: String) : PositionsResult
}

/**
 * 응답 문자열 → [PositionsResult]. 녹화 파일 재생에도 같은 함수를 쓴다.
 * 정상 응답은 {errorMessage:{code}, realtimePositionList:[…]}, 오류 응답은 {code, message}만 온다.
 */
class RealtimePositionParser(private val json: JsonMapper = JsonMapper.builder().build()) {

    fun parse(line: SubwayLine, body: String): PositionsResult {
        val root = try {
            json.readTree(body)
        } catch (e: Exception) {
            return PositionsResult.Failure("PARSE", e.message ?: "JSON이 아님")
        }
        val status = root.path("errorMessage").takeUnless { it.isMissingNode } ?: root
        val code = status.path("code").asString("")
        return when (code) {
            "INFO-000" -> PositionsResult.Ok(root.path("realtimePositionList").mapNotNull { it.toPosition(line) })
            "INFO-200" -> PositionsResult.NoData
            else -> PositionsResult.Failure(code.ifEmpty { "UNKNOWN" }, status.path("message").asString(""))
        }
    }

    /** 다른 노선 열차가 섞여 오거나 필수 값이 비면 버린다. */
    private fun JsonNode.toPosition(line: SubwayLine): TrainPosition? {
        if (path("subwayId").asString("") != line.subwayId) return null
        val trainNo = path("trainNo").asString("").ifBlank { return null }
        val stationId = path("statnId").asString("").ifBlank { return null }
        return TrainPosition(
            line = line,
            trainNo = trainNo,
            stationId = stationId,
            stationName = path("statnNm").asString(""),
            status = TrainStatus.of(path("trainSttus").asString(null)),
            direction = path("updnLine").asString(""),
            terminalStationId = path("statnTid").asString(""),
            terminalName = path("statnTnm").asString(""),
            reportedAt = parseTime(path("recptnDt").asString("")),
            express = path("directAt").asString("0") != "0",
            lastTrain = path("lstcarAt").asString("0") == "1",
        )
    }

    private fun parseTime(text: String): LocalDateTime? = try {
        LocalDateTime.parse(text, TIME)
    } catch (e: DateTimeParseException) {
        null
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
