package com.example.minikec.action

import com.example.minikec.action.adapter.input.web.ExecuteActionController
import com.example.minikec.action.application.port.input.*
import com.example.minikec.action.domain.InvalidActionRequestException
import com.example.minikec.common.adapter.input.web.exception.GlobalExceptionHandler
import com.example.minikec.user.domain.ActionStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

class ActionRequestHttpTest {
    @Test fun `optional request ID is forwarded and legacy body still works`() {
        val commands = mutableListOf<ExecuteActionCommand>()
        val useCase = object : ExecuteActionUseCase {
            override fun execute(command: ExecuteActionCommand): ExecuteActionResult {
                commands.add(command)
                return ExecuteActionResult(command.actionId, ActionStatus.COMPLETE, 1, 1, emptyList())
            }
        }
        val mvc = MockMvcBuilders.standaloneSetup(ExecuteActionController(useCase)).build()
        for (body in listOf("""{"externalUserId":"external"}""", """{"externalUserId":"external","requestId":"request-1"}""")) {
            mvc.perform(post("/ec/v1/g/events/e/actions/a").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk).andExpect(jsonPath("$.actionId").value("a"))
        }
        assertNull(commands[0].requestId)
        assertEquals("request-1", commands[1].requestId)
    }
    @Test fun `invalid action request maps to bad request`() {
        val useCase = object : ExecuteActionUseCase {
            override fun execute(command: ExecuteActionCommand): ExecuteActionResult = throw InvalidActionRequestException("invalid requestId")
        }
        val mvc = MockMvcBuilders.standaloneSetup(ExecuteActionController(useCase)).setControllerAdvice(GlobalExceptionHandler()).build()
        mvc.perform(post("/ec/v1/g/events/e/actions/a").contentType(MediaType.APPLICATION_JSON)
            .content("""{"externalUserId":"external","requestId":" "}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_ACTION_REQUEST"))
    }
}
