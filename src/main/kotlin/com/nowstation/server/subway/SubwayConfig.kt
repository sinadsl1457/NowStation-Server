package com.nowstation.server.subway

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Clock

@Configuration
class SubwayConfig {

    /** 재생 모드(replay.enabled)에서는 ReplayConfig가 녹화 속 시각으로 흐르는 시계를 대신 만든다. */
    @Bean
    @ConditionalOnProperty("replay.enabled", havingValue = "false", matchIfMissing = true)
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun apiCallBudget(clock: Clock) = ApiCallBudget(clock)

    @Bean
    fun realtimePositionParser() = RealtimePositionParser()

    @Bean
    fun stationMaps() = StationMaps.load()

    @Bean
    @ConditionalOnProperty("replay.enabled", havingValue = "false", matchIfMissing = true)
    fun trainDataSource(client: SeoulSubwayClient, parser: RealtimePositionParser): TrainDataSource =
        LiveTrainDataSource(client, parser)

    @Bean
    @ConditionalOnProperty("replay.enabled", havingValue = "false", matchIfMissing = true)
    fun expressTrains(client: SeoulSubwayClient, clock: Clock): ExpressTrains = LiveExpressTrains(client, clock)

    @Bean
    fun seoulSubwayClient(
        builder: RestClient.Builder,
        @Value("\${seoul.subway.base-url}") baseUrl: String,
        @Value("\${seoul.subway.api-key}") apiKey: String,
        budget: ApiCallBudget,
        clock: Clock,
    ) = SeoulSubwayClient(builder.baseUrl(baseUrl).build(), apiKey, budget, clock)
}
