package com.nowstation.server.recording

import com.nowstation.server.subway.PositionsResult
import com.nowstation.server.subway.RawPositions
import com.nowstation.server.subway.RealtimePositionParser
import com.nowstation.server.subway.SubwayLine
import com.nowstation.server.subway.TrainDataSource
import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * 녹화 재생 모드(실기기 없이 시연·확인). 서버 시계를 녹화한 날의 [start]부터 실제 속도로 흘리고,
 * 실시간 API 대신 그 시각까지 녹화된 응답을 돌려준다. 켜는 법:
 * `./gradlew bootRun --args='--replay.enabled=true --replay.start=2026-11-16T09:42:00+09:00'`
 */
@ConfigurationProperties("replay")
data class ReplayProperties(
    val enabled: Boolean = false,
    /** 기본값은 저장소에 들어 있는 오전 녹화(clone 직후에도 돌아가게). */
    val directory: Path = Path.of("src/test/resources/scenarios/2026-11-16-morning"),
    val start: OffsetDateTime? = null,
) {
    /** 지금 이 순간을 [start]로 하는 시계. 배속은 두지 않는다: 폰 움직임·판단 기준이 실제 초 단위라서. */
    fun clock(base: Clock = Clock.systemUTC()): Clock {
        val at = requireNotNull(start) { "replay.start가 필요하다(예: 2026-11-16T09:42:00+09:00)" }.toInstant()
        return Clock.offset(base, Duration.between(base.instant(), at))
    }
}

/** 노선마다 지금 시각까지 녹화된 가장 최근 응답을 돌려준다. 이미 돌려준 응답은 다시 주지 않는다(새 소식 없음 = 못 받음). */
class ReplayTrainDataSource(
    responses: List<RawPositions>,
    private val parser: RealtimePositionParser,
    private val clock: Clock,
) : TrainDataSource {
    private val byLine = responses.groupBy { it.line }.mapValues { (_, list) -> list.sortedBy { it.fetchedAt } }
    private val served = ConcurrentHashMap<SubwayLine, Instant>()

    val range: ClosedRange<Instant>? = responses.takeIf { it.isNotEmpty() }?.let { r ->
        r.minOf { it.fetchedAt }..r.maxOf { it.fetchedAt }
    }

    override fun fetch(line: SubwayLine): Pair<Instant, PositionsResult>? {
        val now = clock.instant()
        val latest = byLine[line]?.lastOrNull { it.fetchedAt <= now } ?: return null
        if (served.put(line, latest.fetchedAt) == latest.fetchedAt) return null
        // 녹화한 시각을 그대로 쓴다: 열차가 그 역에 보인 시각이 실제와 같아야 폰 움직임과 맞춰진다
        return latest.fetchedAt to parser.parse(line, latest.body)
    }
}
