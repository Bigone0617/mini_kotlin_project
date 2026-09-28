package com.example.minikec.user.application.port.output

import com.example.minikec.user.domain.User

interface UserRepositoryPort {

    fun save(user: User): User

    fun findByExternalUserId(
        gameKey: String,
        eventKey: String,
        externalUserId: String
    ): User?
}