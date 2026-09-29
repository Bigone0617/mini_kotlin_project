package com.example.minikec.user

import com.example.minikec.user.application.port.output.ParticipationUnitOfWork

// 단위 테스트 전용. 실제 커밋과 롤백은 MongoDB 통합 테스트에서 검증한다.
object DirectParticipationUnitOfWork : ParticipationUnitOfWork {
    override fun prepare(gameKey: String, eventKey: String) = Unit
    override fun <T : Any> execute(action: () -> T): T = action()
}
