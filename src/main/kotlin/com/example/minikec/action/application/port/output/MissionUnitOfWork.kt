package com.example.minikec.action.application.port.output

interface MissionUnitOfWork {
    fun prepare(gameKey: String, eventKey: String)
    fun <T : Any> execute(action: () -> T): T
}
