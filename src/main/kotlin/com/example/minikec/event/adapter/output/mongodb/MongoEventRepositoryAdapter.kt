package com.example.minikec.event.adapter.output.mongodb

import com.example.minikec.event.application.port.output.EventRepositoryPort
import com.example.minikec.event.domain.Event
import org.springframework.stereotype.Component

@Component
class MongoEventRepositoryAdapter(
    private val repository: SpringDataEventRepository
) : EventRepositoryPort {

    override fun save(event: Event): Event {
        val document = event.toDocument()

        return repository
            .save(document)
            .toDomain()
    }

    override fun findByEventKey(eventKey: String): Event? {
        return repository
            .findByEventKey(eventKey)
            ?.toDomain()
    }

    override fun existsByEventKey(eventKey: String): Boolean {
        return repository.existsByEventKey(eventKey)
    }
}