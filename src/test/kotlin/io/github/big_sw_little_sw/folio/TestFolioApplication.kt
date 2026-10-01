package io.github.big_sw_little_sw.folio

import org.springframework.boot.fromApplication
import org.springframework.boot.with

fun main(args: Array<String>) {
    fromApplication<FolioApplication>().with(TestcontainersConfiguration::class).run(*args)
}
