package com.nowstation.server.ride

import java.time.Instant

/** 폰이 알려 준 움직임 변화. MOVING은 탈것의 움직임(걷기는 폰 쪽에서 거른다). */
enum class MotionKind { MOVING, STOPPED }

data class MotionEvent(val kind: MotionKind, val at: Instant)

/** 한 탑승 세션의 움직임 기록. 늦게 도착한 것도 시각 순서로 끼워 넣는다. */
class MotionLog {
    private val events = mutableListOf<MotionEvent>()

    fun add(event: MotionEvent) {
        val index = events.indexOfFirst { it.at > event.at }.let { if (it < 0) events.size else it }
        events.add(index, event)
    }

    /** [from]~[to] 사이에 움직이기 시작한 시각들. */
    fun movingStartsBetween(from: Instant, to: Instant): List<Instant> =
        events.filter { it.kind == MotionKind.MOVING && it.at in from..to }.map { it.at }

    /**
     * 열차가 떠날 때 폰도 같이 움직였는지: [from]~[to] 사이에 움직이기 시작했거나, 그 전부터 한 번도 멈추지 않고 움직이던 경우.
     * "그 전부터 움직였음"만 보면 역에 들어오기 전(접근 중) 움직임까지 세서, 내린 사람을 계속 타는 것으로 본다.
     */
    fun movedWithDeparture(from: Instant, to: Instant): Boolean =
        movingStartsBetween(from, to).isNotEmpty() ||
            (kindAt(from) == MotionKind.MOVING && events.none { it.kind == MotionKind.STOPPED && it.at in from..to })

    /** [at] 시점의 마지막 상태. 기록이 없으면 null. */
    fun kindAt(at: Instant): MotionKind? = events.lastOrNull { it.at <= at }?.kind

    /** [after] 뒤에 마지막으로 바뀐 상태. */
    fun lastAfter(after: Instant): MotionEvent? = events.lastOrNull { it.at > after }
}
