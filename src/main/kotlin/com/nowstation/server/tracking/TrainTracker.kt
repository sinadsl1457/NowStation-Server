package com.nowstation.server.tracking

import com.nowstation.server.subway.SubwayLine
import com.nowstation.server.subway.TrainPosition
import com.nowstation.server.subway.TrainStatus
import java.time.Duration
import java.time.Instant

/** 열차 하나를 가리킨다. 열차번호는 노선 안에서만 겹치지 않는다. */
data class TrainKey(val line: SubwayLine, val trainNo: String)

/** 열차의 지금 상태. [since]는 이 역·상태가 된 것을 서버가 처음 본 시각(API 기록 시각은 노선에 따라 믿을 수 없어 쓰지 않는다). */
data class TrainState(
    val key: TrainKey,
    val stationId: String,
    val status: TrainStatus,
    val direction: String,
    val terminalStationId: String,
    val since: Instant,
    val lastSeen: Instant,
)

/** 위치 변화에서 뽑은 사건. 시각은 모두 서버가 관측한 시각이다(실제보다 30~50초 늦다). */
sealed interface TrainEvent {
    val key: TrainKey
    val stationId: String
    val observedAt: Instant

    data class Arrived(override val key: TrainKey, override val stationId: String, override val observedAt: Instant) : TrainEvent

    data class Departed(override val key: TrainKey, override val stationId: String, override val observedAt: Instant) : TrainEvent
}

/**
 * 주기적으로 받은 열차 위치를 이전 값과 비교해 도착·출발 사건을 만든다. 노선별 응답 차이를 여기서 흡수한다.
 * - 출발: 같은 역에서 DEPARTED가 되거나, 다른 역에서 처음 보이면 이전 역을 출발한 것으로 본다
 *   (수인분당선은 DEPARTED를 주지 않는다 → 다음 역에서 보이는 순간이 출발 관측 시각).
 * - 처음 보는 열차는 사건 없이 상태만 기록한다(언제 왔는지 모르므로).
 */
class TrainTracker(private val forgetAfter: Duration = Duration.ofMinutes(5)) {
    private val states = mutableMapOf<TrainKey, TrainState>()

    fun state(key: TrainKey): TrainState? = states[key]

    fun states(line: SubwayLine): List<TrainState> = states.values.filter { it.key.line == line }

    /** 한 노선의 한 번 받은 위치 전체. 사건을 관측 순서대로 돌려준다. */
    fun update(line: SubwayLine, positions: List<TrainPosition>, observedAt: Instant): List<TrainEvent> {
        val events = mutableListOf<TrainEvent>()
        for (p in positions) {
            val key = TrainKey(line, p.trainNo)
            val prev = states[key]
            val current = TrainState(key, p.stationId, p.status, p.direction, p.terminalStationId, observedAt, observedAt)
            when {
                prev == null -> states[key] = current
                prev.stationId != p.stationId -> {
                    if (prev.status != TrainStatus.DEPARTED) events += TrainEvent.Departed(key, prev.stationId, observedAt)
                    if (p.status != TrainStatus.DEPARTED) events += TrainEvent.Arrived(key, p.stationId, observedAt)
                    else events += TrainEvent.Departed(key, p.stationId, observedAt)
                    states[key] = current
                }
                prev.status != p.status -> {
                    if (p.status == TrainStatus.DEPARTED) events += TrainEvent.Departed(key, p.stationId, observedAt)
                    states[key] = current
                }
                else -> states[key] = prev.copy(lastSeen = observedAt)
            }
        }
        // 운행을 마쳐 응답에서 사라진 열차는 잠시 뒤 잊는다(같은 번호가 다음 운행에 다시 쓰일 수 있음)
        states.values.removeIf { it.key.line == line && Duration.between(it.lastSeen, observedAt) > forgetAfter }
        return events
    }
}
