package com.example.minikec.user

import com.example.minikec.event.application.port.output.EventRepositoryPort
import com.example.minikec.event.domain.Event
import com.example.minikec.event.domain.PointDefinition
import com.example.minikec.user.application.port.input.ParticipateEventCommand
import com.example.minikec.user.application.port.output.UserPointRepositoryPort
import com.example.minikec.user.application.port.output.UserRepositoryPort
import com.example.minikec.user.application.service.ParticipateEventService
import com.example.minikec.user.domain.User
import com.example.minikec.user.domain.UserPoint
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DuplicateKeyException
import java.time.Clock

class ParticipateEventDuplicateTest {
    private val events = mock(EventRepositoryPort::class.java)
    private val points = mock(UserPointRepositoryPort::class.java)
    private val saved = User(id = "winner", gameKey = "g", eventKey = "e", externalUserId = "external", nickname = "original")
    private val command = ParticipateEventCommand("g", "e", "external", "new nickname")

    private class RacingUsers(val existing: User?, val failure: RuntimeException?) : UserRepositoryPort {
        var reads = 0
        var writes = 0
        override fun findByExternalUserId(gameKey: String, eventKey: String, externalUserId: String): User? {
            assertEquals(listOf("g", "e", "external"), listOf(gameKey, eventKey, externalUserId))
            reads++
            return if (reads == 1) null else existing
        }
        override fun save(user: User): User {
            writes++
            failure?.let { throw it }
            return user.copy(id = "created")
        }
    }

    private fun service(users: UserRepositoryPort): ParticipateEventService {
        `when`(events.findByEventKey("e")).thenReturn(Event(
            "e", "g", "event", active = true,
            points = listOf(PointDefinition("p", "point", initialPoint = 10))
        ))
        return ParticipateEventService(events, users, points, Clock.systemUTC())
    }

    @Test fun `duplicate save returns winning participant without initializing points again`() {
        val users = RacingUsers(saved, DuplicateKeyException("duplicate"))
        val result = service(users).participate(command)
        assertEquals(saved, result.user)
        assertTrue(result.alreadyParticipated)
        assertTrue(result.points.isEmpty())
        assertEquals(2, users.reads)
        assertEquals(1, users.writes)
        verifyNoInteractions(points)
    }

    @Test fun `duplicate without matching participant preserves original failure`() {
        val failure = DuplicateKeyException("unresolved duplicate")
        val users = RacingUsers(null, failure)
        assertSame(failure, assertThrows(DuplicateKeyException::class.java) { service(users).participate(command) })
        verifyNoInteractions(points)
    }

    @Test fun `other database failures are not treated as participation`() {
        val failure = DataAccessResourceFailureException("database unavailable")
        val users = RacingUsers(saved, failure)
        assertSame(failure, assertThrows(DataAccessResourceFailureException::class.java) { service(users).participate(command) })
        assertEquals(1, users.reads)
        verifyNoInteractions(points)
    }

    @Test fun `point duplicate failure is not swallowed as user duplicate`() {
        val users = RacingUsers(saved, null)
        val failure = DuplicateKeyException("point duplicate")
        `when`(points.save("g", UserPoint(eventKey = "e", userId = "created", pointKey = "p", totalPoint = 10, currentPoint = 10))).thenThrow(failure)
        assertSame(failure, assertThrows(DuplicateKeyException::class.java) { service(users).participate(command) })
        assertEquals(1, users.reads)
    }
}
