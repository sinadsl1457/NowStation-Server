package com.nowstation.server.ride

import com.nowstation.server.subway.ApiCallBudget
import com.nowstation.server.subway.ExpressTrains
import com.nowstation.server.subway.PollPacing
import com.nowstation.server.subway.PositionsResult
import com.nowstation.server.subway.StationMaps
import com.nowstation.server.subway.SubwayLine
import com.nowstation.server.subway.TrainDataSource
import com.nowstation.server.tracking.TrainKey
import com.nowstation.server.tracking.TrainTracker
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class UnknownStationException(name: String) : RuntimeException("모르는 역: $name")
class RideNotFoundException(id: String) : RuntimeException("없는 탑승: $id")
class UnknownTrainException(line: String, trainNo: String) : RuntimeException("지금 보이지 않는 열차: $line $trainNo")

/**
 * 진행 중인 탑승들과 열차 추적기 하나를 갖는다. 판단은 [RideSession], 여기는 연결·저장·받아 오기 대상 정하기.
 * 추적기는 모든 탑승이 같이 쓴다(같은 노선은 한 번만 받아 와 하루 한도를 아낀다).
 *
 * 하루 한도 지키기:
 * - 앱이 [idleTimeout] 넘게 소식이 없으면(강제 종료, 폰 꺼짐) 탑승을 끝낸다. 안 그러면 아무도 안 보는 노선을 계속 받아 온다.
 * - 남은 호출이 얼마 없으면 [PollPacing]으로 간격을 늘린다.
 */
@Service
class RideService(
    private val stationMaps: StationMaps,
    private val dataSource: TrainDataSource,
    private val records: RideRecordRepository,
    private val budget: ApiCallBudget,
    private val clock: Clock,
    @Value("\${ride.poll-interval:PT15S}") private val pollInterval: Duration = Duration.ofSeconds(15),
    @Value("\${ride.idle-timeout:PT10M}") private val idleTimeout: Duration = Duration.ofMinutes(10),
    private val expressTrains: ExpressTrains = ExpressTrains { _, _ -> emptySet() },
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sessions = ConcurrentHashMap<String, RideSession>()
    /** 앱이 마지막으로 물어본 시각(시작·움직임·조회). */
    private val lastContact = ConcurrentHashMap<String, Instant>()
    private val loggedPhase = ConcurrentHashMap<String, RidePhase>()
    /** 따라가는 열차의 마지막 기록(역·상태). 폰이 느낀 정차·출발과 공공 데이터 시각을 나중에 비교하려고 바뀔 때만 남긴다. */
    private val loggedTrain = ConcurrentHashMap<String, Pair<String, String>>()
    private val lastFetched = ConcurrentHashMap<SubwayLine, Instant>()
    private val tracker = TrainTracker()
    @Volatile private var pacedInterval: Duration? = pollInterval

    @Transactional
    fun start(origin: String, destination: String?): Pair<String, RideView> {
        if (stationMaps.linesServing(origin).isEmpty()) throw UnknownStationException(origin)
        if (destination != null && stationMaps.linesServing(destination).isEmpty()) throw UnknownStationException(destination)
        val id = UUID.randomUUID().toString()
        val now = Instant.now(clock)
        sessions[id] = RideSession(id, origin, destination, now, stationMaps)
        lastContact[id] = now
        records.save(RideRecord(id, origin, destination, now).also { write(it, sessions.getValue(id).phase, now) })
        // 이번정거장처럼 시작하자마자 다음에 들어올 열차를 골라 둔다
        sessions.getValue(id).currentLeg()?.let { refreshIfStale(it.line, now) }
        suggestTrains(now)
        return id to view(id)
    }

    /**
     * 폰 움직임. 폰 시계가 서버와 다를 수 있어 "보낸 시각과의 차이"만 믿는다:
     * 서버 시각 = 지금 - (폰이 보낸 시각 - 폰이 기록한 시각).
     */
    @Transactional
    fun motion(id: String, events: List<Pair<MotionKind, Instant>>, sentAt: Instant): RideView {
        val session = session(id)
        val now = Instant.now(clock)
        lastContact[id] = now
        // 실기기 시험 때 "폰이 움직임을 언제 잰 것으로 보냈나"를 보려고 남긴다(iOS 시각 밀림 버그는 이게 없어 못 증명했다)
        log.info("움직임 {}: {}", id.take(8), events.joinToString { (kind, at) -> "$kind ${Duration.between(at, sentAt).seconds}초 전" })
        val phase = synchronized(session) {
            val before = session.phase
            for ((kind, at) in events) {
                val age = Duration.between(at, sentAt).coerceAtLeast(Duration.ZERO)
                session.onMotion(MotionEvent(kind, now - age), now)
            }
            session.phase.takeIf { it != before }
        }
        // 단계가 바뀐 순간 바로 적는다(받아 오기 주기를 기다리다 배포되면 바뀐 걸 잃는다)
        if (phase != null) remember(id, phase)
        return view(id)
    }

    fun view(id: String): RideView {
        val session = session(id)
        val now = Instant.now(clock)
        lastContact[id] = now
        return synchronized(session) { synchronized(tracker) { session.view(tracker, now) } }
    }

    /**
     * 가로 노선도(지금 구간 방향의 역과 열차). 탑승을 막 시작해 아직 안 받아 온 노선은 지금 받아 온다(노선도가 비어 있지 않게).
     * 경로가 없으면(내릴 역 없음) null.
     */
    fun strip(id: String): TrainStrip? {
        val session = session(id)
        val now = Instant.now(clock)
        lastContact[id] = now
        val leg = synchronized(session) { session.currentLeg() } ?: return null
        refreshIfStale(leg.line, now)
        val express = expressTrains.at(leg.line, leg.from)
        return synchronized(tracker) { TrainChoices.strip(stationMaps, tracker.states(leg.line), leg, now, STALE_AFTER, express) }
    }

    /** 사용자가 고른 열차를 그대로 따라간다. */
    @Transactional
    fun choose(id: String, line: SubwayLine, trainNo: String): RideView {
        val session = session(id)
        val now = Instant.now(clock)
        lastContact[id] = now
        val key = TrainKey(line, trainNo)
        val strip = strip(id)
        val choice = strip?.trains?.firstOrNull { it.key == key }
        if (choice == null) {
            // 노선도 밖(경로 없음 등)이라도 지금 추적 중인 열차면 그 열차에 탄다
            val state = synchronized(tracker) { tracker.state(key) } ?: throw UnknownTrainException(line.apiName, trainNo)
            synchronized(session) { session.choose(key, state.stationId, now) }
        } else {
            synchronized(session) { session.choose(key, stationMaps[line].stationId(strip.leg.from)!!, now) }
        }
        remember(id, session.phase)
        return view(id)
    }

    fun detail(id: String): RideDetail {
        val session = session(id)
        val now = Instant.now(clock)
        return synchronized(session) { synchronized(tracker) { session.detail(tracker, now) } }
    }

    /** 기다리는 탑승마다 다음에 들어올 열차를 골라 둔다(시작 직후, 환승역에서). 틀리면 사용자가 노선도에서 바꾼다. */
    private fun suggestTrains(now: Instant) {
        for (s in sessions.values) {
            val leg = synchronized(s) { s.wantsSuggestion() } ?: continue
            val express = expressTrains.at(leg.line, leg.from)
            val strip = synchronized(tracker) { TrainChoices.strip(stationMaps, tracker.states(leg.line), leg, now, STALE_AFTER, express) }
            val pick = TrainChoices.suggest(strip) ?: continue
            val boardId = stationMaps[leg.line].stationId(leg.from)!!
            synchronized(s) { s.choose(pick.key, boardId, now) }
            remember(s.id, s.phase)
        }
    }

    private fun logFollowedTrains() {
        for (s in sessions.values) {
            val p = s.phase as? RidePhase.Riding ?: continue
            val state = synchronized(tracker) { tracker.state(p.train) } ?: continue
            val now = state.stationId to state.status.name
            if (loggedTrain.put(s.id, now) != now) {
                log.info("열차 {}: {} {} {} (관측 {})", s.id.take(8), p.train.trainNo, stationMaps[p.train.line].station(state.stationId)?.name, state.status, state.since)
            }
        }
    }

    private fun refreshIfStale(line: SubwayLine, now: Instant) {
        val last = lastFetched[line]
        if (last == null || last > now || Duration.between(last, now) >= pollInterval) fetch(line, now)
    }

    @Transactional
    fun end(id: String) {
        session(id) // 배포 뒤 처음 받은 요청이 끝내기여도 기록을 닫는다
        sessions.remove(id)
        lastContact.remove(id)
        loggedPhase.remove(id)
        loggedTrain.remove(id)
        records.findById(id).ifPresent { it.endedAt = Instant.now(clock) }
    }

    /** 탑승 중인 사람에게 필요한 노선만: 기다리는 역이 있는 노선들 + 타고 있는 열차의 노선. */
    fun linesInUse(): Set<SubwayLine> = sessions.values.flatMap { s ->
        when (val p = s.phase) {
            is RidePhase.Waiting -> s.currentLeg()?.let { listOf(it.line) } ?: stationMaps.linesServing(p.stationName)
            is RidePhase.Riding -> listOf(p.train.line)
            is RidePhase.Arrived -> emptyList()
        }
    }.toSet()

    /** [RidePoller]가 주기적으로 부른다. 필요한 노선만 받아 모든 탑승에 알린다. 받아 온 노선을 돌려준다. */
    @Transactional
    fun poll(): Set<SubwayLine> {
        val now = Instant.now(clock)
        expireIdle(now)
        val lines = linesInUse()
        val interval = PollPacing.interval(pollInterval, budget.remaining(), lines.size, budget.untilReset())
        if (interval != pacedInterval) {
            log.warn("하루 호출 한도: 남은 {}건 → 받아 오기 간격 {}", budget.remaining(), interval?.let { "${it.seconds}초" } ?: "오늘은 중단")
            pacedInterval = interval
        }
        val fetched = mutableSetOf<SubwayLine>()
        for (line in lines) {
            if (interval == null) break
            // 넉넉할 때는 스케줄러 간격 그대로, 늘렸을 때만 노선마다 마지막으로 받은 시각을 본다
            val last = lastFetched[line]
            if (interval > pollInterval && last != null && Duration.between(last, now) < interval) continue
            if (fetch(line, now)) fetched += line
        }
        sessions.values.forEach { s ->
            synchronized(s) { s.tick(now) }
            remember(s.id, s.phase)
        }
        suggestTrains(now)
        logFollowedTrains()
        return fetched
    }

    /** 한 노선을 받아 모든 탑승에 알린다. 받았으면 true. */
    private fun fetch(line: SubwayLine, now: Instant): Boolean {
        lastFetched[line] = now
        val (fetchedAt, result) = dataSource.fetch(line) ?: return false
        when (result) {
            is PositionsResult.Ok -> {
                val events = synchronized(tracker) { tracker.update(line, result.trains, fetchedAt) }
                sessions.values.forEach { s -> synchronized(s) { s.onTrainEvents(events, fetchedAt) } }
            }
            // 키 오류·한도 초과 등 서울시 쪽 문제. 탑승은 그대로 두고(새 소식 없음 → 2분 뒤 STALE) 원인을 남긴다
            is PositionsResult.Failure -> log.warn("서울시 API 오류({}): {} {}", line.apiName, result.code, result.message)
            PositionsResult.NoData -> Unit
        }
        return true
    }

    private fun expireIdle(now: Instant) {
        val idle = lastContact.filterValues { Duration.between(it, now) > idleTimeout }.keys
        for (id in idle) {
            sessions.remove(id)
            lastContact.remove(id)
            loggedPhase.remove(id)
            records.findById(id).ifPresent { it.endedAt = now }
            log.info("앱 소식이 {}분 넘게 없어 탑승 종료: {}", idleTimeout.toMinutes(), id)
        }
    }

    private fun remember(id: String, phase: RidePhase) {
        // 받아 오기마다 불리므로 바뀐 때만 남긴다
        if (loggedPhase.put(id, phase) != phase) log.info("단계 {}: {}", id.take(8), phase)
        val record = records.findById(id).orElse(null) ?: return
        write(record, phase, lastContact[id])
    }

    private fun write(record: RideRecord, phase: RidePhase, contactAt: Instant?) {
        when (phase) {
            is RidePhase.Riding -> {
                record.phase = "RIDING"
                record.phaseSince = phase.since
                record.lastLine = phase.train.line.name
                record.lastTrainNo = phase.train.trainNo
                record.boardedStationId = phase.boardedStationId
            }
            is RidePhase.Arrived -> {
                record.phase = "ARRIVED"
                record.phaseSince = phase.since
                record.lastLine = phase.line.name
                record.lastStation = phase.stationName
            }
            is RidePhase.Waiting -> {
                record.phase = "WAITING"
                record.phaseSince = phase.since
                record.lastStation = phase.stationName
            }
        }
        if (contactAt != null) record.lastContactAt = contactAt
    }

    /** 메모리에 없으면 배포로 서버가 바뀐 것일 수 있다 → DB 기록으로 이어 간다. */
    private fun session(id: String): RideSession =
        sessions[id]
            // 같은 탑승의 요청 둘이 동시에 이어받으려 하면 먼저 넣은 쪽을 쓴다
            ?: resume(id)?.let { sessions.putIfAbsent(id, it) ?: it }
            ?: throw RideNotFoundException(id)

    /** 끝나지 않았고 앱 소식이 [idleTimeout] 안에 있던 탑승만. */
    private fun resume(id: String): RideSession? {
        val r = records.findById(id).orElse(null) ?: return null
        if (r.endedAt != null) return null
        val now = Instant.now(clock)
        if (Duration.between(r.lastContactAt ?: r.startedAt, now) > idleTimeout) return null
        val since = r.phaseSince ?: r.startedAt
        val line = r.lastLine?.let { runCatching { SubwayLine.valueOf(it) }.getOrNull() }
        // CHOSEN은 예전 기록(골라 두기만 한 열차). 지금은 고르면 그대로 따라가므로 같이 이어 간다
        val phase = if (r.phase == "ARRIVED" && line != null) {
            RidePhase.Arrived(r.lastStation ?: r.destinationStation ?: r.originStation, line, since)
        } else if ((r.phase == "RIDING" || r.phase == "CHOSEN") && line != null && r.lastTrainNo != null) {
            RidePhase.Riding(TrainKey(line, r.lastTrainNo!!), r.boardedStationId ?: "", since)
        } else {
            RidePhase.Waiting(r.lastStation ?: r.originStation, since)
        }
        lastContact[id] = now
        log.info("배포 뒤 탑승 이어 감: {} {}", id, phase)
        return RideSession(id, r.originStation, r.destinationStation, r.startedAt, stationMaps, resumedPhase = phase)
    }

    private companion object {
        /** 이보다 오래 소식 없는 열차는 노선도에 올리지 않는다. */
        val STALE_AFTER: Duration = Duration.ofMinutes(2)
    }
}
