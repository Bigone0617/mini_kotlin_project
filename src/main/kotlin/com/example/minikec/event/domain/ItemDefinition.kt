package com.example.minikec.event.domain

data class ItemDefinition(
    val itemKey: String,
    val name: String,
    val externalItemId: String? = null
)