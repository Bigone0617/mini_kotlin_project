package com.example.minikec.action.adapter.output.mongodb

import com.example.minikec.action.application.port.output.MissionUnitOfWork
import com.example.minikec.user.adapter.output.mongodb.DynamicCollectionNameProvider
import com.example.minikec.user.adapter.output.mongodb.MongoParticipationUnitOfWork
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.stereotype.Component

@Component
class MongoMissionUnitOfWork(
    private val template: MongoTemplate,
    private val names: DynamicCollectionNameProvider,
    private val transactions: MongoParticipationUnitOfWork
) : MissionUnitOfWork {
    override fun prepare(gameKey: String, eventKey: String) {
        transactions.prepare(gameKey, eventKey)
        template.indexOps(names.missionExecution(gameKey, eventKey)).createIndex(
            Index().on("userId", Sort.Direction.ASC).on("actionId", Sort.Direction.ASC)
                .on("requestId", Sort.Direction.ASC).unique().named("uk_mission_request")
        )
        template.indexOps(names.userAction(gameKey, eventKey)).createIndex(
            Index().on("userId", Sort.Direction.ASC).on("actionId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC)
        )
    }

    override fun <T : Any> execute(action: () -> T): T = transactions.execute(action)
}
