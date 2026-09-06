package com.example.minikec.event.domain

import java.time.Instant

data class Event(
    val eventKey: String,
    val gameKey: String,

    val name: String,
    val description: String? = null,

    val active: Boolean = false,

    val eventStartAt: Instant? = null,
    val eventEndAt: Instant? = null,

    val points: List<PointDefinition> = emptyList(),
    val items: List<ItemDefinition> = emptyList(),

    val missionGroups: List<ActionGroup> = emptyList(),
    val rewardGroups: List<ActionGroup> = emptyList(),
    val miniGameGroups: List<ActionGroup> = emptyList()
) {

    fun findAction(actionId: String): Action {
        return allActions()
            .firstOrNull { it.actionId == actionId }
            ?: throw IllegalArgumentException(
                "Action not found. actionId=$actionId"
            )
    }

    fun allActions(): List<Action> {
        return buildList {
            addAll(missionGroups.flatMap { it.actions })
            addAll(rewardGroups.flatMap { it.actions })
            addAll(miniGameGroups.flatMap { it.actions })
        }
    }
}