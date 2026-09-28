package com.example.minikec.user.adapter.output.mongodb

import com.example.minikec.user.domain.User
import org.springframework.data.annotation.Id
import java.time.Instant

data class UserDocument(

    @Id
    val id: String? = null,

    val eventKey: String,
    val gameKey: String,

    val externalUserId: String,

    val nickname: String? = null,

    val participatedAt: Instant
)

fun UserDocument.toDomain(): User {
    return User(
        id = id,
        eventKey = eventKey,
        gameKey = gameKey,
        externalUserId = externalUserId,
        nickname = nickname,
        participatedAt = participatedAt
    )
}

fun User.toDocument(): UserDocument {
    return UserDocument(
        id = id,
        eventKey = eventKey,
        gameKey = gameKey,
        externalUserId = externalUserId,
        nickname = nickname,
        participatedAt = participatedAt
    )
}