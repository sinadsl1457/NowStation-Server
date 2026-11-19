package com.nowstation.server.ride

import com.nowstation.server.subway.StationMaps
import com.nowstation.server.subway.SubwayLine
import java.time.Clock
import java.time.Duration
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class StationResponse(val name: String, val lines: List<String>)
data class StartRideRequest(@field:NotBlank val originStation: String, val destinationStation: String? = null)
/** 앱은 끊긴 동안 쌓인 움직임을 최대 50개까지 한꺼번에 보낸다. 그보다 크면 잘못된 요청으로 본다. */
data class MotionRequest(@field:NotEmpty @field:Size(max = 100) val events: List<MotionItem>, val sentAt: Instant)
data class MotionItem(val kind: MotionKind, val at: Instant)

/** 앱의 시험용 기록(운영체제의 "탈것" 판단, 버린 출발). 실기기 시험에서 무엇이 일어났는지 서버 로그로 본다. */
data class NotesRequest(@field:Size(max = 50) val lines: List<@Size(max = 200) String>)

/** 앱이 그대로 그리면 되는 모양. 노선은 표시 이름(2호선)으로 준다. */
data class RideResponse(
    val rideId: String,
    val status: RideStatus,
    val line: String?,
    val thisStation: String?,
    val nextStation: String?,
    val prepareToAlight: Boolean,
)

/** 고를 열차 하나. 앱은 "가천대 도착 · 왕십리행"처럼 보여 준다. status: ENTERING(진입)·ARRIVED(도착)·DEPARTED(출발) 등. */
data class TrainChoiceResponse(
    val line: String,
    val trainNo: String,
    val station: String,
    val status: String,
    val terminal: String?,
    /** 이 위치를 서버가 마지막으로 받은 지 몇 초. */
    val seenSecondsAgo: Long,
)
data class ChooseTrainRequest(@field:NotBlank val line: String, @field:NotBlank val trainNo: String)

private fun RideView.toResponse(id: String) =
    RideResponse(id, status, line?.apiName, thisStation, nextStation, prepareToAlight)

@RestController
@RequestMapping("/api")
class RideController(private val rides: RideService, private val stations: StationMaps, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)


    /** 노선은 표시 이름(2호선, 수인분당선)으로 준다. 탑승 조회 응답과 같은 형식. */
    @GetMapping("/stations")
    fun stations(@RequestParam(defaultValue = "") query: String): List<StationResponse> =
        stations.search(query).map { StationResponse(it.name, it.lines.map { line -> line.apiName }) }

    /** 지원하는 노선의 표시 이름. 역 검색 응답의 lines와 같은 이름. */
    @GetMapping("/lines")
    fun lines(): List<String> = SubwayLine.entries.map { it.apiName }

    @PostMapping("/rides")
    @ResponseStatus(HttpStatus.CREATED)
    fun start(@Valid @RequestBody request: StartRideRequest): RideResponse {
        val (id, view) = rides.start(request.originStation.trim(), request.destinationStation?.trim()?.ifEmpty { null })
        return view.toResponse(id)
    }

    @PostMapping("/rides/{id}/motion")
    fun motion(@PathVariable id: String, @Valid @RequestBody request: MotionRequest): RideResponse =
        rides.motion(id, request.events.map { it.kind to it.at }, request.sentAt).toResponse(id)

    @PostMapping("/rides/{id}/notes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun notes(@PathVariable id: String, @Valid @RequestBody request: NotesRequest) {
        request.lines.forEach { log.info("기록 {}: {}", id.take(8), it) }
    }

    @GetMapping("/rides/{id}/trains")
    fun trains(@PathVariable id: String): List<TrainChoiceResponse> {
        val now = clock.instant()
        return rides.trains(id).map {
            TrainChoiceResponse(it.key.line.apiName, it.key.trainNo, it.stationName, it.status.name, it.terminalName,
                Duration.between(it.seenAt, now).seconds.coerceAtLeast(0))
        }
    }

    @PostMapping("/rides/{id}/train")
    fun choose(@PathVariable id: String, @Valid @RequestBody request: ChooseTrainRequest): RideResponse {
        val line = SubwayLine.entries.firstOrNull { it.apiName == request.line } ?: throw UnknownTrainException(request.line, request.trainNo)
        return rides.choose(id, line, request.trainNo).toResponse(id)
    }

    @GetMapping("/rides/{id}")
    fun get(@PathVariable id: String): RideResponse = rides.view(id).toResponse(id)

    @DeleteMapping("/rides/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun end(@PathVariable id: String) = rides.end(id)

    @ExceptionHandler(UnknownStationException::class)
    fun unknownStation(e: UnknownStationException): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message ?: ""))

    @ExceptionHandler(UnknownTrainException::class)
    fun unknownTrain(e: UnknownTrainException): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message ?: ""))

    @ExceptionHandler(RideNotFoundException::class)
    fun notFound(e: RideNotFoundException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message ?: ""))
}
