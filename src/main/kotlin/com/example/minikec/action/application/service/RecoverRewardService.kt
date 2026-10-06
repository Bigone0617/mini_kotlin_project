package com.example.minikec.action.application.service

import com.example.minikec.action.application.port.output.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

enum class RewardRecoveryOutcome { NOT_FOUND, COMPLETED, REVIEW_REQUIRED, RETRY_ALLOWED, STATE_CHANGED }

/** 수동 복구용 application service. 공개 API나 자동 스케줄러에 연결하지 않는다. */
@Service
class RecoverRewardService(
    private val executions: RewardExecutionRepositoryPort,
    private val counter: RewardCounterPort,
    private val locks: UserLockPort
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun recover(key: RewardRequestKey): RewardRecoveryOutcome = locks.withLock(
        "user-lock:${key.gameKey}:${key.eventKey}:${key.userId}"
    ) {
        val receipt = executions.find(key) ?: return@withLock RewardRecoveryOutcome.NOT_FOUND
        if (receipt.result != null) return@withLock RewardRecoveryOutcome.COMPLETED
        val token = receipt.reservationToken
        val counterKey = receipt.counterKey
        // 오래된 PENDING이나 현재 잔액만으로 실패를 추측하지 않는다.
        if (!receipt.rollbackConfirmed || token == null || counterKey == null) {
            return@withLock RewardRecoveryOutcome.REVIEW_REQUIRED
        }
        if (counter.reservationState(counterKey, token) == ReservationState.MISSING) {
            return@withLock RewardRecoveryOutcome.REVIEW_REQUIRED
        }
        // 반환 후 응답을 잃어도 token의 RELEASED 상태로 다음 복구가 중복 감소하지 않는다.
        // 통신 오류면 예외를 전파하고 receipt를 유지한다.
        counter.releaseReservation(counterKey, token)
        val deleted = executions.deleteRolledBack(key, token)
        logger.info("Reward recovery request={}, reservation={}, retryAllowed={}", key, token, deleted)
        if (deleted) RewardRecoveryOutcome.RETRY_ALLOWED else RewardRecoveryOutcome.STATE_CHANGED
    }
}
