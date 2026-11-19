package com.nowstation.server.ride

import com.nowstation.server.TestcontainersConfiguration
import com.nowstation.server.recording.RecordingReader
import com.nowstation.server.subway.RealtimePositionParser
import com.nowstation.server.subway.SubwayLine
import com.nowstation.server.subway.TrainDataSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 서버 전체(Spring + MySQL)를 띄워 API로 확인한다. 열차 위치는 실제 API 대신 오전 녹화를 시계에 맞춰 돌려준다.
 * 시나리오는 RecordedScenarioTest의 환승과 같다(한티 → 선릉 하차 → 2호선 3154 → 강남 직전 알림).
 */
@SpringBootTest(properties = ["ride.polling.enabled=false"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RideApiTest.Replay::class)
class RideApiTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var rides: RideService
    @Autowired lateinit var clock: MovableClock
    @Autowired lateinit var records: RideRecordRepository
    @Autowired lateinit var txManager: org.springframework.transaction.PlatformTransactionManager
    @Autowired lateinit var stationMaps: com.nowstation.server.subway.StationMaps
    @Autowired lateinit var dataSource: TrainDataSource
    @Autowired lateinit var budget: com.nowstation.server.subway.ApiCallBudget

    private val json = JsonMapper.builder().build()

    class MovableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = now
    }

    @TestConfiguration
    class Replay {
        private val responses = RecordingReader().read(Path.of(Replay::class.java.getResource("/scenarios/2026-11-16-morning")!!.toURI()))

        @Bean @Primary
        fun movableClock() = MovableClock(kst("09:45:00"))

        /** 녹화에는 도착정보가 없고, 테스트가 실제 서울시 API를 부르지 않게. */
        @Bean @Primary
        fun noExpress() = com.nowstation.server.subway.ExpressTrains { _, _ -> emptySet() }

        /** 지금 시각에 받은 것처럼 그 노선의 가장 최근 녹화 응답을 돌려준다. */
        @Bean @Primary
        fun replayDataSource(clock: MovableClock): TrainDataSource {
            val parser = RealtimePositionParser()
            return TrainDataSource { line: SubwayLine ->
                responses.lastOrNull { it.line == line && it.fetchedAt <= clock.now }?.let { clock.now to parser.parse(line, it.body) }
            }
        }
    }

    private fun startRide(origin: String, destination: String?): String {
        val body = mvc.post("/api/rides") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"originStation":"$origin","destinationStation":${destination?.let { "\"$it\"" } ?: "null"}}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return json.readTree(body).path("rideId").asString()
    }

    /** 폰이 [at]에 기록한 움직임을 [sentAt]에 보낸다. */
    private fun motion(id: String, kind: String, at: String, sentAt: String = at) {
        clock.now = kst(sentAt)
        mvc.post("/api/rides/$id/motion") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"events":[{"kind":"$kind","at":"${kst(at)}"}],"sentAt":"${kst(sentAt)}"}"""
        }.andExpect { status { isOk() } }
    }

    /** [until]까지 녹화 응답이 있던 시각마다 받아 오기를 돌린다. */
    private fun pollUntil(until: String) {
        val end = kst(until)
        val times = generateSequence(clock.now) { it.plusSeconds(20) }.takeWhile { it <= end }.toList() + end
        for (t in times) {
            clock.now = t
            rides.poll()
        }
    }

    @Test
    fun `서버가 지금 버전을 알려 준다(자동 배포가 적용 여부를 이것으로 확인)`() {
        mvc.get("/actuator/info").andExpect {
            status { isOk() }
            jsonPath("$.app.version") { value("dev") } // 이미지에서는 APP_VERSION = 커밋 번호
        }
    }

    @Test
    fun `역 검색은 이름으로 찾고 여러 노선에 있는 역은 한 번만`() {
        mvc.get("/api/stations?query=선릉").andExpect {
            status { isOk() }
            jsonPath("$[0].name") { value("선릉") }
            jsonPath("$[0].lines.length()") { value(2) }
            jsonPath("$[0].lines[0]") { value("2호선") }
        }
    }

    @Test
    fun `노선 목록은 지원하는 노선의 표시 이름`() {
        mvc.get("/api/lines").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(3) }
            jsonPath("$[0]") { value("2호선") }
        }
    }

    @Test
    fun `모르는 역으로 시작하면 400, 없는 탑승은 404`() {
        mvc.post("/api/rides") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"originStation":"없는역"}"""
        }.andExpect { status { isBadRequest() } }
        mvc.get("/api/rides/nope").andExpect { status { isNotFound() } }
    }

    @Test
    fun `앱 소식이 10분 넘게 없으면 탑승을 끝내고 그 노선을 더 받아 오지 않는다`() {
        clock.now = kst("09:45:00")
        val id = startRide("태평", null)
        assertEquals(setOf(SubwayLine.SUIN_BUNDANG), rides.poll())

        clock.now = kst("09:54:00") // 9분: 아직 살아 있음(앱이 지하에서 잠깐 끊겼을 수 있다)
        mvc.get("/api/rides/$id").andExpect { status { isOk() } }
        clock.now = kst("10:04:30") // 마지막 조회 뒤 10분 30초
        val fetched = rides.poll()

        assertEquals(emptySet(), fetched)
        mvc.get("/api/rides/$id").andExpect { status { isNotFound() } }
        assertEquals(kst("10:04:30"), records.findById(id).orElseThrow().endedAt)
    }

    @Test
    fun `90일이 지난 탑승 기록만 지운다`() {
        records.save(RideRecord("old", "태평", null, kst("09:00:00").minus(java.time.Duration.ofDays(91))))
        records.save(RideRecord("recent", "태평", null, kst("09:00:00").minus(java.time.Duration.ofDays(89))))
        clock.now = kst("09:00:00")

        // 실제로는 Spring이 @Transactional로 감싼다. 테스트에서는 polling을 꺼서 빈이 없으므로 직접 감싼다
        org.springframework.transaction.support.TransactionTemplate(txManager).execute {
            RideRecordCleanup(records, clock, java.time.Duration.ofDays(90)).run()
        }

        assertEquals(false, records.existsById("old"))
        assertEquals(true, records.existsById("recent"))
    }

    @Test
    fun `움직임을 한 번에 너무 많이 보내면 400`() {
        clock.now = kst("09:45:00")
        val id = startRide("태평", null)
        val events = (1..101).joinToString(",") { """{"kind":"MOVING","at":"${kst("09:45:00")}"}""" }
        mvc.post("/api/rides/$id/motion") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"events":[$events],"sentAt":"${kst("09:45:00")}"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `앱의 시험용 기록은 받아서 로그로만 남기고, 너무 많으면 400`() {
        clock.now = kst("09:45:00")
        val id = startRide("태평", "야탑")
        mvc.post("/api/rides/$id/notes") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"lines":["운영체제 판단 탈것 09:44:58"]}"""
        }.andExpect { status { isNoContent() } }
        val many = (1..51).joinToString(",") { "\"줄 $it\"" }
        mvc.post("/api/rides/$id/notes") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"lines":[$many]}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `탑승을 시작하면 경로 방향 노선도를 받아 다음 열차를 골라 두고, 다른 열차를 고를 수 있다`() {
        clock.now = kst("09:46:00")
        val id = startRide("한티", "강남")
        val started = json.readTree(mvc.get("/api/rides/$id").andReturn().response.contentAsString)
        assertEquals<List<String>>(listOf("수인분당선 한티→선릉", "2호선 선릉→강남"), started.path("legs").toList().map { "${it.path("line").asString()} ${it.path("from").asString()}→${it.path("to").asString()}" })

        val body = mvc.get("/api/rides/$id/trains").andExpect { status { isOk() } }.andReturn().response.contentAsString
        val strip = json.readTree(body)
        assertEquals("선릉", strip.path("leg").path("to").asString())
        assertEquals("한티", strip.path("stations")[strip.path("boardIndex").asInt()].asString())
        val trains = strip.path("trains")
        assert(trains.size() > 0) { "녹화 시각에 한티 → 선릉 쪽 열차가 있어야 한다: $body" }
        val other = trains.toList().firstOrNull { !it.path("selected").asBoolean() } ?: trains[0]

        mvc.post("/api/rides/$id/train") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"line":"수인분당선","trainNo":"${other.path("trainNo").asString()}"}"""
        }.andExpect { status { isOk() }; jsonPath("$.trainNo") { value(other.path("trainNo").asString()) }; jsonPath("$.target") { value("선릉") } }

        mvc.post("/api/rides/$id/train") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"line":"수인분당선","trainNo":"없는열차"}"""
        }.andExpect { status { isBadRequest() } }
        mvc.delete("/api/rides/$id")
    }

    @Test
    fun `한티에서 시작해 선릉 환승 후 강남 직전 알림까지 API로`() {
        clock.now = kst("09:45:00")
        val id = startRide("한티", "강남")
        // 시작하자마자 다음에 들어올 열차를 골라 둔다
        mvc.get("/api/rides/$id").andExpect { jsonPath("$.trainNo") { exists() }; jsonPath("$.target") { value("선릉") } }

        motion(id, "STOPPED", "09:45:00")
        pollUntil("09:47:10")
        motion(id, "MOVING", "09:47:15")
        // 지하라 정차 기록이 늦게 올라감(폰 시계가 서버와 달라도 '보낸 시각과의 차이'로 맞춤)
        motion(id, "STOPPED", at = "09:47:45", sentAt = "09:48:20")
        pollUntil("09:49:30")
        // 골라 둔 수인분당 열차(6062)가 선릉에 옴 → 환승 알림, 2호선 강남 방면 다음 열차를 골라 둠
        mvc.get("/api/rides/$id").andExpect { jsonPath("$.notice") { value("TRANSFER") }; jsonPath("$.target") { value("강남") }; jsonPath("$.line") { value("2호선") } }
        pollUntil("09:51:40")
        motion(id, "MOVING", "09:51:45")
        pollUntil("09:52:35")
        mvc.post("/api/rides/$id/train") { // 사용자가 노선도에서 탈 2호선 열차(3154)를 누른다
            contentType = MediaType.APPLICATION_JSON
            content = """{"line":"2호선","trainNo":"3154"}"""
        }.andExpect { status { isOk() } }
        motion(id, "STOPPED", "09:52:40")
        pollUntil("09:54:00")
        motion(id, "MOVING", "09:54:05")
        pollUntil("09:54:40")

        mvc.get("/api/rides/$id").andExpect {
            jsonPath("$.status") { value("MOVING") }
            jsonPath("$.line") { value("2호선") }
            jsonPath("$.thisStation") { value("강남") }
            jsonPath("$.nextStation") { value("교대") }
            jsonPath("$.prepareToAlight") { value(true) }
        }
        assertEquals("3154", records.findById(id).get().lastTrainNo)

        mvc.delete("/api/rides/$id").andExpect { status { isNoContent() } }
        mvc.get("/api/rides/$id").andExpect { status { isNotFound() } }
    }

    /** 배포로 새로 켜진 서버: 메모리는 비어 있고 DB만 같다. */
    private fun newServer() = RideService(stationMaps, dataSource, records, budget, clock)

    private fun <T> inTx(block: () -> T): T =
        org.springframework.transaction.support.TransactionTemplate(txManager).execute { block() }!!

    @Test
    fun `배포로 서버가 바뀌어도 타고 있던 열차를 이어서 따라간다`() {
        clock.now = kst("09:45:00")
        val id = startRide("한티", "강남")
        motion(id, "STOPPED", "09:45:00")
        pollUntil("09:47:10")
        motion(id, "MOVING", "09:47:15")
        motion(id, "STOPPED", at = "09:47:45", sentAt = "09:48:20")
        pollUntil("09:51:40")
        motion(id, "MOVING", "09:51:45")
        pollUntil("09:52:35")
        rides.choose(id, SubwayLine.LINE_2, "3154") // 선릉 환승: 사용자가 노선도에서 탈 열차(3154)를 누른다
        motion(id, "STOPPED", "09:52:40")
        pollUntil("09:54:00")
        motion(id, "MOVING", "09:54:05")
        pollUntil("09:54:20") // 여기까지 옛 서버. 2호선 3154를 타고 있다

        try {
            val next = newServer()
            clock.now = kst("09:54:40")
            val view = next.view(id) // 새 서버는 장부(DB)를 보고 이어받는다
            inTx { next.poll() }
            val after = next.view(id)

            assertEquals(SubwayLine.LINE_2, view.line)
            assertEquals(SubwayLine.LINE_2, after.line)
            assertEquals("강남", after.thisStation)
            assertEquals(true, after.prepareToAlight)
        } finally {
            rides.end(id) // 같은 Spring 서버를 다른 테스트도 쓴다
        }
    }

    @Test
    fun `갈아타려고 기다리던 역(골라 둔 다음 열차)도 이어 간다`() {
        clock.now = kst("09:45:00")
        val id = startRide("한티", "강남")
        motion(id, "STOPPED", "09:45:00")
        pollUntil("09:47:10")
        motion(id, "MOVING", "09:47:15")
        motion(id, "STOPPED", at = "09:47:45", sentAt = "09:48:20")
        pollUntil("09:51:40") // 선릉에서 내려 2호선을 기다리는 중

        try {
            // 선릉에서 환승: 2호선 다음 열차를 골라 둔 상태까지 이어 간다
            val detail = newServer().detail(id)

            assertEquals("2호선", detail.train?.line?.apiName)
            assertEquals("강남", detail.target)
        } finally {
            rides.end(id)
        }
    }

    @Test
    fun `서버가 꺼진 사이 앱도 10분 넘게 소식이 없던 탑승은 이어 가지 않는다`() {
        clock.now = kst("09:45:00")
        val id = startRide("태평", null)
        inTx { rides.poll() }

        try {
            clock.now = kst("09:56:00")
            kotlin.test.assertFailsWith<RideNotFoundException> { newServer().view(id) }
        } finally {
            rides.end(id)
        }
    }

    companion object {
        fun kst(hms: String): Instant = LocalTime.parse(hms).atDate(LocalDate.of(2026, 11, 16)).atZone(ZoneId.of("Asia/Seoul")).toInstant()
    }
}
