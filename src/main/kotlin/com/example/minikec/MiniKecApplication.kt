package com.example.minikec

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MiniKecApplication

fun main(args: Array<String>) {
	runApplication<MiniKecApplication>(*args)
}
