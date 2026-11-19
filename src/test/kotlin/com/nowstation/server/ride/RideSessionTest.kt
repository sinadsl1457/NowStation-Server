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
    fun `내릴 역이 있으면 반대 방향 열차가 떠날 때 폰이 흔들려도 타지 않고, 내릴 역 쪽 열차를 기다린다`() {
        val h = Harness("태평", "야탑")
        h.stopped(0)
        h.trains(0, SUIN_BUNDANG, Triple("W", "태평", ARRIVED), updn = "0") // 가천대·복정 방면
        h.moving(30) // 승강장에서 걷다 흔들림. 마침 반대편 W가 떠남
        h.stopped(50)
        h.trains(70, SUIN_BUNDANG, Triple("W", "가천대", ENTERING), updn = "0")
        h.tick(200)
        assertIs<RidePhase.Waiting>(h.session.phase, "야탑에 가지 않는 열차다")

        h.trains(300, SUIN_BUNDANG, Triple("Y", "태평", ARRIVED), updn = "1") // 모란·야탑 방면
        h.moving(330)
        h.trains(370, SUIN_BUNDANG, Triple("Y", "모란", ENTERING), updn = "1")

        assertEquals("Y", h.riding().trainNo)
        assertEquals(RideView(RideStatus.MOVING, SUIN_BUNDANG, "모란", "야탑", true), h.view(370), "다음 역이 야탑 → 한 정거장 전부터 내릴 준비")
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
        assertEquals(RideView(RideStatus.MOVING, LINE_2, "역삼", "강남", true), h.view(1260), "다음 역이 강남 → 한 정거장 전부터 내릴 준비")

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

    @Test
    fun `이미 타고 가다가 켤 때 출발역을 열차가 곧 설 역(개포동)으로 하면 내 열차를 찾는다`() {
        val h = Harness("개포동", "왕십리")
        h.trains(0, SUIN_BUNDANG, Triple("MINE", "대모산입구", ENTERING), Triple("BEHIND", "수서", ARRIVED))
        h.stopped(40) // 내 열차가 개포동에 섬
        h.trains(45, SUIN_BUNDANG, Triple("MINE", "개포동", ARRIVED), Triple("BEHIND", "대모산입구", ENTERING))
        h.moving(70) // 개포동 출발
        h.trains(110, SUIN_BUNDANG, Triple("MINE", "구룡", ENTERING), Triple("BEHIND", "대모산입구", ARRIVED))
        h.stopped(130) // 구룡에 섬
        h.moving(160) // 구룡 출발. 마침 뒤 열차가 개포동에 들어옴
        h.trains(170, SUIN_BUNDANG, Triple("MINE", "도곡", ENTERING), Triple("BEHIND", "개포동", ARRIVED))
        h.tick(200)

        assertEquals("MINE", h.riding().trainNo)
    }

    @Test
    fun `이미 지나온 역을 출발역으로 하면 뒤 열차를 탄 것으로 잘못 따라간다(현재 한계)`() {
        val h = Harness("개포동", "왕십리")
        // 내 열차는 이미 개포동을 떠나 구룡에 들어옴. 뒤 열차가 개포동에 서 있음
        h.trains(0, SUIN_BUNDANG, Triple("MINE", "구룡", ENTERING), Triple("BEHIND", "개포동", ARRIVED))
        h.stopped(20) // 구룡에 섬
        h.moving(50) // 구룡 출발 = 마침 뒤 열차도 개포동 출발
        h.trains(90, SUIN_BUNDANG, Triple("MINE", "도곡", ENTERING), Triple("BEHIND", "구룡", ENTERING))
        h.tick(130)

        assertEquals("BEHIND", h.riding().trainNo)
    }

    @Test
    fun `탄 열차가 이미 출발한 뒤에 누르면(자리에 앉고 나서) 출발역 태평으로는 내 열차를 못 찾는다(현재 한계)`() {
        val h = Harness("태평", "왕십리")
        // 내 열차는 누르기 20초 전에 태평을 떠남. 뒤 열차는 아직 멀리
        h.trains(0, SUIN_BUNDANG, Triple("MINE", "태평", ARRIVED))
        h.trains(40, SUIN_BUNDANG, Triple("MINE", "가천대", ENTERING))
        h.stopped(60) // 가천대에 섬
        h.moving(90) // 가천대 출발
        h.trains(130, SUIN_BUNDANG, Triple("MINE", "복정", ENTERING))
        h.tick(300)

        assertIs<RidePhase.Waiting>(h.session.phase)
    }

    @Test
    fun `사용자가 고른 열차는 폰 움직임 없이 바로 타고, 그 열차를 따라간다`() {
        val h = Harness("태평", "왕십리")
        h.trains(0, SUIN_BUNDANG, Triple("MINE", "가천대", ARRIVED), Triple("OTHER", "태평", ARRIVED))
        val gachon = maps[SUIN_BUNDANG].stationId("가천대")!!
        h.session.choose(TrainKey(SUIN_BUNDANG, "MINE"), gachon, at(5))

        assertEquals("MINE", h.riding().trainNo)
        h.trains(60, SUIN_BUNDANG, Triple("MINE", "복정", ENTERING), Triple("OTHER", "가천대", ENTERING))
        assertEquals(RideView(RideStatus.MOVING, SUIN_BUNDANG, "복정", "수서", false), h.view(60))
    }

    @Test
    fun `고른 열차는 폰이 가만히 있어도 그대로 따라가고, 환승역(선릉)에 오면 환승 알림 후 다음 구간을 기다린다`() {
        val h = Harness("태평", "강남")
        h.stopped(0)
        h.trains(0, SUIN_BUNDANG, Triple("6206", "모란", ENTERING))
        h.session.choose(TrainKey(SUIN_BUNDANG, "6206"), maps[SUIN_BUNDANG].stationId("태평")!!, at(0))
        h.trains(60, SUIN_BUNDANG, Triple("6206", "태평", ARRIVED))
        h.trains(120, SUIN_BUNDANG, Triple("6206", "가천대", ENTERING)) // 폰 움직임 없음: 그래도 따라간다
        h.tick(300)
        assertEquals("6206", h.riding().trainNo)
        assertEquals("선릉", h.session.detail(h.tracker, at(300)).target)

        h.trains(1000, SUIN_BUNDANG, Triple("6206", "선릉", ARRIVED))
        assertEquals("선릉", assertIs<RidePhase.Waiting>(h.session.phase).stationName)
        val d = h.session.detail(h.tracker, at(1000))
        assertEquals(RideNotice.TRANSFER, d.notice)
        assertEquals(LINE_2, h.session.wantsSuggestion()?.line, "서비스가 2호선 강남 방면 열차를 골라 두도록")
    }

    @Test
    fun `내릴 역에 오면 환승 대기로 바꾸지 않고 도착`() {
        val h = Harness("한티", "선릉")
        h.trains(0, SUIN_BUNDANG, Triple("T", "한티", ARRIVED))
        h.session.choose(TrainKey(SUIN_BUNDANG, "T"), maps[SUIN_BUNDANG].stationId("한티")!!, at(0))
        assertEquals(false, h.view(0).prepareToAlight, "아직 타는 역(한티)에 있다")
        h.trains(100, SUIN_BUNDANG, Triple("T", "선릉", ARRIVED))
        assertIs<RidePhase.Arrived>(h.session.phase)
        assertEquals(RideStatus.ARRIVED, h.view(100).status)
    }

    @Test
    fun `내릴 준비는 한 정거장 전부터(출발 상태가 없는 수인분당선도), 내릴 역에 오면 더 따라가지 않는다`() {
        val h = Harness("선릉", "태평")
        h.trains(0, SUIN_BUNDANG, Triple("9038", "복정", ARRIVED), updn = "1")
        h.session.choose(TrainKey(SUIN_BUNDANG, "9038"), maps[SUIN_BUNDANG].stationId("선릉")!!, at(0))
        assertEquals(false, h.view(0).prepareToAlight)

        h.trains(150, SUIN_BUNDANG, Triple("9038", "가천대", ARRIVED), updn = "1")
        assertEquals(true, h.view(150).prepareToAlight, "가천대에 서 있을 때 다음 역 태평 → 내릴 준비")

        h.trains(300, SUIN_BUNDANG, Triple("9038", "태평", ARRIVED), updn = "1")
        assertEquals(RidePhase.Arrived("태평", SUIN_BUNDANG, at(300)), h.session.phase)
        h.trains(450, SUIN_BUNDANG, Triple("9038", "모란", ARRIVED), updn = "1")
        assertEquals(RideStatus.ARRIVED, h.view(450).status)
        assertEquals("태평", h.view(450).thisStation, "열차가 모란으로 가도 태평 도착 그대로")
    }

    @Test
    fun `열차가 타는 역에 있는 동안에는 내릴 준비를 켜지 않는다(한 정거장 경로)`() {
        val h = Harness("태평", "모란")
        h.trains(0, SUIN_BUNDANG, Triple("T", "태평", ARRIVED), updn = "1")
        h.session.choose(TrainKey(SUIN_BUNDANG, "T"), maps[SUIN_BUNDANG].stationId("태평")!!, at(0))
        assertEquals(false, h.view(0).prepareToAlight, "태평에 서 있을 때 다음 역이 모란이어도 아직 아니다")
        h.trains(100, SUIN_BUNDANG, Triple("T", "모란", ENTERING), updn = "1")
        assertIs<RidePhase.Arrived>(h.session.phase)
    }

    @Test
    fun `공공 데이터가 도착을 늦게 보여 줘도 폰이 그 역에서 섰다가 떠나면 다음 역으로 넘긴다(센서가 먼저)`() {
        // 2026-11-16 녹화 6073 모란: 폰 정차 09:47:45 → 데이터 모란 진입 09:47:55 → 폰 출발 09:48:20 → 데이터 도착 09:48:36
        val h = Harness("태평", "야탑")
        h.trains(0, SUIN_BUNDANG, Triple("6073", "태평", ARRIVED), updn = "1")
        h.session.choose(TrainKey(SUIN_BUNDANG, "6073"), maps[SUIN_BUNDANG].stationId("태평")!!, at(0))
        h.moving(10)
        h.stopped(110)
        h.trains(120, SUIN_BUNDANG, Triple("6073", "모란", ENTERING), updn = "1")
        assertEquals("모란", h.view(120).thisStation)
        h.moving(145)
        assertEquals("야탑", h.view(150).thisStation, "데이터는 아직 모란 진입이지만 폰이 모란을 떠났다")
        assertEquals(true, h.view(150).prepareToAlight)
    }
}
