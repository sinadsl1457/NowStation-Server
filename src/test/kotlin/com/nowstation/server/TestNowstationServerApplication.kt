package com.nowstation.server

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<NowstationServerApplication>().with(TestcontainersConfiguration::class).run(*args)
}
