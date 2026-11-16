package com.nowstation.server.recording

import com.nowstation.server.subway.RawPositions
import com.nowstation.server.subway.SubwayLine
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class RecordingWriterTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `서울 날짜·노선별 파일에 원문을 한 줄씩 덧붙인다`() {
        val writer = RecordingWriter(dir)
        // 2026-11-16 23:59:30 KST, 그다음은 11-17 00:00:10 KST
        writer.append(RawPositions(SubwayLine.LINE_2, """{"a":1}""", Instant.parse("2026-11-16T14:59:30Z")))
        writer.append(RawPositions(SubwayLine.LINE_2, """{"a":2}""", Instant.parse("2026-11-16T14:59:50Z")))
        writer.append(RawPositions(SubwayLine.LINE_2, """{"a":3}""", Instant.parse("2026-11-16T15:00:10Z")))

        val first = dir.resolve("2026-11-16/LINE_2.jsonl").readLines()
        assertEquals(2, first.size)
        val record = JsonMapper.builder().build().readValue<RecordedResponse>(first[0])
        assertEquals("""{"a":1}""", record.body)
        assertEquals("2026-11-16T14:59:30Z", record.fetchedAt)
        assertEquals(1, dir.resolve("2026-11-17/LINE_2.jsonl").readLines().size)
    }
}
