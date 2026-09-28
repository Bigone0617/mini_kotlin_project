package com.example.minikec.user.application.port.output

import com.example.minikec.user.domain.UserPoint

interface UserPointRepositoryPort {

    fun save(
        gameKey: String,
        userPoint: UserPoint
    ): UserPoint

    fun findByUserIdAndPointKey(
        gameKey: String,
        eventKey: String,
        userId: String,
        pointKey: String
    ): UserPoint?
}