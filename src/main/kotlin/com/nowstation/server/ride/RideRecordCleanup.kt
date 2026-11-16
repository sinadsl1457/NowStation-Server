package com.nowstation.server.ride

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/** 탑승 기록은 [retention](개인정보처리방침: 90일)만 두고 매일 새벽 지운다. */
@Component
@ConditionalOnProperty("ride.polling.enabled", havingValue = "true", matchIfMissing = true)
class RideRecordCleanup(
    private val records: RideRecordRepository,
    private val clock: Clock,
    @Value("\${ride.record-retention:P90D}") private val retention: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    @Transactional
    fun run(): Int {
        val deleted = records.deleteStartedBefore(clock.instant() - retention)
        if (deleted > 0) log.info("보관 기간({}일)이 지난 탑승 기록 {}건 삭제", retention.toDays(), deleted)
        return deleted
    }
}
