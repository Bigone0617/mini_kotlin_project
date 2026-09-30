package com.example.minikec.action.application.port.input

import com.example.minikec.user.domain.ActionStatus
import com.example.minikec.user.domain.UserPoint

interface ExecuteActionUseCase {

    fun execute(
        command: ExecuteActionCommand
    ): ExecuteActionResult
}

data class ExecuteActionCommand(
    val gameKey: String,
    val eventKey: String,
    val actionId: String,
    val externalUserId: String,
    val requestId: String? = null
)

data class ExecuteActionResult(
    val actionId: String,
    val status: ActionStatus,
    val currentProgress: Long,
    val goal: Long,
    val points: List<UserPoint>
)