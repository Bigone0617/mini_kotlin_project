package com.example.minikec.user.adapter.output.mongodb

import com.example.minikec.user.application.port.output.ParticipationUnitOfWork
import com.mongodb.MongoException
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Component
class MongoParticipationUnitOfWork(
    private val template: MongoTemplate,
    private val names: DynamicCollectionNameProvider
) : ParticipationUnitOfWork {
    private val transactions = TransactionTemplate(MongoTransactionManager(template.mongoDatabaseFactory)).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    override fun prepare(gameKey: String, eventKey: String) {
        // DDL은 참여 트랜잭션 밖에서 실행한다. 동시 준비 요청도 같은 인덱스를 사용한다.
        template.indexOps(names.user(gameKey, eventKey)).createIndex(
            Index().on("externalUserId", Sort.Direction.ASC).unique()
        )
        // 인덱스 생성은 포인트 컬렉션도 미리 준비한다.
        template.indexOps(names.userPoint(gameKey, eventKey)).createIndex(
            Index().on("userId", Sort.Direction.ASC).on("pointKey", Sort.Direction.ASC)
        )
    }

    override fun <T : Any> execute(action: () -> T): T {
        var attempts = 0
        while (true) {
            try {
                return requireNotNull(transactions.execute { action() })
            } catch (exception: RuntimeException) {
                val causes = generateSequence<Throwable>(exception) { it.cause }.toList()
                val mongoErrors = causes.filterIsInstance<MongoException>()
                // 결과가 불명확한 커밋은 작업 전체를 재실행하지 않는다.
                val unknownCommit = mongoErrors.any { it.hasErrorLabel("UnknownTransactionCommitResult") }
                val transient = mongoErrors.any { it.hasErrorLabel("TransientTransactionError") }
                if (unknownCommit || !transient || ++attempts >= 5) throw exception
                // 이전 트랜잭션의 롤백이 끝난 뒤 잠시 기다려 쓰기 경쟁을 줄인다.
                try {
                    Thread.sleep(200L shl (attempts - 1))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Participation retry interrupted", interrupted)
                }
            }
        }
    }
}
