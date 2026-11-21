package com.nowstation.server.feedback

import com.nowstation.server.TestcontainersConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest(properties = ["ride.polling.enabled=false"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class FeedbackApiTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var feedback: FeedbackRepository
    @Autowired lateinit var cleanup: FeedbackCleanup

    private fun send(body: String) = mvc.post("/api/feedback") {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    @Test
    fun `문의를 받아 저장하고, 답장 주소는 적었을 때만 남긴다`() {
        feedback.deleteAll()
        send("""{"message":"  강남 도착이 늦어요 ","contact":"a@b.com","platform":"android","appVersion":"0.1.70","device":"Pixel"}""").andExpect { status { isCreated() } }
        send("""{"message":"좋아요","contact":""}""").andExpect { status { isCreated() } }
        val saved = feedback.findAll().sortedBy { it.id }
        assertEquals(listOf("강남 도착이 늦어요", "좋아요"), saved.map { it.message })
        assertEquals("a@b.com", saved[0].contact)
        assertNull(saved[1].contact)
    }

    @Test
    fun `빈 글, 너무 긴 글, 주소 모양이 아닌 답장 주소는 400`() {
        send("""{"message":"   "}""").andExpect { status { isBadRequest() } }
        send("""{"message":"${"가".repeat(2001)}"}""").andExpect { status { isBadRequest() } }
        send("""{"message":"안녕","contact":"메일 아님"}""").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `1년이 지난 문의만 지운다`() {
        feedback.deleteAll()
        feedback.save(Feedback(Instant.now().minusSeconds(366L * 24 * 3600), "오래됨", null, null, null, null))
        feedback.save(Feedback(Instant.now(), "새것", null, null, null, null))
        assertEquals(1, cleanup.run())
        assertEquals(listOf("새것"), feedback.findAll().map { it.message })
    }
}
