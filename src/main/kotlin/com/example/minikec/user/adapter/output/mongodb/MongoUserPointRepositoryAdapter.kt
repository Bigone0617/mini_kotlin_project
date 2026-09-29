package com.example.minikec.user.adapter.output.mongodb

import com.example.minikec.user.application.port.output.UserPointRepositoryPort
import com.example.minikec.user.domain.UserPoint
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component

@Component
class MongoUserPointRepositoryAdapter(
    private val mongoTemplate: MongoTemplate,
    private val collectionNameProvider: DynamicCollectionNameProvider
) : UserPointRepositoryPort {

    override fun save(
        gameKey: String,
        userPoint: UserPoint
    ): UserPoint {

        val collectionName =
            collectionNameProvider.userPoint(
                gameKey,
                userPoint.eventKey
            )

        val saved = mongoTemplate.save(
            userPoint.toDocument(),
            collectionName
        )

        return saved.toDomain()
    }

    override fun findAllByUserId(
        gameKey: String,
        eventKey: String,
        userId: String
    ): List<UserPoint> {
        val collectionName = collectionNameProvider.userPoint(gameKey, eventKey)
        val query = Query.query(Criteria.where("userId").`is`(userId))
            .with(Sort.by(Sort.Direction.ASC, "pointKey"))

        return mongoTemplate.find(query, UserPointDocument::class.java, collectionName)
            .map { it.toDomain() }
    }

    override fun findByUserIdAndPointKey(
        gameKey: String,
        eventKey: String,
        userId: String,
        pointKey: String
    ): UserPoint? {

        val collectionName =
            collectionNameProvider.userPoint(
                gameKey,
                eventKey
            )

        val query = Query.query(
            Criteria
                .where("userId")
                .`is`(userId)
                .and("pointKey")
                .`is`(pointKey)
        )

        return mongoTemplate
            .findOne(
                query,
                UserPointDocument::class.java,
                collectionName
            )
            ?.toDomain()
    }
}