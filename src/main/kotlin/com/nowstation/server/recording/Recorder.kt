package com.nowstation.server.recording

import com.nowstation.server.subway.SeoulSubwayClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * 실제 운행 응답을 녹화한다(실기기 없이 테스트·시연하려고). recording.enabled=true일 때만 생긴다.
 * 정해진 시간이 지나면 스스로 멈춘다(켜 둔 채 잊어도 하루 한도를 다 쓰지 않게).
 */
@Component
@ConditionalOnProperty("recording.enabled", havingValue = "true")
class Recorder(
    private val client: SeoulSubwayClient,
    private val properties: RecordingProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val writer = RecordingWriter(properties.directory)
    private val endsAt: Instant = Instant.now(clock).plus(properties.duration)
    private var finished = false

    @Scheduled(fixedDelayString = "\${recording.interval:PT20S}")
    fun tick() {
        if (finished) return
        if (!Instant.now(clock).isBefore(endsAt)) {
            finished = true
            log.info("녹화 끝: {}", properties.directory.toAbsolutePath())
            return
        }
        for (line in properties.lines) {
            val raw = client.fetchRaw(line)
            if (raw == null) {
                log.warn("{} 받지 못함(한도 또는 네트워크)", line.apiName)
                continue
            }
            writer.append(raw)
        }
    }
}
