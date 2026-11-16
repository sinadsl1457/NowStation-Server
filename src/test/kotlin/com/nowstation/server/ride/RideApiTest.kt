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
    fun `역 검색은 이름으로 찾고 여러 노선에 있는 역은 한 번만`() {
        mvc.get("/api/stations?query=선릉").andExpect {
            status { isOk() }
            jsonPath("$[0].name") { value("선릉") }
            jsonPath("$[0].lines.length()") { value(2) }
            jsonPath("$[0].lines[0]") { value("2호선") }
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
    fun `한티에서 시작해 선릉 환승 후 강남 직전 알림까지 API로`() {
        clock.now = kst("09:45:00")
        val id = startRide("한티", "강남")
        mvc.get("/api/rides/$id").andExpect { jsonPath("$.status") { value("WAITING") }; jsonPath("$.thisStation") { value("한티") } }

        motion(id, "STOPPED", "09:45:00")
        pollUntil("09:47:10")
        motion(id, "MOVING", "09:47:15")
        // 지하라 정차 기록이 늦게 올라감(폰 시계가 서버와 달라도 '보낸 시각과의 차이'로 맞춤)
        motion(id, "STOPPED", at = "09:47:45", sentAt = "09:48:20")
        pollUntil("09:51:40")
        motion(id, "MOVING", "09:51:45")
        pollUntil("09:52:35")
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

    companion object {
        fun kst(hms: String): Instant = LocalTime.parse(hms).atDate(LocalDate.of(2026, 11, 16)).atZone(ZoneId.of("Asia/Seoul")).toInstant()
    }
}
