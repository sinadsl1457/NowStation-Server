package com.nowstation.server.subway

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * 실시간 API 하루 호출 한도(1,000건) 관리. 서울 기준 날짜가 바뀌면 다시 센다.
 * 서버가 다시 시작되면 0부터 센다 → 같은 날 재시작이 잦으면 실제보다 적게 셀 수 있어 [reserve]만큼 남겨 둔다.
 */
class ApiCallBudget(
    private val clock: Clock,
    private val dailyLimit: Int = 1_000,
    private val reserve: Int = 50,
) {
    private var day: LocalDate = today()
    private val used = AtomicInteger(0)

    /** 한 번 쓸 수 있으면 세고 true, 한도에 닿았으면 false(호출하지 말 것). */
    @Synchronized
    fun tryUse(): Boolean {
        rollOverIfNewDay()
        if (used.get() >= dailyLimit - reserve) return false
        used.incrementAndGet()
        return true
    }

    @Synchronized
    fun usedToday(): Int {
        rollOverIfNewDay()
        return used.get()
    }

    private fun rollOverIfNewDay() {
        val now = today()
        if (now != day) {
            day = now
            used.set(0)
        }
    }

    /** 시계의 시간대와 상관없이 서울 날짜로 센다(서버가 UTC로 돌아도 한도는 서울 자정에 초기화). */
    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), SEOUL)

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
