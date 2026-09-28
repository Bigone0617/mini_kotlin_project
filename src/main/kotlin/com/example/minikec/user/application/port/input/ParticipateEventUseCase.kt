package com.example.minikec.user.application.port.input

import com.example.minikec.user.domain.User
import com.example.minikec.user.domain.UserPoint

interface ParticipateEventUseCase {

    fun participate(
        command: ParticipateEventCommand
    ): ParticipateEventResult
}

data class ParticipateEventCommand(
    val gameKey: String,
    val eventKey: String,
    val externalUserId: String,
    val nickname: String?
)

data class ParticipateEventResult(
    val user: User,
    val points: List<UserPoint>,
    val alreadyParticipated: Boolean
)