package com.example.minikec.event.adapter.output.mongodb

import org.springframework.data.mongodb.repository.MongoRepository

interface SpringDataEventRepository :
    MongoRepository<EventDocument, String> {

    fun findByEventKey(eventKey: String): EventDocument?

    fun existsByEventKey(eventKey: String): Boolean
}