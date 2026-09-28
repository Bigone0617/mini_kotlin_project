package com.example.minikec.user.adapter.output.mongodb

import com.example.minikec.user.application.port.output.UserActionRepositoryPort
import com.example.minikec.user.domain.UserAction
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component

@Component
class MongoUserActionRepositoryAdapter(
    private val mongoTemplate: MongoTemplate,
    private val collectionNameProvider: DynamicCollectionNameProvider
) : UserActionRepositoryPort {

    override fun save(
        gameKey: String,
        userAction: UserAction
    ): UserAction {

        val collectionName =
            collectionNameProvider.userAction(
                gameKey,
                userAction.eventKey
            )

        val saved = mongoTemplate.save(
            userAction.toDocument(),
            collectionName
        )

        return saved.toDomain()
    }

    override fun findLatestByUserIdAndActionId(
        gameKey: String,
        eventKey: String,
        userId: String,
        actionId: String
    ): UserAction? {

        val collectionName =
            collectionNameProvider.userAction(
                gameKey,
                eventKey
            )

        val query = Query.query(
            Criteria
                .where("userId")
                .`is`(userId)
                .and("actionId")
                .`is`(actionId)
        )
            .with(
                Sort.by(
                    Sort.Direction.DESC,
                    "createdAt"
                )
            )
            .limit(1)

        return mongoTemplate
            .findOne(
                query,
                UserActionDocument::class.java,
                collectionName
            )
            ?.toDomain()
    }
}