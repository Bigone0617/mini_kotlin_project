package com.example.minikec.action.adapter.output.mongodb

import com.example.minikec.action.application.port.input.ExecuteActionResult
import com.example.minikec.action.application.port.output.*
import com.example.minikec.user.adapter.output.mongodb.DynamicCollectionNameProvider
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class MongoRewardExecutionRepositoryAdapter(
    private val template: MongoTemplate,
    private val names: DynamicCollectionNameProvider
) : RewardExecutionRepositoryPort {
    private fun query(key: RewardRequestKey) = Query.query(Criteria.where("userId").`is`(key.userId)
        .and("actionId").`is`(key.actionId).and("requestId").`is`(key.requestId))
    private fun collection(key: RewardRequestKey) = names.rewardExecution(key.gameKey, key.eventKey)

    override fun find(key: RewardRequestKey): RewardExecution? =
        template.findOne(query(key), RewardExecutionDocument::class.java, collection(key))?.let { RewardExecution(it.result, it.counterKey, it.reservationToken, it.rollbackConfirmed) }

    override fun attachReservation(key: RewardRequestKey, counterKey: String, token: String) {
        val updated = template.updateFirst(query(key).addCriteria(Criteria.where("status").`is`("PENDING")),
            Update().set("counterKey", counterKey).set("reservationToken", token), RewardExecutionDocument::class.java, collection(key))
        check(updated.matchedCount == 1L) { "Pending reward request must exist" }
    }

    override fun confirmRollback(key: RewardRequestKey) {
        val updated = template.updateFirst(query(key).addCriteria(Criteria.where("status").`is`("PENDING")),
            Update().set("rollbackConfirmed", true), RewardExecutionDocument::class.java, collection(key))
        check(updated.matchedCount == 1L) { "Pending reward request must exist" }
    }

    override fun tryClaim(key: RewardRequestKey): Boolean = try {
        template.insert(RewardExecutionDocument(key.userId, key.actionId, key.requestId), collection(key))
        true
    } catch (exception: DuplicateKeyException) {
        // 다른 unique 제약 오류를 동일 요청으로 오인하지 않는다.
        if (find(key) == null) throw exception
        false
    }

    override fun complete(key: RewardRequestKey, result: ExecuteActionResult) {
        val updated = template.updateFirst(query(key).addCriteria(Criteria.where("status").`is`("PENDING")),
            Update().set("status", "COMPLETED").set("result", result), RewardExecutionDocument::class.java, collection(key))
        check(updated.matchedCount == 1L) { "Pending reward request must exist" }
    }

    override fun deleteRolledBack(key: RewardRequestKey, token: String): Boolean =
        template.remove(query(key).addCriteria(Criteria.where("status").`is`("PENDING")
            .and("rollbackConfirmed").`is`(true).and("reservationToken").`is`(token)),
            RewardExecutionDocument::class.java, collection(key)).deletedCount == 1L

    override fun deletePending(key: RewardRequestKey) {
        template.remove(query(key).addCriteria(Criteria.where("status").`is`("PENDING")),
            RewardExecutionDocument::class.java, collection(key))
    }
}

data class RewardExecutionDocument(
    val userId: String,
    val actionId: String,
    val requestId: String,
    val status: String = "PENDING",
    val result: ExecuteActionResult? = null,
    val counterKey: String? = null,
    val reservationToken: String? = null,
    val rollbackConfirmed: Boolean = false,
    val createdAt: Instant = Instant.now()
)
