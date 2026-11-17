package com.nowstation.server.ride

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

/** 탑승 기록(V1, V2). 시작·끝과 마지막 상태. 배포로 서버가 바뀌면 새 서버가 이 값으로 진행 중인 탑승을 이어 간다. */
@Entity
@Table(name = "ride")
class RideRecord(
    @Id val id: String,
    @Column(name = "origin_station") val originStation: String,
    @Column(name = "destination_station") val destinationStation: String?,
    @Column(name = "started_at") val startedAt: Instant,
) {
    @Column(name = "ended_at") var endedAt: Instant? = null
    @Column(name = "last_line") var lastLine: String? = null
    @Column(name = "last_train_no") var lastTrainNo: String? = null
    @Column(name = "last_station") var lastStation: String? = null
    @Column(name = "phase") var phase: String? = null
    @Column(name = "phase_since") var phaseSince: Instant? = null
    @Column(name = "boarded_station_id") var boardedStationId: String? = null
    @Column(name = "last_contact_at") var lastContactAt: Instant? = null
}

interface RideRecordRepository : JpaRepository<RideRecord, String> {
    /** 개인정보처리방침의 보관 기간이 지난 기록 지우기. 지운 수를 돌려준다. */
    @Modifying
    @Query("delete from RideRecord r where r.startedAt < :cutoff")
    fun deleteStartedBefore(cutoff: Instant): Int
}
