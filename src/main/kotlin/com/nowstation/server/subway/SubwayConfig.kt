package com.nowstation.server.subway

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Clock

@Configuration
class SubwayConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun apiCallBudget(clock: Clock) = ApiCallBudget(clock)

    @Bean
    fun realtimePositionParser() = RealtimePositionParser()

    @Bean
    fun seoulSubwayClient(
        builder: RestClient.Builder,
        @Value("\${seoul.subway.base-url}") baseUrl: String,
        @Value("\${seoul.subway.api-key}") apiKey: String,
        budget: ApiCallBudget,
        clock: Clock,
    ) = SeoulSubwayClient(builder.baseUrl(baseUrl).build(), apiKey, budget, clock)
}
