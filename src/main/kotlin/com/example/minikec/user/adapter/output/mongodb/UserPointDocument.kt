package com.example.minikec.user.adapter.output.mongodb

import com.example.minikec.user.domain.UserPoint
import org.springframework.data.annotation.Id

data class UserPointDocument(

    @Id
    val id: String? = null,

    val eventKey: String,

    val userId: String,

    val pointKey: String,

    val totalPoint: Long,
    val currentPoint: Long
)

fun UserPointDocument.toDomain(): UserPoint {
    return UserPoint(
        id = id,
        eventKey = eventKey,
        userId = userId,
        pointKey = pointKey,
        totalPoint = totalPoint,
        currentPoint = currentPoint
    )
}

fun UserPoint.toDocument(): UserPointDocument {
    return UserPointDocument(
        id = id,
        eventKey = eventKey,
        userId = userId,
        pointKey = pointKey,
        totalPoint = totalPoint,
        currentPoint = currentPoint
    )
}