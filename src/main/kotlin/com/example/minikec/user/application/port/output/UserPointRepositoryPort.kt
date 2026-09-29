package com.example.minikec.user.application.port.output

import com.example.minikec.user.domain.UserPoint

interface UserPointRepositoryPort {

    // 현재 잔액이 충분할 때만 차감한다. 문서가 없거나 부족하면 null을 반환한다.
    fun spendIfEnough(gameKey: String, eventKey: String, userId: String, pointKey: String, amount: Long): UserPoint?

    // 적립은 누적 포인트와 현재 잔액을 함께 증가시킨다.
    fun earn(gameKey: String, eventKey: String, userId: String, pointKey: String, amount: Long): UserPoint

    fun save(
        gameKey: String,
        userPoint: UserPoint
    ): UserPoint

    fun findAllByUserId(
        gameKey: String,
        eventKey: String,
        userId: String
    ): List<UserPoint>

    fun findByUserIdAndPointKey(
        gameKey: String,
        eventKey: String,
        userId: String,
        pointKey: String
    ): UserPoint?
}