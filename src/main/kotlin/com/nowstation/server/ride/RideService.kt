package com.nowstation.server.ride

import com.nowstation.server.subway.PositionsResult
import com.nowstation.server.subway.StationMaps
import com.nowstation.server.subway.SubwayLine
import com.nowstation.server.subway.TrainDataSource
import com.nowstation.server.tracking.TrainTracker
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
 */
@Service
class RideService(
    private val stationMaps: StationMaps,
    private val dataSource: TrainDataSource,
    private val records: RideRecordRepository,
    private val clock: Clock,
) {
    private val sessions = ConcurrentHashMap<String, RideSession>()
    private val tracker = TrainTracker()

    @Transactional
    fun start(origin: String, destination: String?): Pair<String, RideView> {
        if (stationMaps.linesServing(origin).isEmpty()) throw UnknownStationException(origin)
        if (destination != null && stationMaps.linesServing(destination).isEmpty()) throw UnknownStationException(destination)
        val id = UUID.randomUUID().toString()
        val now = Instant.now(clock)
        sessions[id] = RideSession(id, origin, destination, now, stationMaps)
        records.save(RideRecord(id, origin, destination, now))
        return id to view(id)
    }

    /**
     * 폰 움직임. 폰 시계가 서버와 다를 수 있어 "보낸 시각과의 차이"만 믿는다:
     * 서버 시각 = 지금 - (폰이 보낸 시각 - 폰이 기록한 시각).
     */
    fun motion(id: String, events: List<Pair<MotionKind, Instant>>, sentAt: Instant): RideView {
        val session = session(id)
        val now = Instant.now(clock)
        synchronized(session) {
            for ((kind, at) in events) {
                val age = Duration.between(at, sentAt).coerceAtLeast(Duration.ZERO)
                session.onMotion(MotionEvent(kind, now - age), now)
            }
        }
        return view(id)
    }

    fun view(id: String): RideView {
        val session = session(id)
        val now = Instant.now(clock)
        return synchronized(session) { synchronized(tracker) { session.view(tracker, now) } }
    }

    @Transactional
    fun end(id: String) {
        sessions.remove(id) ?: throw RideNotFoundException(id)
        records.findById(id).ifPresent { it.endedAt = Instant.now(clock) }
    }

    /** 탑승 중인 사람에게 필요한 노선만: 기다리는 역이 있는 노선들 + 타고 있는 열차의 노선. */
    fun linesInUse(): Set<SubwayLine> = sessions.values.flatMap { s ->
        when (val p = s.phase) {
            is RidePhase.Waiting -> stationMaps.linesServing(p.stationName)
            is RidePhase.Riding -> listOf(p.train.line)
        }
    }.toSet()

    /** [RidePoller]가 주기적으로 부른다. 필요한 노선만 받아 모든 탑승에 알린다. */
    @Transactional
    fun poll() {
        val lines = linesInUse()
        for (line in lines) {
            val (fetchedAt, result) = dataSource.fetch(line) ?: continue
            if (result !is PositionsResult.Ok) continue
            val events = synchronized(tracker) { tracker.update(line, result.trains, fetchedAt) }
            sessions.values.forEach { s -> synchronized(s) { s.onTrainEvents(events, fetchedAt) } }
        }
        val now = Instant.now(clock)
        sessions.values.forEach { s ->
            synchronized(s) { s.tick(now) }
            remember(s.id, s.phase)
        }
    }

    private fun remember(id: String, phase: RidePhase) {
        val record = records.findById(id).orElse(null) ?: return
        when (phase) {
            is RidePhase.Riding -> {
                record.lastLine = phase.train.line.name
                record.lastTrainNo = phase.train.trainNo
            }
            is RidePhase.Waiting -> record.lastStation = phase.stationName
        }
    }

    private fun session(id: String) = sessions[id] ?: throw RideNotFoundException(id)
}
