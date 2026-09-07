package com.example.minikec.event.application.port.input

import com.example.minikec.event.domain.Event

interface CreateEventUseCase {

    fun create(event: Event): Event
}