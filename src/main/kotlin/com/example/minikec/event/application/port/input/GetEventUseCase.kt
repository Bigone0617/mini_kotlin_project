package com.example.minikec.event.application.port.input

import com.example.minikec.event.domain.Event

interface GetEventUseCase {

    fun getAll(): List<Event>

    fun getByEventKey(eventKey: String): Event
}