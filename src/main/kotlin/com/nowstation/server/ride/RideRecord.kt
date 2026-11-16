package com.nowstation.server.ride

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/** 탑승 기록(V1__create_ride.sql). 시작·끝과 마지막으로 따라간 열차·역. */
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
}

interface RideRecordRepository : JpaRepository<RideRecord, String>
