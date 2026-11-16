package com.nowstation.server.recording

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

data class ReplayStatus(val now: Instant, val recordedFrom: Instant?, val recordedTo: Instant?)

/** 재생 모드에서만. 시연 스크립트가 서버의 "지금"(녹화 속 시각)에 센서 주입을 맞추려고 묻는다. */
@RestController
@ConditionalOnProperty("replay.enabled", havingValue = "true")
class ReplayController(private val clock: Clock, private val source: ReplayTrainDataSource) {
    @GetMapping("/api/replay")
    fun status() = ReplayStatus(clock.instant(), source.range?.start, source.range?.endInclusive)
}
