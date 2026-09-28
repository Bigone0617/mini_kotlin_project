package com.example.minikec.action.domain

import com.example.minikec.event.domain.Action
import com.example.minikec.event.domain.ActionRepeatType
import com.example.minikec.user.domain.ActionStatus
import com.example.minikec.user.domain.UserAction
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

object ActionRepeatPolicy {

    fun validate(
        action: Action,
        latestUserAction: UserAction?,
        now: Instant,
        zoneId: ZoneId = ZoneOffset.UTC
    ) {

        // 이전 실행 자체가 없다면 당연히 실행 가능
        if (latestUserAction == null) {
            return
        }

        // 이전 Action이 완료되지 않았다면
        // 아직 다시 실행/진행할 수 있음
        if (latestUserAction.status != ActionStatus.COMPLETE) {
            return
        }

        when (action.actionRepeatType) {

            ActionRepeatType.NONE -> {
                throw ActionRepeatNotAllowedException(
                    "Action already completed: ${action.actionId}"
                )
            }

            ActionRepeatType.DAILY -> {

                val lastExecutedAt =
                    latestUserAction.checkedAt
                        ?: latestUserAction.updatedAt

                val lastDate =
                    lastExecutedAt
                        .atZone(zoneId)
                        .toLocalDate()

                val currentDate =
                    now
                        .atZone(zoneId)
                        .toLocalDate()

                if (lastDate == currentDate) {
                    throw ActionRepeatNotAllowedException(
                        "Action already completed today: ${action.actionId}"
                    )
                }
            }

            ActionRepeatType.INFINITE -> {
                // 아무것도 하지 않음
                // 다시 실행 가능
            }
        }
    }
}