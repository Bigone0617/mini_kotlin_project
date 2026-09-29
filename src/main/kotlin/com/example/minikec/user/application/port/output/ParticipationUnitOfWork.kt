package com.example.minikec.user.application.port.output

interface ParticipationUnitOfWork {
    fun prepare(gameKey: String, eventKey: String)
    fun <T : Any> execute(action: () -> T): T
}
