package com.example.minikec.action.application.port.output

interface RewardCounterPort {

    fun tryAcquire(
        key: String,
        maxCount: Long,
    ): Boolean
    
    fun release(
        key: String
    )
    
    fun getCount(
        key: String
    ): Long
}