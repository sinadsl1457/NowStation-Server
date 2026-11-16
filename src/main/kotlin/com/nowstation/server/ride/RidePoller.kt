package com.nowstation.server.ride

import com.nowstation.server.subway.ApiCallBudget
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 탑승 중인 사람이 있을 때만 받아 온다. 아무도 없으면 API를 부르지 않는다. 테스트에서는 ride.polling.enabled=false로 끈다. */
@Component
@ConditionalOnProperty("ride.polling.enabled", havingValue = "true", matchIfMissing = true)
class RidePoller(private val rides: RideService, private val budget: ApiCallBudget) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ride.poll-interval:PT15S}")
    fun poll() {
        val lines = rides.poll()
        if (lines.isNotEmpty()) log.info("받아 옴: {} (오늘 {}건)", lines.joinToString { it.apiName }, budget.usedToday())
    }
}
