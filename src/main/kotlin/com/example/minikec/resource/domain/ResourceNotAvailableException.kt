package com.example.minikec.resource.domain

class ResourceNotAvailableException(
    val itemKey: String
) : RuntimeException(
    "Resource not available: $itemKey"
)