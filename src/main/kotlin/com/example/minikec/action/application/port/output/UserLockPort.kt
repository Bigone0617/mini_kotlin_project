package com.example.minikec.action.application.port.output

interface UserLockPort {

    fun <T> withLock(
        lockKey: String,
        action: () -> T
    ): T
}