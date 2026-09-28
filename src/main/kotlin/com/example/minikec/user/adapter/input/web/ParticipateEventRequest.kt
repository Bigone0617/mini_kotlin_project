package com.example.minikec.user.adapter.input.web

data class ParticipateEventRequest(
    val externalUserId: String,
    val nickname: String? = null
)