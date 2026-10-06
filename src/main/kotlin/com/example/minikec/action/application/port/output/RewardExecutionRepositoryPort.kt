package com.example.minikec.action.application.port.output

import com.example.minikec.action.application.port.input.ExecuteActionResult

data class RewardRequestKey(val gameKey: String, val eventKey: String, val userId: String, val actionId: String, val requestId: String)
data class RewardExecution(val result: ExecuteActionResult?, val counterKey: String? = null,
    val reservationToken: String? = null, val rollbackConfirmed: Boolean = false)

interface RewardExecutionRepositoryPort {
    fun attachReservation(key: RewardRequestKey, counterKey: String, token: String)
    // 확정된 실패 경로에서 MongoDB 롤백이 끝난 뒤에만 기록한다. 불명확한 커밋에는 호출하지 않는다.
    fun confirmRollback(key: RewardRequestKey)
    fun find(key: RewardRequestKey): RewardExecution?
    // 트랜잭션 밖에서 실행한다. false면 동일 요청이 이미 존재한다.
    fun tryClaim(key: RewardRequestKey): Boolean
    fun complete(key: RewardRequestKey, result: ExecuteActionResult)
    fun deleteRolledBack(key: RewardRequestKey, token: String): Boolean
    fun deletePending(key: RewardRequestKey)
}
