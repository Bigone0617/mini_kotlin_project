package com.example.minikec.event

import com.example.minikec.event.domain.*
import com.example.minikec.user.application.port.input.*
import com.example.minikec.user.adapter.input.web.ParticipateEventController
import com.example.minikec.common.adapter.input.web.exception.GlobalExceptionHandler
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.http.MediaType

class EventAvailabilityHttpTest {
    @ParameterizedTest @EnumSource(EventUnavailableReason::class)
    fun `unavailable event returns conflict and specific code`(reason: EventUnavailableReason) {
        val useCase = object : ParticipateEventUseCase {
            override fun participate(command: ParticipateEventCommand): ParticipateEventResult {
                throw EventUnavailableException(reason, command.eventKey)
            }
        }
        val mvc = MockMvcBuilders.standaloneSetup(ParticipateEventController(useCase))
            .setControllerAdvice(GlobalExceptionHandler()).build()
        mvc.perform(post("/ec/v1/g/events/e/users").contentType(MediaType.APPLICATION_JSON)
            .content("""{"externalUserId":"external"}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value(reason.name))
            .andExpect(jsonPath("$.message").isNotEmpty)
    }
}
