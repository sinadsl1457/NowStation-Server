package com.nowstation.server.recording

import com.nowstation.server.subway.RawPositions
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.LocalDate
import java.time.ZoneId

/** 녹화 파일 한 줄 = 한 번 받은 응답. 원문(body)을 그대로 남겨 나중에 해석 규칙이 바뀌어도 다시 쓸 수 있다. */
data class RecordedResponse(val fetchedAt: String, val line: String, val body: String)

/** recordings/2026-11-16/LINE_2.jsonl 처럼 서울 날짜·노선별 파일에 한 줄씩 덧붙인다. */
class RecordingWriter(
    private val directory: Path,
    private val json: JsonMapper = JsonMapper.builder().build(),
) {
    fun append(raw: RawPositions): Path {
        val date = LocalDate.ofInstant(raw.fetchedAt, SEOUL)
        val file = directory.resolve(date.toString()).resolve("${raw.line.name}.jsonl")
        Files.createDirectories(file.parent)
        val record = RecordedResponse(raw.fetchedAt.toString(), raw.line.name, raw.body)
        Files.writeString(file, json.writeValueAsString(record) + "\n", CREATE, APPEND)
        return file
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
