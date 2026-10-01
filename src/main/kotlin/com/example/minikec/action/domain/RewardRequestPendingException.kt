package com.example.minikec.action.domain

class RewardRequestPendingException : RuntimeException("Reward request is processing or requires reconciliation; retry with the same requestId")
