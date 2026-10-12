package io.github.big_sw_little_sw.folio

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class FolioApplication

fun main(args: Array<String>) {
    runApplication<FolioApplication>(*args)
}
