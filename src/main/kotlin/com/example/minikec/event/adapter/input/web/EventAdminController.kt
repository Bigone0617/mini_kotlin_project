package com.example.minikec.event.adapter.input.web

import com.example.minikec.event.application.port.input.CreateEventUseCase
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/events")
class EventAdminController(
    private val createEventUseCase: CreateEventUseCase
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createEvent(
        @RequestBody request: CreateEventRequest
    ): EventResponse {

        val event = createEventUseCase.create(
            request.toDomain()
        )

        return EventResponse.from(event)
    }
}