package com.nowstation.server.ride

import com.nowstation.server.subway.StationMaps
import com.nowstation.server.subway.SubwayLine
import com.nowstation.server.subway.SubwayLine.LINE_2
import com.nowstation.server.subway.SubwayLine.SUIN_BUNDANG
import com.nowstation.server.subway.TrainPosition
import com.nowstation.server.subway.TrainStatus
import com.nowstation.server.subway.TrainStatus.ARRIVED
import com.nowstation.server.subway.TrainStatus.DEPARTED
import com.nowstation.server.subway.TrainStatus.ENTERING
import com.nowstation.server.tracking.TrainKey
import com.nowstation.server.tracking.TrainTracker
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 실제 역 데이터 위에서 시간 순서대로 열차 위치·폰 움직임을 흘려 넣어 확인한다. 시각은 시작부터 초. */
class RideSessionTest {
    private val maps = StationMaps.load()
    private val t0: Instant = Instant.parse("2026-11-16T23:20:00Z")
    private fun at(s: Long) = t0.plusSeconds(s)

    private inner class Harness(origin: String, destination: String? = null) {
        val tracker = TrainTracker()
        val session = RideSession("r1", origin, destination, at(0), maps)

        /** 한 노선에서 받은 위치. updn: 수인분당 "0"=왕십리 방면, 2호선 "0"=내선. */
        fun trains(s: Long, line: SubwayLine, vararg trains: Triple<String, String, TrainStatus>, updn: String = "0") {
            val positions = trains.map { (no, stationName, status) ->
                val id = maps[line].paths.flatMap { it.stations }.first { it.name == stationName }.id
                TrainPosition(line, no, id, stationName, status, updn, "", "", null, express = false, lastTrain = false)
            }
            session.onTrainEvents(tracker.update(line, positions, at(s)), at(s))
        }

        fun moving(s: Long) = session.onMotion(MotionEvent(MotionKind.MOVING, at(s)), at(s))
        fun stopped(s: Long) = session.onMotion(MotionEvent(MotionKind.STOPPED, at(s)), at(s))
        fun tick(s: Long) = session.tick(at(s))
        fun view(s: Long) = session.view(tracker, at(s))
        fun riding() = assertIs<RidePhase.Riding>(session.phase).train
    }

    @Test
    fun `태평에서 기다리다 폰과 같이 출발한 열차에 탄다`() {
        val h = Harness("태평")
        h.trains(0, SUIN_BUNDANG, Triple("6050", "태평", ARRIVED))
        h.moving(30) // 실제 출발. 서버는 40초 뒤에야 다음 역에서 열차를 본다
        h.trains(70, SUIN_BUNDANG, Triple("6050", "가천대", ENTERING))

        assertEquals(TrainKey(SUIN_BUNDANG, "6050"), h.riding())
        assertEquals(RideView(RideStatus.MOVING, SUIN_BUNDANG, "가천대", "복정", false), h.view(70))
    }

    @Test
    fun `첫 열차를 놓치면 무시하고 폰과 같이 출발한 다음 열차를 따라간다`() {
        val h = Harness("태평")
        h.stopped(0)
        h.trains(0, SUIN_BUNDANG, Triple("A", "태평", ARRIVED))
        h.trains(70, SUIN_BUNDANG, Triple("A", "가천대", ENTERING)) // A 출발, 폰은 그대로
        h.tick(120)
        assertIs<RidePhase.Waiting>(h.session.phase)

        h.trains(300, SUIN_BUNDANG, Triple("A", "복정", ARRIVED), Triple("B", "태평", ARRIVED))
        h.moving(330)
        h.trains(370, SUIN_BUNDANG, Triple("A", "수서", ENTERING), Triple("B", "가천대", ENTERING))

        assertEquals("B", h.riding().trainNo)
    }

    @Test
    fun `한티에서 5분 정차하면 그동안 이번 역 한티, 다음 역 선릉으로 유지한다`() {
        val h = Harness("태평")
        h.trains(0, SUIN_BUNDANG, Triple("T", "태평", ARRIVED))
        h.moving(30)
        h.trains(70, SUIN_BUNDANG, Triple("T", "가천대", ENTERING))
        h.stopped(900)
        h.trains(940, SUIN_BUNDANG, Triple("T", "한티", ARRIVED))

        for (s in 940L..1240L step 20) {
            h.trains(s, SUIN_BUNDANG, Triple("T", "한티", ARRIVED))
            assertEquals(RideView(RideStatus.AT_STATION, SUIN_BUNDANG, "한티", "선릉", false), h.view(s), "${s}초")
        }
        assertIs<RidePhase.Riding>(h.session.phase)
    }

    @Test
    fun `선릉에서 내려 2호선으로 갈아타면 2호선 열차를 따라가고 강남 직전에 하차 알림`() {
        val h = Harness("태평", destination = "강남")
        h.trains(0, SUIN_BUNDANG, Triple("T", "태평", ARRIVED))
        h.moving(30)
        h.trains(70, SUIN_BUNDANG, Triple("T", "가천대", ENTERING))
        h.stopped(1000)
        h.trains(1040, SUIN_BUNDANG, Triple("T", "선릉", ARRIVED))
        // 수인분당 열차는 떠나는데 폰은 멈춘 채(내려서 걸어감)
        h.trains(1100, SUIN_BUNDANG, Triple("T", "선정릉", ENTERING))
        h.tick(1150)
        assertEquals("선릉", assertIs<RidePhase.Waiting>(h.session.phase).stationName)

        h.trains(1200, LINE_2, Triple("2222", "선릉", ARRIVED))
        h.moving(1230)
        h.trains(1260, LINE_2, Triple("2222", "선릉", DEPARTED))

        assertEquals(TrainKey(LINE_2, "2222"), h.riding())
        assertEquals(RideView(RideStatus.MOVING, LINE_2, "역삼", "강남", false), h.view(1260))

        h.trains(1320, LINE_2, Triple("2222", "역삼", ARRIVED))
        h.stopped(1300)
        h.moving(1340)
        h.trains(1380, LINE_2, Triple("2222", "역삼", DEPARTED))
        assertEquals(RideView(RideStatus.MOVING, LINE_2, "강남", "교대", true), h.view(1380))
    }

    @Test
    fun `역 사이 터널에서 멈추면 사이 정차로 보여 준다`() {
        val h = Harness("선릉")
        h.trains(0, LINE_2, Triple("2222", "선릉", ARRIVED))
        h.moving(20)
        h.trains(40, LINE_2, Triple("2222", "선릉", DEPARTED))
        h.stopped(60)

        assertEquals(RideView(RideStatus.HOLDING_BETWEEN, LINE_2, "역삼", "강남", false), h.view(80))
    }

    @Test
    fun `탄 열차 소식이 2분 넘게 없으면 위치 확인 중`() {
        val h = Harness("태평")
        h.trains(0, SUIN_BUNDANG, Triple("T", "태평", ARRIVED))
        h.moving(30)
        h.trains(70, SUIN_BUNDANG, Triple("T", "가천대", ENTERING))

        assertEquals(RideStatus.STALE, h.view(70 + 121).status)
    }

    @Test
    fun `폰 움직임이 열차 출발 관측보다 늦게 도착해도 탄 열차를 찾는다`() {
        val h = Harness("태평")
        h.trains(0, SUIN_BUNDANG, Triple("T", "태평", ARRIVED))
        h.trains(70, SUIN_BUNDANG, Triple("T", "가천대", ENTERING))
        // 지하라 업로드가 늦어 80초에 도착한 30초 시각의 움직임
        h.session.onMotion(MotionEvent(MotionKind.MOVING, at(30)), at(80))

        assertEquals("T", h.riding().trainNo)
    }

    @Test
    fun `수인분당선은 출발을 다음 역에서야 알아도(2분 넘게 늦게) 탄 열차를 찾고, 다음 역 정차를 내림으로 오해하지 않는다`() {
        val h = Harness("태평")
        h.stopped(0)
        h.trains(0, SUIN_BUNDANG, Triple("T", "태평", ARRIVED))
        h.moving(30) // 실제 출발
        h.trains(60, SUIN_BUNDANG, Triple("T", "태평", ARRIVED)) // 응답은 아직 태평 도착
        h.trains(120, SUIN_BUNDANG, Triple("T", "태평", ARRIVED))
        h.stopped(130) // 가천대 도착(폰)
        h.trains(170, SUIN_BUNDANG, Triple("T", "가천대", ENTERING)) // 그제야 태평 출발을 앎(움직임 140초 뒤)
        h.tick(240)
        assertEquals("T", h.riding().trainNo)

        h.moving(170) // 가천대 출발
        h.trains(320, SUIN_BUNDANG, Triple("T", "복정", ENTERING)) // 가천대 출발 관측(150초 뒤)
        h.stopped(300)
        h.tick(400)
        assertEquals("T", h.riding().trainNo, "가천대에서 내린 게 아니다")
    }
}
