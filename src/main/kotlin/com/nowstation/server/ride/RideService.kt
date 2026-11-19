package com.nowstation.server.ride

import com.nowstation.server.subway.ApiCallBudget
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
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sessions = ConcurrentHashMap<String, RideSession>()
    /** 앱이 마지막으로 물어본 시각(시작·움직임·조회). */
    private val lastContact = ConcurrentHashMap<String, Instant>()
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

    @Transactional
    fun end(id: String) {
        session(id) // 배포 뒤 처음 받은 요청이 끝내기여도 기록을 닫는다
        sessions.remove(id)
        lastContact.remove(id)
        records.findById(id).ifPresent { it.endedAt = Instant.now(clock) }
    }

    /** 탑승 중인 사람에게 필요한 노선만: 기다리는 역이 있는 노선들 + 타고 있는 열차의 노선. */
    fun linesInUse(): Set<SubwayLine> = sessions.values.flatMap { s ->
        when (val p = s.phase) {
            is RidePhase.Waiting -> stationMaps.linesServing(p.stationName)
            is RidePhase.Riding -> listOf(p.train.line)
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
            lastFetched[line] = now
            val (fetchedAt, result) = dataSource.fetch(line) ?: continue
            fetched += line
            when (result) {
                is PositionsResult.Ok -> {
                    val events = synchronized(tracker) { tracker.update(line, result.trains, fetchedAt) }
                    sessions.values.forEach { s -> synchronized(s) { s.onTrainEvents(events, fetchedAt) } }
                }
                // 키 오류·한도 초과 등 서울시 쪽 문제. 탑승은 그대로 두고(새 소식 없음 → 2분 뒤 STALE) 원인을 남긴다
                is PositionsResult.Failure -> log.warn("서울시 API 오류({}): {} {}", line.apiName, result.code, result.message)
                PositionsResult.NoData -> Unit
            }
        }
        sessions.values.forEach { s ->
            synchronized(s) { s.tick(now) }
            remember(s.id, s.phase)
        }
        return fetched
    }

    private fun expireIdle(now: Instant) {
        val idle = lastContact.filterValues { Duration.between(it, now) > idleTimeout }.keys
        for (id in idle) {
            sessions.remove(id)
            lastContact.remove(id)
            records.findById(id).ifPresent { it.endedAt = now }
            log.info("앱 소식이 {}분 넘게 없어 탑승 종료: {}", idleTimeout.toMinutes(), id)
        }
    }

    private fun remember(id: String, phase: RidePhase) {
        log.info("단계 {}: {}", id.take(8), phase)
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
        val phase = if (r.phase == "RIDING" && line != null && r.lastTrainNo != null) {
            RidePhase.Riding(TrainKey(line, r.lastTrainNo!!), r.boardedStationId ?: "", since)
        } else {
            RidePhase.Waiting(r.lastStation ?: r.originStation, since)
        }
        lastContact[id] = now
        log.info("배포 뒤 탑승 이어 감: {} {}", id, phase)
        return RideSession(id, r.originStation, r.destinationStation, r.startedAt, stationMaps, resumedPhase = phase)
    }
}
