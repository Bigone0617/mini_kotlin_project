package com.example.minikec.action.adapter.output.mongodb

import com.example.minikec.action.application.port.input.ExecuteActionResult
import com.example.minikec.action.application.port.output.MissionExecutionRepositoryPort
import com.example.minikec.user.adapter.output.mongodb.DynamicCollectionNameProvider
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component

@Component
class MongoMissionExecutionRepositoryAdapter(
    private val template: MongoTemplate,
    private val names: DynamicCollectionNameProvider
) : MissionExecutionRepositoryPort {
    override fun find(gameKey: String, eventKey: String, userId: String, actionId: String, requestId: String): ExecuteActionResult? {
        val query = Query.query(Criteria.where("userId").`is`(userId)
            .and("actionId").`is`(actionId).and("requestId").`is`(requestId))
        return template.findOne(query, MissionExecutionDocument::class.java, names.missionExecution(gameKey, eventKey))?.result
    }

    override fun insert(gameKey: String, eventKey: String, userId: String, actionId: String, requestId: String, result: ExecuteActionResult) {
        template.insert(MissionExecutionDocument(userId, actionId, requestId, result), names.missionExecution(gameKey, eventKey))
    }
}

data class MissionExecutionDocument(
    val userId: String,
    val actionId: String,
    val requestId: String,
    val result: ExecuteActionResult
)
