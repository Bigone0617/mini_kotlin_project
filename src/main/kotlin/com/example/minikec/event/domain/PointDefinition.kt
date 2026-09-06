package com.example.minikec.event.domain

data class PointDefinition(
    val pointKey: String,
    val name: String,
    val initialPoint: Long = 0,
    val limitPoint: Long? = null
)