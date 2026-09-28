package com.example.minikec.user.adapter.input.web

import com.example.minikec.user.application.port.input.ParticipateEventCommand
import com.example.minikec.user.application.port.input.ParticipateEventUseCase
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/ec/v1/{gameKey}/events/{eventKey}/users")
class ParticipateEventController(
    private val participateEventUseCase: ParticipateEventUseCase
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun participate(
        @PathVariable gameKey: String,
        @PathVariable eventKey: String,
        @RequestBody request: ParticipateEventRequest
    ): ParticipateEventResponse {

        val result = participateEventUseCase.participate(
            ParticipateEventCommand(
                gameKey = gameKey,
                eventKey = eventKey,
                externalUserId = request.externalUserId,
                nickname = request.nickname
            )
        )

        return ParticipateEventResponse.from(result)
    }
}