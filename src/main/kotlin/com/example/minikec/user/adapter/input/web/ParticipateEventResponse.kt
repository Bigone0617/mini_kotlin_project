package com.example.minikec.user.adapter.input.web

import com.example.minikec.user.application.port.input.ParticipateEventResult

data class ParticipateEventResponse(
    val userId: String?,
    val externalUserId: String,
    val nickname: String?,
    val eventKey: String,
    val points: List<UserPointResponse>,
    val alreadyParticipated: Boolean
) {

    companion object {

        fun from(
            result: ParticipateEventResult
        ): ParticipateEventResponse {

            return ParticipateEventResponse(
                userId = result.user.id,
                externalUserId = result.user.externalUserId,
                nickname = result.user.nickname,
                eventKey = result.user.eventKey,
                points = result.points.map {
                    UserPointResponse(
                        pointKey = it.pointKey,
                        totalPoint = it.totalPoint,
                        currentPoint = it.currentPoint
                    )
                },
                alreadyParticipated = result.alreadyParticipated
            )
        }
    }
}

data class UserPointResponse(
    val pointKey: String,
    val totalPoint: Long,
    val currentPoint: Long
)