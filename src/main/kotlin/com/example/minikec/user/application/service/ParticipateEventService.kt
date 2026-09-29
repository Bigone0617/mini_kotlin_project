package com.example.minikec.user.application.service

import com.example.minikec.event.application.port.output.EventRepositoryPort
import com.example.minikec.user.application.port.input.ParticipateEventCommand
import com.example.minikec.user.application.port.input.ParticipateEventResult
import com.example.minikec.user.application.port.input.ParticipateEventUseCase
import com.example.minikec.user.application.port.output.ParticipationUnitOfWork
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
    private val clock: Clock,
    private val unitOfWork: ParticipationUnitOfWork
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
            return existingParticipation(existingUser)
        }

        unitOfWork.prepare(command.gameKey, command.eventKey)
        return try {
            unitOfWork.execute {
                // 준비 또는 재시도 중 이벤트가 종료되었으면 저장하지 않는다.
                event.validateAvailable(clock.instant())
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
                    // 포인트 저장 오류와 구분하고, 트랜잭션 밖까지 전달한다.
                    throw DuplicateParticipant(exception)
                }
                val userId = requireNotNull(user.id) { "Saved user must have an id" }
                val userPoints = event.points.map { definition ->
                    userPointRepositoryPort.save(
                        gameKey = event.gameKey,
                        userPoint = UserPoint(
                            eventKey = event.eventKey,
                            userId = userId,
                            pointKey = definition.pointKey,
                            totalPoint = definition.initialPoint,
                            currentPoint = definition.initialPoint
                        )
                    )
                }
                ParticipateEventResult(user, userPoints, alreadyParticipated = false)
            }
        } catch (exception: DuplicateParticipant) {
            // execute가 실패한 트랜잭션을 롤백한 뒤 기존 참여자를 조회한다.
            val concurrentUser = userRepositoryPort.findByExternalUserId(
                command.gameKey, command.eventKey, command.externalUserId
            ) ?: throw exception.original
            existingParticipation(concurrentUser)
        }
    }

    private fun existingParticipation(user: User): ParticipateEventResult {
        val userId = requireNotNull(user.id) { "Existing user must have an id" }
        // 재참여는 저장된 현재 포인트만 조회하며, 초기화하거나 누락분을 생성하지 않는다.
        val points = userPointRepositoryPort.findAllByUserId(user.gameKey, user.eventKey, userId)
        return ParticipateEventResult(user, points, alreadyParticipated = true)
    }

    private class DuplicateParticipant(val original: DuplicateKeyException) : RuntimeException(original)
}
