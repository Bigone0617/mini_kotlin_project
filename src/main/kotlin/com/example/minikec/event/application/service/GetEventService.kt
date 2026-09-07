package com.example.minikec.event.application.service

import com.example.minikec.event.application.port.input.GetEventUseCase
import com.example.minikec.event.application.port.output.EventRepositoryPort
import com.example.minikec.event.domain.Event
import org.springframework.stereotype.Service

@Service
class GetEventService(
    private val eventRepositoryPort: EventRepositoryPort
) : GetEventUseCase {

    override fun getByEventKey(eventKey: String): Event {
        return eventRepositoryPort.findByEventKey(eventKey)
            ?: throw IllegalArgumentException(
                "Event not found. eventKey=$eventKey"
            )
    }
}