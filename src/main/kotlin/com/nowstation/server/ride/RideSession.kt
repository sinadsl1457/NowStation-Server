package com.nowstation.server.ride

import com.nowstation.server.subway.Heading
import com.nowstation.server.subway.StationMaps
import com.nowstation.server.subway.SubwayLine
import com.nowstation.server.subway.TrainStatus
import com.nowstation.server.tracking.TrainEvent
import com.nowstation.server.tracking.TrainKey
import com.nowstation.server.tracking.TrainTracker
import java.time.Duration
import java.time.Instant

/** 탑승 세션의 단계. */
sealed interface RidePhase {
    /** 역에서 열차를 기다리는 중(처음 시작, 열차를 놓침, 환승하려고 내림). 역은 이름으로 본다(선릉처럼 노선마다 ID가 다름). */
    data class Waiting(val stationName: String, val since: Instant) : RidePhase

    data class Riding(val train: TrainKey, val boardedStationId: String, val since: Instant) : RidePhase
}

enum class RideStatus { WAITING, AT_STATION, MOVING, HOLDING_BETWEEN, STALE }

/** 앱에 보여 줄 것. 열차 안 방송처럼, 출발한 뒤에는 "이번 역"이 다가오는 역이다. */
data class RideView(
    val status: RideStatus,
    val line: SubwayLine?,
    val thisStation: String?,
    val nextStation: String?,
    /** 내릴 역을 정했고 이번 역이 그 역이면 true(한 정거장 전 진동). */
    val prepareToAlight: Boolean,
)

/**
 * 탑승 한 번. 폰 움직임과 열차 사건을 맞춰 "어느 열차에 탔는지"를 정하고 따라간다.
 * - 기다리는 역을 떠난 열차 중, 그 출발 즈음 폰도 움직이기 시작한 열차 = 탄 열차(놓친 열차는 이때 폰이 멈춰 있음)
 * - 탄 열차가 어느 역을 떠날 때 폰이 계속 멈춰 있었으면 그 역에서 내린 것 → 그 역에서 다시 기다림(환승)
 * 판단은 폰 움직임이 늦게 올 수 있어 [RideRules.motionUploadGrace]만큼 미뤘다가 [tick]에서 확정한다.
 */
class RideSession(
    val id: String,
    originStationName: String,
    val destinationStationName: String?,
    startedAt: Instant,
    private val stationMaps: StationMaps,
    private val rules: RideRules = RideRules(),
    /** 배포 뒤 DB 기록으로 이어 갈 때의 단계. 폰 움직임·후보 열차 같은 짧은 기억은 새로 쌓는다. */
    resumedPhase: RidePhase? = null,
) {
    var phase: RidePhase = resumedPhase ?: RidePhase.Waiting(originStationName, startedAt)
        private set

    private val motion = MotionLog()

    /** 기다리는 역을 떠난 열차들(아직 탈지 말지 판단 전). */
    private val candidates = mutableListOf<TrainEvent.Departed>()

    /** 탄 열차가 떠났는데 폰이 안 움직였던 역(내림 판단 대기). */
    private var pendingAlight: TrainEvent.Departed? = null

    fun onMotion(event: MotionEvent, now: Instant) {
        motion.add(event)
        tick(now)
    }

    fun onTrainEvents(events: List<TrainEvent>, now: Instant) {
        for (e in events.filterIsInstance<TrainEvent.Departed>()) {
            when (val p = phase) {
                is RidePhase.Waiting -> if (nameOf(e.key.line, e.stationId) == p.stationName) candidates += e
                is RidePhase.Riding -> if (e.key == p.train) pendingAlight = e
            }
        }
        tick(now)
    }

    /** 미뤄 둔 판단을 확정한다. 주기적으로 불러야 한다(폰 움직임이 끝내 안 오는 경우). */
    fun tick(now: Instant) {
        when (val p = phase) {
            is RidePhase.Waiting -> resolveBoarding(now)
            is RidePhase.Riding -> resolveAlighting(p, now)
        }
    }

    fun view(tracker: TrainTracker, now: Instant): RideView = when (val p = phase) {
        is RidePhase.Waiting -> RideView(RideStatus.WAITING, null, p.stationName, null, prepareToAlight = false)
        is RidePhase.Riding -> ridingView(p, tracker, now)
    }

    /**
     * 기다리는 역을 떠난 열차 중 그 출발 구간에 폰도 움직이기 시작한 열차에 탄다.
     * 여럿이면 폰이 움직이기 전에 가장 늦게 들어온 열차(먼저 온 열차는 이미 떠났거나 놓친 것).
     */
    private fun resolveBoarding(now: Instant) {
        val waiting = phase as RidePhase.Waiting
        val matched = candidates.filter { d ->
            val (from, to) = departureWindow(d)
            motion.movingStartsBetween(maxOf(from, waiting.since), to).isNotEmpty()
        }
        val boarded = matched.maxByOrNull { departureWindow(it).first }
        if (boarded != null) {
            phase = RidePhase.Riding(boarded.key, boarded.stationId, boarded.observedAt)
            candidates.clear()
            return
        }
        // 판단을 미루는 시간까지 지났는데도 폰이 안 움직였으면 놓친 열차
        candidates.removeIf { now > departureWindow(it).second + rules.motionUploadGrace }
    }

    /**
     * 실제 출발이 있었을 수 있는 구간. 출발 상태를 받은 경우는 관측 직전 [RideRules.departureLookBack],
     * 다음 역에서 보고서야 안 경우(수인분당선)는 그 역에 온 것을 본 때부터다(실제 출발이 2분 넘게 앞설 수 있음).
     * 다만 폰이 그 역에 와서 멈춘 뒤부터만 본다. 그 전 움직임은 이 역으로 들어오던 것이지 떠난 게 아니다.
     */
    private fun departureWindow(d: TrainEvent.Departed): Pair<Instant, Instant> {
        val base = if (d.explicit || d.arrivedObservedAt == null) d.observedAt else d.arrivedObservedAt
        var from = base - rules.departureLookBack
        motion.lastStopAtOrBefore(d.arrivedObservedAt ?: d.observedAt)?.let { stoppedHere -> if (stoppedHere > from) from = stoppedHere }
        return from to (d.observedAt + rules.departureLookAhead)
    }

    private fun resolveAlighting(riding: RidePhase.Riding, now: Instant) {
        val d = pendingAlight ?: return
        val (from, to) = departureWindow(d)
        if (motion.movedWithDeparture(from, to)) {
            pendingAlight = null
            return
        }
        if (now <= to + rules.motionUploadGrace) return
        pendingAlight = null
        phase = RidePhase.Waiting(nameOf(riding.train.line, d.stationId) ?: d.stationId, d.observedAt)
    }

    private fun ridingView(riding: RidePhase.Riding, tracker: TrainTracker, now: Instant): RideView {
        val line = riding.train.line
        val state = tracker.state(riding.train)
            ?: return RideView(RideStatus.STALE, line, null, null, prepareToAlight = false)
        val map = stationMaps[line]
        val heading = map.heading(state.direction) ?: Heading.ASCENDING
        val here = map.station(state.stationId)
        val next = map.next(state.stationId, heading, state.terminalStationId)
        // 수인분당선은 출발 상태가 없다 → 열차가 이 역에 온 걸 본 뒤 폰이 움직이기 시작했으면 출발로 본다
        val departed = state.status == TrainStatus.DEPARTED ||
            (state.status == TrainStatus.ARRIVED && motion.movingStartsBetween(state.since, now).isNotEmpty())

        val (thisStation, nextStation) = if (departed) {
            next to next?.let { map.next(it.id, heading, state.terminalStationId) }
        } else {
            here to next
        }
        val status = when {
            Duration.between(state.lastSeen, now) > rules.staleAfter -> RideStatus.STALE
            departed && motion.lastAfter(state.since)?.kind == MotionKind.STOPPED -> RideStatus.HOLDING_BETWEEN
            departed || state.status == TrainStatus.ENTERING -> RideStatus.MOVING
            else -> RideStatus.AT_STATION
        }
        return RideView(
            status = status,
            line = line,
            thisStation = thisStation?.name,
            nextStation = nextStation?.name,
            prepareToAlight = destinationStationName != null && thisStation?.name == destinationStationName,
        )
    }

    private fun nameOf(line: SubwayLine, stationId: String): String? = stationMaps[line].station(stationId)?.name
}
