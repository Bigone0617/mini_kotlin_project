package com.example.minikec.user.application.port.output

import com.example.minikec.user.domain.UserAction

interface UserActionRepositoryPort {

    fun save(
        gameKey: String,
        userAction: UserAction
    ): UserAction

    fun findLatestByUserIdAndActionId(
        gameKey: String,
        eventKey: String,
        userId: String,
        actionId: String
    ): UserAction?
}