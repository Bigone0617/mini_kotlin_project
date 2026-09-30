package com.example.minikec.action.application.port.output

import com.example.minikec.action.application.port.input.ExecuteActionResult

interface MissionExecutionRepositoryPort {
    fun find(gameKey: String, eventKey: String, userId: String, actionId: String, requestId: String): ExecuteActionResult?
    fun insert(gameKey: String, eventKey: String, userId: String, actionId: String, requestId: String, result: ExecuteActionResult)
}
