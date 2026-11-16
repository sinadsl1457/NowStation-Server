package com.nowstation.server.ride

import com.nowstation.server.recording.RecordingReader
import com.nowstation.server.subway.PositionsResult
import com.nowstation.server.subway.RealtimePositionParser
import com.nowstation.server.subway.StationMaps
import com.nowstation.server.subway.SubwayLine.LINE_2
import com.nowstation.server.subway.SubwayLine.SUIN_BUNDANG
import com.nowstation.server.tracking.TrainKey
import com.nowstation.server.tracking.TrainTracker
import java.nio.file.Path
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 실제 녹화(2026-11-16 09:37~10:00, 평일 오전) 위에서 폰 움직임만 지어 넣어 확인한다.
 * 열차 위치는 반대 방향·다른 노선 잡음까지 실제 그대로다(scripts/extract_fixture.py로 뽑음).
 * 폰 시각은 녹화의 관측 시각에서 API 지연(30~50초)을 빼 실제로 있었을 법한 값으로 정했다.
 */
class RecordedScenarioTest {
    private val maps = StationMaps.load()
    private val parser = RealtimePositionParser()
    private val responses = RecordingReader().read(Path.of(javaClass.getResource("/scenarios/2026-11-16-morning")!!.toURI()))

    private fun kst(hms: String): Instant =
        LocalTime.parse(hms).atDate(java.time.LocalDate.of(2026, 11, 16)).atZone(ZoneId.of("Asia/Seoul")).toInstant()

    /** 녹화 응답과 폰 움직임을 시각 순서대로 섞어 흘리고, [until]까지 진행한 세션과 추적기를 돌려준다. */
    private fun play(session: RideSession, motions: List<MotionEvent>, until: Instant, tracker: TrainTracker = TrainTracker()): TrainTracker {
        val queue = (responses.filter { it.fetchedAt <= until }.map { it.fetchedAt to it } + motions.map { it.at to it })
            .sortedBy { it.first }
        for ((at, item) in queue) {
            when (item) {
                is MotionEvent -> session.onMotion(item, at)
                is com.nowstation.server.subway.RawPositions -> {
                    val result = parser.parse(item.line, item.body)
                    if (result is PositionsResult.Ok) session.onTrainEvents(tracker.update(item.line, result.trains, at), at)
                }
            }
            session.tick(at)
        }
        session.tick(until)
        return tracker
    }

    private fun move(hms: String) = MotionEvent(MotionKind.MOVING, kst(hms))
    private fun stop(hms: String) = MotionEvent(MotionKind.STOPPED, kst(hms))

    @Test
    fun `태평 - 6064는 놓치고(폰 그대로) 6518에 탄다`() {
        // 6064: 태평 09:43:12 도착 관측 → 가천대 09:44:12 진입 관측 / 6518: 태평 09:52:37 도착 → 가천대 09:53:58 진입
        val session = RideSession("r", "태평", null, kst("09:40:00"), maps)
        val motions = listOf(stop("09:40:00"), move("09:53:05"), stop("09:53:45"))

        val tracker = play(session, motions, until = kst("09:54:20"))

        assertEquals(TrainKey(SUIN_BUNDANG, "6518"), assertIs<RidePhase.Riding>(session.phase).train)
        assertEquals(RideView(RideStatus.AT_STATION, SUIN_BUNDANG, "가천대", "복정", false), session.view(tracker, kst("09:54:20")))
    }

    @Test
    fun `한티에서 6062 타고 선릉에서 내려 2호선 3154로 갈아타면 강남 직전에 하차 알림`() {
        // 6062: 한티 09:46:14 진입·09:46:55 도착 → 선릉 09:47:55 진입·09:48:36 도착 → 선정릉 09:50:16 도착
        // 3154: 선릉 09:50:36 도착 → 09:52:17 출발 → 역삼 09:53:18 도착 → 09:54:38 출발 → 강남 09:54:58 도착
        val session = RideSession("r", "한티", "강남", kst("09:45:00"), maps)
        val motions = listOf(
            stop("09:45:00"),
            move("09:47:15"), stop("09:47:45"), // 한티 → 선릉
            // 선릉에서 내려 환승 통로를 걸음(탈것 움직임 아님)
            move("09:51:45"), stop("09:52:40"), // 3154 선릉 → 역삼
            move("09:54:05"), // 역삼 출발
        )

        val tracker = play(session, motions, until = kst("09:54:40"))

        assertEquals(TrainKey(LINE_2, "3154"), assertIs<RidePhase.Riding>(session.phase).train)
        assertEquals(RideView(RideStatus.MOVING, LINE_2, "강남", "교대", true), session.view(tracker, kst("09:54:40")))
    }
}
