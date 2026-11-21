package com.nowstation.server.feedback

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

/** 앱 안 문의하기로 받은 글(V3). 탑승 기록과 묶지 않는다. */
@Entity
@Table(name = "feedback")
class Feedback(
    @Column(name = "created_at") val createdAt: Instant,
    @Column(name = "message") val message: String,
    @Column(name = "contact") val contact: String?,
    @Column(name = "platform") val platform: String?,
    @Column(name = "app_version") val appVersion: String?,
    @Column(name = "device") val device: String?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}

interface FeedbackRepository : JpaRepository<Feedback, Long> {
    @Modifying
    @Query("delete from Feedback f where f.createdAt < :cutoff")
    fun deleteCreatedBefore(cutoff: Instant): Int
}
