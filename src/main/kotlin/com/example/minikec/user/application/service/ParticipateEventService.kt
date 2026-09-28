package com.example.minikec.user.application.service

import com.example.minikec.event.application.port.output.EventRepositoryPort
import com.example.minikec.user.application.port.input.ParticipateEventCommand
import com.example.minikec.user.application.port.input.ParticipateEventResult
import com.example.minikec.user.application.port.input.ParticipateEventUseCase
import com.example.minikec.user.application.port.output.UserPointRepositoryPort
import com.example.minikec.user.application.port.output.UserRepositoryPort
import com.example.minikec.user.domain.User
import com.example.minikec.user.domain.UserPoint
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class ParticipateEventService(
    private val eventRepositoryPort: EventRepositoryPort,
    private val userRepositoryPort: UserRepositoryPort,
    private val userPointRepositoryPort: UserPointRepositoryPort,
    private val clock: Clock
) : ParticipateEventUseCase {

    override fun participate(
        command: ParticipateEventCommand
    ): ParticipateEventResult {

        val event = eventRepositoryPort
            .findByEventKey(command.eventKey)
            ?: throw IllegalArgumentException(
                "Event not found. eventKey=${command.eventKey}"
            )

        require(event.gameKey == command.gameKey) {
            "Game does not match event. gameKey=${command.gameKey}"
        }

        event.validateAvailable(clock.instant())

        val existingUser =
            userRepositoryPort.findByExternalUserId(
                gameKey = command.gameKey,
                eventKey = command.eventKey,
                externalUserId = command.externalUserId
            )

        if (existingUser != null) {
            return ParticipateEventResult(
                user = existingUser,
                points = emptyList(),
                alreadyParticipated = true
            )
        }

        val user = try {
            userRepositoryPort.save(
                User(
                    gameKey = command.gameKey,
                    eventKey = command.eventKey,
                    externalUserId = command.externalUserId,
                    nickname = command.nickname
                )
            )
        } catch (exception: DuplicateKeyException) {
            // // 사전 조회 이후 다른 요청이 먼저 저장했으면 그 참여자를 반환한다.
            // // 동일 참여자가 확인되지 않으면 정상 참여로 간주하지 않는다.
            val concurrentUser = userRepositoryPort.findByExternalUserId(
                gameKey = command.gameKey,
                eventKey = command.eventKey,
                externalUserId = command.externalUserId
            ) ?: throw exception

            return ParticipateEventResult(
                user = concurrentUser,
                points = emptyList(),
                alreadyParticipated = true
            )
        }

        val userId = requireNotNull(user.id) {
            "Saved user must have an id"
        }

        val userPoints = event.points.map { pointDefinition ->

            val userPoint = UserPoint(
                eventKey = event.eventKey,
                userId = userId,
                pointKey = pointDefinition.pointKey,
                totalPoint = pointDefinition.initialPoint,
                currentPoint = pointDefinition.initialPoint
            )

            userPointRepositoryPort.save(
                gameKey = event.gameKey,
                userPoint = userPoint
            )
        }

        return ParticipateEventResult(
            user = user,
            points = userPoints,
            alreadyParticipated = false
        )
    }
}