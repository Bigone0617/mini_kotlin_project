package com.example.minikec.event.domain

data class Action(
    val actionId: String,
    val name: String,

    val actionType: ActionType,
    val actionSubType: ActionSubType,

    val actionRepeatType: ActionRepeatType = ActionRepeatType.NONE,

    val goal: Long? = null,

    val totalCount: Long? = null,
    val personalMaxCount: Long? = null,
    val personalMaxCountPerDay: Long? = null,

    val winnerCount: Long? = null,

    val percent: Double? = null,

    val parentActionId: String? = null,

    val pointRewards: List<ActionPoint> = emptyList(),
    val itemRewards: List<ActionItem> = emptyList(),

    val rewardActions: List<Action> = emptyList()
)