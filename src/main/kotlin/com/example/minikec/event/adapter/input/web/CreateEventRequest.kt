package com.example.minikec.event.adapter.input.web

import com.example.minikec.event.domain.ActionGroup
import com.example.minikec.event.domain.Event
import com.example.minikec.event.domain.ItemDefinition
import com.example.minikec.event.domain.PointDefinition
import java.time.Instant

data class CreateEventRequest(

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

    fun toDomain(): Event {
        return Event(
            eventKey = eventKey,
            gameKey = gameKey,
            name = name,
            description = description,
            active = active,
            eventStartAt = eventStartAt,
            eventEndAt = eventEndAt,
            points = points,
            items = items,
            missionGroups = missionGroups,
            rewardGroups = rewardGroups,
            miniGameGroups = miniGameGroups
        )
    }
}