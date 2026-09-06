package com.example.minikec.event.domain

data class ActionGroup(
    val groupId: String,
    val name: String,
    val frontVisible: Boolean = true,
    val actions: List<Action> = emptyList()
)