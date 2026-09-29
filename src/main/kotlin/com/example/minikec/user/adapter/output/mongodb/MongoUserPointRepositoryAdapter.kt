package com.example.minikec.user.adapter.output.mongodb

import com.example.minikec.user.application.port.output.UserPointRepositoryPort
import com.example.minikec.user.domain.UserPoint
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.query.Update

@Component
class MongoUserPointRepositoryAdapter(
    private val mongoTemplate: MongoTemplate,
    private val collectionNameProvider: DynamicCollectionNameProvider
) : UserPointRepositoryPort {

    override fun spendIfEnough(
        gameKey: String, eventKey: String, userId: String, pointKey: String, amount: Long
    ): UserPoint? {
        require(amount > 0) { "Spend amount must be positive" }
        val query = Query.query(Criteria.where("userId").`is`(userId)
            .and("pointKey").`is`(pointKey).and("currentPoint").gte(amount))
        // 잔액 확인과 차감을 한 명령으로 수행한다. totalPoint는 차감하지 않는다.
        return mongoTemplate.findAndModify(
            query, Update().inc("currentPoint", -amount),
            FindAndModifyOptions.options().returnNew(true), UserPointDocument::class.java,
            collectionNameProvider.userPoint(gameKey, eventKey)
        )?.toDomain()
    }

    override fun earn(
        gameKey: String, eventKey: String, userId: String, pointKey: String, amount: Long
    ): UserPoint {
        require(amount > 0) { "Earn amount must be positive" }
        val query = Query.query(Criteria.where("userId").`is`(userId).and("pointKey").`is`(pointKey))
        val update = Update().inc("currentPoint", amount).inc("totalPoint", amount)
            .setOnInsert("eventKey", eventKey)
        val collection = collectionNameProvider.userPoint(gameKey, eventKey)
        val document = try {
            mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().upsert(true).returnNew(true), UserPointDocument::class.java, collection)
        } catch (exception: DuplicateKeyException) {
            // 동일 포인트의 동시 최초 생성 경쟁에서 진 요청도 자신의 적립분을 반영한다.
            // 실패한 upsert는 적립되지 않았으므로, 이미 생성된 문서에만 다시 증가시킨다.
            mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), UserPointDocument::class.java, collection)
                ?: throw exception
        }
        return requireNotNull(document) { "Earned point must exist" }.toDomain()
    }

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