package com.terraworld

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TerraWorldApplication

fun main(args: Array<String>) {
    runApplication<TerraWorldApplication>(*args)
}
