package com.example.minikec.user.domain

import java.time.Instant

data class User(
    val id: String? = null,

    val eventKey: String,
    val gameKey: String,

    val externalUserId: String,

    val nickname: String? = null,

    val participatedAt: Instant = Instant.now()
)