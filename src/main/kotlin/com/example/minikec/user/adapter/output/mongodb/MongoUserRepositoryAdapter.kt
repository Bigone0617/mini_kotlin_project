package com.example.minikec.user.adapter.output.mongodb

import com.example.minikec.user.application.port.output.UserRepositoryPort
import com.example.minikec.user.domain.User
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.index.Index
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

        // 동적 컬렉션이므로 실제 컬렉션 이름으로 인덱스를 생성한다.
        // 동일 인덱스가 이미 있으면 재사용하며, 생성 실패 시 저장하지 않는다.
        mongoTemplate.indexOps(collectionName).createIndex(
            Index().on("externalUserId", Sort.Direction.ASC).unique()
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