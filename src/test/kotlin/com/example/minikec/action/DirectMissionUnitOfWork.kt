package com.example.minikec.action

import com.example.minikec.action.application.port.output.MissionUnitOfWork

// 단위 테스트 전용. 트랜잭션 보장은 MongoDB 통합 테스트에서 검증한다.
object DirectMissionUnitOfWork : MissionUnitOfWork {
    override fun prepare(gameKey: String, eventKey: String) = Unit
    override fun <T : Any> execute(action: () -> T): T = action()
}
