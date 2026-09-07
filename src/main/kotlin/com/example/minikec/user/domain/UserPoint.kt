package com.example.minikec.user.domain

data class UserPoint(

    val id: String? = null,

    val eventKey: String,

    val userId: String,

    val pointKey: String,

    val totalPoint: Long = 0,

    val currentPoint: Long = 0
) {

    fun earn(amount: Long): UserPoint {

        require(amount > 0) {
            "Earn amount must be greater than 0"
        }

        return copy(
            totalPoint = totalPoint + amount,
            currentPoint = currentPoint + amount
        )
    }

    fun spend(amount: Long): UserPoint {

        require(amount > 0) {
            "Spend amount must be greater than 0"
        }

        require(currentPoint >= amount) {
            "Not enough points"
        }

        return copy(
            currentPoint = currentPoint - amount
        )
    }
}