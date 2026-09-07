package com.example.minikec.event.adapter.input.web

import com.example.minikec.event.domain.Event
import java.time.Instant

data class EventResponse(
    val eventKey: String,
    val gameKey: String,
    val name: String,
    val active: Boolean,
    val eventStartAt: Instant?,
    val eventEndAt: Instant?
) {

    companion object {

        fun from(event: Event): EventResponse {
            return EventResponse(
                eventKey = event.eventKey,
                gameKey = event.gameKey,
                name = event.name,
                active = event.active,
                eventStartAt = event.eventStartAt,
                eventEndAt = event.eventEndAt
            )
        }
    }
}