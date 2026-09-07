package com.example.minikec.user.domain

import com.example.minikec.event.domain.Action
import com.example.minikec.event.domain.ActionItem
import com.example.minikec.event.domain.ActionPoint
import com.example.minikec.event.domain.ActionRepeatType
import com.example.minikec.event.domain.ActionSubType
import com.example.minikec.event.domain.ActionType

data class ActionSnapshot(

    val actionId: String,
    val name: String,

    val actionType: ActionType,
    val actionSubType: ActionSubType,
    val actionRepeatType: ActionRepeatType,

    val goal: Long?,

    val totalCount: Long?,
    val personalMaxCount: Long?,

    val pointRewards: List<ActionPoint>,
    val itemRewards: List<ActionItem>
) {

    companion object {

        fun from(action: Action): ActionSnapshot {
            return ActionSnapshot(
                actionId = action.actionId,
                name = action.name,

                actionType = action.actionType,
                actionSubType = action.actionSubType,
                actionRepeatType = action.actionRepeatType,

                goal = action.goal,

                totalCount = action.totalCount,
                personalMaxCount = action.personalMaxCount,

                pointRewards = action.pointRewards,
                itemRewards = action.itemRewards
            )
        }
    }
}