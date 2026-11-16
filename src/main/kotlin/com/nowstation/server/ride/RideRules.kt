package com.nowstation.server.ride

import java.time.Duration

/**
 * 탑승 판단 기준값. 실기기 없이(에뮬레이터·녹화 데이터) 정한 값이라 실제 지하철에서 다시 맞춰야 한다.
 *
 * 폰은 출발을 바로 느끼지만, 서버가 열차 출발을 관측하는 건 API 지연(보통 30~50초, 최대 1~2분)과
 * 받아 오는 간격(15~20초)만큼 늦다. 그래서 "출발 관측 [departureLookBack] 전 ~ [departureLookAhead] 후"에
 * 폰이 움직이기 시작했으면 같은 출발로 본다.
 */
data class RideRules(
    val departureLookBack: Duration = Duration.ofSeconds(120),
    val departureLookAhead: Duration = Duration.ofSeconds(15),
    /** 폰 움직임이 서버에 늦게 도착할 수 있어, 판단을 이만큼 미룬다. */
    val motionUploadGrace: Duration = Duration.ofSeconds(30),
    /** 탄 열차 정보가 이보다 오래 갱신되지 않으면 "위치 확인 중". */
    val staleAfter: Duration = Duration.ofSeconds(120),
)
