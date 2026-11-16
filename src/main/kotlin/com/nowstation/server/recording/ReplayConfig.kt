package com.nowstation.server.recording

import com.nowstation.server.subway.RealtimePositionParser
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

/** 재생 모드일 때 시계와 열차 데이터 출처를 바꿔 끼운다(SubwayConfig의 실제 것은 이때 만들지 않는다). */
@Configuration
@ConditionalOnProperty("replay.enabled", havingValue = "true")
class ReplayConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun clock(replay: ReplayProperties): Clock = replay.clock()

    @Bean
    fun replayTrainDataSource(replay: ReplayProperties, parser: RealtimePositionParser, clock: Clock): ReplayTrainDataSource {
        val source = ReplayTrainDataSource(RecordingReader().read(replay.directory), parser, clock)
        val seoul = ZoneId.of("Asia/Seoul")
        log.info(
            "재생 모드: {} (녹화 {} ~ {}), 지금 {}",
            replay.directory,
            source.range?.start?.atZone(seoul)?.toLocalTime(),
            source.range?.endInclusive?.atZone(seoul)?.toLocalTime(),
            clock.instant().atZone(seoul).toLocalTime(),
        )
        return source
    }
}
