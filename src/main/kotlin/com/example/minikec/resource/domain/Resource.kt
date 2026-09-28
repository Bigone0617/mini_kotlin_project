package com.example.minikec.resource.domain

import java.time.Instant

data class Resource(
    val id: String? = null,
    val eventKey: String,
    val itemKey: String,
    val key: String,
    val value: String? = null,
    val status: ResourceStatus = ResourceStatus.READY,
    val assignedUserId: String? = null,
    val assignedAt: Instant? = null
)