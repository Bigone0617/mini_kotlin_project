package com.example.minikec.event.adapter.input.web

import com.example.minikec.event.domain.ActionGroup
import com.example.minikec.event.domain.Event
import com.example.minikec.event.domain.ItemDefinition
import com.example.minikec.event.domain.PointDefinition
import java.time.Instant

data class EventDetailResponse(
    val eventKey: String,
    val gameKey: String,

    val name: String,
    val description: String?,

    val active: Boolean,

    val eventStartAt: Instant?,
    val eventEndAt: Instant?,

    val points: List<PointDefinition>,
    val items: List<ItemDefinition>,

    val missionGroups: List<ActionGroup>,
    val rewardGroups: List<ActionGroup>,
    val miniGameGroups: List<ActionGroup>
) {

    companion object {

        fun from(event: Event): EventDetailResponse {
            return EventDetailResponse(
                eventKey = event.eventKey,
                gameKey = event.gameKey,
                name = event.name,
                description = event.description,
                active = event.active,
                eventStartAt = event.eventStartAt,
                eventEndAt = event.eventEndAt,
                points = event.points,
                items = event.items,
                missionGroups = event.missionGroups,
                rewardGroups = event.rewardGroups,
                miniGameGroups = event.miniGameGroups
            )
        }
    }
}