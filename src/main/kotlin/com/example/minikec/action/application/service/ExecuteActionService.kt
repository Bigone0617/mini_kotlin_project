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
import com.example.minikec.resource.domain.Resource

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
    private val clock: Clock
) : ExecuteActionUseCase {

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
                    executeMission(
                        command = command,
                        action = action,
                        userId = userId,
                        latestUserAction = latestUserAction,
                        now = now
                    )
                }

                ActionType.REWARD -> {
                    executeReward(
                        command = command,
                        event = event,
                        action = action,
                        userId = userId,
                        now = now
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
     * REWARD Action 실행
     *
     * 현재 Step 9에서는:
     *
     * coupon-reward.goal = 30
     *
     * 즉,
     * 30 Point가 있어야 Reward 실행 가능
     *
     * 아직 실제 Coupon 지급은 하지 않고
     * Point 차감 + Reward COMPLETE까지만 구현
     */
   private fun executeReward(
    command: ExecuteActionCommand,
    event: Event,
    action: Action,
    userId: String,
    now: Instant
    ): ExecuteActionResult {

        /*
        * 1. Reward를 받기 위해 필요한 Point
        *
        * 현재 mini-kec에서는
        * action.goal을 Reward 필요 Point로 사용
        */
        val pointCost =
            action.goal
                ?: throw IllegalStateException(
                    "Reward point cost is missing: ${action.actionId}"
                )

        /*
        * 2. 현재 Step에서는 Point 종류 하나만 지원
        */
        val pointDefinition =
            event.points.singleOrNull()
                ?: throw IllegalStateException(
                    "Step 12 supports exactly one point type"
                )

        /*
        * 3. 현재 UserPoint 조회
        */
        val currentPoint =
            userPointRepositoryPort
                .findByUserIdAndPointKey(
                    gameKey = command.gameKey,
                    eventKey = command.eventKey,
                    userId = userId,
                    pointKey = pointDefinition.pointKey
                )
                ?: UserPoint(
                    eventKey = command.eventKey,
                    userId = userId,
                    pointKey = pointDefinition.pointKey
                )

        /*
        * 4. Point가 충분한지 확인
        *
        * spend()는 새로운 UserPoint 객체를 반환할 뿐
        * 아직 MongoDB에는 저장하지 않는다.
        */
        currentPoint.spend(
                pointCost
            )
            
        println("===== REWARD ITEM DEBUG =====")
        println("actionId = ${action.actionId}")
        println("itemRewards size = ${action.itemRewards.size}")
        println("itemRewards = ${action.itemRewards}")
        println("=============================")

        /*
        * 5. Reward Item 확인
        *
        * Step 12에서는
        * Item 1종 + quantity 1만 지원
        */
        val itemReward =
            action.itemRewards.singleOrNull()
                ?: throw IllegalStateException(
                    "Step 12 supports exactly one reward item"
                )

        if (itemReward.quantity != 1L) {
            throw IllegalStateException(
                "Step 12 supports reward item quantity = 1 only"
            )
        }

        /*
        * 6. 전체 Reward 최대 수량
        */
        val maxCount =
            action.totalCount

        val counterKey =
            "reward-count:${command.gameKey}:${command.eventKey}:${action.actionId}"

        /*
        * 이후 실패 시 compensation을 하기 위한 상태값
        */
        var counterAcquired = false
        var assignedResource: Resource? = null

        try {
            
            println("REWARD STEP 1: point checked")

            println("REWARD STEP 2: counter acquire start")

            /*
            * 7. Atomic Counter 자리 확보
            *
            * totalCount가 null이면
            * 전체 수량 제한이 없는 Reward
            */
            if (maxCount != null) {

                counterAcquired =
                    rewardCounterPort.tryAcquire(
                        key = counterKey,
                        maxCount = maxCount
                    )

                if (!counterAcquired) {
                    throw RewardSoldOutException(
                        action.actionId
                    )
                }
            }
            
            println("REWARD STEP 3: counterAcquired=$counterAcquired")

            /*
            * 8. 실제 Resource 하나 확보
            *
            * 예:
            * pizza-coupon
            *
            * READY 상태의 Resource 하나를 찾아
            * ASSIGNED로 원자적으로 변경
            */
            assignedResource =
                resourceRepositoryPort.assignReadyResource(
                    gameKey = command.gameKey,
                    eventKey = command.eventKey,
                    itemKey = itemReward.itemKey,
                    userId = userId
                )
                
            println("REWARD STEP 4: assignedResource=$assignedResource")

            if (assignedResource == null) {
                throw ResourceNotAvailableException(
                    itemReward.itemKey
                )
            }

            /*
            * 9. Point 차감 저장
            */
            val updatedPoint = userPointRepositoryPort.spendIfEnough(
                gameKey = command.gameKey,
                eventKey = command.eventKey,
                userId = userId,
                pointKey = pointDefinition.pointKey,
                amount = pointCost
            ) ?: throw InsufficientPointException(
                pointKey = pointDefinition.pointKey,
                required = pointCost,
                current = userPointRepositoryPort.findByUserIdAndPointKey(
                    command.gameKey, command.eventKey, userId, pointDefinition.pointKey
                )?.currentPoint ?: 0
            )
            
            println("REWARD STEP 5: point saved")

            /*
            * 10. Reward 완료 UserAction 저장
            */
            val userAction =
                UserAction(
                    eventKey = command.eventKey,
                    userId = userId,
                    actionId = action.actionId,
                    action = ActionSnapshot.from(action),
                    status = ActionStatus.COMPLETE,
                    progress = null,
                    checkedAt = now
                )

            userActionRepositoryPort.save(
                command.gameKey,
                userAction
            )
            
            println("REWARD STEP 6: userAction saved")

            /*
            * 11. 정상 완료
            */
            return ExecuteActionResult(
                actionId = action.actionId,
                status = ActionStatus.COMPLETE,
                currentProgress = pointCost,
                goal = pointCost,
                points = listOf(updatedPoint)
            )

        } catch (exception: Exception) {

            /*
            * Resource까지 확보했는데
            * 그 이후 처리가 실패했다면
            * Resource를 READY 상태로 되돌린다.
            */
            assignedResource?.id?.let { resourceId ->

                resourceRepositoryPort.release(
                    gameKey = command.gameKey,
                    eventKey = command.eventKey,
                    resourceId = resourceId
                )
            }

            /*
            * Counter 자리를 확보했는데
            * Reward가 최종적으로 실패했다면
            * Counter도 한 자리 반환
            */
            if (counterAcquired) {

                rewardCounterPort.release(
                    counterKey
                )
            }

            throw exception
        }
    }
}