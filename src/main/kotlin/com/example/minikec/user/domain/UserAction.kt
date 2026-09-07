package com.example.minikec.user.domain

import java.time.Instant

data class UserAction(

    val id: String? = null,

    val eventKey: String,

    val userId: String,

    val actionId: String,

    val action: ActionSnapshot,

    val status: ActionStatus,

    val progress: ActionProgress? = null,

    val checkedAt: Instant? = null,

    val createdAt: Instant = Instant.now(),

    val updatedAt: Instant = Instant.now()
)