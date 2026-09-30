package com.example.minikec.action.adapter.input.web

data class ExecuteActionRequest(
    val externalUserId: String,
    val requestId: String? = null
)