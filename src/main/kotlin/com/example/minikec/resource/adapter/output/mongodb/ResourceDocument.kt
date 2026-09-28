package com.example.minikec.resource.adapter.output.mongodb

import com.example.minikec.resource.domain.Resource
import com.example.minikec.resource.domain.ResourceStatus
import org.springframework.data.annotation.Id
import java.time.Instant

data class ResourceDocument(
    @Id
    val id: String? = null,
    val eventKey: String,
    val itemKey: String,
    val key: String,
    val value: String? = null,
    val status: ResourceStatus = ResourceStatus.READY,
    val assignedUserId: String? = null,
    val assignedAt: Instant? = null
) {

    fun toDomain(): Resource =
        Resource(
            id = id,
            eventKey = eventKey,
            itemKey = itemKey,
            key = key,
            value = value,
            status = status,
            assignedUserId = assignedUserId,
            assignedAt = assignedAt
        )
}