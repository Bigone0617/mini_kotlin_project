package com.example.minikec.action.application.port.output

import com.example.minikec.action.application.port.input.ExecuteActionResult

data class RewardRequestKey(val gameKey: String, val eventKey: String, val userId: String, val actionId: String, val requestId: String)
data class RewardExecution(val result: ExecuteActionResult?)

interface RewardExecutionRepositoryPort {
    fun find(key: RewardRequestKey): RewardExecution?
    // 트랜잭션 밖에서 실행한다. false면 동일 요청이 이미 존재한다.
    fun tryClaim(key: RewardRequestKey): Boolean
    fun complete(key: RewardRequestKey, result: ExecuteActionResult)
    fun deletePending(key: RewardRequestKey)
}
