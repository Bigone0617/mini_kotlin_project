package com.example.minikec.user.domain

class InsufficientPointException(
    val pointKey: String,
    val required: Long,
    val current: Long
) : RuntimeException(
    "Insufficient point: pointKey=$pointKey, required=$required, current=$current"
)