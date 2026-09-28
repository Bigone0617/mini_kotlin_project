package com.example.minikec.action.domain

import com.example.minikec.event.domain.Action
import com.example.minikec.event.domain.ActionRepeatType
import com.example.minikec.user.domain.ActionProgress
import com.example.minikec.user.domain.ActionStatus
import com.example.minikec.user.domain.UserAction
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

object ActionProgressPolicy {

    fun next(
        action: Action,
        latestUserAction: UserAction?,
        now: Instant,
        zoneId: ZoneId = ZoneOffset.UTC
    ): ActionProgress {

        val goal = action.goal ?: 1L

        val previousProgress = previousProgress(
            action = action,
            latestUserAction = latestUserAction,
            now = now,
            zoneId = zoneId
        )

        val current = minOf(
            previousProgress + 1L,
            goal
        )

        return ActionProgress(
            current = current,
            goal = goal
        )
    }

    private fun previousProgress(
        action: Action,
        latestUserAction: UserAction?,
        now: Instant,
        zoneId: ZoneId
    ): Long {

        if (latestUserAction == null) {
            return 0L
        }

        val latestProgress =
            latestUserAction.progress?.current ?: 0L

        return when (action.actionRepeatType) {

            ActionRepeatType.NONE -> {
                latestProgress
            }

            ActionRepeatType.DAILY -> {

                val lastExecutedAt =
                    latestUserAction.checkedAt
                        ?: latestUserAction.updatedAt

                val lastDate =
                    lastExecutedAt
                        .atZone(zoneId)
                        .toLocalDate()

                val today =
                    now
                        .atZone(zoneId)
                        .toLocalDate()

                if (lastDate == today) {
                    latestProgress
                } else {
                    0L
                }
            }

            ActionRepeatType.INFINITE -> {

                if (
                    latestUserAction.status ==
                    ActionStatus.COMPLETE
                ) {
                    0L
                } else {
                    latestProgress
                }
            }
        }
    }
}