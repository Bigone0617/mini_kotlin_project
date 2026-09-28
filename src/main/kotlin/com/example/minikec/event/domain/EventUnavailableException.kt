package com.example.minikec.event.domain

enum class EventUnavailableReason {
    EVENT_INACTIVE,
    EVENT_NOT_STARTED,
    EVENT_ENDED
}

class EventUnavailableException(
    val reason: EventUnavailableReason,
    eventKey: String
) : RuntimeException("${reason.name}: eventKey=$eventKey")
