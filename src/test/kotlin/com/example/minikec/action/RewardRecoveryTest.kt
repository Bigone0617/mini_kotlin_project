package com.example.minikec.action

import com.example.minikec.action.application.port.output.*
import com.example.minikec.action.application.service.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RewardRecoveryTest {
    private val key = RewardRequestKey("g", "e", "u", "a", "r")
    private val executions = mock(RewardExecutionRepositoryPort::class.java)
    private val counter = mock(RewardCounterPort::class.java)
    private val mutex = Any()
    private val lock = object : UserLockPort {
        override fun <T> withLock(lockKey: String, action: () -> T): T = synchronized(mutex) { action() }
    }
    private val service = RecoverRewardService(executions, counter, lock)
    private fun rolledBack() {
        `when`(executions.find(key)).thenReturn(RewardExecution(null, "counter", "token", true))
    }

    @Test fun `legacy and running pending requests require review`() {
        `when`(executions.find(key)).thenReturn(RewardExecution(null), RewardExecution(null, "counter", "token", false))
        assertEquals(RewardRecoveryOutcome.REVIEW_REQUIRED, service.recover(key))
        assertEquals(RewardRecoveryOutcome.REVIEW_REQUIRED, service.recover(key))
        verifyNoInteractions(counter)
        verify(executions, never()).deleteRolledBack(key, "token")
    }

    @Test fun `missing reservation evidence preserves receipt`() {
        rolledBack()
        `when`(counter.reservationState("counter", "token")).thenReturn(ReservationState.MISSING)
        assertEquals(RewardRecoveryOutcome.REVIEW_REQUIRED, service.recover(key))
        verify(counter, never()).releaseReservation("counter", "token")
        verify(executions, never()).deleteRolledBack(key, "token")
    }

    @Test fun `lost release reply preserves receipt and next recovery retries same token`() {
        rolledBack()
        `when`(counter.reservationState("counter", "token")).thenReturn(ReservationState.RESERVED, ReservationState.RELEASED)
        doThrow(IllegalStateException("lost reply")).doNothing().`when`(counter).releaseReservation("counter", "token")
        `when`(executions.deleteRolledBack(key, "token")).thenReturn(true)
        assertThrows(IllegalStateException::class.java) { service.recover(key) }
        verify(executions, never()).deleteRolledBack(key, "token")
        assertEquals(RewardRecoveryOutcome.RETRY_ALLOWED, service.recover(key))
        verify(counter, times(2)).releaseReservation("counter", "token")
    }

    @Test fun `cleanup failure can retry without changing reservation identity`() {
        rolledBack()
        `when`(counter.reservationState("counter", "token")).thenReturn(ReservationState.RELEASED)
        `when`(executions.deleteRolledBack(key, "token")).thenThrow(IllegalStateException("DB unavailable")).thenReturn(true)
        assertThrows(IllegalStateException::class.java) { service.recover(key) }
        assertEquals(RewardRecoveryOutcome.RETRY_ALLOWED, service.recover(key))
        verify(counter, times(2)).releaseReservation("counter", "token")
    }

    @Test fun `conditional cleanup failure does not report retry permission`() {
        rolledBack()
        `when`(counter.reservationState("counter", "token")).thenReturn(ReservationState.RELEASED)
        assertEquals(RewardRecoveryOutcome.STATE_CHANGED, service.recover(key))
    }

    @Test fun `concurrent recovery serializes with same user lock`() {
        var receipt: RewardExecution? = RewardExecution(null, "counter", "token", true)
        `when`(executions.find(key)).thenAnswer { receipt }
        `when`(counter.reservationState("counter", "token")).thenReturn(ReservationState.RESERVED)
        `when`(executions.deleteRolledBack(key, "token")).thenAnswer { receipt = null; true }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map { pool.submit(Callable { service.recover(key) }) }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == RewardRecoveryOutcome.RETRY_ALLOWED })
            assertEquals(7, results.count { it == RewardRecoveryOutcome.NOT_FOUND })
            verify(counter, times(1)).releaseReservation("counter", "token")
        } finally { pool.shutdownNow(); check(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }
}
