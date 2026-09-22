package com.ostracker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class OsTrackerApplication

fun main(args: Array<String>) {
    runApplication<OsTrackerApplication>(*args)
}
