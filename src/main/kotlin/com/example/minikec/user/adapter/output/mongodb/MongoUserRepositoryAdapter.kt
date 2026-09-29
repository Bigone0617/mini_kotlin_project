package com.example.minikec.user.adapter.output.mongodb

import com.example.minikec.user.application.port.output.UserRepositoryPort
import com.example.minikec.user.domain.User
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component

@Component
class MongoUserRepositoryAdapter(
    private val mongoTemplate: MongoTemplate,
    private val collectionNameProvider: DynamicCollectionNameProvider
) : UserRepositoryPort {

    override fun save(user: User): User {

        val collectionName =
            collectionNameProvider.user(
                user.gameKey,
                user.eventKey
            )

        val saved = mongoTemplate.save(
            user.toDocument(),
            collectionName
        )

        return saved.toDomain()
    }

    override fun findByExternalUserId(
        gameKey: String,
        eventKey: String,
        externalUserId: String
    ): User? {

        val collectionName =
            collectionNameProvider.user(
                gameKey,
                eventKey
            )

        val query = Query.query(
            Criteria
                .where("externalUserId")
                .`is`(externalUserId)
        )

        return mongoTemplate
            .findOne(
                query,
                UserDocument::class.java,
                collectionName
            )
            ?.toDomain()
    }
}