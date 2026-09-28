package com.example.minikec.user.adapter.output.mongodb

import org.springframework.stereotype.Component

@Component
class DynamicCollectionNameProvider {

    fun user(
        gameKey: String,
        eventKey: String
    ): String {
        return "${gameKey}_${eventKey}_user"
    }

    fun userAction(
        gameKey: String,
        eventKey: String
    ): String {
        return "${gameKey}_${eventKey}_userAction"
    }

    fun userPoint(
        gameKey: String,
        eventKey: String
    ): String {
        return "${gameKey}_${eventKey}_userPoint"
    }
}