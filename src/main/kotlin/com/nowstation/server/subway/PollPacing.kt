package com.nowstation.server.subway

import java.time.Duration

/**
 * 하루 호출 한도에 맞춘 받아 오기 간격. 넉넉할 때는 기본 간격 그대로 두고,
 * 얼마 안 남으면 남은 호출로 서울 자정까지 버티도록 간격을 늘린다.
 * 다만 [max]보다 늘리지 않는다: 역 사이가 2분 안팎이라 1분 넘게 늦은 데이터로는 따라갈 수 없다(차라리 끊김으로 보여 준다).
 */
object PollPacing {
    fun interval(
        base: Duration,
        remaining: Int,
        lines: Int,
        untilMidnight: Duration,
        slowBelow: Int = 300,
        max: Duration = Duration.ofSeconds(60),
    ): Duration? {
        if (remaining <= 0) return null
        if (lines == 0 || remaining >= slowBelow) return base
        val spread = untilMidnight.multipliedBy(lines.toLong()).dividedBy(remaining.toLong())
        return spread.coerceIn(base, max)
    }
}
