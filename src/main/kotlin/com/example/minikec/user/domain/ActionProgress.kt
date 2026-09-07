package com.example.minikec.user.domain

data class ActionProgress(
    val current: Long,
    val goal: Long
) {

    fun isCompleted(): Boolean {
        return current >= goal
    }
}