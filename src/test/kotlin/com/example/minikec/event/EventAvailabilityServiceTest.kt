package com.example.minikec.event

import com.example.minikec.event.domain.*
import com.example.minikec.event.application.port.output.EventRepositoryPort
import com.example.minikec.user.application.port.output.*
import com.example.minikec.user.application.port.input.ParticipateEventCommand
import com.example.minikec.user.application.service.ParticipateEventService
import com.example.minikec.user.domain.User
import com.example.minikec.action.application.port.output.*
import com.example.minikec.action.application.port.input.ExecuteActionCommand
import com.example.minikec.action.application.service.ExecuteActionService
import com.example.minikec.resource.application.port.output.ResourceRepositoryPort
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class EventAvailabilityServiceTest {
    private val now = Instant.parse("2026-09-28T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val events = mock(EventRepositoryPort::class.java)
    private val users = mock(UserRepositoryPort::class.java)
    private val points = mock(UserPointRepositoryPort::class.java)
    private val actions = mock(UserActionRepositoryPort::class.java)
    private val lock = mock(UserLockPort::class.java)
    private val counter = mock(RewardCounterPort::class.java)
    private val resources = mock(ResourceRepositoryPort::class.java)
    private fun event(reason: EventUnavailableReason) = when(reason) {
        EventUnavailableReason.EVENT_INACTIVE -> Event("e", "g", "test", active = false)
        EventUnavailableReason.EVENT_NOT_STARTED -> Event("e", "g", "test", active = true, eventStartAt = now.plusSeconds(1))
        EventUnavailableReason.EVENT_ENDED -> Event("e", "g", "test", active = true, eventEndAt = now)
    }

    @ParameterizedTest @EnumSource(EventUnavailableReason::class)
    fun `participation rejection performs no user or point operations`(reason: EventUnavailableReason) {
        `when`(events.findByEventKey("e")).thenReturn(event(reason))
        val service = ParticipateEventService(events, users, points, clock, com.example.minikec.user.DirectParticipationUnitOfWork)
        val error = assertThrows(EventUnavailableException::class.java) {
            service.participate(ParticipateEventCommand("g", "e", "external", null))
        }
        assertEquals(reason, error.reason)
        verifyNoInteractions(users, points)
    }
    @ParameterizedTest @EnumSource(EventUnavailableReason::class)
    fun `action rejection performs no persistence or redis operations`(reason: EventUnavailableReason) {
        `when`(events.findByEventKey("e")).thenReturn(event(reason))
        val service = ExecuteActionService(events, users, actions, points, lock, counter, resources, clock, com.example.minikec.action.DirectRewardUnitOfWork, com.example.minikec.action.DirectMissionUnitOfWork)
        val error = assertThrows(EventUnavailableException::class.java) {
            service.execute(ExecuteActionCommand("g", "e", "a", "external"))
        }
        assertEquals(reason, error.reason)
        verifyNoInteractions(users, actions, points, lock, counter, resources)
    }
    @Test fun `existing participant can participate at start`() {
        `when`(events.findByEventKey("e")).thenReturn(Event("e", "g", "test", active = true, eventStartAt = now))
        `when`(users.findByExternalUserId("g", "e", "external")).thenReturn(User("u", "e", "g", "external"))
        val result = ParticipateEventService(events, users, points, clock, com.example.minikec.user.DirectParticipationUnitOfWork)
            .participate(ParticipateEventCommand("g", "e", "external", null))
        assertTrue(result.alreadyParticipated)
        verify(points).findAllByUserId("g", "e", "u")
        verifyNoMoreInteractions(points)
    }
    @Test fun `event ending while waiting for lock is rejected before writes`() {
        val action = Action("a", "test", ActionType.MISSION, ActionSubType.VISIT)
        `when`(events.findByEventKey("e")).thenReturn(Event("e", "g", "test", active = true,
            eventEndAt = now.plusSeconds(1), missionGroups = listOf(ActionGroup("m", "missions", actions = listOf(action)))))
        `when`(users.findByExternalUserId("g", "e", "external")).thenReturn(User("u", "e", "g", "external"))
        val changingClock = mock(Clock::class.java)
        `when`(changingClock.instant()).thenReturn(now, now.plusSeconds(1))
        val immediateLock = object : UserLockPort {
            override fun <T> withLock(lockKey: String, action: () -> T): T = action()
        }
        val service = ExecuteActionService(events, users, actions, points, immediateLock, counter, resources, changingClock, com.example.minikec.action.DirectRewardUnitOfWork, com.example.minikec.action.DirectMissionUnitOfWork)
        val error = assertThrows(EventUnavailableException::class.java) {
            service.execute(ExecuteActionCommand("g", "e", "a", "external"))
        }
        assertEquals(EventUnavailableReason.EVENT_ENDED, error.reason)
        verifyNoInteractions(actions, points, counter, resources)
    }
}
