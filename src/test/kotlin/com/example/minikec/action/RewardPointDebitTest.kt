package com.example.minikec.action

import com.example.minikec.action.application.port.input.ExecuteActionCommand
import com.example.minikec.action.application.port.output.*
import com.example.minikec.action.application.service.ExecuteActionService
import com.example.minikec.event.application.port.output.EventRepositoryPort
import com.example.minikec.event.domain.*
import com.example.minikec.resource.application.port.output.ResourceRepositoryPort
import com.example.minikec.resource.domain.Resource
import com.example.minikec.user.application.port.output.*
import com.example.minikec.user.domain.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito.*
import java.time.Clock

class RewardPointDebitTest {
    private val events = mock(EventRepositoryPort::class.java)
    private val users = mock(UserRepositoryPort::class.java)
    private val actions = mock(UserActionRepositoryPort::class.java)
    private val points = mock(UserPointRepositoryPort::class.java)
    private val counter = mock(RewardCounterPort::class.java)
    private val resources = mock(ResourceRepositoryPort::class.java)
    private val lock = object : UserLockPort {
        override fun <T> withLock(lockKey: String, action: () -> T): T = action()
    }
    private val point = UserPoint(id = "p", eventKey = "e", userId = "u", pointKey = "ticket", totalPoint = 10, currentPoint = 10)
    private val command = ExecuteActionCommand("g", "e", "reward", "external")
    private val counterKey = "reward-count:g:e:reward"

    private fun service(): ExecuteActionService {
        val reward = Action("reward", "reward", ActionType.REWARD, ActionSubType.INSTANT_REWARD,
            goal = 3, totalCount = 10, itemRewards = listOf(ActionItem("coupon")))
        `when`(events.findByEventKey("e")).thenReturn(Event("e", "g", "event", active = true,
            points = listOf(PointDefinition("ticket", "ticket")),
            rewardGroups = listOf(ActionGroup("rewards", "rewards", actions = listOf(reward)))))
        `when`(users.findByExternalUserId("g", "e", "external")).thenReturn(User(id = "u", eventKey = "e", gameKey = "g", externalUserId = "external"))
        `when`(points.findByUserIdAndPointKey("g", "e", "u", "ticket")).thenReturn(point)
        `when`(counter.tryAcquire(counterKey, 10)).thenReturn(true)
        `when`(resources.assignReadyResource("g", "e", "coupon", "u")).thenReturn(Resource(id = "r", eventKey = "e", itemKey = "coupon", key = "code"))
        return ExecuteActionService(events, users, actions, points, lock, counter, resources, Clock.systemUTC())
    }

    @Test fun `reward returns atomic debit result rather than stale calculated balance`() {
        val service = service()
        val updated = point.copy(currentPoint = 5)
        `when`(points.spendIfEnough("g", "e", "u", "ticket", 3)).thenReturn(updated)
        val result = service.execute(command)
        assertEquals(listOf(updated), result.points)
        verify(points).findByUserIdAndPointKey("g", "e", "u", "ticket")
        verify(points).spendIfEnough("g", "e", "u", "ticket", 3)
        verifyNoMoreInteractions(points)
        verify(counter, never()).release(counterKey)
    }

    @Test fun `balance lost after initial check releases resource and counter without completion`() {
        val service = service()
        `when`(points.findByUserIdAndPointKey("g", "e", "u", "ticket")).thenReturn(point, point.copy(currentPoint = 1))
        `when`(points.spendIfEnough("g", "e", "u", "ticket", 3)).thenReturn(null)
        val failure = assertThrows(InsufficientPointException::class.java) { service.execute(command) }
        assertEquals(1L, failure.current)
        verify(resources).release("g", "e", "r")
        verify(counter).release(counterKey)
        verify(actions).findLatestByUserIdAndActionId("g", "e", "u", "reward")
        verifyNoMoreInteractions(actions)
    }
}
