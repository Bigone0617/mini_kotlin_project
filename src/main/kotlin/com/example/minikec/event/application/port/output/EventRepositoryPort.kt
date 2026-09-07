package com.example.minikec.event.application.port.output

import com.example.minikec.event.domain.Event

interface EventRepositoryPort {

    fun save(event: Event): Event

    fun findByEventKey(eventKey: String): Event?

    fun existsByEventKey(eventKey: String): Boolean
}