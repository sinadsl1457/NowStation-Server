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

    /** 따라가는 열차. 사용자가 고른(또는 골라 둔) 열차를 그대로 따라간다: 탄 열차가 중간에 바뀔 일은 없다(사용자 결정, 2026-11-19). */
    data class Riding(val train: TrainKey, val boardedStationId: String, val since: Instant) : RidePhase

    /** 내릴 역에 도착. 더 따라가지 않는다(따라가던 열차가 태평을 지나 모란까지 가도 계속 따라가던 문제, 퇴근 시간 시나리오). */
    data class Arrived(val stationName: String, val line: SubwayLine, val since: Instant) : RidePhase
}

/** 앱에 알릴 일. TRANSFER: 환승역에 도착(다음 구간 열차를 고르게). */
enum class RideNotice { TRANSFER }

/** 화면에 더 보여 줄 것: 경로 구간, 따라가는 열차, 이번 구간 끝(환승역·내릴 역)까지 남은 정거장. */
data class RideDetail(
    val route: Route?,
    val train: TrainKey?,
    val target: String?,
    val remainingStops: Int?,
    val notice: RideNotice?,
    /** 최종 목적지까지 남은 정거장(지금 구간 남은 수 + 다음 구간들, 환승역은 한 번). 환승이 여러 번이어도 사용자가 보는 숫자는 이것 하나. */
    val totalRemainingStops: Int? = null,
)

enum class RideStatus { WAITING, AT_STATION, MOVING, HOLDING_BETWEEN, STALE, ARRIVED }

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
    val originStationName: String,
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


    /** 지도 앱 "지하철만" 첫 결과처럼 정한 경로. 방향과 환승역이 여기서 나온다. */
    val route: Route? = destinationStationName?.let { RoutePlanner.plan(stationMaps, originStationName, it) }

    private var notice: Pair<RideNotice, Instant>? = null

    /** 기다리는 중이고 이 역에서 시작하는 구간이 있으면, 서비스가 다음에 들어올 열차를 골라 둔다. */
    fun wantsSuggestion(): Leg? = (phase as? RidePhase.Waiting)?.let { w -> route?.legs?.firstOrNull { it.from == w.stationName } }

    /** 지금 보여 줄 구간: 기다리는 역에서 시작하는 구간, 타고 있으면 그 노선 구간. */
    fun currentLeg(): Leg? = when (val p = phase) {
        is RidePhase.Waiting -> route?.legs?.firstOrNull { it.from == p.stationName } ?: route?.legs?.lastOrNull { it.to == p.stationName }
        is RidePhase.Riding -> route?.legOn(p.train.line)
        is RidePhase.Arrived -> route?.legs?.lastOrNull()
    }

    fun onMotion(event: MotionEvent, now: Instant) {
        motion.add(event)
        tick(now)
    }

    fun onTrainEvents(events: List<TrainEvent>, now: Instant) {
        for (e in events.filterIsInstance<TrainEvent.Departed>()) {
            when (val p = phase) {
                is RidePhase.Waiting -> if (nameOf(e.key.line, e.stationId) == p.stationName && headsToDestination(e)) candidates += e
                is RidePhase.Riding, is RidePhase.Arrived -> Unit
            }
        }
        // 따라가는 열차가 이번 구간 끝(환승역)에 오면 내려서 다음 구간 열차를 고르게 한다. 내릴 역이면 그대로 둔다(내릴 준비 화면)
        val p = phase
        if (p is RidePhase.Riding) {
            val leg = route?.legOn(p.train.line)
            val reached = events.firstOrNull { it.key == p.train && nameOf(it.key.line, it.stationId) == leg?.to }
            if (leg != null && reached != null && leg.to != destinationStationName) {
                phase = RidePhase.Waiting(leg.to, reached.observedAt)
                notice = RideNotice.TRANSFER to now
                candidates.clear()
            } else if (leg != null && reached != null) {
                phase = RidePhase.Arrived(leg.to, p.train.line, reached.observedAt)
            }
        }
        tick(now)
    }

    /** 사용자가 고른 열차에 바로 탄다(폰 움직임으로 맞히지 않는다). 이미 타고 있어도 바꿔 탄다(잘못 고른 걸 다시 고름). */
    fun choose(train: TrainKey, stationId: String, now: Instant) {
        phase = RidePhase.Riding(train, stationId, now)
        candidates.clear()
    }

    /** 미뤄 둔 판단을 확정한다. 주기적으로 불러야 한다(폰 움직임이 끝내 안 오는 경우). */
    fun tick(now: Instant) {
        when (val p = phase) {
            is RidePhase.Waiting -> resolveBoarding(now)
            is RidePhase.Riding, is RidePhase.Arrived -> Unit
        }
    }

    fun detail(tracker: TrainTracker, now: Instant): RideDetail {
        val p = phase
        val train = (p as? RidePhase.Riding)?.train
        val leg = currentLeg()
        val target = leg?.to ?: destinationStationName
        val remaining = if (p is RidePhase.Riding && target != null) {
            tracker.state(p.train)?.let { s ->
                val map = stationMaps[p.train.line]
                val heading = map.heading(s.direction) ?: return@let null
                val view = ridingView(p, tracker, now)
                val here = view.thisStation?.let { map.stationId(it) } ?: s.stationId
                map.stopsTo(here, heading, target, s.terminalStationId)
            }
        } else null
        val shown = notice?.takeIf { Duration.between(it.second, now) < NOTICE_FOR }?.first
        val total = remaining?.let { r ->
            val legs = route?.legs.orEmpty()
            val at = legs.indexOfFirst { it.to == target }
            r + if (at >= 0) legs.drop(at + 1).sumOf { it.stops } else 0
        }
        return RideDetail(route, train, target, remaining, shown, total)
    }


    fun view(tracker: TrainTracker, now: Instant): RideView = when (val p = phase) {
        is RidePhase.Waiting -> RideView(RideStatus.WAITING, null, p.stationName, null, prepareToAlight = false)
        is RidePhase.Riding -> ridingView(p, tracker, now)
        is RidePhase.Arrived -> RideView(RideStatus.ARRIVED, p.line, p.stationName, null, prepareToAlight = true)
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


    private fun ridingView(riding: RidePhase.Riding, tracker: TrainTracker, now: Instant): RideView {
        val line = riding.train.line
        val state = tracker.state(riding.train)
            ?: return RideView(RideStatus.STALE, line, null, null, prepareToAlight = false)
        val map = stationMaps[line]
        val heading = map.heading(state.direction) ?: Heading.ASCENDING
        val here = map.station(state.stationId)
        val next = map.next(state.stationId, heading, state.terminalStationId)
        // 출발은 공공 데이터보다 폰이 먼저 안다(수인분당선은 출발 상태를 아예 주지 않음):
        // - 데이터가 이 역 도착을 보인 뒤 폰이 움직이기 시작했거나
        // - 폰이 이 역에서 섰다가 다시 움직였으면(데이터가 도착을 늦게 보여 줘 폰 정차·출발이 먼저 와도) 떠난 것으로 본다
        val seenHere = state.arrivedAt ?: state.since
        val departed = state.status == TrainStatus.DEPARTED ||
            (state.status == TrainStatus.ARRIVED && motion.movingStartsBetween(state.since, now).isNotEmpty()) ||
            (state.status != TrainStatus.DEPARTED && motion.stoppedThenMovedSince(seenHere - rules.stopBeforeSeen))

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
            // 내릴(갈아탈) 역 바로 앞 역을 떠나 그 역으로 가는 중부터. 한 정거장 더 앞(앞 역으로 가는 중·앞 역에 서 있음)부터 켜니
            // 두 정거장 전부터 "곧 왕십리"가 떠 헷갈렸다(2026-11-27 실기기, 개발자 피드백). 출발 상태가 없는 노선도 폰 출발로 떠난 걸 안다
            prepareToAlight = (route?.legOn(line)?.to ?: route?.legs?.lastOrNull()?.to ?: destinationStationName)
                ?.let { thisStation?.name == it } ?: false,
        )
    }

    /**
     * 내릴 역이 이 열차의 노선에 있으면 그쪽으로 가는 열차만 후보로 본다. 반대편 열차가 떠날 때 승강장에서 폰이 흔들려도 타지 않게.
     * 내릴 역이 다른 노선(갈아타야 함)이거나 방향 값을 모르면 거르지 않는다.
     */
    private fun headsToDestination(d: TrainEvent.Departed): Boolean {
        val map = stationMaps[d.key.line]
        route?.legOn(d.key.line)?.let { leg -> return map.heading(d.direction)?.let { it == leg.heading } ?: true }
        val destination = destinationStationName ?: return true
        if (map.paths.none { p -> p.stations.any { it.name == destination } }) return true
        val heading = map.heading(d.direction) ?: return true
        return map.reaches(d.stationId, heading, destination, d.terminalStationId)
    }

    private fun nameOf(line: SubwayLine, stationId: String): String? = stationMaps[line].station(stationId)?.name

    private companion object {
        /** 환승 알림을 화면에 띄워 두는 시간(내려서 갈아타는 승강장까지 걷는 동안). */
        val NOTICE_FOR: Duration = Duration.ofMinutes(3)
    }
}
