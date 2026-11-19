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
    /** 폰 움직임이 서버에 늦게 도착할 수 있어, 내림 판단을 이만큼 미룬다(타고 있는 동안은 앱이 바로 보낸다). */
    val motionUploadGrace: Duration = Duration.ofSeconds(30),
    /**
     * 탈 열차 판단은 더 오래 미룬다. 앱은 "출발"을 운영체제의 "탈것 탑승 중" 확인(최대 2분) 뒤에 보내므로
     * (책상 위 기울기·집 안 걷기를 출발로 착각한 실기기 오작동을 거르려고) 2분 + 보내는 시간.
     */
    val boardingMotionGrace: Duration = Duration.ofSeconds(150),
    /** 탄 열차 정보가 이보다 오래 갱신되지 않으면 "위치 확인 중". */
    val staleAfter: Duration = Duration.ofSeconds(120),
)
