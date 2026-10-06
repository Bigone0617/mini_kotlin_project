package com.example.minikec.action.application.port.output

enum class ReservationState { RESERVED, RELEASED, REJECTED, MISSING }

interface RewardCounterPort {
    // token은 한 실행 시도의 식별자다. 응답 유실 후 같은 token으로 호출해도 중복 증가하지 않는다.
    fun reserve(key: String, token: String, maxCount: Long): Boolean
    fun releaseReservation(key: String, token: String)
    fun reservationState(key: String, token: String): ReservationState


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