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
    /** 경로 구간들(태평 ▶ 선릉, 선릉 ▶ 강남). 내릴 역이 없으면 빈 목록. */
    val legs: List<LegResponse> = emptyList(),
    /** 따라가는 열차 번호. 기다리는 중이면 null. */
    val trainNo: String? = null,
    /** false면 골라 둔 열차(아직 탄 것이 확인 안 됨). */
    val confirmed: Boolean = false,
    /** 이번 구간 끝(환승역·내릴 역)과 남은 정거장. */
    val target: String? = null,
    val remainingStops: Int? = null,
    /** MISSED(골라 둔 열차를 놓쳐 다음 열차로 바꿈)·SWITCHED(폰과 같이 출발한 다른 열차로 바꿈). 잠깐만 온다. */
    val notice: String? = null,
)
data class LegResponse(val line: String, val from: String, val to: String)

/** 가로 노선도. stations는 구간 방향 순서, boardIndex는 타는 역 위치. 열차의 status: ENTERING(진입)·ARRIVED(도착)·DEPARTED(출발) 등. */
data class TrainStripResponse(
    val leg: LegResponse,
    val stations: List<String>,
    val boardIndex: Int,
    val trains: List<StripTrainResponse>,
)
data class StripTrainResponse(
    val trainNo: String,
    val station: String,
    val status: String,
    val terminal: String?,
    /** 지금 따라가는(골라 둔) 열차. */
    val selected: Boolean,
    /** 이 위치를 서버가 마지막으로 받은 지 몇 초. */
    val seenSecondsAgo: Long,
)
data class ChooseTrainRequest(@field:NotBlank val line: String, @field:NotBlank val trainNo: String)

private fun Leg.toResponse() = LegResponse(line.apiName, from, to)

private fun RideView.toResponse(id: String, d: RideDetail) =
    RideResponse(id, status, line?.apiName, thisStation, nextStation, prepareToAlight,
        d.route?.legs?.map { it.toResponse() }.orEmpty(), d.train?.trainNo, d.confirmed, d.target, d.remainingStops, d.notice?.name)

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
        return view.let { respond(id, it) }
    }

    @PostMapping("/rides/{id}/motion")
    fun motion(@PathVariable id: String, @Valid @RequestBody request: MotionRequest): RideResponse =
        rides.motion(id, request.events.map { it.kind to it.at }, request.sentAt).let { respond(id, it) }

    @PostMapping("/rides/{id}/notes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun notes(@PathVariable id: String, @Valid @RequestBody request: NotesRequest) {
        request.lines.forEach { log.info("기록 {}: {}", id.take(8), it) }
    }

    @GetMapping("/rides/{id}/trains")
    fun trains(@PathVariable id: String): ResponseEntity<TrainStripResponse> {
        val strip = rides.strip(id) ?: return ResponseEntity.noContent().build()
        val now = clock.instant()
        val selected = rides.detail(id).train
        return ResponseEntity.ok(TrainStripResponse(strip.leg.toResponse(), strip.stations, strip.boardIndex, strip.trains.map {
            StripTrainResponse(it.key.trainNo, it.stationName, it.status.name, it.terminalName, it.key == selected,
                Duration.between(it.seenAt, now).seconds.coerceAtLeast(0))
        }))
    }

    @PostMapping("/rides/{id}/train")
    fun choose(@PathVariable id: String, @Valid @RequestBody request: ChooseTrainRequest): RideResponse {
        val line = SubwayLine.entries.firstOrNull { it.apiName == request.line } ?: throw UnknownTrainException(request.line, request.trainNo)
        return rides.choose(id, line, request.trainNo).let { respond(id, it) }
    }

    @GetMapping("/rides/{id}")
    fun get(@PathVariable id: String): RideResponse = rides.view(id).let { respond(id, it) }

    @DeleteMapping("/rides/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun end(@PathVariable id: String) = rides.end(id)

    private fun respond(id: String, view: RideView) = view.toResponse(id, rides.detail(id))

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
