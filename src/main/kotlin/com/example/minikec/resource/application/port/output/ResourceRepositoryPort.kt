package com.example.minikec.resource.application.port.output

import com.example.minikec.resource.domain.Resource

interface ResourceRepositoryPort {

    fun assignReadyResource(
        gameKey: String,
        eventKey: String,
        itemKey: String,
        userId: String
    ): Resource?

    fun release(
        gameKey: String,
        eventKey: String,
        resourceId: String
    )
}