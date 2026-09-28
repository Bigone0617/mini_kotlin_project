package com.example.minikec.user.adapter.output.mongodb

import com.example.minikec.user.domain.ActionProgress
import com.example.minikec.user.domain.ActionSnapshot
import com.example.minikec.user.domain.ActionStatus
import com.example.minikec.user.domain.UserAction
import org.springframework.data.annotation.Id
import java.time.Instant

data class UserActionDocument(

    @Id
    val id: String? = null,

    val eventKey: String,

    val userId: String,
    val actionId: String,

    val action: ActionSnapshot,

    val status: ActionStatus,

    val progress: ActionProgress? = null,

    val checkedAt: Instant? = null,

    val createdAt: Instant,
    val updatedAt: Instant
)

fun UserActionDocument.toDomain(): UserAction {
    return UserAction(
        id = id,
        eventKey = eventKey,
        userId = userId,
        actionId = actionId,
        action = action,
        status = status,
        progress = progress,
        checkedAt = checkedAt,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun UserAction.toDocument(): UserActionDocument {
    return UserActionDocument(
        id = id,
        eventKey = eventKey,
        userId = userId,
        actionId = actionId,
        action = action,
        status = status,
        progress = progress,
        checkedAt = checkedAt,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}