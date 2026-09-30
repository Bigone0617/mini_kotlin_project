package com.example.minikec.action.domain

// 커밋/롤백 완료를 확정할 수 없으므로 Redis 재고를 자동 반환하지 않는다.
class RewardCommitUncertainException(cause: Throwable) : RuntimeException("Reward transaction outcome requires reconciliation", cause)
