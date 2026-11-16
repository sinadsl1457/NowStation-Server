package com.nowstation.server.recording

import com.nowstation.server.subway.RawPositions
import com.nowstation.server.subway.SubwayLine
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.readLines

/**
 * 녹화 파일(*.jsonl)을 받은 시각 순서로 읽는다. 여러 노선 파일을 섞어 실제로 받던 순서를 되살린다.
 * 서버 재생 모드와 시나리오 테스트가 같이 쓴다.
 */
class RecordingReader(private val json: JsonMapper = JsonMapper.builder().build()) {

    fun read(directory: Path): List<RawPositions> =
        Files.list(directory).use { files -> files.filter { it.extension == "jsonl" }.toList() }
            .flatMap { file -> file.readLines().filter { it.isNotBlank() }.map { json.readValue<RecordedResponse>(it) } }
            .map { RawPositions(SubwayLine.valueOf(it.line), it.body, Instant.parse(it.fetchedAt)) }
            .sortedBy { it.fetchedAt }
}
