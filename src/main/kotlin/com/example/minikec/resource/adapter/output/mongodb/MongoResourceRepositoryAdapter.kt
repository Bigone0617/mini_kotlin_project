package com.example.minikec.resource.adapter.output.mongodb

import com.example.minikec.resource.application.port.output.ResourceRepositoryPort
import com.example.minikec.resource.domain.Resource
import com.example.minikec.resource.domain.ResourceStatus
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class MongoResourceRepositoryAdapter(
    private val mongoTemplate: MongoTemplate
) : ResourceRepositoryPort {

    override fun assignReadyResource(
        gameKey: String,
        eventKey: String,
        itemKey: String,
        userId: String
    ): Resource? {

        val query =
            Query(
                Criteria.where("itemKey")
                    .`is`(itemKey)
                    .and("status")
                    .`is`(ResourceStatus.READY)
            )

        val update =
            Update()
                .set("status", ResourceStatus.ASSIGNED)
                .set("assignedUserId", userId)
                .set("assignedAt", Instant.now())

        val options =
            FindAndModifyOptions.options()
                .returnNew(true)

        val document =
            mongoTemplate.findAndModify(
                query,
                update,
                options,
                ResourceDocument::class.java,
                collectionName(
                    gameKey,
                    eventKey
                )
            )

        return document?.toDomain()
    }

    override fun release(
        gameKey: String,
        eventKey: String,
        resourceId: String
    ) {

        val query =
            Query(
                Criteria.where("_id")
                    .`is`(resourceId)
                    .and("status")
                    .`is`(ResourceStatus.ASSIGNED)
            )

        val update =
            Update()
                .set("status", ResourceStatus.READY)
                .unset("assignedUserId")
                .unset("assignedAt")

        mongoTemplate.updateFirst(
            query,
            update,
            collectionName(
                gameKey,
                eventKey
            )
        )
    }

    private fun collectionName(
        gameKey: String,
        eventKey: String
    ): String =
        "${gameKey}_${eventKey}_commonResource"
}