package com.nowstation.server.recording

import com.nowstation.server.subway.SubwayLine
import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration

/**
 * 실시간 응답 녹화 설정. 켜는 법:
 * `./gradlew bootRun --args='--recording.enabled=true --recording.duration=PT30M'`
 * 4개 노선 × 20초 간격이면 1시간에 720건이다(하루 한도 1,000건). 필요한 노선만 `--recording.lines=LINE_3`처럼 고른다.
 */
@ConfigurationProperties("recording")
data class RecordingProperties(
    val enabled: Boolean = false,
    val interval: Duration = Duration.ofSeconds(20),
    val duration: Duration = Duration.ofMinutes(30),
    val lines: List<SubwayLine> = SubwayLine.entries,
    val directory: Path = Path.of("recordings"),
)
