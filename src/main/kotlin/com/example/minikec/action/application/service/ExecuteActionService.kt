package com.example.minikec.action.application.service

import com.example.minikec.action.application.port.input.ExecuteActionCommand
import com.example.minikec.action.application.port.input.ExecuteActionResult
import com.example.minikec.action.application.port.input.ExecuteActionUseCase
import com.example.minikec.action.domain.ActionProgressPolicy
import com.example.minikec.action.domain.ActionRepeatPolicy
import com.example.minikec.event.application.port.output.EventRepositoryPort
import com.example.minikec.event.domain.Action
import com.example.minikec.event.domain.ActionType
import com.example.minikec.event.domain.Event
import com.example.minikec.user.application.port.output.UserActionRepositoryPort
import com.example.minikec.user.application.port.output.UserPointRepositoryPort
import com.example.minikec.user.application.port.output.UserRepositoryPort
import com.example.minikec.user.domain.ActionSnapshot
import com.example.minikec.user.domain.ActionStatus
import com.example.minikec.user.domain.UserAction
import com.example.minikec.user.domain.InsufficientPointException
import com.example.minikec.user.domain.UserPoint
import com.example.minikec.action.application.port.output.UserLockPort
import com.example.minikec.action.application.port.output.RewardCounterPort
import com.example.minikec.action.domain.RewardSoldOutException
import com.example.minikec.resource.application.port.output.ResourceRepositoryPort
import com.example.minikec.resource.domain.ResourceNotAvailableException
import com.example.minikec.action.application.port.output.MissionUnitOfWork
import com.example.minikec.action.application.port.output.RewardUnitOfWork
import com.example.minikec.action.domain.RewardCommitUncertainException
import org.slf4j.LoggerFactory

import org.springframework.stereotype.Service
import java.time.Instant
import java.time.Clock

@Service
class ExecuteActionService(
    private val eventRepositoryPort: EventRepositoryPort,
    private val userRepositoryPort: UserRepositoryPort,
    private val userActionRepositoryPort: UserActionRepositoryPort,
    private val userPointRepositoryPort: UserPointRepositoryPort,
    private val userLockPort: UserLockPort,
    private val rewardCounterPort: RewardCounterPort,
    private val resourceRepositoryPort: ResourceRepositoryPort,
    private val clock: Clock,
    private val rewardUnitOfWork: RewardUnitOfWork,
    private val missionUnitOfWork: MissionUnitOfWork
) : ExecuteActionUseCase {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun execute(
        command: ExecuteActionCommand
    ): ExecuteActionResult {

        /*
         * 1. Event 조회
         */
        val event =
            eventRepositoryPort
                .findByEventKey(command.eventKey)
                ?: throw IllegalArgumentException(
                    "Event not found: ${command.eventKey}"
                )

        /*
         * 2. gameKey 검증
         */
        require(event.gameKey == command.gameKey) {
            "Game key mismatch"
        }

        event.validateAvailable(clock.instant())

        /*
         * 3. 이벤트에 참여한 User 조회
         */
        val user =
            userRepositoryPort
                .findByExternalUserId(
                    gameKey = command.gameKey,
                    eventKey = command.eventKey,
                    externalUserId = command.externalUserId
                )
                ?: throw IllegalArgumentException(
                    "User has not participated in event"
                )

        val userId =
            requireNotNull(user.id) {
                "User id is null"
            }
            
        println("===== EVENT DEBUG =====")
        println("requested actionId = ${command.actionId}")
        println("eventKey = ${event.eventKey}")
        println("gameKey = ${event.gameKey}")

        println(
            "missionActions = ${
                event.missionGroups
                    .flatMap { it.actions }
                    .map { it.actionId }
            }"
        )

        println(
            "rewardActions = ${
                event.rewardGroups
                    .flatMap { it.actions }
                    .map { it.actionId }
            }"
        )

        println("=======================")

        /*
         * 4. Event Metadata에서 Action 조회
         */
        val action =
            event.findAction(command.actionId)
            
        val lockKey =
            "user-lock:${command.gameKey}:${command.eventKey}:$userId"

        return userLockPort.withLock(lockKey) {
            /*
            * 이번 Action 실행의 기준 시간
            */
            val now = clock.instant()
            // 락 대기 중 이벤트가 종료됐다면 저장이나 재고 확보 전에 거부한다.
            event.validateAvailable(now)

            /*
            * 5. 가장 최근 UserAction 조회
            *
            * Repeat 정책 / Progress 계산 등에 사용
            */
            val latestUserAction =
                userActionRepositoryPort
                    .findLatestByUserIdAndActionId(
                        gameKey = command.gameKey,
                        eventKey = command.eventKey,
                        userId = userId,
                        actionId = action.actionId
                    )

            /*
            * 6. Action 반복 가능 여부 검사
            *
            * NONE
            * → 이미 완료했으면 실행 불가
            *
            * DAILY
            * → 오늘 이미 완료했으면 실행 불가
            *
            * INFINITE
            * → 반복 가능
            */
            ActionRepeatPolicy.validate(
                action = action,
                latestUserAction = latestUserAction,
                now = now
            )

            /*
            * 7. ActionType에 따라 실행 로직 분기
            */
            when (action.actionType) {

                ActionType.MISSION -> {
                    missionUnitOfWork.prepare(command.gameKey, command.eventKey)
                    missionUnitOfWork.execute {
                        // 재시도마다 새 시각과 DB 상태를 기준으로 판단한다.
                        val missionNow = clock.instant()
                        event.validateAvailable(missionNow)
                        val latest = userActionRepositoryPort.findLatestByUserIdAndActionId(
                            command.gameKey, command.eventKey, userId, action.actionId
                        )
                        ActionRepeatPolicy.validate(action, latest, missionNow)
                        executeMission(command, action, userId, latest, missionNow)
                    }
                }

                ActionType.REWARD -> {
                    executeReward(
                        command = command,
                        event = event,
                        action = action,
                        userId = userId
                    )
                }

                else -> {
                    throw IllegalArgumentException(
                        "Unsupported action type: ${action.actionType}"
                    )
                }
            }
        }
    }

    /*
     * MISSION Action 실행
     *
     * 예:
     * goal = 3
     *
     * 1회 → 1 / 3 PROGRESS
     * 2회 → 2 / 3 PROGRESS
     * 3회 → 3 / 3 COMPLETE
     *
     * COMPLETE가 되면 pointRewards 지급
     */
    private fun executeMission(
        command: ExecuteActionCommand,
        action: Action,
        userId: String,
        latestUserAction: UserAction?,
        now: Instant
    ): ExecuteActionResult {

        /*
         * 이전 UserAction을 기준으로
         * 다음 Progress 계산
         */
        val progress =
            ActionProgressPolicy.next(
                action = action,
                latestUserAction = latestUserAction,
                now = now
            )

        /*
         * goal 달성 여부에 따라 상태 결정
         */
        val status =
            if (progress.isCompleted()) {
                ActionStatus.COMPLETE
            } else {
                ActionStatus.PROGRESS
            }

        /*
         * 실행 결과 UserAction 저장
         */
        val userAction =
            UserAction(
                eventKey = command.eventKey,
                userId = userId,
                actionId = action.actionId,
                action = ActionSnapshot.from(action),
                status = status,
                progress = progress,
                checkedAt = now
            )

        userActionRepositoryPort.save(
            command.gameKey,
            userAction
        )

        /*
         * Mission이 COMPLETE 되었을 경우에만
         * Point Reward 지급
         */
        val updatedPoints =
            if (status == ActionStatus.COMPLETE) {

                action.pointRewards.map { reward ->

                    userPointRepositoryPort.earn(
                        gameKey = command.gameKey,
                        eventKey = command.eventKey,
                        userId = userId,
                        pointKey = reward.pointKey,
                        amount = reward.amount
                    )
                }

            } else {
                emptyList()
            }

        return ExecuteActionResult(
            actionId = action.actionId,
            status = status,
            currentProgress = progress.current,
            goal = progress.goal,
            points = updatedPoints
        )
    }

    /*
     * Redis 수량 확보는 한 번만 수행한다.
     * 자원 할당 + 포인트 차감 + 완료 기록은 MongoDB 트랜잭션으로 묶는다.
     */
    private fun executeReward(
        command: ExecuteActionCommand,
        event: Event,
        action: Action,
        userId: String
    ): ExecuteActionResult {
        val pointCost = action.goal
            ?: throw IllegalStateException("Reward point cost is missing: ${action.actionId}")
        val pointDefinition = event.points.singleOrNull()
            ?: throw IllegalStateException("Step 12 supports exactly one point type")
        val currentPoint = userPointRepositoryPort.findByUserIdAndPointKey(
            command.gameKey, command.eventKey, userId, pointDefinition.pointKey
        ) ?: UserPoint(eventKey = command.eventKey, userId = userId, pointKey = pointDefinition.pointKey)
        // 빠른 거절을 위한 사전 검사. 최종 판단은 트랜잭션 안의 조건부 차감이 수행한다.
        currentPoint.spend(pointCost)
        val itemReward = action.itemRewards.singleOrNull()
            ?: throw IllegalStateException("Step 12 supports exactly one reward item")
        check(itemReward.quantity == 1L) { "Step 12 supports reward item quantity = 1 only" }

        rewardUnitOfWork.prepare(command.gameKey, command.eventKey)
        val counterKey = "reward-count:${command.gameKey}:${command.eventKey}:${action.actionId}"
        var counterAcquired = false
        try {
            action.totalCount?.let { maxCount ->
                counterAcquired = rewardCounterPort.tryAcquire(counterKey, maxCount)
                if (!counterAcquired) throw RewardSoldOutException(action.actionId)
            }
            return rewardUnitOfWork.execute {
                // 재시도는 새 트랜잭션이다. 기간/반복 정책도 다시 확인한다.
                val now = clock.instant()
                event.validateAvailable(now)
                val latest = userActionRepositoryPort.findLatestByUserIdAndActionId(
                    command.gameKey, command.eventKey, userId, action.actionId
                )
                ActionRepeatPolicy.validate(action, latest, now)
                resourceRepositoryPort.assignReadyResource(
                    command.gameKey, command.eventKey, itemReward.itemKey, userId
                ) ?: throw ResourceNotAvailableException(itemReward.itemKey)
                val updatedPoint = userPointRepositoryPort.spendIfEnough(
                    command.gameKey, command.eventKey, userId, pointDefinition.pointKey, pointCost
                ) ?: throw InsufficientPointException(
                    pointDefinition.pointKey, pointCost,
                    userPointRepositoryPort.findByUserIdAndPointKey(
                        command.gameKey, command.eventKey, userId, pointDefinition.pointKey
                    )?.currentPoint ?: 0
                )
                userActionRepositoryPort.save(command.gameKey, UserAction(
                    eventKey = command.eventKey,
                    userId = userId,
                    actionId = action.actionId,
                    action = ActionSnapshot.from(action),
                    status = ActionStatus.COMPLETE,
                    checkedAt = now
                ))
                ExecuteActionResult(action.actionId, ActionStatus.COMPLETE, pointCost, pointCost, listOf(updatedPoint))
            }
        } catch (exception: RewardCommitUncertainException) {
            // 성공했을 수도 있다. 카운터를 반환하면 실제 재고보다 더 지급할 수 있다.
            logger.error("Reward outcome uncertain; reconcile counter={}, userId={}", counterKey, userId, exception)
            throw exception
        } catch (exception: Exception) {
            // MongoDB 변경은 트랜잭션이 롤백한다. 자원을 별도로 READY로 덮어쓰지 않는다.
            if (counterAcquired) {
                try {
                    rewardCounterPort.release(counterKey)
                } catch (releaseFailure: Exception) {
                    exception.addSuppressed(releaseFailure)
                    logger.error("Reward counter release failed; reconcile counter={}", counterKey, releaseFailure)
                }
            }
            throw exception
        }
    }
}
