package com.example.minikec.action

import com.example.minikec.action.application.port.output.RewardUnitOfWork

// 서비스 단위 테스트 전용. 실제 롤백은 MongoDB 통합 테스트에서 검증한다.
object DirectRewardUnitOfWork : RewardUnitOfWork {
    override fun prepare(gameKey: String, eventKey: String) = Unit
    override fun <T : Any> execute(action: () -> T): T = action()
}
