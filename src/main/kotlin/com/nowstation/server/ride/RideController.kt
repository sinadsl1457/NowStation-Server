package com.nowstation.server.ride

import com.nowstation.server.subway.StationMaps
import com.nowstation.server.subway.SubwayLine
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
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

/** 앱이 그대로 그리면 되는 모양. 노선은 표시 이름(2호선)으로 준다. */
data class RideResponse(
    val rideId: String,
    val status: RideStatus,
    val line: String?,
    val thisStation: String?,
    val nextStation: String?,
    val prepareToAlight: Boolean,
)

private fun RideView.toResponse(id: String) =
    RideResponse(id, status, line?.apiName, thisStation, nextStation, prepareToAlight)

@RestController
@RequestMapping("/api")
class RideController(private val rides: RideService, private val stations: StationMaps) {

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

    @GetMapping("/rides/{id}")
    fun get(@PathVariable id: String): RideResponse = rides.view(id).toResponse(id)

    @DeleteMapping("/rides/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun end(@PathVariable id: String) = rides.end(id)

    @ExceptionHandler(UnknownStationException::class)
    fun unknownStation(e: UnknownStationException): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message ?: ""))

    @ExceptionHandler(RideNotFoundException::class)
    fun notFound(e: RideNotFoundException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message ?: ""))
}
