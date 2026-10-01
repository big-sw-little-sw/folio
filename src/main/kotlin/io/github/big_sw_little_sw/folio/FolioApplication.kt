package io.github.big_sw_little_sw.folio

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class FolioApplication

fun main(args: Array<String>) {
    runApplication<FolioApplication>(*args)
}
