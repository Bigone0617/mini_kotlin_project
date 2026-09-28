package com.example.minikec.action.domain

class UserLockAcquisitionException(
    lockKey: String
) : RuntimeException(
    "Failed to acquire user lock: $lockKey"
)