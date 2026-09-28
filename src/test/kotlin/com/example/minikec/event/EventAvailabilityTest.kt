package com.example.minikec.event

import com.example.minikec.event.domain.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

class EventAvailabilityTest {
    private val start = Instant.parse("2026-09-28T00:00:00Z")
    private val end = start.plusSeconds(60)
    private val event = Event("event", "game", "test", active = true, eventStartAt = start, eventEndAt = end)

    @Test fun `inactive takes precedence over time`() {
        val error = assertThrows(EventUnavailableException::class.java) {
            event.copy(active = false).validateAvailable(start.minusNanos(1))
        }
        assertEquals(EventUnavailableReason.EVENT_INACTIVE, error.reason)
    }
    @Test fun `before start is rejected`() {
        val error = assertThrows(EventUnavailableException::class.java) { event.validateAvailable(start.minusNanos(1)) }
        assertEquals(EventUnavailableReason.EVENT_NOT_STARTED, error.reason)
    }
    @Test fun `start and instant before end are allowed`() {
        event.validateAvailable(start)
        event.validateAvailable(end.minusNanos(1))
    }
    @Test fun `end and after end are rejected`() {
        for (now in listOf(end, end.plusNanos(1))) {
            val error = assertThrows(EventUnavailableException::class.java) { event.validateAvailable(now) }
            assertEquals(EventUnavailableReason.EVENT_ENDED, error.reason)
        }
    }
    @Test fun `null bounds are independently unlimited`() {
        event.copy(eventStartAt = null).validateAvailable(start.minusSeconds(1))
        event.copy(eventEndAt = null).validateAvailable(end.plusSeconds(1))
        event.copy(eventStartAt = null, eventEndAt = null).validateAvailable(end)
    }
    @Test fun `offset representation represents the same instant`() {
        event.validateAvailable(Instant.parse("2026-09-28T09:00:00+09:00"))
    }
}
