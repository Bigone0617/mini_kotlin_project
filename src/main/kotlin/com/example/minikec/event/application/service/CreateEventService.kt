package com.example.minikec.event.application.service

import com.example.minikec.event.application.port.input.CreateEventUseCase
import com.example.minikec.event.application.port.output.EventRepositoryPort
import com.example.minikec.event.domain.Event
import org.springframework.stereotype.Service

@Service
class CreateEventService(
    private val eventRepositoryPort: EventRepositoryPort
) : CreateEventUseCase {

    override fun create(event: Event): Event {

        if (eventRepositoryPort.existsByEventKey(event.eventKey)) {
            throw IllegalArgumentException(
                "Event already exists. eventKey=${event.eventKey}"
            )
        }

        return eventRepositoryPort.save(event)
    }
}