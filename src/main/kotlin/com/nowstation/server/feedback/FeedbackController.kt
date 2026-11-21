package com.nowstation.server.feedback

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration

data class FeedbackRequest(
    @field:NotBlank @field:Size(max = 2000) val message: String,
    /** 답장받을 주소(선택). */
    @field:Email @field:Size(max = 200) val contact: String? = null,
    @field:Size(max = 20) val platform: String? = null,
    @field:Size(max = 40) val appVersion: String? = null,
    @field:Size(max = 100) val device: String? = null,
)

/** 앱 설정의 문의하기. 메일 앱을 열지 않고 앱 안에서 보낸다. */
@RestController
@RequestMapping("/api")
class FeedbackController(private val feedback: FeedbackRepository, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    fun send(@Valid @RequestBody request: FeedbackRequest) {
        val saved = feedback.save(
            Feedback(
                createdAt = clock.instant(),
                message = request.message.trim(),
                contact = request.contact?.trim()?.ifEmpty { null },
                platform = request.platform,
                appVersion = request.appVersion,
                device = request.device,
            ),
        )
        // 운영자가 서버 로그에서 바로 보게(내용은 DB에서 읽는다)
        log.info("문의 {}: {} {} 답장 {}", saved.id, request.platform, request.appVersion, if (saved.contact != null) "있음" else "없음")
    }
}

/** 개인정보처리방침의 문의 보관 기간(1년)이 지난 글 지우기. */
@Component
class FeedbackCleanup(
    private val feedback: FeedbackRepository,
    private val clock: Clock,
    @Value("\${feedback.retention:P365D}") private val retention: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 40 4 * * *", zone = "Asia/Seoul")
    @Transactional
    fun run(): Int {
        val deleted = feedback.deleteCreatedBefore(clock.instant() - retention)
        if (deleted > 0) log.info("보관 기간이 지난 문의 {}건 삭제", deleted)
        return deleted
    }
}
