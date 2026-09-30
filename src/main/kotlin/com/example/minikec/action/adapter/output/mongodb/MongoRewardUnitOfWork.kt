package com.example.minikec.action.adapter.output.mongodb

import com.example.minikec.action.application.port.output.RewardUnitOfWork
import com.example.minikec.action.domain.RewardCommitUncertainException
import com.example.minikec.user.adapter.output.mongodb.DynamicCollectionNameProvider
import com.example.minikec.user.adapter.output.mongodb.MongoParticipationUnitOfWork
import com.mongodb.MongoException
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionSystemException

@Component
class MongoRewardUnitOfWork(
    private val template: MongoTemplate,
    private val names: DynamicCollectionNameProvider,
    private val transactions: MongoParticipationUnitOfWork
) : RewardUnitOfWork {
    override fun prepare(gameKey: String, eventKey: String) {
        transactions.prepare(gameKey, eventKey)
        template.indexOps(names.userAction(gameKey, eventKey)).createIndex(
            Index().on("userId", Sort.Direction.ASC).on("actionId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC)
        )
        template.indexOps("${gameKey}_${eventKey}_commonResource").createIndex(
            Index().on("itemKey", Sort.Direction.ASC).on("status", Sort.Direction.ASC)
        )
    }

    override fun <T : Any> execute(action: () -> T): T = try {
        // 이미 검증한 MongoDB 트랜잭션/재시도 구현을 재사용한다. 콜백에는 DB 작업만 넣는다.
        transactions.execute(action)
    } catch (exception: RuntimeException) {
        val causes = generateSequence<Throwable>(exception) { it.cause }.toList()
        if (causes.any { it is TransactionSystemException } ||
            causes.filterIsInstance<MongoException>().any { it.hasErrorLabel("UnknownTransactionCommitResult") }) {
            throw RewardCommitUncertainException(exception)
        }
        throw exception
    }
}
