package com.example.marketplace

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.example.marketplace"])
@ConfigurationPropertiesScan
class MarketplaceApiApplication

fun main(args: Array<String>) {
    runApplication<MarketplaceApiApplication>(*args)
}
