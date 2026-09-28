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

    // 시작은 포함하고 종료는 제외한다. null은 해당 시간 제한이 없다는 뜻이다.
    fun validateAvailable(now: Instant) {
        val reason = when {
            !active -> EventUnavailableReason.EVENT_INACTIVE
            eventStartAt != null && now.isBefore(eventStartAt) -> EventUnavailableReason.EVENT_NOT_STARTED
            eventEndAt != null && !now.isBefore(eventEndAt) -> EventUnavailableReason.EVENT_ENDED
            else -> return
        }
        throw EventUnavailableException(reason, eventKey)
    }

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