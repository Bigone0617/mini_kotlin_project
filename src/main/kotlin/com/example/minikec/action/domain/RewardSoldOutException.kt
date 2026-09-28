package com.example.minikec.action.domain

class RewardSoldOutException(
    val actionId: String
) : RuntimeException(
    "Reward sold out: $actionId"
)